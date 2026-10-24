// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

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
            if (session.usbFds.isEmpty()) {
                p.devices.filter(exists).forEach { bind(it) }
            } else {
                // Bridged USB devices are opened through the shim, which these stand-ins list.
                p.devices.filter { exists(it) && !it.startsWith(UsbBridge.USB_PATH) }.forEach { bind(it) }
                bind(paths.sessionUsb(session).invariantSeparatorsPath, UsbBridge.USB_PATH.removeSuffix("/"))
            }

            add("/usr/bin/env")
            add("-i")
            val env = guestEnv(app, session) + SANDBOX_OFF
            val usb = if (session.usbFds.isEmpty()) emptyMap() else UsbBridge.env(session.usbFds, env)
            addAll((env + usb).map { (k, v) -> "$k=$v" })
            addAll(guestCommand(app))
        }
        val hostEnv = buildMap {
            put("PROOT_TMP_DIR", paths.cacheDir.invariantSeparatorsPath)
            put("PROOT_LOADER", paths.prootLoader.invariantSeparatorsPath)
            put("PROOT_LOADER_32", paths.prootLoader32.invariantSeparatorsPath)
        }
        return LaunchSpec(argv, hostEnv, paths.filesDir.invariantSeparatorsPath)
    }

    override fun buildFlatpakLaunch(app: PermissionManifest, session: Session, env: Map<String, String>): LaunchSpec {
        val ref = requireNotNull(app.flatpak) { "${app.id} isn't a Flathub app" }
        val p = app.permissions
        val rootfs = paths.prootRootfs.invariantSeparatorsPath
        // Launcher entries are shared by both engines, but each has its own system: an app
        // installed in chroot mode isn't here, and PRoot would only say /usr/bin/env is missing.
        check(exists("$rootfs${ref.appDir}") && exists("$rootfs${ref.runtimeDir}")) {
            "it isn't installed in PRoot's Linux system (each engine has its own). " +
                "Install it from Flathub again here, or switch to the Chroot engine."
        }
        val skeleton = FlatpakSkeleton.ensure(paths.flatpakSkeleton)
        val argv = buildList {
            add(paths.proot.invariantSeparatorsPath)
            add("--kill-on-exit")
            appId?.let { (uid, gid) -> add("--change-id=$uid:$gid") }
            add("--rootfs=${skeleton.invariantSeparatorsPath}")
            add("--cwd=/home/user")
            bind("$rootfs${ref.runtimeDir}", "/usr")
            bind("$rootfs${ref.appDir}", "/app")
            if (ref.glDir != null && ref.glMount != null) bind("$rootfs${ref.glDir}", ref.glMount)
            // Flatpak's store hard-links every file; PRoot (--link2symlink) made those symlinks
            // to absolute paths in the container, which must resolve here too.
            bind("$rootfs${Flatpak.ROOT}")
            bind("/dev")
            bind("/proc")
            fakeProc.forEach { bind(File(paths.fakeProcDir, it).invariantSeparatorsPath, "/proc/$it") }
            bind("/sys")
            bind("/dev/urandom", "/dev/random")
            bind(paths.sessionTmp(session).invariantSeparatorsPath, "/tmp")
            bind(paths.sessionShm(session).invariantSeparatorsPath, "/dev/shm")
            // The same home (and so the same app data, ~/.var/app/<id>) as in the container.
            bind("$rootfs/home/user", "/home/user")
            for (f in listOf("passwd", "group", "hosts", "resolv.conf")) {
                if (exists("$rootfs/etc/$f")) bind("$rootfs/etc/$f", "/etc/$f")
            }
            // The container's fonts, which the runtime's fontconfig looks for under /run/host.
            bind("$rootfs/usr/share/fonts", "/run/host/fonts")
            if (session.gpu) bind("$rootfs${GpuDriver.GUEST_DIR}", GpuDriver.GUEST_DIR)
            if (exists("$rootfs${ThemePack.GUEST_DIR}")) bind("$rootfs${ThemePack.GUEST_DIR}", ThemePack.GUEST_DIR)
            p.filesystem.filter { exists(it.path) }.forEach { bind(it.path, it.mountAt) }
            add("/usr/bin/env")
            add("-i")
            addAll((env + SANDBOX_OFF).map { (k, v) -> "$k=$v" })
            addAll(app.exec)
        }
        val hostEnv = mapOf(
            "PROOT_TMP_DIR" to paths.cacheDir.invariantSeparatorsPath,
            "PROOT_LOADER" to paths.prootLoader.invariantSeparatorsPath,
            "PROOT_LOADER_32" to paths.prootLoader32.invariantSeparatorsPath,
        )
        return LaunchSpec(argv, hostEnv, paths.filesDir.invariantSeparatorsPath)
    }

    private fun MutableList<String>.bind(host: String, guest: String = host) {
        add(if (host == guest) "--bind=$host" else "--bind=$host:$guest")
    }
}

