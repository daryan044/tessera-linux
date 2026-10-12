// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

/**
 * Root backend. Each launch gets its own mount namespace (and network namespace when network is
 * not granted), a minimal /dev, and runs as an unprivileged uid, so the manifest is enforced by
 * the kernel. The heavy lifting is in assets/chroot/enter.sh.
 */
class ChrootBackend(
    private val paths: ContainerPaths,
    private val su: String,
) : ContainerBackend {
    override val name = "Chroot"

    override fun enforcement(capability: Capability, app: PermissionManifest) =
        // Root inside a chroot can mount, load modules and walk out of it.
        if (app.permissions.root) Enforcement.ADVISORY else Enforcement.ENFORCED

    override fun buildLaunch(app: PermissionManifest, session: Session): LaunchSpec {
        val p = app.permissions
        val vars = linkedMapOf(
            "R" to paths.chrootRootfs.invariantSeparatorsPath,
            // One "src:dst:ro|rw" per line; enter.sh splits on newlines.
            "BINDS" to p.filesystem.joinToString("\n") {
                "${it.path}:${it.mountAt}:${if (it.readOnly) "ro" else "rw"}"
            },
            "DEVICES" to p.devices.joinToString("\n"),
            "X11_DIR" to if (p.display) paths.x11SocketDir(session).invariantSeparatorsPath else "",
            "PULSE" to if (p.audio) paths.pulseSocket.invariantSeparatorsPath else "",
            "RUN_AS_ROOT" to if (p.root) "1" else "0",
            // Android only lets members of AID_INET (3003) open sockets; grant it with network.
            "NET_GROUPS" to if (p.network) "3003,3004" else "",
            "GUEST_ENV" to guestEnv(app, session).map { (k, v) -> "$k=$v" }.joinToString("\n"),
            "CMD" to app.exec.joinToString(" ") { shellQuote(it) },
        )
        val unshare = if (p.network) "unshare -m" else "unshare -m -n"
        val script = buildString {
            vars.forEach { (k, v) -> append("export $k=${shellQuote(v)}; ") }
            append("exec $unshare /system/bin/sh ${shellQuote(paths.chrootScript.invariantSeparatorsPath)}")
        }
        return LaunchSpec(listOf(su, "-c", script), emptyMap(), "/")
    }
}

/** Single-quotes [s] for POSIX sh. */
internal fun shellQuote(s: String): String =
    if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "@%+=:,./_-" }) s
    else "'" + s.replace("'", """'\''""") + "'"
