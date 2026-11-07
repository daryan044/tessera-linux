// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Flathub's catalog for arm64: its AppStream data, which lists only apps built for aarch64
 * (about 10 MB, parsed into a compact cache and refreshed after a week). Icons aren't packed
 * with it; each is downloaded the first time it's shown ([icon]).
 */
class FlathubCatalog(private val dir: File, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Empty)
    val state: StateFlow<CatalogState> = _state

    /** Loads the cache, or downloads when it's missing, stale, or [refresh] is set. */
    fun load(refresh: Boolean = false) {
        if (_state.value is CatalogState.Loading) return
        scope.launch(Dispatchers.IO) {
            _state.value = try {
                val cache = File(dir, "flathub-aarch64.json")
                (if (!refresh) readCache(cache) else null) ?: download(cache)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CatalogState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** [app]'s icon if it's already downloaded. */
    fun iconFile(app: StoreApp): File? = iconPath(app).takeIf { it.isFile }

    /** [app]'s icon, downloaded the first time; null when it has none or it can't be fetched. */
    suspend fun icon(app: StoreApp): File? = withContext(Dispatchers.IO) {
        iconFile(app) ?: app.iconUrl?.let { url ->
            runCatching {
                val file = iconPath(app)
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                get(url) { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
                file
            }.getOrNull()
        }
    }

    private fun iconPath(app: StoreApp) = File(dir, "flathub-icons/${app.id}.png")

    private fun readCache(cache: File): CatalogState.Ready? {
        if (!cache.isFile || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS) return null
        return readCacheApps(cache.readText())?.let { CatalogState.Ready(it, cache.lastModified()) }
    }

    private fun download(cache: File): CatalogState.Ready {
        _state.value = CatalogState.Loading("Reading Flathub's catalog", null)
        var apps: List<StoreApp> = emptyList()
        get(APPSTREAM) { input -> apps = parse(GZIPInputStream(input)) }
        val sorted = apps.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        cache.parentFile?.mkdirs()
        cache.writeText(writeCacheApps(sorted))
        return CatalogState.Ready(sorted, System.currentTimeMillis())
    }

    private fun get(url: String, read: (InputStream) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) throw IOException("GET $url: HTTP ${conn.responseCode}")
            conn.inputStream.buffered(64 * 1024).use(read)
        } finally {
            conn.disconnect()
        }
    }

    @Serializable
    private data class Cache(val apps: List<StoreApp>, val version: Int = 0)

    companion object {
        private const val APPSTREAM = "https://dl.flathub.org/repo/appstream/aarch64/appstream.xml.gz"
        private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        /** Raised when what's cached changes. 1: ids are Flatpak's, not AppStream's. */
        private const val CACHE_VERSION = 1
        /** Screenshots closest to this width: about a phone's. */
        private const val SHOT_WIDTH = 624
        private val json = Json { ignoreUnknownKeys = true }

        /** The windowed apps in an AppStream XML document that Flatpak installs as apps. */
        fun parse(input: InputStream): List<StoreApp> {
            val handler = Handler()
            SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser().parse(input, handler)
            return handler.apps
        }

        internal fun writeCacheApps(apps: List<StoreApp>): String =
            json.encodeToString(Cache.serializer(), Cache(apps, CACHE_VERSION))

        /** The apps in a cache file, or null when it can't be read or is from an older version. */
        internal fun readCacheApps(text: String): List<StoreApp>? =
            runCatching { json.decodeFromString(Cache.serializer(), text) }.getOrNull()
                ?.takeIf { it.version == CACHE_VERSION }?.apps
    }

    /** Reads one <component> at a time; only the untranslated texts. */
    private class Handler : DefaultHandler() {
        val apps = mutableListOf<StoreApp>()
        private val path = ArrayDeque<String>()
        private val text = StringBuilder()
        // The component being read.
        private var type: String? = null
        private var name: String? = null
        private var summary: String? = null
        private val description = StringBuilder()
        private val categories = mutableListOf<String>()
        private var icon: String? = null
        private var desktopId: String? = null
        private var bundle: String? = null
        private val shots = mutableListOf<List<Pair<Int, String>>>()
        private var shotImages = mutableListOf<Pair<Int, String>>()
        // The element being read, when its text matters.
        private var lang: String? = null
        private var imageWidth = 0
        private var imageType: String? = null
        private var iconAttrs: Triple<String?, Int, String?>? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            path.addLast(qName)
            text.setLength(0)
            lang = attrs.getValue("xml:lang")
            when {
                qName == "component" && path.size == 2 -> {
                    type = attrs.getValue("type")
                    name = null; summary = null; icon = null; desktopId = null; bundle = null
                    description.setLength(0); categories.clear(); shots.clear()
                }
                qName == "icon" -> iconAttrs = Triple(attrs.getValue("type"), attrs.getValue("width")?.toIntOrNull() ?: 0, attrs.getValue("scale"))
                qName == "screenshot" -> shotImages = mutableListOf()
                qName == "image" -> {
                    imageType = attrs.getValue("type")
                    imageWidth = attrs.getValue("width")?.toIntOrNull() ?: 0
                }
                qName == "bundle" -> if (attrs.getValue("type") == "flatpak") bundle = ""
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim().replace(Regex("\\s+"), " ")
            val parent = path.elementAtOrNull(path.size - 2)
            val inComponent = path.size >= 3 && path.elementAt(1) == "component"
            if (inComponent) when (qName) {
                "name" -> if (parent == "component" && lang == null) name = value
                "summary" -> if (parent == "component" && lang == null) summary = value
                "p" -> if (parent == "description" && lang == null) description.append(value).append("\n\n")
                "li" -> if (lang == null && "description" in path) description.append("• ").append(value).append('\n')
                "ul", "ol" -> if ("description" in path) description.append('\n')
                "category" -> categories += value
                "icon" -> iconAttrs?.let { (t, w, scale) ->
                    if (t == "remote" && w == 128 && scale == null) icon = value
                }
                "launchable" -> desktopId = value
                "bundle" -> if (bundle != null) bundle = value
                "image" -> if (value.startsWith("http")) shotImages += (if (imageType == "source") Int.MAX_VALUE else imageWidth) to value
                "screenshot" -> shots += shotImages
            }
            if (qName == "component" && path.size == 2) finish()
            path.removeLast()
            text.setLength(0)
        }

        private fun finish() {
            // Windowed apps only: console ones would need a terminal session, which Flathub apps lack so far.
            if (type != "desktop-application") return
            // Only apps (not runtimes or add-ons), and only those Flatpak installs as apps.
            if (bundle?.startsWith("app/") != true) return
            // The id Flatpak knows it by, from its ref (app/<id>/<arch>/<branch>). AppStream's own
            // <id> can differ: org.kde.konsole.desktop is org.kde.konsole, while
            // org.telegram.desktop really is called that, so no suffix can be trimmed instead.
            val id = bundle?.split('/')?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return
            apps += StoreApp(
                id = id,
                pkg = id,
                name = name ?: id,
                summary = summary.orEmpty(),
                description = description.toString().trim(),
                categories = categories.toList(),
                component = "flathub",
                desktopId = desktopId,
                source = StoreSource.FLATHUB,
                screenshots = shots.mapNotNull { images -> images.minByOrNull { kotlin.math.abs(it.first.toLong() - SHOT_WIDTH) }?.second },
                iconUrl = icon,
            )
        }
    }
}
