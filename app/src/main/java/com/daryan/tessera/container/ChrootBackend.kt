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
    /** The su to run as root with; also what ends a session's processes ([ProcessTree.killSessionAsRoot]). */
    internal val su: String,
    /** The uid apps run as inside: the launcher's own (not 1000, which is Android's "system"). */
    private val appUid: Int = android.os.Process.myUid(),
    /** Android's DNS servers now; enter.sh writes them while resolv.conf is automatic ([HostDns]). */
    private val dns: () -> List<String> = { emptyList() },
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
            "DNS" to HostDns.resolvConf(dns()),
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
            "DNS" to HostDns.resolvConf(dns()),
        )
        return suLaunch(su, vars, p.network, paths.chrootFlatpakScript)
    }
}

/** Starts [vars] in a fresh namespace with [script]; network namespace too unless [network]. */
private fun suLaunch(su: String, vars: Map<String, String>, network: Boolean, script: java.io.File): LaunchSpec {
    val flags = if (network) "-m" else "-m -n"
    val line = buildString {
        vars.forEach { (k, v) -> append("export $k=${shellQuote(v)}; ") }
        append(FIND_UNSHARE)
        append("exec \$UNSHARE $flags /system/bin/sh ${shellQuote(script.invariantSeparatorsPath)}")
    }
    return LaunchSpec(listOf(su, "-c", line), emptyMap(), "/")
}

/**
 * Sets UNSHARE: the phone's, when it has one that can make a mount namespace, else Debian's from
 * the chroot system at $R, started by Debian's loader with Debian's libc. Some phones have none
 * anywhere (a Galaxy Z Fold5 on One UI 7 with KernelSU, #5); every Debian system has util-linux.
 */
internal const val FIND_UNSHARE =
    "if unshare -m true 2>/dev/null; then UNSHARE=unshare; else " +
        "for ld in \"\$R\"/lib/ld-linux-aarch64.so.1 \"\$R\"/lib/ld-linux-armhf.so.3 \"\$R\"/lib64/ld-linux-x86-64.so.2; do " +
        "[ -e \"\$ld\" ] && break; done; " +
        "[ -e \"\$ld\" ] && [ -x \"\$R/usr/bin/unshare\" ] || " +
        "{ echo \"unshare not found, on the phone or in Linux at \$R (util-linux)\" >&2; exit 127; }; " +
        // The library path with the shell alone: such phones may lack the rest too (tr, on a Fold5).
        "lp=; for d in \"\$R\"/usr/lib/*-linux-gnu*; do lp=\${lp:+\$lp:}\$d; done; " +
        "UNSHARE=\"\$ld --library-path \$lp \$R/usr/bin/unshare\"; fi; "

/** Single-quotes [s] for POSIX sh. */
internal fun shellQuote(s: String): String =
    if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "@%+=:,./_-" }) s
    else "'" + s.replace("'", """'\''""") + "'"
