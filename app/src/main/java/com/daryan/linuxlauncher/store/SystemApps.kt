// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.store

import com.daryan.linuxlauncher.container.FsGrant
import com.daryan.linuxlauncher.container.PermissionManifest
import com.daryan.linuxlauncher.container.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Apps installed in the container some other way than the Store (apt in a terminal, a .deb),
 * found the way desktop menus find them: from the .desktop files in /usr/share/applications.
 * PRoot mode only for now: the app reads its rootfs directly.
 */
class SystemApps {
    private val _apps = MutableStateFlow<List<PermissionManifest>>(emptyList())
    val apps: StateFlow<List<PermissionManifest>> = _apps
    /** Launcher id -> the .desktop file it came from, to find its package for uninstalling. */
    private val sources = mutableMapOf<String, String>()

    suspend fun scan(rootfs: File) = withContext(Dispatchers.IO) {
        val dir = File(rootfs, APPLICATIONS.removePrefix("/"))
        val found = dir.listFiles { f -> f.name.endsWith(".desktop") }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { f ->
                val entry = runCatching { DesktopEntry.parse(f.readText()) }.getOrNull() ?: return@mapNotNull null
                if (entry.hidden || entry.name.isNullOrBlank()) return@mapNotNull null
                val m = manifestFor(f.name, entry, iconFile(rootfs, entry.icon))
                synchronized(sources) { sources[m.id] = f.name }
                m
            }
            // The same app under two desktop files (VS Code ships code.desktop running "vscode"
            // and com.microsoft.VSCode.desktop running /usr/share/code/code): keep the first.
            .distinctBy { it.name.lowercase() }
        _apps.value = found
    }

    fun clear() {
        _apps.value = emptyList()
    }

    /** The package that installed [app]'s desktop file, from dpkg's file lists; null if unknown. */
    suspend fun packageOf(app: PermissionManifest, rootfs: File): String? = withContext(Dispatchers.IO) {
        val file = synchronized(sources) { sources[app.id] } ?: return@withContext null
        val path = "$APPLICATIONS/$file"
        File(rootfs, "var/lib/dpkg/info").listFiles { f -> f.name.endsWith(".list") }.orEmpty()
            .firstOrNull { list -> runCatching { list.useLines { lines -> lines.any { it == path } } }.getOrDefault(false) }
            ?.name?.removeSuffix(".list")?.substringBefore(':')
    }

    companion object {
        const val ID_PREFIX = "system-"
        private const val APPLICATIONS = "/usr/share/applications"
        private val ICON_SIZES = listOf("256x256", "128x128", "96x96", "64x64", "48x48")

        fun isSystemApp(app: PermissionManifest) = app.id.startsWith(ID_PREFIX)

        /**
         * A launcher entry for a .desktop file. Permissions follow what such apps need: a window
         * and sound when graphical, the network when they're in a network category.
         */
        fun manifestFor(desktopFile: String, entry: DesktopEntry, iconFile: String?): PermissionManifest {
            val cats = entry.categories.toSet()
            val terminal = entry.terminal
            return PermissionManifest(
                id = ID_PREFIX + desktopFile.removeSuffix(".desktop").lowercase().replace(Regex("[^a-z0-9._-]"), "-").take(56),
                name = entry.name.orEmpty(),
                description = entry.comment.orEmpty(),
                category = StoreRegistry.launcherCategory(entry.categories),
                icon = "terminal",
                iconFile = iconFile,
                exec = entry.exec,
                gui = !terminal,
                permissions = Permissions(
                    network = cats.any { it in setOf("Network", "WebBrowser", "Email", "Chat", "Development", "IDE") },
                    display = !terminal,
                    audio = !terminal,
                    filesystem = if ("Development" in cats || "Office" in cats) {
                        listOf(FsGrant("/storage/emulated/0/Documents", "/home/user/Documents"))
                    } else {
                        emptyList()
                    },
                ),
            )
        }

        /** A PNG for [icon] (a theme name or a path) inside [rootfs], as a host path. */
        fun iconFile(rootfs: File, icon: String?): String? {
            if (icon.isNullOrBlank()) return null
            if (icon.startsWith("/")) return File(rootfs, icon.removePrefix("/")).takeIf { it.isFile && icon.endsWith(".png") }?.path
            val name = icon.removeSuffix(".png")
            val candidates = ICON_SIZES.map { "usr/share/icons/hicolor/$it/apps/$name.png" } + "usr/share/pixmaps/$name.png"
            return candidates.map { File(rootfs, it) }.firstOrNull { it.isFile }?.path
        }
    }
}
