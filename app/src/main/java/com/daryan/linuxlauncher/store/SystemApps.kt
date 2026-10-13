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
 * The app reads PRoot's system directly ([scan]) and chroot's through su ([scanAsRoot]).
 */
class SystemApps {
    private val _apps = MutableStateFlow<List<PermissionManifest>>(emptyList())
    val apps: StateFlow<List<PermissionManifest>> = _apps
    /** Launcher id -> the .desktop file it came from, to find its package for uninstalling. */
    private val sources = mutableMapOf<String, String>()

    suspend fun scan(rootfs: File) = withContext(Dispatchers.IO) {
        val dir = File(rootfs, APPLICATIONS.removePrefix("/"))
        val files = dir.listFiles { f -> f.name.endsWith(".desktop") }.orEmpty()
            .mapNotNull { f -> runCatching { f.name to f.readText() }.getOrNull() }
        publish(files) { iconFile(rootfs, it) }
    }

    /**
     * Like [scan], for the chroot system at [rootfs], which only root can read: [su] prints the
     * desktop files, then the icons they name, which are kept in [iconDir] for the launcher.
     */
    suspend fun scanAsRoot(su: String, rootfs: String, iconDir: File) = withContext(Dispatchers.IO) {
        val listing = runSu(su, "cd '$rootfs$APPLICATIONS' 2>/dev/null || exit 0; " +
            "for f in *.desktop; do [ -f \"${'$'}f\" ] && echo \"$MARK${'$'}f\" && cat \"${'$'}f\"; done") ?: return@withContext
        val files = sections(listing).map { (name, lines) -> name to lines.joinToString("\n") }
        // Each icon's candidate paths, tried in order; the first that exists comes back as base64.
        val wanted = files.mapNotNull { (_, text) -> runCatching { DesktopEntry.parse(text) }.getOrNull()?.icon }
            // They go into a root shell script: names that could break out of its quotes are skipped.
            .filter { it.isNotBlank() && '\'' !in it && '\n' !in it }.distinct()
        val script = wanted.joinToString("\n") { icon ->
            val tries = iconCandidates(icon).joinToString(" ") { "'$rootfs/$it'" }
            "for c in $tries; do [ -f \"${'$'}c\" ] && echo '$MARK$icon' && base64 \"${'$'}c\" && break; done"
        }
        iconDir.mkdirs()
        val icons = if (wanted.isEmpty()) emptyMap() else sections(runSu(su, script).orEmpty()).mapNotNull { (icon, lines) ->
            val bytes = runCatching { android.util.Base64.decode(lines.joinToString(""), android.util.Base64.DEFAULT) }.getOrNull()
                ?: return@mapNotNull null
            val file = File(iconDir, icon.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80) + ".png")
            file.writeBytes(bytes)
            icon to file.path
        }.toMap()
        publish(files) { icons[it] }
    }

    private fun publish(files: List<Pair<String, String>>, iconFor: (String?) -> String?) {
        _apps.value = files.sortedBy { it.first }
            .mapNotNull { (name, text) ->
                val entry = runCatching { DesktopEntry.parse(text) }.getOrNull() ?: return@mapNotNull null
                if (entry.hidden || entry.name.isNullOrBlank()) return@mapNotNull null
                val m = manifestFor(name, entry, iconFor(entry.icon))
                synchronized(sources) { sources[m.id] = name }
                m
            }
            // The same app under two desktop files (VS Code ships code.desktop running "vscode"
            // and com.microsoft.VSCode.desktop running /usr/share/code/code): keep the first.
            .distinctBy { it.name.lowercase() }
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

    /** Like [packageOf], for the chroot system at [rootfs], through [su]. */
    suspend fun packageOfAsRoot(app: PermissionManifest, su: String, rootfs: String): String? = withContext(Dispatchers.IO) {
        val file = synchronized(sources) { sources[app.id] }?.takeIf { '\'' !in it } ?: return@withContext null
        runSu(su, "grep -lxF '$APPLICATIONS/$file' '$rootfs'/var/lib/dpkg/info/*.list 2>/dev/null | head -n 1")
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?.substringAfterLast('/')?.removeSuffix(".list")?.substringBefore(':')
    }

    companion object {
        const val ID_PREFIX = "system-"
        /** Starts each file in what [scanAsRoot]'s scripts print; no desktop file has a line like it. */
        private const val MARK = "@@launcher@@ "
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
            return iconCandidates(icon).map { File(rootfs, it) }.firstOrNull { it.isFile }?.path
        }

        /** Where a themed icon named [icon] may be, relative to the root, best first. */
        private fun iconCandidates(icon: String): List<String> {
            if (icon.startsWith("/")) return if (icon.endsWith(".png")) listOf(icon.removePrefix("/")) else emptyList()
            val name = icon.removeSuffix(".png")
            return ICON_SIZES.map { "usr/share/icons/hicolor/$it/apps/$name.png" } + "usr/share/pixmaps/$name.png"
        }

        /** Splits output of the scripts above into (name after [MARK], following lines). */
        private fun sections(output: String): List<Pair<String, List<String>>> {
            val out = mutableListOf<Pair<String, MutableList<String>>>()
            output.lineSequence().forEach { line ->
                if (line.startsWith(MARK)) out += line.removePrefix(MARK) to mutableListOf()
                else out.lastOrNull()?.second?.add(line)
            }
            return out
        }

        private fun runSu(su: String, script: String): String? = runCatching {
            val p = ProcessBuilder(su, "-c", script).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return null }
            out
        }.getOrNull()
    }
}
