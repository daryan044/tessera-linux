// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Whether an app's distro packages are installed in the container. */
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
}
