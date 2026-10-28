// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallQueueTest {
    @Test fun `apt status lines map onto one rising progress figure`() {
        val p = AptProgress()
        assertEquals(0f to "Refreshing package lists", p.feed("##phase update"))
        assertEquals(0.05f, p.feed("dlstatus:1:50.0:Retrieving file 1 of 3")!!.first!!, 0.001f)
        assertEquals(0.1f, p.feed("##phase main")!!.first!!, 0.001f)
        val dl = p.feed("dlstatus:7:50.0:Retrieving file 7 of 40")!!
        assertEquals(0.325f, dl.first!!, 0.001f)
        assertEquals("Retrieving file 7 of 40", dl.second)
        val dpkg = p.feed("pmstatus:vlc:80.0:Setting up vlc (3.0.21-10)")!!
        assertEquals(0.91f, dpkg.first!!, 0.001f)
        assertEquals("Setting up vlc (3.0.21-10)", dpkg.second)
        // Ordinary apt output isn't progress.
        assertNull(p.feed("Reading package lists..."))
        assertNull(p.feed("Get:1 http://deb.debian.org/debian trixie/main arm64 vlc 3.0.21"))
    }

    @Test fun `install script refreshes lists first and reports through status-fd`() {
        val job = QueueJob(1, "VLC", listOf("vlc", "matchbox-window-manager"), QueueAction.INSTALL, null)
        val s = InstallQueue.script(job)
        assertTrue(s.indexOf("apt-get update") < s.indexOf("apt-get install"))
        assertTrue("-o APT::Status-Fd=1" in s)
        assertTrue("--no-install-recommends -o APT::Status-Fd=1 -o Dpkg::Use-Pty=0 -o Dpkg::Progress-Fancy=0 vlc matchbox-window-manager ||" in s)
        // A failed install finishes pending package setup and tries once more.
        assertTrue(s.indexOf("dpkg --configure -a") > s.indexOf("apt-get install"))
        assertTrue(s.endsWith("vlc matchbox-window-manager; }"))
        val remove = InstallQueue.script(job.copy(action = QueueAction.REMOVE, packages = listOf("vlc")))
        assertTrue("apt-get update" !in remove && "apt-get remove -y" in remove)
    }

    @Test fun `a job killed by a signal says which, and only the user's stop reads Stopped`() {
        // As reported from a Blackview Tab 13 on Android 12: SIGKILL, no output.
        assertTrue("signal 9" in InstallQueue.ended(9, byUser = false))
        assertTrue("background process limit" in InstallQueue.ended(9, byUser = false))
        assertEquals("Ended by signal 11", InstallQueue.ended(11, byUser = false))
        assertEquals("Stopped", InstallQueue.ended(9, byUser = true))
    }

    @Test fun `flatpak's bar maps onto one figure over its steps, naming the runtimes`() {
        // As flatpak draws it in a terminal, without its colors.
        val p = FlatpakProgress("org.kde.kate")
        assertEquals(null to "Looking for matches…", p.feed("Looking for matches…"))
        assertNull(p.feed("        ID                       Arch    Branch Op Remote  Download"))
        assertNull(p.feed(" 1.     org.kde.Platform         aarch64 6.9    i  flathub < 400.1 MB"))
        assertNull(p.feed(" 2.     org.kde.kate             aarch64 stable i  flathub  < 30.2 MB"))
        val rt = p.feed("Installing 1/2… ████▌                 23%  1.2 MB/s  05:12")!!
        assertEquals(0.115f, rt.first!!, 0.001f)
        assertEquals("Installing runtime org.kde.Platform (1 of 2), 1.2 MB/s", rt.second)
        val app = p.feed("Installing 2/2… ██████████          50%  800.0 kB/s  00:20")!!
        assertEquals(0.75f, app.first!!, 0.001f)
        assertEquals("Installing (2 of 2), 800.0 kB/s", app.second)
        assertTrue(FlatpakProgress.isProgress("Installing 2/2… ███ 50%"))
        // As a phone printed it: a no-break space before the unit, and no speed while it starts.
        assertEquals("Installing runtime org.kde.Platform (1 of 3), 454.6\u00A0kB/s", p.feed("Installing 1/3… █       75%  454.6\u00A0kB/s")!!.second)
        assertEquals("Installing (2 of 2)", p.feed("Installing 2/2…                        0%  0\u00A0bytes/s")!!.second)
        // After the bar, bubblewrap's failures aren't news.
        assertNull(p.feed("bwrap: Creating new namespace failed: Invalid argument"))
        // One step: no count.
        assertEquals(1f to "Installing", FlatpakProgress("net.zdechov.app.x2048").feed("Installing… ████████ 100%"))
    }

    @Test fun `flatpak's redrawn table and bar, all on one line, still read as progress`() {
        // What flatpak sends when its cursor queries are answered, as a phone sent it: back to the
        // top, clear, then the table's rows padded to the width and the bar, with no line breaks.
        val nb = " "
        val raw = "\u001B[?25l\u001B[6n\u001B[1;1H\u001B[0J" +
            " 1. [|] org.freedesktop.Platform.openh264 aarch64 2.5.1  i  flathub < 930.4${nb}kB" +
            " 2. [ ] org._2006Scape.Launcher           aarch64 stable i  flathub  < 61.6${nb}MB" +
            "Installing 1/2… \u001B[2m████████ \u001B[22m             40%  217.0${nb}kB/s" +
            "\u001B[6n\u001B[1;1H\u001B[0J" +
            " 1. [✓] org.freedesktop.Platform.openh264 aarch64 2.5.1  i  flathub 930.4${nb}kB / 930.4${nb}kB" +
            " 2. [/] org._2006Scape.Launcher           aarch64 stable i  flathub  1.0${nb}MB / 61.6${nb}MB" +
            "Installing 2/2… \u001B[2m█ \u001B[22m             5%"
        var asked = 0
        val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
        val chunks = terminalLines(java.io.StringReader(raw)) { asked++ }.map { it.replace(ansi, "") }.filter { it.isNotBlank() }.toList()
        assertEquals(2, asked)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all(FlatpakProgress::isProgress))
        val p = FlatpakProgress("org._2006Scape.Launcher")
        val rt = p.feed(chunks[0])!!
        assertEquals(0.2f, rt.first!!, 0.001f)
        assertEquals("Installing runtime org.freedesktop.Platform.openh264 (1 of 2), 217.0${nb}kB/s", rt.second)
        val app = p.feed(chunks[1])!!
        assertEquals(0.525f, app.first!!, 0.001f)
        assertEquals("Installing (2 of 2)", app.second)
    }

    @Test fun `terminal lines split at returns, and cursor queries are answered at once`() {
        var asked = 0
        val text = "Looking\r\n\u001B[6n\rInstalling… 2%\u001B[6n\rInstalling… 100%\nend"
        val lines = terminalLines(java.io.StringReader(text)) { asked++ }.toList()
        assertEquals(listOf("Looking", "", "", "Installing… 2%", "Installing… 100%", "end"), lines)
        assertEquals(2, asked)
    }
}
