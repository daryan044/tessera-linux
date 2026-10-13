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
}
