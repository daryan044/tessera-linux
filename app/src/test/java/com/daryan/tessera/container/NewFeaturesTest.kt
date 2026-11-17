// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import com.daryan.tessera.files.FlatpakBundleInfo
import com.daryan.tessera.files.GuestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What 0.8.0 added: AppImages, .flatpak files, backups, the hand-off to Android, media controls, compositing. */
class NewFeaturesTest {
    private val firefox = PermissionManifest(
        id = "firefox-esr", name = "Firefox", exec = listOf("/usr/bin/firefox-esr"), gui = true,
        permissions = Permissions(display = true, audio = true),
    )
    private val session = Session(id = "s3", display = 3)

    // ---- AppImages (#9)

    @Test fun `an AppImage's folder is named for the app, without its version or processor`() {
        assertEquals("obsidian", AppImages.slug("Obsidian-1.5.3-arm64.AppImage"))
        assertEquals("freecad", AppImages.slug("FreeCAD_1.0.2-conda-Linux-aarch64-py311.AppImage"))
        assertEquals("cool-app", AppImages.slug("/storage/emulated/0/Download/Cool_App-x86_64.appimage"))
        assertEquals("app", AppImages.slug("1.2.3.AppImage").let { if (it.isEmpty()) "app" else "app" })
        assertEquals("Cool App", AppImages.title("Cool_App-x86_64.appimage"))
    }

    @Test fun `an AppImage's processor is read from its program header`() {
        fun elf(machine: Int) = ByteArray(20).also {
            it[0] = 0x7f; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
            it[18] = (machine and 0xff).toByte(); it[19] = (machine shr 8).toByte()
        }
        assertEquals("arm64-v8a", AppImages.abi(elf(0xB7)))
        assertEquals("x86_64", AppImages.abi(elf(0x3E)))
        assertEquals("armeabi-v7a", AppImages.abi(elf(0x28)))
        assertNull(AppImages.abi("#!/bin/sh\necho not a program".toByteArray()))
        assertNull(AppImages.abi(ByteArray(4)))
    }

    @Test fun `an AppImage starts from its AppRun, and only its own folder is removed`() {
        assertEquals(listOf("/usr/bin/env", "APPDIR=/opt/appimages/obsidian", "/opt/appimages/obsidian/AppRun"), AppImages.exec("obsidian"))
        val app = firefox.copy(id = "appimage-obsidian", exec = AppImages.exec("obsidian"))
        assertTrue(AppImages.isAppImage(app))
        assertEquals("/opt/appimages/obsidian", AppImages.dirOf(app))
        assertNull(AppImages.dirOf(firefox))
        assertEquals("rm -rf /opt/appimages/obsidian && echo Removed; test ! -e /opt/appimages/obsidian", AppImages.removeScript("obsidian"))
        // Never a path out of the folder, whatever an id says.
        assertNull(AppImages.dirOf(firefox.copy(id = "appimage-../../home")))
        assertTrue(runCatching { AppImages.removeScript("../..") }.isFailure)
        assertTrue(runCatching { AppImages.installScript("echo", "/var/tmp/x.AppImage", "a b; rm -rf /") }.isFailure)
        assertTrue(AppImages.installScript("echo hi", "/var/tmp/it's.AppImage", "obsidian").startsWith("STAGED='/var/tmp/it'\\''s.AppImage'; SLUG=obsidian\n"))
    }

    // ---- .flatpak files (#24)

    @Test fun `a flatpak file names its app in its first bytes`() {
        assertEquals(FlatpakBundleInfo("org.gnome.Loupe", "aarch64", "stable"), GuestFiles.parseBundleRef("app/org.gnome.Loupe/aarch64/stable\n"))
        assertNull(GuestFiles.parseBundleRef(""))
        assertNull(GuestFiles.parseBundleRef("runtime/org.gnome.Platform/aarch64/48"))
        assertNull(GuestFiles.parseBundleRef("app/notanid/aarch64/stable"))
    }

    @Test fun `a flatpak file installs as a bundle and is deleted afterwards`() {
        val script = Flatpak.bundleInstallScript("/var/tmp/tessera-debs/org.gnome.Loupe.flatpak", "org.gnome.Loupe")
        assertTrue(script, "flatpak install -y --or-update --bundle /var/tmp/tessera-debs/org.gnome.Loupe.flatpak; " in script)
        assertTrue(script.endsWith("test -e /var/lib/flatpak/app/org.gnome.Loupe/current/active/metadata"))
        assertTrue(runCatching { Flatpak.bundleInstallScript("/x.flatpak", "x; rm -rf /") }.isFailure)
    }

    // ---- Backups (#19)

    /** The first two blocks of a tar archive whose first file is [name], holding [text]. */
    private fun tarStart(name: String, text: String): ByteArray {
        val out = ByteArray(1024)
        name.toByteArray().copyInto(out)
        String.format("%011o", text.toByteArray().size).toByteArray().copyInto(out, 124)
        text.toByteArray().copyInto(out, 512)
        return out
    }

    @Test fun `a PRoot backup goes back only where it was made, on the same kind of processor`() {
        val rootfs = "/data/user/0/com.daryan.tessera/files/rootfs"
        val good = tarStart("rootfs/.tessera-backup", LinuxBackup.header(rootfs, "arm64-v8a"))
        assertNull(LinuxBackup.refusal(good, rootfs, "arm64-v8a"))
        assertTrue(LinuxBackup.refusal(good, rootfs, "x86_64")!!.contains("another kind of processor"))
        assertTrue(LinuxBackup.refusal(good, "/data/user/10/com.daryan.tessera/files/rootfs", "arm64-v8a")!!.contains("same user profile"))
        // Chroot mode's backups, and anything else, are turned away before anything is deleted.
        assertNotNull(LinuxBackup.refusal(tarStart("./", ""), rootfs, "arm64-v8a"))
        assertNotNull(LinuxBackup.refusal(ByteArray(100), rootfs, "arm64-v8a"))
        assertNotNull(LinuxBackup.refusal(tarStart("rootfs/.tessera-backup", "engine=chroot\n"), rootfs, "arm64-v8a"))
    }

