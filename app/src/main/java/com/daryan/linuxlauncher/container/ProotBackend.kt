// Copyright (C) 2026 Daryan
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
            add("--rootfs=${paths.prootRootfs.invariantSeparatorsPath}")
            add("--cwd=${if (p.root) "/root" else "/home/user"}")
            bind("/dev")
            bind("/proc")
            fakeProc.forEach { bind(File(paths.fakeProcDir, it).invariantSeparatorsPath, "/proc/$it") }
            bind("/sys")
            bind("/dev/urandom", "/dev/random")
            bind(tmp.invariantSeparatorsPath, "/tmp")
            // proot has no read-only binds; FsGrant.readOnly is advisory here.
            p.filesystem.forEach { bind(it.path, it.mountAt) }
            p.devices.forEach { bind(it) }
            if (p.audio) bind(paths.pulseSocket.invariantSeparatorsPath, ContainerPaths.PULSE_GUEST)

            add("/usr/bin/env")
            add("-i")
            addAll(guestEnv(app, session).map { (k, v) -> "$k=$v" })
            addAll(app.exec)
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

/** Environment inside the container; shared by both backends. */
internal fun guestEnv(app: PermissionManifest, session: Session): Map<String, String> = buildMap {
    val p = app.permissions
    put("HOME", if (p.root) "/root" else "/home/user")
    put("USER", if (p.root) "root" else "user")
    put("PATH", ContainerPaths.GUEST_PATH)
    put("LANG", "C.UTF-8")
    put("TERM", session.term)
    put("TMPDIR", "/tmp")
    if (p.display && session.display != null) put("DISPLAY", ":${session.display}")
    if (p.audio) put("PULSE_SERVER", "unix:${ContainerPaths.PULSE_GUEST}")
}
