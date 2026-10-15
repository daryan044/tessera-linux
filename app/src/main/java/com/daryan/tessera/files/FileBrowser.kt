// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.files

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.daryan.tessera.container.ContainerBackend
import com.daryan.tessera.container.ContainerPaths
import com.daryan.tessera.store.DesktopEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

enum class FileSort(val label: String) { NAME("Name"), MODIFIED("Date modified"), SIZE("Size"), TYPE("Type") }
enum class FileView { LIST, GRID }

enum class PlaceKind { PHONE, DOWNLOADS, PICTURES, CAMERA, DOCUMENTS, MUSIC, SD_CARD, USB, HOME, ROOT_HOME, SYSTEM, BOOKMARK }

/** A mounted Android storage volume: internal storage, an SD card or a USB drive. */
data class Volume(val path: String, val title: String, val usb: Boolean, val primary: Boolean, val readOnly: Boolean)

/** A starting point on the Places page. [android] ones are the phone's own storage. */
data class Place(val title: String, val path: String, val kind: PlaceKind, val android: Boolean)

/** How full a volume is, for the Places page. */
data class Space(val free: Long, val total: Long)

/** Copied or cut files waiting to be pasted. */
data class Clip(val paths: List<String>, val cut: Boolean)

/** Something long-running (copying, extracting) shown under the path bar. */
class FileOperation(val title: String) {
    /** 0..1, or null when there's no telling. */
    var progress by mutableStateOf<Float?>(null)
    internal var cancel: () -> Unit = {}
    internal var cancelled = false
}

/** Where the Files tab runs: the active engine, or why it can't. */
data class FilesEngine(val key: String, val backend: ContainerBackend?, val blocker: String?)

/** Everything that can open a file, for the Open with sheet. */
data class OpenOptions(
    /** Its types as Linux names them; Android's guess first when it has one. */
    val mimes: List<String>,
    /** (desktop file name, entry) of each Linux app that opens one of [mimes]. */
    val linux: List<Pair<String, DesktopEntry>>,
    /** Readable in the built-in editor. */
    val text: Boolean,
    val archive: Boolean,
    /** Set for a .deb: what installing it would put on the system. */
    val deb: DebInfo?,
)

/**
 * Everything the Files tab shows and does, kept in the view model so it outlives the screen.
 * The shell behind it ([GuestFiles]) starts on first use and again after the engine changes.
 */
