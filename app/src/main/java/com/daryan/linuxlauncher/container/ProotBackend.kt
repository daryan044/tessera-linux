// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import java.io.File

/**
 * No-root backend. PRoot rewrites paths via ptrace, so it confines what an app *sees*, but every
 * process still runs as the app's own Android uid: it is not a security boundary.
 */
class ProotBackend(
    private val paths: ContainerPaths,
    /** [FakeProc] files to bind over their /proc counterparts. */
    private val fakeProc: List<String> = emptyList(),
    /** PRoot fails on a binding whose host path is missing, so missing grants are skipped. */
    private val exists: (String) -> Boolean = { File(it).exists() },
    /** The app's uid/gid. Non-root sessions run as it, with PRoot emulating set*id calls. */
    private val appId: Pair<Int, Int>? = null,
) : ContainerBackend {
    override val name = "PRoot"

    override fun enforcement(capability: Capability, app: PermissionManifest) = when (capability) {
        // The app's uid holds INTERNET, and PRoot cannot filter connect().
        Capability.NETWORK -> Enforcement.UNAVAILABLE
        else -> Enforcement.ADVISORY
    }

    override fun buildLaunch(app: PermissionManifest, session: Session): LaunchSpec {
        val p = app.permissions
        val tmp = paths.sessionTmp(session)
        val argv = buildList {
            add(paths.proot.invariantSeparatorsPath)
            add("--kill-on-exit")
            // SELinux blocks hard links in app storage; dpkg and tar need them.
            add("--link2symlink")
            if (p.root) add("--root-id")
            // Android's app seccomp filter rejects setuid()/setgid(), even to the current ids,
            // and programs like xterm refuse to run when dropping privileges fails.
            else appId?.let { (uid, gid) -> add("--change-id=$uid:$gid") }
            add("--rootfs=${paths.prootRootfs.invariantSeparatorsPath}")
            add("--cwd=${if (p.root) "/root" else "/home/user"}")
            bind("/dev")
            bind("/proc")
            fakeProc.forEach { bind(File(paths.fakeProcDir, it).invariantSeparatorsPath, "/proc/$it") }
            bind("/sys")
            bind("/dev/urandom", "/dev/random")
            bind(tmp.invariantSeparatorsPath, "/tmp")
            bind(paths.sessionShm(session).invariantSeparatorsPath, "/dev/shm")
            // proot has no read-only binds; FsGrant.readOnly is advisory here.
            p.filesystem.filter { exists(it.path) }.forEach { bind(it.path, it.mountAt) }
            p.devices.filter(exists).forEach { bind(it) }
            val pulse = paths.pulseSocket.invariantSeparatorsPath
            if (p.audio && exists(pulse)) bind(pulse, ContainerPaths.PULSE_GUEST)

            add("/usr/bin/env")
            add("-i")
            addAll((guestEnv(app, session) + SANDBOX_OFF).map { (k, v) -> "$k=$v" })
            addAll(guestCommand(app))
        }
        val hostEnv = buildMap {
            put("PROOT_TMP_DIR", paths.cacheDir.invariantSeparatorsPath)
            put("PROOT_LOADER", paths.prootLoader.invariantSeparatorsPath)
            put("PROOT_LOADER_32", paths.prootLoader32.invariantSeparatorsPath)
        }
        return LaunchSpec(argv, hostEnv, paths.filesDir.invariantSeparatorsPath)
    }

    private fun MutableList<String>.bind(host: String, guest: String = host) {
        add(if (host == guest) "--bind=$host" else "--bind=$host:$guest")
    }
}

/**
 * Firefox's per-process sandboxes install seccomp filters that clash with PRoot's ptrace, and
 * its child processes die with SIGSEGV. PRoot mode can't offer that isolation anyway; chroot
 * mode keeps the sandbox.
 */
private val SANDBOX_OFF = mapOf(
    "MOZ_DISABLE_CONTENT_SANDBOX" to "1",
    "MOZ_DISABLE_GMP_SANDBOX" to "1",
    "MOZ_DISABLE_RDD_SANDBOX" to "1",
    "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX" to "1",
    "MOZ_DISABLE_UTILITY_SANDBOX" to "1",
)

/** Environment inside the container; shared by both backends. */
internal fun guestEnv(app: PermissionManifest, session: Session): Map<String, String> = buildMap {
    val p = app.permissions
    put("HOME", if (p.root) "/root" else "/home/user")
    put("USER", if (p.root) "root" else "user")
    put("PATH", ContainerPaths.GUEST_PATH)
    put("LANG", "C.UTF-8")
    put("TERM", session.term)
    put("TMPDIR", "/tmp")
    if (p.display && session.display != null) {
        put("DISPLAY", ":${session.display}")
        // Termux:X11 sends real multi-touch events; Firefox only uses them (finger scrolling,
        // pinch zoom) with XInput2 on, otherwise it treats a finger as a mouse drag.
        put("MOZ_USE_XINPUT2", "1")
        // Each session has its own display: Firefox handing a window to "the running copy"
        // would put it in another app's window, and a copy we killed leaves a remote endpoint
        // that never answers ("Firefox is already running, but is not responding").
        put("MOZ_NO_REMOTE", "1")
        // Accessibility on, so the focus watcher hears about text fields (see guestCommand).
        // GTK 3/4 report over AT-SPI by default; Firefox and Qt need asking.
        put("GNOME_ACCESSIBILITY", "1")
        put("QT_LINUX_ACCESSIBILITY_ALWAYS_ON", "1")
    }
    if (p.audio) put("PULSE_SERVER", "unix:${ContainerPaths.PULSE_GUEST}")
}
