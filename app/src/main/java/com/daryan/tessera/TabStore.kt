// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A user-made launcher tab holding a hand-picked set of apps. */
@Serializable
data class CustomTab(val name: String, val appIds: List<String> = emptyList())

/** How the user arranged the launcher: custom tabs, pinned apps and favorites. */
class TabStore(context: Context) {
    private val prefs = context.getSharedPreferences("tabs", Context.MODE_PRIVATE)
    private val _tabs = MutableStateFlow(load())
    val tabs: StateFlow<List<CustomTab>> = _tabs

    /** Apps kept at the top of every tab, in the order they were pinned. */
    private val _pinned = MutableStateFlow(loadIds(KEY_PINNED))
    val pinned: StateFlow<List<String>> = _pinned

    /** Apps in the Favorites tab, in the order they were added. */
    private val _favorites = MutableStateFlow(loadIds(KEY_FAVORITES))
    val favorites: StateFlow<List<String>> = _favorites

    fun togglePinned(appId: String) = toggleId(_pinned, KEY_PINNED, appId)

    fun toggleFavorite(appId: String) = toggleId(_favorites, KEY_FAVORITES, appId)

    private fun toggleId(flow: MutableStateFlow<List<String>>, key: String, id: String) {
        flow.update { ids -> if (id in ids) ids - id else ids + id }
        prefs.edit().putString(key, Json.encodeToString(flow.value)).apply()
    }

    private fun loadIds(key: String): List<String> =
        prefs.getString(key, null)
            ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()

    fun add(name: String) = edit { tabs ->
        val n = name.trim()
        if (n.isEmpty() || tabs.any { it.name == n }) tabs else tabs + CustomTab(n)
    }

    fun remove(name: String) = edit { tabs -> tabs.filterNot { it.name == name } }

    fun toggle(tab: String, appId: String) = edit { tabs ->
        tabs.map {
            if (it.name != tab) it
            else it.copy(appIds = if (appId in it.appIds) it.appIds - appId else it.appIds + appId)
        }
    }

    private fun edit(f: (List<CustomTab>) -> List<CustomTab>) {
        _tabs.update(f)
        prefs.edit().putString(KEY, Json.encodeToString(_tabs.value)).apply()
    }

    private fun load(): List<CustomTab> =
        prefs.getString(KEY, null)
            ?.let { runCatching { Json.decodeFromString<List<CustomTab>>(it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        const val KEY = "custom_tabs"
        const val KEY_PINNED = "pinned_apps"
        const val KEY_FAVORITES = "favorite_apps"
    }
}