class FileBrowser(
    private val context: Context,
    private val scope: CoroutineScope,
    private val paths: ContainerPaths,
) {
    private val prefs = context.getSharedPreferences("files", Context.MODE_PRIVATE)

    /** The folder being shown, or [PLACES] for the Places page. */
    var dir by mutableStateOf(PLACES)
        private set
    private val history = ArrayDeque<String>()
    val canGoBack get() = history.isNotEmpty()

    var entries by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var selection by mutableStateOf<Set<String>>(emptySet())
    var clip by mutableStateOf<Clip?>(null)
    var operation by mutableStateOf<FileOperation?>(null)
        private set

    /** Non-null while the search field is open. */
    var query by mutableStateOf<String?>(null)
    /** Search every folder below this one, not just filter it. */
    var deepSearch by mutableStateOf(false)
    var searchResults by mutableStateOf<List<FileEntry>?>(null)
        private set
    var searching by mutableStateOf(false)
        private set

    var sort by mutableStateOf(runCatching { FileSort.valueOf(prefs.getString("sort", null)!!) }.getOrDefault(FileSort.NAME))
        private set
    var descending by mutableStateOf(prefs.getBoolean("descending", false))
        private set
    var view by mutableStateOf(runCatching { FileView.valueOf(prefs.getString("view", null)!!) }.getOrDefault(FileView.LIST))
        private set
    var showHidden by mutableStateOf(prefs.getBoolean("hidden", false))
        private set
    var foldersFirst by mutableStateOf(prefs.getBoolean("folders_first", true))
        private set
    /** Browse as root inside the container: real root in chroot mode, PRoot's fake one otherwise. */
    var root by mutableStateOf(prefs.getBoolean("root", false))
        private set
    var bookmarks by mutableStateOf(prefs.getStringSet("bookmarks", emptySet())!!.sorted())
        private set

    var engine by mutableStateOf<FilesEngine?>(null)
        private set

    private val shellLock = Mutex()
    private var shell: GuestFiles? = null
    private var shellKey: String? = null
    private val sessions = AtomicInteger(1)
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    /** Called whenever the engine or its readiness may have changed. */
    fun useEngine(e: FilesEngine) {
        if (engine == e) return
        engine = e
        scope.launch {
            shellLock.withLock { dropShell() }
            if (dir != PLACES) reload()
        }
    }

    // ---- Places ----

    /**
     * Android's mounted storage: internal storage first, then SD cards and USB drives, as the
     * storage manager reports them (getExternalFilesDirs leaves USB drives out). Followed live:
     * plugging a drive in or pulling it out updates it.
     */
    var storage by mutableStateOf(readVolumes())
        private set
    val volumes: List<String> get() = storage.map { it.path }

    private fun readVolumes(): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val found = sm.storageVolumes.mapNotNull { v ->
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            val dir = volumePath(v) ?: return@mapNotNull null
            val label = v.getDescription(context).orEmpty()
            Volume(
                path = dir,
                title = if (v.isPrimary) "Phone storage" else label.ifBlank { dir.substringAfterLast('/') },
                usb = !v.isPrimary && isUsb(v, label),
                primary = v.isPrimary,
                readOnly = v.state == Environment.MEDIA_MOUNTED_READ_ONLY,
            )
        }
        // Internal storage under the path every app and the container use for it.
        val others = found.filterNot { it.primary }.distinctBy { it.path }
        return listOf(Volume(PHONE, "Phone storage", usb = false, primary = true, readOnly = false)) + others
    }

    private fun volumePath(v: android.os.storage.StorageVolume): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) v.directory?.path
        // Before Android 11 the path is only reachable through the hidden getPath().
        else v.javaClass.getMethod("getPath").invoke(v) as? String
    }.getOrNull()?.takeIf { it.startsWith("/storage/") || it.startsWith("/mnt/media_rw/") }

    /** Android names USB drives by their make, or "USB drive"; the volume's disk says for sure. */
    private fun isUsb(v: android.os.storage.StorageVolume, label: String): Boolean {
        if (label.contains("USB", ignoreCase = true)) return true
        return runCatching {
            // Hidden, but present since Android 6: the DiskInfo behind a public volume.
            val id = v.javaClass.getMethod("getId").invoke(v) as? String ?: return@runCatching false
            val sm = context.getSystemService(StorageManager::class.java)
            val vol = sm.javaClass.getMethod("findVolumeById", String::class.java).invoke(sm, id) ?: return@runCatching false
            val disk = vol.javaClass.getMethod("getDisk").invoke(vol) ?: return@runCatching false
            disk.javaClass.getMethod("isUsb").invoke(disk) as? Boolean ?: false
        }.getOrDefault(false)
    }

    /** Reads the volumes again; the shell restarts so its view of Android's storage matches. */
    fun refreshVolumes() {
        val now = readVolumes()
        if (now == storage) return
        val gone = storage.map { it.path } - now.map { it.path }.toSet()
        storage = now
        scope.launch {
            shellLock.withLock { dropShell() }
            // A drive that was pulled out takes its folders with it.
            if (gone.any { dir == it || dir.startsWith("$it/") }) {
                history.clear()
                show(PLACES)
            }
        }
    }

    init {
        // Drives come and go; Android announces each.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // Mounting finishes a moment after the broadcast on some phones.
                scope.launch { refreshVolumes(); delay(1500); refreshVolumes() }
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun places(): List<Place> = buildList {
        add(Place("Phone storage", PHONE, PlaceKind.PHONE, true))
        add(Place("Downloads", "$PHONE/Download", PlaceKind.DOWNLOADS, true))
        add(Place("Camera", "$PHONE/DCIM", PlaceKind.CAMERA, true))
        add(Place("Pictures", "$PHONE/Pictures", PlaceKind.PICTURES, true))
        add(Place("Documents", "$PHONE/Documents", PlaceKind.DOCUMENTS, true))
        add(Place("Music", "$PHONE/Music", PlaceKind.MUSIC, true))
        storage.filterNot { it.primary }.forEach { v ->
            add(Place(v.title, v.path, if (v.usb) PlaceKind.USB else PlaceKind.SD_CARD, true))
        }
        add(Place("Home", HOME, PlaceKind.HOME, false))
        add(Place("Root's home", "/root", PlaceKind.ROOT_HOME, false))
        add(Place("Whole system", "/", PlaceKind.SYSTEM, false))
        bookmarks.forEach { add(Place(it.substringAfterLast('/').ifEmpty { "/" }, it, PlaceKind.BOOKMARK, GuestFiles.onAndroid(it))) }
    }

    /** Free space on Android's storage and where the Linux system lives. */
    fun space(android: Boolean, path: String = PHONE): Space? = runCatching {
        val at = when {
            android -> path
            engine?.key == "CHROOT" -> paths.chrootRootfs.parentFile?.path ?: "/data"
            else -> paths.prootRootfs.path
        }
        val s = StatFs(at)
        Space(s.availableBytes, s.totalBytes)
    }.getOrNull()

    fun toggleBookmark(path: String = dir) {
        bookmarks = if (path in bookmarks) bookmarks - path else (bookmarks + path).sorted()
        prefs.edit().putStringSet("bookmarks", bookmarks.toSet()).apply()
    }

    // ---- Navigation ----

    fun open(path: String) {
        if (path == dir) return reload()
        history.addLast(dir)
        if (history.size > 100) history.removeFirst()
        show(path)
    }

    /** Back: the search, then the selection, then the previous folder. False when there's nothing to go back to. */
    fun back(): Boolean {
        when {
            query != null -> closeSearch()
            selection.isNotEmpty() -> selection = emptySet()
            history.isNotEmpty() -> show(history.removeLast())
            else -> return false
        }
        return true
    }

    fun up() {
        if (dir == PLACES) return
        val parent = if (dir == "/") PLACES else dir.substringBeforeLast('/').ifEmpty { "/" }
        open(parent)
    }

    private fun show(path: String) {
        dir = path
        selection = emptySet()
        closeSearch()
        entries = emptyList()
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        if (dir == PLACES) {
            error = null
            loading = false
            return
        }
        val target = dir
        loadJob = scope.launch {
            loading = true
            error = null
            try {
                // Android's storage is the launcher's to read too, and far quicker straight than
                // through PRoot or su; anything it can't read goes through the shell after all.
                val list = (if (GuestFiles.onAndroid(target)) hostList(target) else null) ?: withShell { it.list(target) }
                if (dir == target) entries = list
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (dir == target) {
                    entries = emptyList()
                    error = e.message ?: "Couldn't open this folder"
                }
            } finally {
                if (dir == target) loading = false
            }
        }
    }

    /** What the list shows: the folder, or the search, filtered and sorted. */
    fun visible(): List<FileEntry> = shown

    /**
     * Filtered and sorted once per change to what it reads, not on every frame: a crowded
     * folder re-sorted on each recomposition (a progress tick, a selection) made scrolling stall.
     */
    private val shown: List<FileEntry> by derivedStateOf {
        val q = query
        val base = when {
            q.isNullOrBlank() -> entries
            deepSearch -> searchResults ?: emptyList()
            else -> entries.filter { it.name.contains(q, ignoreCase = true) }
        }
        val kept = if (showHidden) base else base.filterNot { it.hidden }
        val bySort = when (sort) {
            FileSort.NAME -> compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSort.MODIFIED -> compareBy { it.modified }
            FileSort.SIZE -> compareBy { it.size }
            FileSort.TYPE -> compareBy<FileEntry> { it.extension }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = if (descending) bySort.reversed() else bySort
        kept.sortedWith(if (foldersFirst) compareBy<FileEntry> { !it.isDir }.then(ordered) else ordered)
    }

    // ---- Search ----

    fun openSearch() {
        query = ""
    }

    fun closeSearch() {
        searchJob?.cancel()
        query = null
        searchResults = null
        searching = false
    }

    fun search(text: String) {
        query = text
        if (deepSearch) runDeepSearch()
    }

    fun setDeep(on: Boolean) {
        deepSearch = on
        if (on) runDeepSearch() else { searchJob?.cancel(); searchResults = null; searching = false }
    }

    private fun runDeepSearch() {
        searchJob?.cancel()
        val q = query?.trim().orEmpty()
        if (q.isEmpty() || dir == PLACES) {
            searchResults = null
            return
        }
        val from = dir
        searchJob = scope.launch {
            // Wait for a pause in typing; each keystroke would otherwise walk the tree again.
            delay(350)
            searching = true
            try {
                searchResults = withShell { it.search(from, q) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchResults = emptyList()
            } finally {
                searching = false
            }
        }
    }

    // ---- Preferences ----

    fun sortBy(s: FileSort) {
        if (sort == s) descending = !descending else { sort = s; descending = s == FileSort.MODIFIED || s == FileSort.SIZE }
        prefs.edit().putString("sort", sort.name).putBoolean("descending", descending).apply()
    }

    fun showAs(v: FileView) {
        view = v
        prefs.edit().putString("view", v.name).apply()
    }

    fun showHiddenFiles(on: Boolean) {
        showHidden = on
        prefs.edit().putBoolean("hidden", on).apply()
    }

    fun groupFolders(on: Boolean) {
        foldersFirst = on
        prefs.edit().putBoolean("folders_first", on).apply()
    }

    fun useRoot(on: Boolean) {
        if (root == on) return
        root = on
        prefs.edit().putBoolean("root", on).apply()
        scope.launch {
            shellLock.withLock { dropShell() }
            reload()
        }
    }

    // ---- Selection ----

    fun toggle(path: String) {
        selection = if (path in selection) selection - path else selection + path
    }

    fun selectAll() {
        val all = visible().map { it.path }.toSet()
        selection = if (selection.containsAll(all)) emptySet() else all
    }

    fun selected(): List<FileEntry> = visible().filter { it.path in selection }

    // ---- Changes ----

    /** Runs [block] on the browsing shell and reloads; returns the failure's message, if any. */
    suspend fun change(block: suspend (GuestFiles) -> Unit): String? = try {
        withShell(block)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "That didn't work"
    } finally {
        reload()
    }

    suspend fun newFolder(name: String) = change { it.mkdir(child(name)) }
    suspend fun newFile(name: String) = change { it.newFile(child(name)) }
    suspend fun rename(entry: FileEntry, name: String) = change { it.rename(entry.path, name) }
    suspend fun chmod(entry: FileEntry, mode: Int, recursive: Boolean) = change { it.chmod(entry.path, mode, recursive) }
    suspend fun usage(entry: FileEntry): Usage? = runCatching { withShell { it.usage(entry.path) } }.getOrNull()
    suspend fun canZip(): Boolean = runCatching { withShell { it.has("zip") } }.getOrDefault(false)

    fun copySelection(cut: Boolean) {
        clip = Clip(selected().map { it.path }, cut)
        selection = emptySet()
    }

    /** Deletes, copies, moves and the like run in a shell of their own so browsing goes on. */
    fun delete(targets: List<FileEntry>, onDone: (String?) -> Unit) {
        val list = targets.map { it.path }
        if (list.any { it.trimEnd('/').count { c -> c == '/' } < 1 || it == "/" || it in PROTECTED }) {
            return onDone("That's part of the system and can't be deleted from here")
        }
        selection = emptySet()
        operation("Deleting ${describe(targets.size)}", onDone) { it.delete(list) }
    }

    fun paste(onDone: (String?) -> Unit) {
        val c = clip ?: return
        val into = dir
        if (into == PLACES) return onDone("Open a folder to paste into")
        c.paths.firstOrNull { into == it || into.startsWith("$it/") }?.let {
            return onDone("Can't put ${it.substringAfterLast('/')} inside itself")
        }
        // Cutting and pasting into the same folder leaves things where they are.
        val sources = if (c.cut) c.paths.filter { it.substringBeforeLast('/').ifEmpty { "/" } != into } else c.paths
        if (sources.isEmpty()) { clip = null; return onDone(null) }
        val verb = if (c.cut) "Moving" else "Copying"
        operation("$verb ${describe(sources.size)}", onDone) { files ->
            val taken = files.names(into).toMutableSet()
            val pairs = sources.map { src ->
                val name = freeName(src.substringAfterLast('/'), taken)
                taken += name
                src to child(name, into)
            }
            val watcher = watchProgress(sources, pairs.map { it.second })
            try {
                if (c.cut) files.move(pairs) else files.copy(pairs)
            } finally {
                watcher.cancel()
            }
            if (c.cut) clip = null
        }
    }

    fun compress(targets: List<FileEntry>, name: String, format: ArchiveFormat, onDone: (String?) -> Unit) {
        val into = dir
        selection = emptySet()
        operation("Compressing ${describe(targets.size)}", onDone) { files ->
            val archive = freeName(name + format.extension, files.names(into))
            files.compress(targets.map { it.path }, child(archive, into), format)
        }
    }

    fun extract(archive: FileEntry, onDone: (String?) -> Unit) {
        val into = archive.path.substringBeforeLast('/').ifEmpty { "/" }
        val base = archive.name.replace(Regex("""(\.tar)?\.[A-Za-z0-9]+$"""), "").ifEmpty { "extracted" }
        operation("Extracting ${archive.name}", onDone) { files ->
            files.extract(archive.path, child(freeName(base, files.names(into)), into))
        }
    }

    /** Copies the phone's [uris] (from Android's picker) into this folder. */
    fun import(uris: List<Uri>, onDone: (String?) -> Unit) {
        val into = dir
        if (into == PLACES || uris.isEmpty()) return
        operation("Bringing in ${describe(uris.size)}", onDone) { files ->
            val taken = files.names(into).toMutableSet()
            uris.forEachIndexed { i, uri ->
                val name = freeName(displayName(uri) ?: "file", taken)
                taken += name
                val tmp = File(files.transferDir, "${System.nanoTime()}-import")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        ?: throw IOException("Couldn't read $name")
                }
                files.import(tmp, child(name, into))
                operation?.progress = (i + 1f) / uris.size
            }
        }
    }

    /** Copies [targets] out to Android, for sharing. */
    suspend fun export(targets: List<FileEntry>): List<File> = withShell { s -> targets.filterNot { it.isDir }.map { s.export(it.path) } }

    /** Android's guess at [entry]'s type, from its extension. */
    fun androidMime(entry: FileEntry): String? =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension)

    /** What can open [entry], in Linux and as text; Android's apps are the screen's to ask for. */
    suspend fun options(entry: FileEntry): OpenOptions {
        val hint = androidMime(entry)
        val (mimes, linux) = runCatching { withShell { it.handlers(entry.path, hint) } }.getOrDefault(listOfNotNull(hint) to emptyList())
        val lower = entry.name.lowercase()
        val archive = entry.extension in ARCHIVES || lower.endsWith(".tar.gz") || lower.endsWith(".tar.xz")
        val text = entry.size <= GuestFiles.MAX_TEXT && (
            entry.extension in TEXT || mimes.any { it.startsWith("text/") || it in TEXT_TYPES } ||
                (entry.extension.isEmpty() && mimes.none { !it.startsWith("text/") && it != "application/octet-stream" } && looksLikeText(entry))
            )
        val deb = if (entry.extension == "deb") runCatching { withShell { it.debInfo(entry.path) } }.getOrNull() else null
        return OpenOptions(listOfNotNull(hint) + (mimes - setOfNotNull(hint)), linux, text, archive, deb)
    }

    /** Copies [entry] out for an Android app. */
    suspend fun exportFile(entry: FileEntry): File = withShell { it.export(entry.path) }

    /** Puts a .deb where the install queue's apt can reach it; returns its path there. */
    suspend fun stageDeb(entry: FileEntry, info: DebInfo): String = withShell { it.stageDeb(entry.path, info) }

    suspend fun removeStaged(path: String) = runCatching { withShell { it.removeStaged(path) } }

    /** No NULs and valid UTF-8 in the first 8 KB. */
    private suspend fun looksLikeText(entry: FileEntry): Boolean {
        if (entry.size == 0L) return true
        val f = runCatching { withShell { it.export(entry.path) } }.getOrNull() ?: return false
        return try {
            val head = withContext(Dispatchers.IO) {
                f.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    var n = 0
                    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    buf.copyOf(n)
                }
            }
            head.none { it == 0.toByte() } && runCatching {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(head, 0, utf8Boundary(head)))
            }.isSuccess
        } finally {
            shell?.discard(f)
        }
    }

    suspend fun readText(path: String): String? = withShell { it.readText(path) }
    suspend fun writeText(path: String, text: String) = withShell { it.writeText(path, text) }

    // ---- Thumbnails ----

    private val thumbs = LruCache<String, ImageBitmap>(80)

    fun cachedThumbnail(e: FileEntry): ImageBitmap? = thumbs.get(thumbKey(e))

    /** A small picture of an image file; null for anything else or when it won't decode. */
    suspend fun thumbnail(e: FileEntry, px: Int): ImageBitmap? {
        if (e.extension !in IMAGES || e.size > 25L * 1024 * 1024 || e.size == 0L) return null
        thumbs.get(thumbKey(e))?.let { return it }
        // A row that scrolls straight past is gone before this ends, and so is its work.
        delay(120)
        val direct = hostFile(e)
        val f = direct ?: runCatching { withShell { it.export(e.path) } }.getOrNull() ?: return null
        return decoding.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                        ?.also { thumbs.put(thumbKey(e), it) }
                } finally {
                    if (direct == null) shell?.discard(f)
                }
            }
        }
    }

    /** A few pictures decode at once; more only fight over memory and the disk. */
    private val decoding = Semaphore(3)

    /**
     * [e] as a file the launcher can read itself: Android's storage, and PRoot's system (which
     * lives in app storage) where the path doesn't pass through a link out of it.
     */
    private fun hostFile(e: FileEntry): File? {
        if (e.isLink) return null
        if (GuestFiles.onAndroid(e.path)) return File(e.path).takeIf { it.canRead() }
        if (engine?.key != "PROOT") return null
        val root = paths.prootRootfs.canonicalFile
        val f = File(root, e.path.removePrefix("/"))
        return f.takeIf { runCatching { it.canonicalPath.startsWith(root.path + "/") }.getOrDefault(false) && it.canRead() }
    }

    /** Lists an Android folder directly; null when it can't (no permission yet). */
    private suspend fun hostList(dir: String): List<FileEntry>? = withContext(Dispatchers.IO) {
        val kids = File(dir).listFiles() ?: return@withContext null
        kids.map { k ->
            val folder = k.isDirectory
            FileEntry(
                path = if (dir == "/") "/${k.name}" else "$dir/${k.name}",
                name = k.name,
                isDir = folder,
                isLink = false,
                broken = false,
                size = if (folder) 0 else k.length(),
                modified = k.lastModified(),
                // Android's storage has no Linux owners or modes; these are what it presents.
                mode = if (folder) 0b111_111_000 else 0b110_110_000,
                owner = "",
                group = "",
                linkTarget = null,
            )
        }
    }

    private fun thumbKey(e: FileEntry) = "${e.path}@${e.modified}:${e.size}"

    // ---- Internals ----

    private fun child(name: String, parent: String = dir) = if (parent == "/") "/$name" else "$parent/$name"

    private fun describe(n: Int) = if (n == 1) "1 item" else "$n items"

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.replace('/', '_') ?: uri.lastPathSegment?.substringAfterLast('/')

    private suspend fun <T> withShell(block: suspend (GuestFiles) -> T): T {
        val s = shellLock.withLock {
            shell?.takeIf { it.alive && shellKey == key() } ?: run {
                dropShell()
                newShell("files").also { shell = it; shellKey = key() }
            }
        }
        return block(s)
    }

    private fun key() = "${engine?.key}:$root"

    private suspend fun newShell(id: String): GuestFiles {
        val e = engine ?: throw IOException("Checking the system")
        val backend = e.backend ?: throw IOException(e.blocker ?: "Linux isn't ready")
        // Leftovers from shares and edits long done.
        withContext(Dispatchers.IO) {
            File(paths.cacheDir, "files-transfer").listFiles()?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.deleteRecursively()
            }
        }
        return GuestFiles.start(backend, paths, volumes, root, id)
    }

    private fun dropShell() {
        shell?.close()
        shell = null
        shellKey = null
    }

    /**
     * Runs [block] in a shell of its own, shown as [title].
     */
    private fun operation(
        title: String,
        onDone: (String?) -> Unit,
        block: suspend (GuestFiles) -> Unit,
    ) {
        if (operation != null) return onDone("Wait for ${operation!!.title.lowercase()} to finish")
        val op = FileOperation(title)
        operation = op
        scope.launch {
            var opShell: GuestFiles? = null
            val result = try {
                opShell = newShell("files-op${sessions.getAndIncrement()}")
                val leader = opShell.sessionLeader()
                op.cancel = {
                    // Closing the shell ends PRoot and everything under it; in chroot mode what
                    // runs under su outlives it, so its session is ended from the browsing shell.
                    if (leader != null) scope.launch { runCatching { withShell { it.killSession(leader) } } }
                    opShell.close()
                }
                block(opShell)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (opShell?.alive == false && op.cancelled) "Stopped" else e.message ?: "That didn't work"
            } finally {
                opShell?.close()
                operation = null
                reload()
            }
            onDone(result)
        }
    }

    /** Measures [targets] against [sources] as they fill, through the browsing shell. */
    private fun watchProgress(sources: List<String>, targets: List<String>): Job = scope.launch {
        val total = runCatching { withShell { it.bytes(sources) } }.getOrDefault(0L)
        if (total <= 0) return@launch
        while (isActive) {
            delay(700)
            val now = runCatching { withShell { it.bytes(targets) } }.getOrNull() ?: continue
            operation?.progress = (now.toFloat() / total).coerceIn(0f, 1f)
        }
    }

    fun cancelOperation() {
        operation?.let { it.cancelled = true; it.cancel() }
    }

    companion object {
        const val PLACES = ""
        const val PHONE = "/storage/emulated/0"
        const val HOME = "/home/user"

        private val PROTECTED = setOf("/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/proc", "/root", "/run", "/sbin", "/sys", "/usr", "/var", "/storage", "/storage/emulated", PHONE)
        val ARCHIVES = setOf("zip", "tar", "tgz", "txz", "tbz2", "gz", "xz", "bz2", "zst")
        val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
        /** Types that are text though not under "text/". */
        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/x-shellscript", "application/javascript", "application/x-desktop", "application/toml", "application/x-yaml")
        val TEXT = setOf(
            "txt", "md", "log", "conf", "cfg", "ini", "toml", "yaml", "yml", "json", "xml", "csv", "tsv",
            "sh", "bash", "zsh", "py", "js", "ts", "kt", "kts", "java", "c", "h", "cpp", "hpp", "rs", "go",
            "rb", "pl", "lua", "php", "html", "htm", "css", "scss", "sql", "desktop", "service", "list",
            "sources", "properties", "gradle", "env", "gitignore", "diff", "patch", "srt",
        )

        /** Free name for [name] in a folder holding [taken]: "a.txt", "a (2).txt", "a (3).txt"… */
        fun freeName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val dot = when {
                name.lowercase().endsWith(".tar.gz") || name.lowercase().endsWith(".tar.xz") -> name.length - 7
                name.lastIndexOf('.') > 0 -> name.lastIndexOf('.')
                else -> name.length
            }
            val base = name.substring(0, dot)
            val ext = name.substring(dot)
            var n = 2
            while ("$base ($n)$ext" in taken) n++
            return "$base ($n)$ext"
        }

        /** Length of [b] without a UTF-8 sequence cut off at its end. */
        private fun utf8Boundary(b: ByteArray): Int {
            var i = b.size
            var back = 0
            while (i > 0 && back < 4 && (b[i - 1].toInt() and 0xC0) == 0x80) { i--; back++ }
            if (i > 0 && (b[i - 1].toInt() and 0x80) != 0) i--
            return if (i == 0) b.size else i
        }
    }
}
