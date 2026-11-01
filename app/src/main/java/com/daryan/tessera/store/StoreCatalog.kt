// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.GuestRelease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

sealed interface CatalogState {
    data object Empty : CatalogState
    data class Loading(val step: String, val progress: Float?) : CatalogState
    data class Ready(val apps: List<StoreApp>, val updated: Long) : CatalogState
    data class Failed(val message: String) : CatalogState
}

/**
 * Where a system's AppStream catalog is: Debian's for Debian, Ubuntu's for Ubuntu and its
 * derivatives. [key] names its cache and icons, so catalogs of different systems don't mix.
 */
data class CatalogSource(val key: String, val mirror: String, val suite: String, val components: List<String>) {
    companion object {
        private const val DEBIAN = "https://deb.debian.org/debian"
        private val DEBIAN_COMPONENTS = listOf("main", "contrib", "non-free")
        private val UBUNTU_COMPONENTS = listOf("main", "universe", "restricted", "multiverse")
        /** Debian's own suite names; a derivative's codename (kali-rolling) is none of them. */
        private val DEBIAN_SUITES = setOf(
            "buster", "bullseye", "bookworm", "trixie", "forky", "duke", "sid",
            "oldstable", "stable", "testing", "unstable",
        )

        /**
         * The catalog for [release] on [arch] (Debian's name). Ubuntu publishes amd64 and i386 on
         * its archive and the rest on ports. A Debian derivative with no catalog of its own (Kali
         * has none) gets Debian testing's, which rolling derivatives follow; packages it lacks fail
         * to install with apt's own words. No release: Debian 13, what Tessera installs.
         */
        fun forRelease(release: GuestRelease?, arch: String): CatalogSource {
            val ubuntu = release?.ubuntuCodename ?: release?.codename?.takeIf { release.isUbuntu }
            if (release != null && release.isUbuntu && ubuntu != null) {
                val mirror = if (arch == "amd64" || arch == "i386") "https://archive.ubuntu.com/ubuntu" else "https://ports.ubuntu.com/ubuntu-ports"
                return CatalogSource("ubuntu-$ubuntu", mirror, ubuntu, UBUNTU_COMPONENTS)
            }
            val suite = when {
                release == null -> "trixie"
                release.codename in DEBIAN_SUITES -> release.codename!!
                else -> "testing"
            }
            return CatalogSource("debian-$suite", DEBIAN, suite, DEBIAN_COMPONENTS)
        }
    }
}

/**
 * A system's AppStream catalog ([CatalogSource]) for one architecture: every desktop app in its
 * components, with icons. Downloaded once (about 20 MB), parsed into a compact cache, and
 * refreshed after a week.
 */
class StoreCatalog(private val dir: File, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Empty)
    val state: StateFlow<CatalogState> = _state
    private var job: Job? = null
    /** What [job] is loading, so asking for another system's catalog meanwhile isn't dropped. */
    private var loading: String? = null

    /** [StoreApp.component] is the catalog's key and the component ("ubuntu-noble/universe"). */
    fun iconFile(app: StoreApp): File? = app.icon?.let { File(dir, "icons/${app.component}/$it") }?.takeIf { it.isFile }

    /** Loads the cache, or downloads when it's missing, stale, or [refresh] is set. */
    fun load(source: CatalogSource, arch: String, refresh: Boolean = false) {
        val key = "${source.key}-$arch"
        if (_state.value is CatalogState.Loading && loading == key) return
        job?.cancel()
        loading = key
        job = scope.launch(Dispatchers.IO) {
            _state.value = try {
                val cache = File(dir, "catalog-$key.json")
                val cached = if (!refresh) readCache(cache) else null
                cached ?: download(source, arch, cache)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CatalogState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun readCache(cache: File): CatalogState.Ready? {
        if (!cache.isFile || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS) return null
        return runCatching {
            val c = json.decodeFromString(Cache.serializer(), cache.readText())
            CatalogState.Ready(c.apps, cache.lastModified())
        }.getOrNull()
    }

    private suspend fun download(source: CatalogSource, arch: String, cache: File): CatalogState.Ready {
        // Icons from before catalogs had keys: one folder per component, whichever system's.
        listOf("main", "contrib", "non-free").forEach { File(dir, "icons/$it").deleteRecursively() }
        val apps = mutableListOf<StoreApp>()
        val components = source.components
        components.forEachIndexed { i, component ->
            val base = "${source.mirror}/dists/${source.suite}/$component/dep11"
            val share = 1f / components.size
            _state.value = CatalogState.Loading("Reading the $component catalog", i * share)
            fetch("$base/Components-$arch.yml.gz") { input, total ->
                val counted = Progress(input, total) { f ->
                    _state.value = CatalogState.Loading("Reading the $component catalog", (i + f * 0.5f) * share)
                }
                apps += Dep11.parse(GZIPInputStream(counted).bufferedReader().lineSequence(), "${source.key}/$component")
            }
            currentCoroutineContext().ensureActive()
            _state.value = CatalogState.Loading("Getting $component icons", (i + 0.5f) * share)
            val icons = File(dir, "icons/${source.key}/$component").apply { deleteRecursively() }
            fetch("$base/icons-128x128.tar.gz") { input, total ->
                val counted = Progress(input, total) { f ->
                    _state.value = CatalogState.Loading("Getting $component icons", (i + 0.5f + f * 0.5f) * share)
                }
                Tar.extractFlat(GZIPInputStream(counted), icons) { it.endsWith(".png") }
            }
        }
        // The same package can appear once per component it ships desktop files from.
        val unique = apps.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        cache.parentFile?.mkdirs()
        cache.writeText(json.encodeToString(Cache.serializer(), Cache(unique)))
        return CatalogState.Ready(unique, System.currentTimeMillis())
    }

    /** GET [url] and hand the body to [read]; a 404 (component without apps) is skipped. */
    private fun fetch(url: String, read: (InputStream, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            when (conn.responseCode) {
                200 -> conn.inputStream.buffered(64 * 1024).use { read(it, conn.contentLengthLong) }
                404 -> return
                else -> throw IOException("GET $url: HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Reports the fraction of [total] bytes read so far. */
    private class Progress(input: InputStream, private val total: Long, private val report: (Float) -> Unit) :
        FilterInputStream(input) {
        private var read = 0L
        private var lastReported = -1

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(n: Long) {
            read += n
            if (total <= 0) return
            val pct = (read * 100 / total).toInt()
            if (pct != lastReported) {
                lastReported = pct
                report(pct / 100f)
            }
        }
    }

    @Serializable
    private data class Cache(val apps: List<StoreApp>)

    companion object {
        private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        private val json = Json { ignoreUnknownKeys = true }

        /** Debian's name for an Android ABI. */
        fun debianArch(abi: String): String = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armhf"
            "x86_64" -> "amd64"
            "x86" -> "i386"
            else -> throw IllegalArgumentException("unsupported ABI $abi")
        }
    }
}
