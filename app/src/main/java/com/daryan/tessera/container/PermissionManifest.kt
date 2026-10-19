// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Describes one launchable Linux app and exactly what it may touch. Backends compile this into
 * launch arguments; nothing outside it is mounted or exposed.
 */
@Serializable
data class PermissionManifest(
    val id: String,
    val name: String,
    val description: String = "",
    /** Launcher tab the app is listed under. */
    val category: String = "Apps",
    /** Key into the launcher's icon set. */
    val icon: String = "terminal",
    /** Host path of a PNG icon (store-installed apps); used instead of [icon] when present. */
    val iconFile: String? = null,
    /** Command run inside the container, argv style. */
    val exec: List<String>,
    /** GUI apps get their own X display in their own tab. */
    val gui: Boolean = false,
    /** Distro packages that provide [exec]; offered for install when it's missing. */
    val packages: List<String> = emptyList(),
    val permissions: Permissions = Permissions(),
    /**
     * A whole desktop rather than one app: [exec] is its window manager, and apps opened from
     * its Start menu run inside it, under these permissions. See [DesktopFiles].
     */
    val desktop: Boolean = false,
    /** A Flathub app: where its files are and what to run; see [FlatpakRef]. */
    val flatpak: FlatpakRef? = null,
) {
    fun validate(): List<String> = buildList {
        if (!ID.matches(id)) add("id '$id' must match ${ID.pattern}")
        if (exec.isEmpty()) add("exec is empty")
        if (gui && !permissions.display) add("gui apps need the display permission")
        if (desktop && !gui) add("a desktop needs gui")
        permissions.filesystem.forEach { addAll(it.validate()) }
        packages.forEach { if (!PACKAGE.matches(it)) add("package '$it' is not a valid package name") }
        permissions.devices.forEach {
            if (!it.startsWith("/dev/") || ".." in it) add("device '$it' must be a path under /dev/")
        }
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        /** Debian package names; also keeps them safe to put on an apt command line. */
        private val PACKAGE = Regex("[a-z0-9][a-z0-9+.-]{0,127}")
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): PermissionManifest = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class Permissions(
    val filesystem: List<FsGrant> = emptyList(),
    val network: Boolean = false,
    val display: Boolean = false,
    val audio: Boolean = false,
    /** Device nodes, e.g. /dev/dri/renderD128 or /dev/bus/usb/001/004. */
    val devices: List<String> = emptyList(),
    /** Run as root inside the container. Weakens every other guarantee in chroot mode. */
    val root: Boolean = false,
    /**
     * USB drives and SD cards: each one mounted when the app starts appears at
     * /media/user/<its name>, where Linux file managers look for drives. Android mounts them
     * itself; the raw USB device (in [devices]) gives no access to their files.
     */
    val drives: Boolean = false,
    /**
     * Every USB device, as a PC sees them, for tools like fastboot, adb, heimdall or edl. In
     * chroot mode /dev/bus/usb is shared live, so a device that reconnects under a new number
     * (a phone rebooting into fastboot) is still there; PRoot can only open, through Android,
     * those plugged in when the app starts.
     */
    val usb: Boolean = false,
)

/** Exposes an Android path inside the container. */
@Serializable
data class FsGrant(
    val path: String,
    val mountAt: String = path,
    val readOnly: Boolean = false,
) {
    fun validate(): List<String> = buildList {
        for (p in listOf(path, mountAt)) {
            if (!p.startsWith("/")) add("'$p' must be absolute")
            if (p.split('/').any { it == ".." }) add("'$p' must not contain ..")
        }
        val target = mountAt.trimEnd('/').ifEmpty { "/" }
        if (target == "/" || RESERVED.any { target == it || target.startsWith("$it/") }) {
            add("cannot mount over $mountAt")
        }
    }

    private companion object {
        val RESERVED = listOf("/proc", "/sys", "/dev", "/bin", "/sbin", "/lib", "/usr", "/etc")
    }
}
