// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessTreeSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun process(proc: File, pid: Int, vararg env: String) = File(proc, "$pid").apply {
        mkdirs()
        File(this, "environ").writeBytes(env.joinToString("\u0000", postfix = "\u0000").toByteArray())
    }

    @Test fun `finds a session's processes by their environment, detached ones included`() {
        val proc = tmp.newFolder("proc")
        process(proc, 10, "HOME=/home/user", "LAUNCHER_SESSION=s1")
        process(proc, 11, "LAUNCHER_SESSION=s1", "DBUS_SESSION_BUS_ADDRESS=x") // a daemon reparented to init
        process(proc, 12, "LAUNCHER_SESSION=s12") // another session, same prefix
        process(proc, 13, "PATH=/bin")
        File(proc, "self").mkdirs()
        assertEquals(listOf(10, 11), ProcessTree.inSession("s1", proc).sorted())
    }
}
