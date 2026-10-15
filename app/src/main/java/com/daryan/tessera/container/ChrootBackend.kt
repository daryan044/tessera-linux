// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

/**
 * Root backend. Each launch gets its own mount namespace (and network namespace when network is
 * not granted), a minimal /dev, and runs as an unprivileged uid, so the manifest is enforced by
 * the kernel. The heavy lifting is in assets/chroot/enter.sh.
 */
class ChrootBackend(
    private val paths: ContainerPaths,
    private val su: String,
    /** The uid apps run as inside: the launcher's own (not 1000, which is Android's "system"). */
    private val appUid: Int = android.os.Process.myUid(),
) : ContainerBackend {
    override val name = NAME

    companion object {
        const val NAME = "Chroot"
    }

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
            "USB_ALL" to if (p.usb) "1" else "",
            // Bound like a device, but its group (Android's "system") isn't handed over.
            "GPU_DEVICE" to if (session.gpu) GpuDriver.KGSL else "",
            "X11_DIR" to if (p.display) paths.x11SocketDir(session).invariantSeparatorsPath else "",
            "RUN_AS_ROOT" to if (p.root) "1" else "0",
            "APP_UID" to appUid.toString(),
            // Android only lets members of AID_INET (3003) open sockets; grant it with network.
            "NET_GROUPS" to if (p.network) "3003,3004" else "",
            "GUEST_ENV" to guestEnv(app, session).map { (k, v) -> "$k=$v" }.joinToString("\n"),
            "CMD" to guestCommand(app).joinToString(" ") { shellQuote(it) },
        )
        return suLaunch(su, vars, p.network, paths.chrootScript)
    }

    override fun buildFlatpakLaunch(app: PermissionManifest, session: Session, env: Map<String, String>): LaunchSpec {
        val ref = requireNotNull(app.flatpak) { "${app.id} isn't a Flathub app" }
        val p = app.permissions
        val vars = linkedMapOf(
            "R" to paths.chrootRootfs.invariantSeparatorsPath,
            "FP_RUNTIME" to ref.runtimeDir,
            "FP_APP" to ref.appDir,
            "FP_GL" to ref.glDir.orEmpty(),
            "FP_GL_MOUNT" to ref.glMount.orEmpty(),
            "BINDS" to p.filesystem.joinToString("\n") { "${it.path}:${it.mountAt}:${if (it.readOnly) "ro" else "rw"}" },
            "GPU_DEVICE" to if (session.gpu) GpuDriver.KGSL else "",
            "X11_DIR" to paths.x11SocketDir(session).invariantSeparatorsPath,
            "APP_UID" to appUid.toString(),
            "NET_GROUPS" to if (p.network) "3003,3004" else "",
            "GUEST_ENV" to env.map { (k, v) -> "$k=$v" }.joinToString("\n"),
            "CMD" to app.exec.joinToString(" ") { shellQuote(it) },
        )
        return suLaunch(su, vars, p.network, paths.chrootFlatpakScript)
    }
}

/** Starts [vars] in a fresh namespace with [script]; network namespace too unless [network]. */
private fun suLaunch(su: String, vars: Map<String, String>, network: Boolean, script: java.io.File): LaunchSpec {
    val unshare = if (network) "unshare -m" else "unshare -m -n"
    val line = buildString {
        vars.forEach { (k, v) -> append("export $k=${shellQuote(v)}; ") }
        append("exec $unshare /system/bin/sh ${shellQuote(script.invariantSeparatorsPath)}")
    }
    return LaunchSpec(listOf(su, "-c", line), emptyMap(), "/")
}

/** Single-quotes [s] for POSIX sh. */
internal fun shellQuote(s: String): String =
    if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "@%+=:,./_-" }) s
    else "'" + s.replace("'", """'\''""") + "'"
