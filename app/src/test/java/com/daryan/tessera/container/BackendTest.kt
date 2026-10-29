// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackendTest {
    private val paths = ContainerPaths(
        nativeLibDir = File("/app/lib"),
        filesDir = File("/app/files"),
        cacheDir = File("/app/cache"),
        prootRootfs = File("/app/files/rootfs/debian"),
        chrootRootfs = File("/data/local/linux/debian"),
    )

    private val firefox = PermissionManifest(
        id = "firefox-esr",
        name = "Firefox",
        gui = true,
        exec = listOf("/usr/bin/firefox-esr", "--new-window", "it's"),
        permissions = Permissions(
            network = true,
            display = true,
            filesystem = listOf(FsGrant("/storage/emulated/0/Download", "/home/user/Downloads")),
        ),
    )
    private val offline = PermissionManifest(id = "htop", name = "htop", exec = listOf("/usr/bin/htop"))
    private val session = Session(id = "s3", display = 3)

    @Test fun `proot binds only granted paths and runs the app under env -i`() {
        val spec = ProotBackend(paths, exists = { true }).buildLaunch(firefox, session)
        assertEquals("/app/lib/libproot.so", spec.executable)
        assertTrue("--rootfs=/app/files/rootfs/debian" in spec.argv)
        assertTrue("--bind=/storage/emulated/0/Download:/home/user/Downloads" in spec.argv)
        assertTrue("--bind=/app/cache/sessions/s3/tmp:/tmp" in spec.argv)
        assertTrue("--bind=/app/cache/sessions/s3/shm:/dev/shm" in spec.argv)
        assertTrue("MOZ_DISABLE_CONTENT_SANDBOX=1" in spec.argv)
        assertTrue("ELECTRON_DISABLE_SANDBOX=1" in spec.argv)
        assertFalse(spec.argv.any { it.startsWith("--bind=/storage/emulated/0:") || it == "--bind=/storage" })
        assertTrue("DISPLAY=:3" in spec.argv)
        assertTrue("MOZ_USE_XINPUT2=1" in spec.argv)
        assertTrue("MOZ_NO_REMOTE=1" in spec.argv)
        assertEquals(firefox.exec, spec.argv.takeLast(3))
        assertEquals("/app/lib/libproot-loader.so", spec.env["PROOT_LOADER"])
    }

    @Test fun `non-root proot sessions emulate set-id calls as the app user`() {
        val proot = ProotBackend(paths, exists = { true }, appId = 10484 to 10484)
        assertTrue("--change-id=10484:10484" in proot.buildLaunch(offline, session).argv)
        val root = proot.buildLaunch(offline.copy(permissions = Permissions(root = true)), session).argv
        assertTrue("--root-id" in root)
        assertFalse(root.any { it.startsWith("--change-id") })
        // Root sessions (installs) see Tessera's proot, for the bubblewrap stand-in; apps don't.
        assertTrue("--bind=/app/lib:/opt/tessera-proot" in root)
        assertFalse(proot.buildLaunch(offline, session).argv.any { "tessera-proot" in it })
    }

    @Test fun `proot cannot withhold network and says so`() {
        val proot = ProotBackend(paths, exists = { true })
        assertEquals(Enforcement.UNAVAILABLE, proot.enforcement(Capability.NETWORK, offline))
        assertEquals(Enforcement.ADVISORY, proot.enforcement(Capability.FILESYSTEM, offline))
    }

    @Test fun `chroot isolates network unless granted`() {
        val chroot = ChrootBackend(paths, "/system/bin/su", appUid = 10123)
        val denied = chroot.buildLaunch(offline, Session("s1")).argv
        assertEquals("/system/bin/su", denied[0])
        assertTrue(denied[2].endsWith("exec \$UNSHARE -m -n /system/bin/sh /app/files/chroot/enter.sh"))
        assertTrue("export NET_GROUPS=''" in denied[2])

        val granted = chroot.buildLaunch(firefox, session).argv[2]
        assertTrue(granted.endsWith("exec \$UNSHARE -m /system/bin/sh /app/files/chroot/enter.sh"))
        assertTrue("export NET_GROUPS=3003,3004" in granted)
        assertTrue("export X11_DIR=/app/cache/sessions/s3/tmp/.X11-unix" in granted)
    }

    @get:Rule val tmp = TemporaryFolder()

    /** Runs [FIND_UNSHARE] on a phone without unshare, with Linux at [root]; prints UNSHARE. */
    private fun findUnshare(root: File): Pair<Int, String> {
        val sh = File("/bin/sh")
        assumeTrue(sh.canExecute())
        // A function hides any unshare the machine running the test has.
        val proc = ProcessBuilder(sh.path, "-c", "unshare() { return 1; }; R=${root.path}; ${FIND_UNSHARE}echo \"\$UNSHARE\"")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        return proc.waitFor() to out
    }

    @Test fun `chroot falls back to Debian's unshare when the phone has none`() {
        val root = tmp.root
        File(root, "lib/aarch64-linux-gnu").mkdirs()
        File(root, "lib/ld-linux-aarch64.so.1").writeText("")
        File(root, "usr/lib/aarch64-linux-gnu").mkdirs()
        File(root, "usr/bin/unshare").apply { parentFile?.mkdirs(); writeText(""); setExecutable(true) }
        val (code, out) = findUnshare(root)
        assertEquals(0, code)
        assertEquals("$root/lib/ld-linux-aarch64.so.1 --library-path $root/usr/lib/aarch64-linux-gnu $root/usr/bin/unshare", out)
    }

    @Test fun `chroot says so when there's no unshare at all`() {
        val (code, out) = findUnshare(tmp.root)
        assertEquals(127, code)
        assertTrue(out, out.startsWith("unshare not found"))
    }

    @Test fun `chroot quotes the command so arguments survive the shell`() {
        val script = ChrootBackend(paths, "su", appUid = 10123).buildLaunch(firefox, session).argv[2]
        // Two layers: argv joined into CMD for `sh -c`, then CMD quoted again for `export`.
        val cmd = guestCommand(firefox).joinToString(" ") { shellQuote(it) }
        assertTrue(cmd.endsWith("/usr/bin/firefox-esr --new-window 'it'\\''s'"))
        assertTrue(script.contains("export CMD=${shellQuote(cmd)};"))
    }

    @Test fun `chroot shares every USB device only when asked`() {
        val chroot = ChrootBackend(paths, "su", appUid = 10123)
        val usbApp = offline.copy(permissions = Permissions(root = true, usb = true))
        assertTrue("export USB_ALL=1;" in chroot.buildLaunch(usbApp, session).argv[2])
        assertTrue("export USB_ALL='';" in chroot.buildLaunch(offline, session).argv[2])
    }

    @Test fun `only sessions given adb to this phone are told where it is`() {
        val proot = ProotBackend(paths, exists = { true })
        assertTrue("${PhoneAdb.ENV}=127.0.0.1:5555" in proot.buildLaunch(offline, session.copy(adb = "127.0.0.1:5555")).argv)
        assertFalse(proot.buildLaunch(offline, session).argv.any { it.startsWith("${PhoneAdb.ENV}=") })
        val chroot = ChrootBackend(paths, su = "su", appUid = 10484).buildLaunch(offline, session.copy(adb = "127.0.0.1:5555"))
        assertTrue("${PhoneAdb.ENV}=127.0.0.1:5555" in chroot.argv.last())
    }

    @Test fun `chroot enforcement drops to advisory for root apps`() {
        val chroot = ChrootBackend(paths, "su", appUid = 10123)
        val rootApp = offline.copy(permissions = Permissions(root = true))
        assertEquals(Enforcement.ENFORCED, chroot.enforcement(Capability.NETWORK, offline))
        assertEquals(Enforcement.ADVISORY, chroot.enforcement(Capability.NETWORK, rootApp))
    }

    @Test fun `software-only GUI sessions use GTK 4 Cairo renderer`() {
        assertEquals("cairo", guestEnv(firefox, session.copy(gpu = false))["GSK_RENDERER"])
    }

    @Test fun `GUI wrapper keeps its mode separate from the window manager pid`() {
        val wrapper = guestCommand(firefox)[2]
        assertTrue("wm_mode=${'$'}6" in wrapper)
        assertTrue("wm_pid=${'$'}!" in wrapper)
        assertTrue("[ \"${'$'}wm_mode\" = app ]" in wrapper)
        assertFalse("wm=${'$'}!" in wrapper)
    }

    @Test fun `themed sessions get the theme's directory and data dirs`() {
        val env = guestEnv(firefox, session.copy(theme = true))
        assertEquals(ThemeFiles.GUEST_DIR, env["TESSERA_THEME"])
        assertEquals("${ThemeFiles.GUEST_DIR}/share:/usr/local/share:/usr/share", env["XDG_DATA_DIRS"])
        assertFalse("TESSERA_THEME" in guestEnv(firefox, session))
        assertEquals(File("/app/cache/sessions/s3/tmp/.X11-unix/tessera-theme"), paths.sessionTheme(session))
    }

    @Test fun `gui apps run next to a window manager, terminal apps run bare`() {
        val gui = guestCommand(firefox)
        assertEquals(listOf("/bin/sh", "-c"), gui.take(2))
        assertTrue("matchbox-window-manager" in gui[2])
        assertEquals("launch", gui[3])
        assertTrue("pyatspi" in gui[4])
        assertEquals(FOCUS_FILE_GUEST, gui[5])
        assertTrue("Xlib" in gui[6])
        assertEquals(WINDOWS_FILE_GUEST, gui[7])
        assertTrue("org.freedesktop.portal.Settings" in gui[8])
        assertEquals("app", gui[9])
        assertTrue("org.freedesktop.Notifications" in gui[10])
        assertEquals(firefox.exec, gui.drop(11))
        assertEquals(offline.exec, guestCommand(offline))
    }

    @Test fun `full resolution tells each toolkit how much larger to draw`() {
        val at250 = uiScaleEnv(250)
        assertEquals("2.50", at250["TESSERA_UI_SCALE"])
        // GTK only scales widgets by whole numbers; the rest goes to its fonts.
        assertEquals("2", at250["GDK_SCALE"])
        assertEquals("1.25", at250["GDK_DPI_SCALE"])
        // Firefox's own factor replaces GDK_SCALE and is multiplied by GDK_DPI_SCALE.
        assertEquals("2.00", at250["TESSERA_FIREFOX_SCALE"])
        assertEquals("2.50", at250["QT_SCALE_FACTOR"])
        assertEquals("60", at250["XCURSOR_SIZE"])
        val at100 = uiScaleEnv(100)
        assertEquals("1", at100["GDK_SCALE"])
        assertEquals("1.00", at100["GDK_DPI_SCALE"])
        // Stretched sessions get none of it.
        assertFalse("TESSERA_UI_SCALE" in guestEnv(firefox, session))
        assertEquals("1.80", guestEnv(firefox, session.copy(uiScale = 180))["TESSERA_UI_SCALE"])
    }

    @Test fun `a desktop runs its window manager beside the desktop helper, not matchbox`() {
        val desktop = firefox.copy(id = "desktop", desktop = true, exec = listOf("/usr/bin/xfwm4"))
        val cmd = guestCommand(desktop)
        assertEquals(guestCommand(firefox).take(9), cmd.take(9))
        assertEquals("desktop", cmd[9])
        assertEquals(guestCommand(firefox)[10], cmd[10])
        assertEquals(listOf("/bin/sh", "-c", DESKTOP_SESSION, "desktop", DESKTOP_HELPER), cmd.subList(11, 16))
        assertEquals(listOf("/usr/bin/xfwm4"), cmd.drop(16))
        assertTrue(desktop.validate().isEmpty())
        assertEquals(listOf("a desktop needs gui"), offline.copy(desktop = true).validate())
    }

    @Test fun `shellQuote round-trips awkward strings`() {
        assertEquals("plain/path-1.0", shellQuote("plain/path-1.0"))
        assertEquals("''", shellQuote(""))
        assertEquals("'a b'", shellQuote("a b"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("'\$HOME'", shellQuote("\$HOME"))
    }
}

class PermissionManifestTest {
    @Test fun `parses sample manifests`() {
        val dir = File("src/main/assets/apps")
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("no sample manifests found in $dir", files.isNotEmpty())
        files.forEach { f ->
            val m = PermissionManifest.parse(f.readText())
            assertEquals("${f.name}: ${m.validate()}", emptyList<String>(), m.validate())
        }
    }

    @Test fun `rejects grants that escape or shadow the system`() {
        assertFalse(FsGrant("/sdcard/../data").validate().isEmpty())
        assertFalse(FsGrant("/sdcard", "/etc").validate().isEmpty())
        assertFalse(FsGrant("/sdcard", "/usr/lib/x").validate().isEmpty())
        assertFalse(FsGrant("/sdcard", "/").validate().isEmpty())
        assertFalse(FsGrant("relative").validate().isEmpty())
        assertTrue(FsGrant("/storage/emulated/0/Music", "/home/user/Music").validate().isEmpty())
    }

    @Test fun `gui apps must ask for a display`() {
        val m = PermissionManifest(id = "x", name = "x", exec = listOf("x"), gui = true)
        assertTrue(m.validate().any { "display" in it })
    }

    @Test fun `bad devices and ids are rejected`() {
        val m = PermissionManifest(
            id = "Bad Id", name = "x", exec = listOf("x"),
            permissions = Permissions(devices = listOf("/sys/class/x", "/dev/../etc")),
        )
        assertEquals(3, m.validate().size)
    }
}