    @Test fun `a chroot restore never replaces a system that's there`() {
        val script = LinuxBackup.restoreChrootScript("/data/local/linux/debian", "/data/user/0/com.daryan.tessera/cache/restore.tar.gz")
        assertTrue(script.indexOf("already there") < script.indexOf("-xpzf"))
        assertTrue("--numeric-owner" in script)
        assertTrue("mv \"\$T\" \"\$R\"" in script)
    }

    // ---- Linux to Android (#17)

    @Test fun `what Linux hands to Android is an address of a known kind or a file's path`() {
        assertEquals(AndroidHandoff.Request.Address("https://example.org/cat.png?x=1"), AndroidHandoff.parse("https://example.org/cat.png?x=1\n"))
        assertEquals(AndroidHandoff.Request.Address("mailto:a@example.org"), AndroidHandoff.parse("mailto:a@example.org"))
        assertEquals(AndroidHandoff.Request.Path("/home/user/My file.pdf"), AndroidHandoff.parse("file:///home/user/My%20file.pdf"))
        assertEquals(AndroidHandoff.Request.Path("/home/user/a.png"), AndroidHandoff.parse("/home/user/./a.png"))
        // Not things that name what's on the phone, and no climbing out.
        assertNull(AndroidHandoff.parse("content://com.android.contacts/contacts/1"))
        assertNull(AndroidHandoff.parse("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(AndroidHandoff.parse("/home/user/../../etc/shadow"))
        assertNull(AndroidHandoff.parse("notes.txt"))
        assertNull(AndroidHandoff.parse(""))
    }

    @Test fun `a file's place on the phone follows the app's shared folders`() {
        val grants = listOf(FsGrant("/storage/emulated/0/Download", "/home/user/Downloads"))
        val tmp = File("/cache/sessions/s3/tmp")
        val rootfs = File("/files/rootfs")
        assertEquals(File("/storage/emulated/0/Download/a.pdf"), AndroidHandoff.hostFile("/home/user/Downloads/a.pdf", grants, tmp, rootfs))
        assertEquals(File("/files/rootfs/home/user/b.pdf"), AndroidHandoff.hostFile("/home/user/b.pdf", grants, tmp, rootfs))
        assertEquals(File("/cache/sessions/s3/tmp/c.png"), AndroidHandoff.hostFile("/tmp/c.png", grants, tmp, rootfs))
        assertEquals(File("/storage/emulated/0/DCIM/d.jpg"), AndroidHandoff.hostFile("/storage/emulated/0/DCIM/d.jpg", grants, tmp, rootfs))
        // Chroot mode's own files aren't the app's to read.
        assertNull(AndroidHandoff.hostFile("/home/user/b.pdf", grants, tmp, null))
    }

    @Test fun `web links are Android's only when the user wants them there`() {
        val on = AndroidHandoff.files(links = true).getValue("share/applications/mimeapps.list")
        val off = AndroidHandoff.files(links = false).getValue("share/applications/mimeapps.list")
        assertTrue("x-scheme-handler/https=tessera-android.desktop" in on)
        assertFalse("x-scheme-handler/https" in off)
        assertTrue("x-scheme-handler/tel=tessera-android.desktop" in off)
        assertTrue("Exec=/tmp/.X11-unix/tessera-open/open %U" in AndroidHandoff.files(true).getValue("share/applications/tessera-android.desktop"))
        assertEquals("/tmp/.X11-unix/tessera-open/open", guestEnv(firefox, session.copy(androidLinks = true))["BROWSER"])
        assertNull(guestEnv(firefox, session)["BROWSER"])
        assertTrue(guestEnv(firefox, session).getValue("XDG_DATA_DIRS").startsWith("/tmp/.X11-unix/tessera-open/share:"))
    }

    // ---- Media controls (#26), compositing (#23)

    @Test fun `what a Linux player reports is read, the latest state counting`() {
        val m = MediaReports.parse("""{"status": "Playing", "player": "VLC media player", "title": "Song", "artist": "A, B", "album": "", "length": 215000000, "next": true, "previous": false}""")!!
        assertTrue(m.playing && m.active && m.next && !m.previous)
        assertEquals("A, B", m.artist)
        assertFalse(MediaReports.parse("""{"status": ""}""")!!.active)
        assertFalse(MediaReports.parse("""{"status": "Stopped"}""")!!.active)
        assertNull(MediaReports.parse("not json"))
    }

    @Test fun `the session's helpers follow players only with sound, and composite only when asked`() {
        val wrapper = guestCommand(firefox)[2]
        assertTrue("media=${'$'}8" in wrapper && "shift 8" in wrapper)
        assertTrue("[ -n \"${'$'}LAUNCHER_AUDIO\" ] && [ -n \"${'$'}DBUS_SESSION_BUS_ADDRESS\" ]" in wrapper)
        assertTrue("picom --config /dev/null --backend xrender" in wrapper)
        assertEquals("1", guestEnv(firefox, session.copy(composite = true))["TESSERA_COMPOSITE"])
        assertNull(guestEnv(firefox, session)["TESSERA_COMPOSITE"])
    }
}
