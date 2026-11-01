// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import com.daryan.tessera.Shortcut

/**
 * What a Droidspaces container actually has, for its own app menu: a container is the user's
 * system, not one Tessera installed, so bundled apps and Store entries made in another engine
 * are listed only where their files are. PRoot and chroot list every entry, offering Install.
 */
data class ContainerMenu(
    /** Of the entries' programs (absolute paths), those in the container; null until checked. */
    val programs: Set<String>?,
    /** dpkg's installed packages there; null until read. */
    val packages: Set<String>?,
    /** The Flathub apps whose files are there, by Flathub id; null until read. */
    val flatpaks: Set<String>?,
) {
    /** Whether [app] is in the container. Unknown counts as not, so nothing shows that isn't there. */
    fun has(app: PermissionManifest): Boolean {
        val exe = app.exec.firstOrNull()
        return when {
            app.flatpak != null -> flatpaks?.contains(app.flatpak.app) == true
            // A shortcut's command is bash's; what it needs beyond that is its packages.
            Shortcut.isShortcut(app) -> app.packages.isEmpty() || packages?.containsAll(app.packages) == true
            exe != null && exe.startsWith("/") && programs != null -> exe in programs
            app.packages.isNotEmpty() -> packages?.containsAll(app.packages) == true
            // Needs nothing installed (the terminals), or a command found on PATH.
            else -> true
        }
    }

    /** The programs worth checking for [apps]: what [has] looks up in [programs]. */
    companion object {
        fun programsOf(apps: List<PermissionManifest>): Set<String> = apps
            .filter { it.flatpak == null && !Shortcut.isShortcut(it) }
            .mapNotNull { a -> a.exec.firstOrNull()?.takeIf { it.startsWith("/") } }
            .toSet()
    }
}
