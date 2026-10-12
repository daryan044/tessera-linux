// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Installing an app's distro packages, as a root session the user watches in a terminal. */
object PackageInstall {

    /**
     * Whether [app]'s executable exists in [rootfs]. Null when that can't be told: no packages
     * to offer, or a relative command that depends on PATH.
     */
    fun isInstalled(app: PermissionManifest, rootfs: File): Boolean? {
        val exe = app.exec.firstOrNull()?.takeIf { it.startsWith("/") } ?: return null
        if (app.packages.isEmpty()) return null
        // Don't follow links: absolute targets like /usr/lib/firefox-esr/... would resolve on
        // the host, not inside the rootfs.
        return Files.exists(File(rootfs, exe.removePrefix("/")).toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    /** A one-off root session that installs [app]'s packages and reports how it went. */
    fun installerFor(app: PermissionManifest): PermissionManifest {
        // GUI apps also get the window manager that keeps them fullscreen (see guestCommand).
        val packages = app.packages + if (app.gui) listOf("matchbox-window-manager") else emptyList()
        val script = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get update && apt-get install -y --no-install-recommends ${packages.joinToString(" ")}; " +
            "s=\$?; echo; " +
            "if [ \$s = 0 ]; then echo 'Installed. Go back and open it.'; else echo \"Install failed (\$s).\"; fi; " +
            "exit \$s"
        return PermissionManifest(
            id = "install-${app.id}".take(64),
            name = "Installing ${app.name}",
            icon = app.icon,
            exec = listOf("/bin/sh", "-c", script),
            permissions = Permissions(network = true, root = true),
        )
    }
}
