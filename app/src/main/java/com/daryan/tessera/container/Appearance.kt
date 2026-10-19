// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Light or dark for Linux apps. */
@Serializable
enum class ColorMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * How Linux apps look (docs/LOOK-AND-FEEL.md): themed through the settings their toolkits
 * already read, with the phone's colors ([Palette], [ThemeFiles]).
 */
@Serializable
data class Appearance(
    /** Theme Linux apps at all; off leaves every toolkit on its own defaults. */
    val enabled: Boolean = true,
    val colorMode: ColorMode = ColorMode.SYSTEM,
    /** Colors from the wallpaper (Android 12+); otherwise a fixed palette. */
    val dynamicColor: Boolean = true,
    /**
     * An imported Linux theme's family ([LinuxThemes]), used instead of the phone's colors: by
     * Linux apps as it is, and for Tessera's own colors and shapes. Null: the phone's colors.
     */
    val linuxTheme: String? = null,
)

/**
 * The user's appearance settings: one [Appearance] for every app, and the apps left out of it
 * ("Don't theme this app"), for those that break or look worse themed. Kept in a file of its own
 * beside the permission overrides.
 */
class AppearanceSettings(private val file: File) {
    @Serializable
    private data class Stored(val global: Appearance = Appearance(), val unthemed: Set<String> = emptySet())

    private val _stored = MutableStateFlow(load())

    private val _global = MutableStateFlow(_stored.value.global)
    val global: StateFlow<Appearance> = _global
    private val _unthemed = MutableStateFlow(_stored.value.unthemed)
    val unthemed: StateFlow<Set<String>> = _unthemed

    /** What [appId] gets, or null when it isn't themed. */
    fun forApp(appId: String): Appearance? =
        _global.value.takeIf { it.enabled && appId !in _unthemed.value }

    fun setGlobal(appearance: Appearance) = edit { it.copy(global = appearance) }

    fun setThemed(appId: String, themed: Boolean) =
        edit { it.copy(unthemed = if (themed) it.unthemed - appId else it.unthemed + appId) }

    private fun edit(f: (Stored) -> Stored) {
        _stored.update(f)
        _global.value = _stored.value.global
        _unthemed.value = _stored.value.unthemed
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(Stored.serializer(), _stored.value))
            // Some filesystems won't rename over an existing file.
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): Stored =
        runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrDefault(Stored())

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }
}
