// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Screenshots for store apps. Debian's catalog links them to screenshots.debian.net, which is
 * gone, so they come from Flathub's AppStream data instead: by the app's AppStream id when
 * Flathub uses the same one (org.gimp.GIMP), else by its exact name (Debian's firefox-esr.desktop
 * is Flathub's org.mozilla.firefox). Downloaded once into [dir].
 */
class Screenshots(private val dir: File) {
    /** Store app id -> its screenshot URLs, once looked up (empty when Flathub has none). */
    private val found = ConcurrentHashMap<String, List<String>>()

    /** Up to [max] of [app]'s screenshots as local files; empty when there are none or offline. */
    suspend fun forApp(app: StoreApp, max: Int = 4): List<File> = withContext(Dispatchers.IO) {
        val urls = found[app.id] ?: runCatching { lookup(app) }.getOrNull()?.also { found[app.id] = it } ?: emptyList()
        urls.take(max).mapNotNull { runCatching { download(it) }.getOrNull() }
    }

    private fun lookup(app: StoreApp): List<String> {
        val id = app.id.removeSuffix(".desktop")
        // Only reverse-DNS ids can be Flathub's; others go straight to the name search.
        if (id.count { it == '.' } >= 2) appstream(id)?.let { return it }
        val match = search(app.name) ?: return emptyList()
        return appstream(match).orEmpty()
    }

    /** The screenshots in Flathub's AppStream data for [id], or null when Flathub has no such app. */
    private fun appstream(id: String): List<String>? {
        val body = get("$API/appstream/${URLEncoder.encode(id, "UTF-8")}") ?: return null
        val shots = (Json.parseToJsonElement(body).jsonObject["screenshots"] as? JsonArray) ?: return emptyList()
        return shots.mapNotNull { shot -> pickSize(shot) }
    }

    /** The size closest to a phone-wide image (Flathub offers several per screenshot). */
    private fun pickSize(shot: JsonElement): String? {
        val sizes = (shot.jsonObject["sizes"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        fun width(s: JsonObject) = s["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: Int.MAX_VALUE
        return sizes.minByOrNull { kotlin.math.abs(width(it) - WIDTH) }?.get("src")?.jsonPrimitive?.content
    }

    /** Flathub's id for the app called exactly [name], if its search finds one. */
    private fun search(name: String): String? {
        val conn = URL("$API/search").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(buildJsonObject { put("query", name) }.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val hits = Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject["hits"]?.jsonArray.orEmpty()
            return hits.map { it.jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.content.equals(name, ignoreCase = true) }
                ?.get("app_id")?.jsonPrimitive?.content
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String): File {
        val file = File(dir, sha1(url) + ".img")
        if (file.isFile) return file
        dir.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) throw IOException("GET $url: HTTP ${conn.responseCode}")
            val tmp = File(dir, file.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** The body of a GET, or null on a 404. */
    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            return when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().readText()
                404 -> null
                else -> throw IOException("GET $url: HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val API = "https://flathub.org/api/v2"
        /** Wide enough for a phone screen at 2-3x, without the 1920px originals. */
        const val WIDTH = 1000
    }
}
