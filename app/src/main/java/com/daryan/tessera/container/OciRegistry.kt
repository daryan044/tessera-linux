// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** A distribution we can install, as an official Docker Hub image. */
enum class Distro(val label: String, val repo: String, val tag: String, val downloadMb: Int) {
    DEBIAN("Debian 13", "library/debian", "trixie", 50),
}

/** OCI platform for an Android ABI. */
data class Platform(val architecture: String, val variant: String? = null) {
    companion object {
        fun forAbi(abi: String) = when (abi) {
            "arm64-v8a" -> Platform("arm64", "v8")
            "armeabi-v7a" -> Platform("arm", "v7")
            "x86_64" -> Platform("amd64")
            "x86" -> Platform("386")
            else -> throw IllegalArgumentException("unsupported ABI $abi")
        }
    }
}

data class Layer(val digest: String, val size: Long)

/**
 * Just enough of the registry API to pull a single-layer image: anonymous token, image index,
 * per-platform manifest, then the layer blob. Blobs are content-addressed, so the SHA-256 we
 * check after downloading is the integrity guarantee.
 */
class OciRegistry(
    private val registry: String = "https://registry-1.docker.io",
    private val auth: String = "https://auth.docker.io/token?service=registry.docker.io",
) {
    fun resolve(distro: Distro, platform: Platform): Layer {
        val token = token(distro.repo)
        val top = getJson("$registry/v2/${distro.repo}/manifests/${distro.tag}", token, MANIFEST_TYPES)
        val manifest = if ("manifests" in top) {
            val digest = selectManifest(top, platform)
            getJson("$registry/v2/${distro.repo}/manifests/$digest", token, MANIFEST_TYPES)
        } else top
        return singleLayer(manifest)
    }

    /** Downloads [layer] to [dest], verifying its digest. Calls [onProgress] with bytes so far. */
    suspend fun download(distro: Distro, layer: Layer, dest: File, onProgress: (Long) -> Unit) {
        val token = token(distro.repo)
        // The registry answers with a redirect to a signed CDN URL; follow it without our token.
        var conn = open("$registry/v2/${distro.repo}/blobs/${layer.digest}", token, null)
        conn.instanceFollowRedirects = false
        var hops = 0
        while (conn.responseCode in 300..399) {
            if (++hops > 5) throw IOException("too many redirects")
            val next = conn.getHeaderField("Location") ?: throw IOException("redirect without Location")
            conn.disconnect()
            conn = open(URL(conn.url, next).toString(), null, null)
            conn.instanceFollowRedirects = false
        }
        if (conn.responseCode != 200) throw IOException("blob download failed: HTTP ${conn.responseCode}")

        val sha = MessageDigest.getInstance("SHA-256")
        val part = File(dest.path + ".part")
        var done = 0L
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    sha.update(buf, 0, n)
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done)
                }
            }
        }
        val actual = "sha256:" + sha.digest().joinToString("") { "%02x".format(it) }
        if (actual != layer.digest) {
            part.delete()
            throw IOException("download corrupted: expected ${layer.digest}, got $actual")
        }
        if (!part.renameTo(dest)) throw IOException("could not move ${part.name} into place")
    }

    private fun token(repo: String): String {
        val url = "$auth&scope=" + URLEncoder.encode("repository:$repo:pull", "UTF-8")
        return getJson(url, null, null)["token"]!!.jsonPrimitive.content
    }

    private fun getJson(url: String, token: String?, accept: String?): JsonObject {
        val conn = open(url, token, accept)
        try {
            if (conn.responseCode != 200) throw IOException("GET $url: HTTP ${conn.responseCode}")
            return json.parseToJsonElement(conn.inputStream.reader().use { it.readText() }).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, token: String?, accept: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            accept?.let { setRequestProperty("Accept", it) }
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val MANIFEST_TYPES =
            "application/vnd.oci.image.index.v1+json, " +
                "application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, " +
                "application/vnd.docker.distribution.manifest.v2+json"

        /** Picks the manifest digest for [platform] out of an image index. */
        internal fun selectManifest(index: JsonObject, platform: Platform): String {
            val candidates = index["manifests"]!!.jsonArray.map { it.jsonObject }.filter { m ->
                val p = m["platform"]?.jsonObject ?: return@filter false
                p["architecture"]?.jsonPrimitive?.content == platform.architecture &&
                    p["os"]?.jsonPrimitive?.content == "linux" &&
                    (platform.variant == null || p["variant"] == null ||
                        p["variant"]!!.jsonPrimitive.content == platform.variant)
            }
            val m = candidates.firstOrNull()
                ?: throw IOException("image has no linux/${platform.architecture} build")
            return m["digest"]!!.jsonPrimitive.content
        }

        internal fun singleLayer(manifest: JsonObject): Layer {
            val layers = manifest["layers"]!!.jsonArray.map { it.jsonObject }
            // Multi-layer images need whiteout handling; the official base images are one layer.
            if (layers.size != 1) throw IOException("expected a single-layer image, got ${layers.size} layers")
            val l = layers[0]
            val type = l["mediaType"]!!.jsonPrimitive.content
            if (!type.endsWith("tar+gzip") && !type.endsWith("tar.gzip")) {
                throw IOException("unsupported layer type $type")
            }
            return Layer(l["digest"]!!.jsonPrimitive.content, l["size"]!!.jsonPrimitive.long)
        }
    }
}
