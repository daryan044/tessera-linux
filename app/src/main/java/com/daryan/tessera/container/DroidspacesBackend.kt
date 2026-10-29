// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.util.Base64

/**
 * Root backend for a container of Droidspaces (https://github.com/ravindu644/Droidspaces-OSS):
 * a whole system with its own init, which the user made and runs beside Tessera. An app is
 * started inside the running container with `droidspaces run`, around assets/droidspaces/enter.sh,
 * which gives it a private mount namespace (and network namespace when network is not granted),
 * the way [ChrootBackend] does on the phone's side, and drops it to an unprivileged uid. So the
 * manifest is enforced by the kernel just the same.
 *
 * The container is its own mount namespace, so it sees only what Droidspaces put in it. Tessera's
 * session folders and the script are bound into it at its boot ([Droidspaces.bridgeBinds]); the
 * settings of each launch go to the script as small, separate arguments, so no environment has
 * to survive `droidspaces run`. Not as a file in the session's folder: apps run as the uid that
 * owns it.
 */
class DroidspacesBackend(
    private val paths: ContainerPaths,
    /** The su to run as root with; also what ends a session's processes ([ProcessTree.killSessionAsRoot]). */
    internal val su: String,
    /** Droidspaces' binary. */
    private val binary: String,
    /** The container the apps run in. */
    private val container: DroidspacesContainer,
    /** The uid apps run as inside: the launcher's own (not 1000, which is Android's "system"). */
    private val appUid: Int = android.os.Process.myUid(),
    /** Encodes the launch payload as one line; replaceable by a JVM-safe encoder in tests. */
    private val encodeSettings: (String) -> String = { text ->
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    },
) : ContainerBackend {
    override val name = NAME

    companion object {
        const val NAME = "Droidspaces"
        /** Below Droidspaces v7's 8192-byte per-argument cap, with room for future protocol changes. */
        internal const val SETTINGS_ARG_CHUNK_SIZE = 6000
    }

    /** The container's GPU node is among its devices, so hardware graphics can work in it. */
    val hasGpuDevice: Boolean get() = container.config.hasGpuDevice

    override fun enforcement(capability: Capability, app: PermissionManifest) =
        // Root inside the container can mount and walk out of the app's namespaces.
        if (app.permissions.root) Enforcement.ADVISORY else Enforcement.ENFORCED

    /** The folders [app] is granted that the container can't see, so they won't be there. */
    fun unreachableGrants(app: PermissionManifest): List<FsGrant> =
        app.permissions.filesystem.filter { DroidspacesView.visibleAt(container.config, it.path) == null }

    override fun buildLaunch(app: PermissionManifest, session: Session): LaunchSpec {
        // Droidspaces v7's daemon rejects any individual argv element above 8192 bytes.
        // GUI CMD includes the wrapper and embedded Python helpers, so even xterm's settings
        // exceed that limit. Base64 prevents literal newlines, and each quoted chunk stays below
        // the daemon's cap. enter.sh reassembles them without a mutable launch.env file.
        val payloadArgs = encodeSettings(settings(app, session))
            .chunked(SETTINGS_ARG_CHUNK_SIZE)
            .joinToString(" ") { shellQuote(it) }
        val line = "exec ${shellQuote(binary)} --name=${shellQuote(container.name)} run -- " +
            "/bin/sh ${shellQuote(Droidspaces.GUEST_SCRIPT)} ${shellQuote(session.id)} $payloadArgs"
        return LaunchSpec(listOf(su, "-c", line), emptyMap(), "/")
    }

    /** What enter.sh needs to know of [app]'s launch, as shell assignments, one per line. */
    internal fun settings(app: PermissionManifest, session: Session): String {
        val p = app.permissions
        val vars = linkedMapOf(
            // One "src:dst:ro|rw" per line, the source as the container sees it; folders the
            // container doesn't have are left out (see unreachableGrants).
            "BINDS" to p.filesystem.mapNotNull { g ->
                DroidspacesView.visibleAt(container.config, g.path)?.let { "$it:${g.mountAt}:${if (g.readOnly) "ro" else "rw"}" }
            }.joinToString("\n"),
            "DEVICES" to p.devices.joinToString("\n"),
            "USB_ALL" to if (p.usb) "1" else "",
            "GPU_DEVICE" to if (session.gpu) GpuDriver.KGSL else "",
            "X11" to if (p.display) "1" else "",
            "RUN_AS_ROOT" to if (p.root) "1" else "0",
            "APP_UID" to appUid.toString(),
            // Without network the app gets a network namespace with nothing in it.
            "NO_NET" to if (p.network) "" else "1",
            // Android only lets members of AID_INET (3003) open sockets; grant it with network.
            "NET_GROUPS" to if (p.network) "3003,3004" else "",
            "GUEST_ENV" to guestEnv(app, session).map { (k, v) -> "$k=$v" }.joinToString("\n"),
            "CMD" to guestCommand(app).joinToString(" ") { shellQuote(it) },
        )
        return vars.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=${shellQuote(v)}" }
    }
}
