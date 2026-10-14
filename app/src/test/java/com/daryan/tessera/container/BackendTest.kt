// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertTrue(denied[2].endsWith("exec unshare -m -n /system/bin/sh /app/files/chroot/enter.sh"))
        assertTrue("export NET_GROUPS=''" in denied[2])

        val granted = chroot.buildLaunch(firefox, session).argv[2]
        assertTrue(granted.endsWith("exec unshare -m /system/bin/sh /app/files/chroot/enter.sh"))
        assertTrue("export NET_GROUPS=3003,3004" in granted)
        assertTrue("export X11_DIR=/app/cache/sessions/s3/tmp/.X11-unix" in granted)
    }

    @Test fun `chroot quotes the command so arguments survive the shell`() {
        val script = ChrootBackend(paths, "su", appUid = 10123).buildLaunch(firefox, session).argv[2]
        // Two layers: argv joined into CMD for `sh -c`, then CMD quoted again for `export`.
        val cmd = guestCommand(firefox).joinToString(" ") { shellQuote(it) }
        assertTrue(cmd.endsWith("/usr/bin/firefox-esr --new-window 'it'\\''s'"))
        assertTrue(script.contains("export CMD=${shellQuote(cmd)};"))
    }

    @Test fun `chroot enforcement drops to advisory for root apps`() {
        val chroot = ChrootBackend(paths, "su", appUid = 10123)
        val rootApp = offline.copy(permissions = Permissions(root = true))
        assertEquals(Enforcement.ENFORCED, chroot.enforcement(Capability.NETWORK, offline))
        assertEquals(Enforcement.ADVISORY, chroot.enforcement(Capability.NETWORK, rootApp))
    }

    @Test fun `gui apps run next to a window manager, terminal apps run bare`() {
        val gui = guestCommand(firefox)
        assertEquals(listOf("/bin/sh", "-c"), gui.take(2))
        assertTrue("matchbox-window-manager" in gui[2])
        assertEquals("launch", gui[3])
        assertTrue("pyatspi" in gui[4])
        assertEquals(FOCUS_FILE_GUEST, gui[5])
        assertTrue("Xlib" in gui[6])
        assertEquals(listOf(WINDOWS_FILE_GUEST) + firefox.exec, gui.drop(7))
        assertEquals(offline.exec, guestCommand(offline))
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
