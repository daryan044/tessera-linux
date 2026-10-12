// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.store

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
 * Debian's AppStream catalog for one suite and architecture: every desktop app in main,
 * contrib and non-free, with icons. Downloaded once (about 20 MB), parsed into a compact
 * cache, and refreshed after a week.
 */
class StoreCatalog(private val dir: File, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Empty)
    val state: StateFlow<CatalogState> = _state
    private var job: Job? = null

    fun iconFile(app: StoreApp): File? = app.icon?.let { File(dir, "icons/${app.component}/$it") }?.takeIf { it.isFile }

    /** Loads the cache, or downloads when it's missing, stale, or [refresh] is set. */
    fun load(suite: String, arch: String, refresh: Boolean = false) {
        if (_state.value is CatalogState.Loading) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = try {
                val cache = File(dir, "catalog-$suite-$arch.json")
                val cached = if (!refresh) readCache(cache) else null
                cached ?: download(suite, arch, cache)
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

    private suspend fun download(suite: String, arch: String, cache: File): CatalogState.Ready {
        val apps = mutableListOf<StoreApp>()
        COMPONENTS.forEachIndexed { i, component ->
            val base = "$MIRROR/dists/$suite/$component/dep11"
            val share = 1f / COMPONENTS.size
            _state.value = CatalogState.Loading("Reading the $component catalog", i * share)
            fetch("$base/Components-$arch.yml.gz") { input, total ->
                val counted = Progress(input, total) { f ->
                    _state.value = CatalogState.Loading("Reading the $component catalog", (i + f * 0.5f) * share)
                }
                apps += Dep11.parse(GZIPInputStream(counted).bufferedReader().lineSequence(), component)
            }
            currentCoroutineContext().ensureActive()
            _state.value = CatalogState.Loading("Getting $component icons", (i + 0.5f) * share)
            val icons = File(dir, "icons/$component").apply { deleteRecursively() }
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
        private const val MIRROR = "https://deb.debian.org/debian"
        private val COMPONENTS = listOf("main", "contrib", "non-free")
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