/**
 * Firefox's per-process sandboxes install seccomp filters that clash with PRoot's ptrace, and
 * its child processes die with SIGSEGV. Electron's does the same and dies with SIGTRAP, taking
 * VS Code and every other Electron app with it. PRoot mode can't offer that isolation anyway;
 * chroot mode keeps the sandbox.
 */
private val SANDBOX_OFF = mapOf(
    "MOZ_DISABLE_CONTENT_SANDBOX" to "1",
    "MOZ_DISABLE_GMP_SANDBOX" to "1",
    "MOZ_DISABLE_RDD_SANDBOX" to "1",
    "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX" to "1",
    "MOZ_DISABLE_UTILITY_SANDBOX" to "1",
    // Electron turns this into --no-sandbox itself; it has no MOZ_-style per-process switches.
    "ELECTRON_DISABLE_SANDBOX" to "1",
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
    // Inherited by everything the session starts, daemons that detach from its process tree
    // included (D-Bus, the accessibility bus), so ending the session can find them all.
    put(SESSION_ENV, session.id)
    if (p.display && session.display != null) {
        put("DISPLAY", ":${session.display}")
        // Termux:X11 sends real multi-touch events; Firefox only uses them (finger scrolling,
        // pinch zoom) with XInput2 on, otherwise it treats a finger as a mouse drag.
        put("MOZ_USE_XINPUT2", "1")
        // Each session has its own display: Firefox handing a window to "the running copy"
        // would put it in another app's window, and a copy we killed leaves a remote endpoint
        // that never answers ("Firefox is already running, but is not responding").
        put("MOZ_NO_REMOTE", "1")
        // Ending a session kills Firefox, which it counts as a crash at startup; after three it
        // opens a "closed unexpectedly" prompt offering Safe Mode instead of the browser.
        put("MOZ_DISABLE_AUTO_SAFE_MODE", "1")
        putAll(ThemePack.CURSOR_ENV)
        // Accessibility on, so the focus watcher hears about text fields (see guestCommand).
        // GTK 3/4 report over AT-SPI by default; Firefox and Qt need asking.
        put("GNOME_ACCESSIBILITY", "1")
        put("QT_LINUX_ACCESSIBILITY_ALWAYS_ON", "1")
        // Termux:X11 advertises DRI3, but Mesa's DRI3 path then waits forever on a fence the
        // server never signals: once Mesa is installed (Qt apps pull it in), every GTK app,
        // Firefox included, hangs at startup with a black window. Mesa's other path works.
        put("LIBGL_DRI3_DISABLE", "1")
        // That covers GLX only. GTK 4 apps (HandBrake) set up EGL, which takes the same path and
        // draws black; Mesa's software rasterizer presents through plain X images instead.
        if (session.gpu) putAll(GpuDriver.env(session.gpuFixes)) else put("LIBGL_ALWAYS_SOFTWARE", "1")
        if (session.theme) putAll(ThemeFiles.ENV)
    }
    if (p.audio && p.display && session.display != null) {
        // The session's own PulseAudio, started by guestCommand; see AudioPipe for the rest. A
        // Flathub app shares only the X11 directory with the session, so its sound goes there.
        val pulse = if (session.flatpak) "/tmp/.X11-unix/pulse" else "/tmp/pulse"
        put("LAUNCHER_AUDIO", "1")
        put("PULSE_RUNTIME_PATH", pulse)
        put("PULSE_SERVER", "unix:$pulse/native")
        // The pipe sink takes ~85 ms at a time; apps asking for less (OpenAL games, at ~60 ms)
        // ran dry each time and played 21 ms of silence every 85: choppy sound. 110 was the
        // least that left no gaps.
        put("PULSE_LATENCY_MSEC", "120")
    }
    if (session.flatpak) put("TESSERA_FLATPAK", "1")
    session.adb?.let { put(PhoneAdb.ENV, it) }
}
