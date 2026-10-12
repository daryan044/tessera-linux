// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import java.io.File

enum class Capability(val label: String) {
    FILESYSTEM("Files"),
    NETWORK("Network"),
    DISPLAY("Display"),
    AUDIO("Audio"),
    DEVICES("Devices"),
}

/** How far a backend can actually hold an app to its manifest. Shown to users as-is. */
enum class Enforcement {
    /** Kernel-enforced (namespaces, uid, missing mounts). */
    ENFORCED,
    /** Honoured by path translation only; a determined process can get around it. */
    ADVISORY,
    /** The backend cannot restrict this at all. */
    UNAVAILABLE,
}

/** Everything [Pty.spawn] needs. argv[0] is the executable. */
data class LaunchSpec(
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: String,
) {
    val executable: String get() = argv.first()
}

/** Per-launch state that is not part of the app's manifest. */
data class Session(
    val id: String,
    /** X display number, when the app has the display permission. */
    val display: Int? = null,
    val term: String = "xterm-256color",
)

/** Host-side locations a backend needs. Built from a Context in the app, by hand in tests. */
data class ContainerPaths(
    val nativeLibDir: File,
    val filesDir: File,
    val cacheDir: File,
    /** rootfs used in PRoot mode; lives in app storage. */
    val prootRootfs: File,
    /** rootfs used in chroot mode; outside app storage so root-owned files stay root-owned. */
    val chrootRootfs: File,
) {
    val proot get() = File(nativeLibDir, "libproot.so")
    val prootLoader get() = File(nativeLibDir, "libproot-loader.so")
    val prootLoader32 get() = File(nativeLibDir, "libproot-loader32.so")
    val chrootScript get() = File(filesDir, "chroot/enter.sh")

    /** Private /tmp per session, so apps cannot see each other's sockets. */
    fun sessionTmp(session: Session) = File(cacheDir, "sessions/${session.id}/tmp")
    fun x11SocketDir(session: Session) = File(sessionTmp(session), ".X11-unix")
    val pulseSocket get() = File(filesDir, "pulse/native")
    /** Stand-ins for /proc files Android hides from apps; see [FakeProc]. */
    val fakeProcDir get() = File(filesDir, "proc")

    companion object {
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        const val PULSE_GUEST = "/run/pulse/native"
        /** Written last by [RootfsInstaller]; a rootfs without it is incomplete. */
        const val INSTALLED_MARKER = ".launcher-installed"
    }
}

/**
 * What actually runs in the container. GUI apps get matchbox-window-manager next to them when
 * it's installed: one app per display, kept fullscreen, without title bars. The manager is
 * stopped when the app exits so the session (and its window) ends with the app.
 */
internal fun guestCommand(app: PermissionManifest): List<String> =
    if (!app.gui) app.exec
    else listOf("/bin/sh", "-c", GUI_WRAPPER, "launch") + app.exec

private const val GUI_WRAPPER =
    "if command -v matchbox-window-manager >/dev/null 2>&1; then " +
        "matchbox-window-manager -use_titlebar no -use_cursor no & wm=\$!; fi; " +
        "\"\$@\"; status=\$?; [ -n \"\$wm\" ] && kill \$wm 2>/dev/null; exit \$status"

interface ContainerBackend {
    val name: String
    fun enforcement(capability: Capability, app: PermissionManifest): Enforcement
    fun buildLaunch(app: PermissionManifest, session: Session): LaunchSpec
}
