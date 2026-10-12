// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.store

import com.daryan.linuxlauncher.container.ConfigStore
import com.daryan.linuxlauncher.container.FsGrant
import com.daryan.linuxlauncher.container.PermissionManifest
import com.daryan.linuxlauncher.container.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Apps installed from the store: their launcher entries (manifests saved under [appsDir]),
 * and which packages the container has. Installs themselves run in [InstallQueue].
 */
class StoreRegistry(private val appsDir: File, private val catalog: StoreCatalog) {
    private val _userApps = MutableStateFlow(loadManifests())
    /** Launcher entries for store-installed apps, next to the bundled ones. */
    val userApps: StateFlow<List<PermissionManifest>> = _userApps

    private val _installed = MutableStateFlow<Set<String>?>(null)
    /** Installed package names in the active container; null until read. */
    val installed: StateFlow<Set<String>?> = _installed

    suspend fun refreshInstalled(store: ConfigStore?) {
        _installed.value = store?.let { s ->
            runCatching { s.read("/var/lib/dpkg/status")?.let(::installedPackages) }.getOrNull()
        }
    }

    /** Adds the launcher entry for a freshly installed [app], reading its .desktop file. */
    suspend fun register(app: StoreApp, store: ConfigStore) = withContext(Dispatchers.IO) {
        val desktop = app.desktopId
            ?.let { runCatching { store.read("/usr/share/applications/$it") }.getOrNull() }
            ?.let(DesktopEntry::parse)
        val icon = catalog.iconFile(app)?.let { src ->
            File(appsDir, "icons/${app.pkg}.png").also { dst -> dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true) }
        }
        val manifest = manifestFor(app, desktop, icon?.path)
        File(appsDir, "${manifest.id}.json").apply { parentFile?.mkdirs() }
            .writeText(json.encodeToString(PermissionManifest.serializer(), manifest))
        _userApps.update { list -> list.filterNot { it.id == manifest.id } + manifest }
        refreshInstalled(store)
    }

    suspend fun unregister(app: StoreApp, store: ConfigStore?) = withContext(Dispatchers.IO) {
        val id = idFor(app)
        File(appsDir, "$id.json").delete()
        File(appsDir, "icons/${app.pkg}.png").delete()
        _userApps.update { list -> list.filterNot { it.id == id } }
        refreshInstalled(store)
    }

    private fun loadManifests(): List<PermissionManifest> =
        appsDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { PermissionManifest.parse(f.readText()) }.getOrNull()?.takeIf { it.validate().isEmpty() }
        }

    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = false }

        fun idFor(app: StoreApp) = app.pkg.lowercase().replace(Regex("[^a-z0-9._-]"), "-").take(64)

        /** Packages dpkg reports as installed, from /var/lib/dpkg/status. */
        fun installedPackages(status: String): Set<String> = status.split("\n\n").mapNotNull { block ->
            val pkg = block.lineSequence().firstOrNull { it.startsWith("Package: ") }?.removePrefix("Package: ")?.trim()
            val ok = block.lineSequence().any { it.trim() == "Status: install ok installed" }
            pkg?.takeIf { ok }
        }.toSet()

        /**
         * Launcher entry for a store app. Permissions start from what its category suggests and
         * nothing more: browsers and mail get the network, image editors get Pictures, and so on.
         */
        fun manifestFor(app: StoreApp, desktop: DesktopEntry?, iconFile: String?): PermissionManifest {
            val cats = app.categories.toSet()
            val terminal = desktop?.terminal == true
            val network = cats.any { it in NETWORK_CATEGORIES }
            val folders = buildList {
                if ("Network" in cats) add("Download")
                if ("Graphics" in cats || "Photography" in cats) add("Pictures")
                if ("AudioVideo" in cats || "Audio" in cats || "Music" in cats) add("Music")
                if ("AudioVideo" in cats || "Video" in cats) add("Movies")
                if ("Office" in cats || "Development" in cats || "Education" in cats) add("Documents")
            }.distinct()
            return PermissionManifest(
                id = idFor(app),
                name = app.name,
                description = app.summary,
                category = launcherCategory(app.categories),
                icon = "terminal",
                iconFile = iconFile,
                exec = desktop?.exec ?: listOf("/usr/bin/${app.pkg}"),
                gui = !terminal,
                packages = listOf(app.pkg),
                permissions = Permissions(
                    network = network,
                    display = !terminal,
                    audio = !terminal,
                    filesystem = folders.map {
                        FsGrant("/storage/emulated/0/$it", "/home/user/${if (it == "Download") "Downloads" else it}")
                    },
                ),
            )
        }

        /** AppStream main categories as the launcher's tab names. */
        fun launcherCategory(categories: List<String>): String = categories.firstNotNullOfOrNull { CATEGORY_LABELS[it] } ?: "Apps"

        val CATEGORY_LABELS = linkedMapOf(
            "Network" to "Internet", "Office" to "Office", "Graphics" to "Graphics", "AudioVideo" to "Media",
            "Development" to "Development", "Game" to "Games", "Education" to "Education", "Science" to "Science",
            "Utility" to "Utilities", "System" to "System", "Settings" to "System",
        )

        private val NETWORK_CATEGORIES = setOf(
            "Network", "WebBrowser", "Email", "Chat", "InstantMessaging", "IRCClient", "FileTransfer", "P2P", "News", "Feed",
        )
    }
}
