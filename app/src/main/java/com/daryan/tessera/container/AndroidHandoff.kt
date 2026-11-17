// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Linux to Android (#17): a link or a file a Linux app opens goes to Android's own "Open with",
 * which lists the phone's apps and, through Tessera's "Open in Linux", the Linux ones too.
 *
 * Inside, every windowed session finds [GUEST_DIR] in its X11 directory: a program, [OPENER],
 * that writes what it's given to [REPORT_NAME], and a desktop file that makes it an app like any
 * other to Linux ("Open in Android"), listed for links and common files, and the default for
 * links when the user wants them on the phone ([files]). Nothing is patched: toolkits and
 * xdg-open find it through XDG_DATA_DIRS, and what the user set in their own mimeapps.list wins.
 */
object AndroidHandoff {
    /** In the session's X11 directory, which the app's root and a Flathub app's both see. */
    const val DIR_NAME = "tessera-open"
    const val GUEST_DIR = "/tmp/.X11-unix/$DIR_NAME"
    const val OPENER = "$GUEST_DIR/open"
    /** First in a session's XDG_DATA_DIRS. */
    const val SHARE = "$GUEST_DIR/share"
    /** What [OPENER] appends to, a line for each thing to open. */
    const val REPORT_NAME = "launcher-open"
    const val DESKTOP_ID = "tessera-android.desktop"

    /** Addresses only Android has apps for; these are its to open whatever links do. */
    private val PHONE_SCHEMES = listOf("mailto", "tel", "sms", "smsto", "geo")
    private val WEB_SCHEMES = listOf("http", "https")

    /** What Linux may hand over as an address. Not content:, file: or intent:, which name things on the phone. */
    internal val SCHEMES = (WEB_SCHEMES + PHONE_SCHEMES + listOf("market", "magnet")).toSet()

    /** File types "Open in Android" is offered for in Linux apps' Open with lists. */
    private val FILE_TYPES = listOf(
        "application/pdf", "application/epub+zip", "application/zip", "application/vnd.android.package-archive",
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "image/bmp",
        "video/mp4", "video/x-matroska", "video/webm", "video/quicktime", "video/x-msvideo",
        "audio/mpeg", "audio/flac", "audio/ogg", "audio/x-wav", "audio/mp4", "audio/aac",
        "text/plain", "text/html", "text/csv", "text/calendar", "text/vcard",
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
    )

    /**
     * The session's files, by path under [DIR_NAME]. With [links], web links are Android's by
     * default too; without, only the addresses Linux has nothing for.
     */
    fun files(links: Boolean): Map<String, String> {
        val schemes = if (links) WEB_SCHEMES + PHONE_SCHEMES else PHONE_SCHEMES
        val types = (WEB_SCHEMES + PHONE_SCHEMES).map { "x-scheme-handler/$it" } + FILE_TYPES
        return linkedMapOf(
            // A line for each argument; a name with a line break in it can't be passed on.
            "open" to "#!/bin/sh\nfor a in \"\$@\"; do\n  case \$a in *'\n'*) continue ;; esac\n" +
                "  printf '%s\\n' \"\$a\" >> /tmp/.X11-unix/$REPORT_NAME\ndone\n",
            "share/applications/$DESKTOP_ID" to "[Desktop Entry]\nType=Application\nName=Open in Android\n" +
                "Comment=Hand it to an app on the phone\nExec=$OPENER %U\nIcon=phone\nTerminal=false\nNoDisplay=true\n" +
                "MimeType=${types.joinToString(";")};\n",
            "share/applications/mimeapps.list" to "[Default Applications]\n" +
                schemes.joinToString("") { "x-scheme-handler/$it=$DESKTOP_ID\n" },
        )
    }

    /** Writes [files] into the session's X11 directory [x11Dir]. */
    fun write(x11Dir: File, links: Boolean) {
        val dir = File(x11Dir, DIR_NAME)
        files(links).forEach { (path, text) ->
            File(dir, path).apply { parentFile?.mkdirs() }.writeText(text)
        }
        // For the app's user, whoever that is in this engine.
        File(dir, "open").setExecutable(true, false)
        generateSequence(File(dir, "share/applications")) { it.parentFile }.takeWhile { it != x11Dir }.forEach {
            it.setReadable(true, false)
            it.setExecutable(true, false)
        }
        dir.walkTopDown().filter { it.isFile }.forEach { it.setReadable(true, false) }
    }

    /** What a line of [REPORT_NAME] asks for. */
    sealed interface Request {
        data class Address(val uri: String) : Request
        /** A file, by its path as the app sees it. */
        data class Path(val path: String) : Request
    }

    /** Reads a line [OPENER] wrote: an address of a scheme in [SCHEMES], or a file's path. Null for anything else. */
    internal fun parse(line: String): Request? {
        val arg = line.trim()
        if (arg.isEmpty() || arg.length > 8192) return null
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(arg)?.groupValues?.get(1)?.lowercase()
        return when {
            scheme == "file" -> percentDecode(arg.substringAfter(':').removePrefix("//").substringBefore('#'))
                ?.takeIf { it.startsWith("/") }?.let { Request.Path(normal(it) ?: return null) }
            scheme != null -> if (scheme in SCHEMES) Request.Address(arg) else null
            arg.startsWith("/") -> normal(arg)?.let { Request.Path(it) }
            else -> null
        }
    }

