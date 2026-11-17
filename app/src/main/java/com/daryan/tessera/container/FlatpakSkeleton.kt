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
        mapOf(ZYPAK_STUB to ZYPAK_SCRIPT, ZYPAK_HELPER_STUB to ZYPAK_HELPER_SCRIPT, CHROMIUM_STUB to CHROMIUM_SCRIPT).forEach { (path, script) ->
            File(dir, path).takeIf { !it.isFile || it.readText() != script }?.apply { writeText(script); setExecutable(true, false) }
        }
        return dir
    }

    /**
     * What stands in for zypak's wrapper in Flathub's Electron apps (VS Code, Discord, Obsidian):
     * zypak sandboxes Electron through Flatpak's own services, which aren't here, and aborts
     * without them ("Assertion failed: bus"). This runs the app straight, its sandbox off as it
     * is for every PRoot app. Relative to the skeleton.
     */
    const val ZYPAK_STUB = "run/host/tessera-zypak"
    const val ZYPAK_SCRIPT = "#!/bin/sh\nexec \"\$@\"\n"

    /** The wrappers [ZYPAK_STUB] goes over, in an app's /app/bin. */
    val ZYPAK_WRAPPERS = listOf("zypak-wrapper.sh", "zypak-wrapper")

    /**
     * What goes over zypak's own helper, which some apps call straight, as
     * `zypak-helper host - <program> <args>` (Flathub's IDE wrapper: VSCodium, #18). It drops
     * the mode and the fd list up to "-", and runs the rest.
     */
    const val ZYPAK_HELPER_STUB = "run/host/tessera-zypak-helper"
    const val ZYPAK_HELPER = "zypak-helper"
    const val ZYPAK_HELPER_SCRIPT = "#!/bin/sh\nshift\nwhile [ \$# -gt 0 ] && [ \"\$1\" != - ]; do shift; done\n[ \$# -gt 0 ] && shift\nexec \"\$@\"\n"

    /**
     * zypak's wrapper for Chromium browsers started by cobalt (Flathub's Chrome, #25). Electron
     * reads ELECTRON_DISABLE_SANDBOX; Chrome reads no variable, only --no-sandbox, and without a
     * sandbox it can set up (none here: no user namespaces, no setuid helper) it won't start.
     */
    const val CHROMIUM_STUB = "run/host/tessera-zypak-chromium"
    const val CHROMIUM_SCRIPT = "#!/bin/sh\np=\$1\nshift\nexec \"\$p\" --no-sandbox \"\$@\"\n"

    /** cobalt, the launcher of Flathub's Chromium browsers; with it, /.flatpak-info too ([Flatpak.info]). */
    const val COBALT = "cobalt"
}
