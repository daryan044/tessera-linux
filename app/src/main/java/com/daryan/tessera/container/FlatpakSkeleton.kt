// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * The root a Flathub app runs in under PRoot: empty folders the runtime, the app and the
 * session are bound onto, the usual top-level links into /usr, /etc as the runtime's, and the
 * fontconfig file the runtime reads to find the host's fonts (bound at /run/host/fonts).
 * Chroot mode builds the same in a tmpfs (assets/chroot/flatpak.sh).
 */
object FlatpakSkeleton {
    private val DIRS = listOf("usr", "app", "tmp", "dev", "proc", "sys", "home/user", "opt", "run/host", "var/tmp", "media", "mnt")
    private val LINKS = mapOf("bin" to "usr/bin", "lib" to "usr/lib", "sbin" to "usr/sbin", "lib64" to "usr/lib", "etc" to "usr/etc")

    /** What the runtime's fontconfig includes from /run/host: the container's fonts. */
    const val FONT_DIRS = """<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
<fontconfig>
  <dir>/run/host/fonts</dir>
</fontconfig>
"""

    /** Makes the skeleton in [dir] if it isn't complete; returns [dir]. */
    fun ensure(dir: File): File {
        DIRS.forEach { File(dir, it).mkdirs() }
        LINKS.forEach { (name, target) ->
            val link = File(dir, name)
            val isLink = runCatching { OsConstants.S_ISLNK(Os.lstat(link.path).st_mode) }.getOrDefault(false)
            if (!isLink) {
                link.deleteRecursively()
                Os.symlink(target, link.path)
            }
        }
        File(dir, "run/host/font-dirs.xml").takeIf { !it.isFile }?.writeText(FONT_DIRS)
        return dir
    }
}