    /** A file: address's path with its %XX bytes as the characters they are; null when they aren't UTF-8 text. */
    private fun percentDecode(text: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length + 0 && text.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                out.write(text.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(c.toString().toByteArray())
                i++
            }
        }
        return runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        }.getOrNull()?.takeIf { '\u0000' !in it }
    }

    /** [path] without "." and empty parts; null when it climbs with "..". */
    private fun normal(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return "/" + parts.joinToString("/")
    }

    /**
     * Where the file an app calls [path] is for Tessera itself: a shared Android folder by its
     * own place ([grants], mount point to Android path), the session's /tmp in [sessionTmp], the
     * rest inside [rootfs] (PRoot's, which the app can read; null in the root engines).
     */
    internal fun hostFile(path: String, grants: List<FsGrant>, sessionTmp: File, rootfs: File?): File? {
        fun under(base: String) = path == base || path.startsWith("$base/")
        grants.sortedByDescending { it.mountAt.length }.firstOrNull { under(it.mountAt.trimEnd('/')) }?.let { g ->
            return File(g.path.trimEnd('/') + path.removePrefix(g.mountAt.trimEnd('/')))
        }
        return when {
            under("/storage") || under("/sdcard") -> File(path)
            under("/tmp") -> File(sessionTmp, path.removePrefix("/tmp").removePrefix("/"))
            else -> rootfs?.let { File(it, path.removePrefix("/")) }
        }
    }
}

/** Reads what a session's apps asked to open on the phone since last asked ([AndroidHandoff.REPORT_NAME]). */
class OpenReports(private val dir: File) {
    private var read = 0L

    fun poll(): List<AndroidHandoff.Request> {
        val file = File(dir, AndroidHandoff.REPORT_NAME)
        if (!file.isFile || file.length() <= read) return emptyList()
        val text = runCatching {
            java.io.RandomAccessFile(file, "r").use { f ->
                f.seek(read)
                ByteArray((f.length() - read).coerceAtMost(1 shl 20).toInt()).also { f.readFully(it); read = f.length() }.decodeToString()
            }
        }.getOrNull() ?: return emptyList()
        // A burst of them would bury the phone in "Open with" sheets.
        return text.lines().mapNotNull(AndroidHandoff::parse).take(3)
    }
}

/** Opens what a Linux app handed over with Android's apps. */
class AndroidOpener(private val context: Context, private val paths: ContainerPaths) {
    /**
     * [request] from [running]'s app, shown in Android's "Open with". A file outside PRoot's
     * reach is copied out as root with [su] (chroot mode); call off the main thread.
     */
    fun open(running: RunningSession, request: AndroidHandoff.Request, su: String?) {
        val view = when (request) {
            is AndroidHandoff.Request.Address -> Intent(Intent.ACTION_VIEW, Uri.parse(request.uri))
            is AndroidHandoff.Request.Path -> fileIntent(running, request.path, su)
        }
        if (view == null) return tell("Couldn't hand that file to Android; open it from Tessera's Files tab")
        val chooser = Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { context.startActivity(chooser) }.onFailure {
                Log.w(TAG, "nothing opened $request", it)
                tell("No app on this phone opens that")
            }
        }
    }

    private fun fileIntent(running: RunningSession, path: String, su: String?): Intent? {
        val rootfs = paths.prootRootfs.takeIf { running.backend == ProotBackend_NAME }
        val host = AndroidHandoff.hostFile(path, running.app.permissions.filesystem, paths.sessionTmp(running.session), rootfs)
        val name = path.substringAfterLast('/').ifEmpty { return null }
        val uri = when {
            // The phone's own storage is handed over where it is.
            host != null && host.path.startsWith(PHONE) && host.isFile -> runCatching { FileProvider.getUriForFile(context, authority, host) }.getOrNull()
            else -> null
        } ?: copyOut(host, path, name, su, running)?.let { runCatching { FileProvider.getUriForFile(context, authority, it) }.getOrNull() }
            ?: return null
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** A copy of the file in the transfer folder Android apps are let into; older copies go first. */
    private fun copyOut(host: File?, guestPath: String, name: String, su: String?, running: RunningSession): File? {
        val transfer = File(context.cacheDir, TRANSFER).apply { mkdirs() }
        val day = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        transfer.listFiles { f -> f.name.startsWith(PREFIX) && f.lastModified() < day }?.forEach { it.deleteRecursively() }
        val target = File(File(transfer, "$PREFIX${System.nanoTime()}").apply { mkdirs() }, name)
        if (host != null && host.isFile && host.canRead()) {
            if (host.length() > MAX_COPY) return null
            return runCatching { host.copyTo(target, overwrite = true) }.getOrNull()
        }
        // Chroot mode's files are root's to read: its system is outside the app's storage.
        if (su == null || running.backend != ChrootBackend.NAME) return null
        val source = paths.chrootRootfs.path + guestPath
        val uid = android.os.Process.myUid()
        val script = "[ -f ${shellQuote(source)} ] && cp -L -- ${shellQuote(source)} ${shellQuote(target.path)} && " +
            "chown $uid:$uid ${shellQuote(target.path)} && chmod 600 ${shellQuote(target.path)}"
        val ok = runCatching { ProcessBuilder(su, "-c", script).redirectErrorStream(true).start().let { p -> p.inputStream.readBytes(); p.waitFor() == 0 } }
            .getOrDefault(false)
        return target.takeIf { ok && it.isFile }
    }

    private fun tell(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    private val authority get() = "${context.packageName}.files"

    private companion object {
        const val TAG = "AndroidHandoff"
        const val PHONE = "/storage/emulated/0/"
        const val TRANSFER = "files-transfer"
        const val PREFIX = "handoff-"
        const val MAX_COPY = 2L * 1024 * 1024 * 1024
        const val ProotBackend_NAME = "PRoot"
    }
}
