// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** A published version of Tessera: its number ("0.8.0"), its page, and what its notes say. */
@Serializable
data class Release(val version: String, val url: String, val notes: String = "")

/** What the update check last found; see [UpdateChecker]. */
data class UpdateState(
    /** Whether Tessera looks for new versions by itself. */
    val enabled: Boolean = true,
    /** The newest release, when it's newer than this build. */
    val available: Release? = null,
    /** Whether [available] should be announced: not once the user skipped that version. */
    val announce: Boolean = false,
    val checking: Boolean = false,
    /** When a check last got an answer (epoch ms), or 0. */
    val checkedAt: Long = 0,
    /** Why the last check the user asked for failed. */
    val error: String? = null,
)

/**
 * Looks for a newer Tessera among the project's GitHub releases, at most once a day, and
 * remembers what it found so the notice doesn't depend on being online. It only tells: the
 * user gets the release's page, and installs it the way they installed Tessera.
 */
class UpdateChecker(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** This build's version, "0.7.0". */
    val current: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    private val _state = MutableStateFlow(stateFrom(checking = false, error = null))
    val state: StateFlow<UpdateState> = _state

    private fun stateFrom(checking: Boolean, error: String?): UpdateState {
        val latest = prefs.getString(KEY_LATEST, null)?.let { runCatching { json.decodeFromString(Release.serializer(), it) }.getOrNull() }
        val available = latest?.takeIf { Updates.newer(it.version, current) }
        return UpdateState(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            available = available,
            announce = available != null && prefs.getString(KEY_SKIPPED, null) != available.version,
            checking = checking,
            checkedAt = prefs.getLong(KEY_CHECKED_AT, 0),
            error = error,
        )
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _state.value = stateFrom(checking = _state.value.checking, error = null)
        if (on) check()
    }

    /** Stops announcing [version]; a later one is announced again. */
    fun skip(version: String) {
        prefs.edit().putString(KEY_SKIPPED, version).apply()
        _state.value = stateFrom(checking = _state.value.checking, error = null)
    }

    /**
     * Asks GitHub for the latest release: by itself only when checks are on and the last answer
     * is a day old; [force] is the user asking, whose failures are told.
     */
    fun check(force: Boolean = false) {
        val s = _state.value
        if (s.checking) return
        if (!force && (!s.enabled || System.currentTimeMillis() - s.checkedAt < INTERVAL_MS)) return
        _state.value = s.copy(checking = true, error = null)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { Updates.parse(fetch()) ?: error("GitHub's answer named no release") }
            result.onSuccess { release ->
                prefs.edit()
                    .putString(KEY_LATEST, json.encodeToString(Release.serializer(), release))
                    .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                    .apply()
            }.onFailure { Log.w(TAG, "update check failed", it) }
            _state.value = stateFrom(
                checking = false,
                error = result.exceptionOrNull()?.takeIf { force }?.let { it.message ?: it.javaClass.simpleName },
            )
        }
    }

    private fun fetch(): String {
        val conn = URL(Updates.LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub's API turns away requests without one; nothing here names the phone or the user.
            conn.setRequestProperty("User-Agent", "Tessera")
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "Updates"
        const val KEY_ENABLED = "update_check"
        const val KEY_LATEST = "update_latest"
        const val KEY_CHECKED_AT = "update_checked_at"
        const val KEY_SKIPPED = "update_skipped"
        const val INTERVAL_MS = 24 * 60 * 60 * 1000L
        val json = Json { ignoreUnknownKeys = true }
    }
}

object Updates {
    /** The newest full release: GitHub leaves drafts and pre-releases out of "latest". */
    const val LATEST = "https://api.github.com/repos/daryan044/tessera-linux/releases/latest"
    const val RELEASES = "https://github.com/daryan044/tessera-linux/releases"

    private val json = Json { ignoreUnknownKeys = true }

    /** The release in GitHub's answer ([text]), or null when it names none. */
    internal fun parse(text: String): Release? = runCatching {
        val o = json.decodeFromString(JsonObject.serializer(), text)
        fun field(name: String) = o[name]?.jsonPrimitive?.takeIf { it.isString }?.content
        val version = field("tag_name")?.removePrefix("v")?.takeIf { numbers(it) != null } ?: return null
        // Only the project's own pages: the link is opened as it is.
        val url = field("html_url")?.takeIf { it.startsWith("$RELEASES/") } ?: "$RELEASES/tag/v$version"
        Release(version, url, field("body").orEmpty().trim().take(MAX_NOTES))
    }.getOrNull()

    /** "0.7.1" as numbers, or null when it isn't a plain version. */
    private fun numbers(version: String): List<Int>? =
        version.removePrefix("v").split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }.takeIf { it.isNotEmpty() }

    /** Whether [candidate] is a later version than [current]; false when either can't be read. */
    internal fun newer(candidate: String, current: String): Boolean {
        val a = numbers(candidate) ?: return false
        // A build's own name may carry a suffix ("0.7.0-debug"): its numbers are what count.
        val b = numbers(current.substringBefore('-')) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private const val MAX_NOTES = 4000
}
