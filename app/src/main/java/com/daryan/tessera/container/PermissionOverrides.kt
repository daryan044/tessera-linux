// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Permissions the user changed, by app id. They replace the manifest's when the app is listed
 * or launched; the manifest itself is left alone, so resetting is just dropping the override
 * (and a store reinstall, which rewrites the manifest, keeps the user's choice).
 */
class PermissionOverrides(private val file: File) {
    private val _overrides = MutableStateFlow(load())
    val overrides: StateFlow<Map<String, Permissions>> = _overrides

    /** [app] with the user's permissions, if they changed any. */
    fun apply(app: PermissionManifest): PermissionManifest =
        _overrides.value[app.id]?.let { app.copy(permissions = it) } ?: app

    /**
     * Sets [app]'s permissions. Returns what's wrong with them instead, leaving things as they
     * were, when they don't pass the manifest's own checks.
     */
    fun set(app: PermissionManifest, permissions: Permissions, defaults: Permissions): List<String> {
        val problems = app.copy(permissions = permissions).validate()
        if (problems.isNotEmpty()) return problems
        edit { if (permissions == defaults) it - app.id else it + (app.id to permissions) }
        return emptyList()
    }

    fun reset(appId: String) = edit { it - appId }

    private fun edit(f: (Map<String, Permissions>) -> Map<String, Permissions>) {
        _overrides.update(f)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(SERIALIZER, _overrides.value))
            // Some filesystems won't rename over an existing file.
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): Map<String, Permissions> =
        runCatching { json.decodeFromString(SERIALIZER, file.readText()) }.getOrDefault(emptyMap())

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val SERIALIZER = MapSerializer(String.serializer(), Permissions.serializer())
    }
}
