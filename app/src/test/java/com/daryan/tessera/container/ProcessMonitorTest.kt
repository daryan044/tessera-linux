// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessMonitorTest {
    private fun proc(pid: Int, ppid: Int, session: String = "", command: String = "x", uid: Int = 10498) =
        GuestProcess(pid, ppid, session, "x", command, 'S', 0f, 0, 1, uid)

    @Test fun `a process seen by both readers keeps the line naming its session`() {
        val raws = listOf(
            ProcessMonitor.Raw(7, "", "", "", 0, "su"),
            ProcessMonitor.Raw(7, "s2", "", "", 0, "su"),
            ProcessMonitor.Raw(8, "", "", "", 0, "sh"),
        )
        assertEquals(mapOf(7 to "s2", 8 to ""), ProcessMonitor.merge(raws).associate { it.pid to it.session })
    }

    @Test fun `tessera's own processes go with the session they serve, the rest under helpers`() {
        val list = listOf(
            proc(10, 1, command = "app_process / com.termux.x11.CmdEntryPoint :3 -tag linux-s3"),
            proc(20, 1, command = "libproot.so --rootfs=…"), // the tracer, above a guest
            proc(21, 20, session = "s4", command = "firefox-esr"),
            proc(30, 1, command = "su"), // su above unshare above the guest
            proc(31, 30, command = "unshare -m sh enter.sh"),
            proc(32, 31, session = "s5", command = "gimp"),
            proc(40, 1823, command = "tr '\\000\\n' '  '", uid = 0), // an orphaned root helper
        )
        val by = ProcessMonitor.attribute(list).associate { it.pid to it.session }
        assertEquals("s3", by[10])
        assertEquals("s4", by[20])
        assertEquals("s5", by[30])
        assertEquals("s5", by[31])
        assertEquals(HELPERS, by[40])
    }
}
