// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val code = PermissionManifest(
        id = "system-com.microsoft.vscode",
        name = "Code",
        exec = listOf("/usr/share/code/code", "--no-sandbox"),
        gui = true,
        permissions = Permissions(display = true, network = true),
    )
    private val session = Session(id = "s4", display = 4)

    private fun dir() = File(tmp.root, "diagnostics")
    private fun sessions() = File(dir(), "sessions").listFiles().orEmpty()

    @Test fun `a session that failed is recorded with its command and its output`() {
        val diagnostics = Diagnostics(dir())
        diagnostics.recordSession(code, session, "PRoot", 1, "Trace/breakpoint trap")

        assertEquals(1, sessions().size)
        val log = sessions().single().readText()
        assertTrue(log, "/usr/share/code/code --no-sandbox" in log)
        assertTrue(log, "engine: PRoot" in log)
        assertTrue(log, "exit code: 1" in log)
        assertTrue(log, "Trace/breakpoint trap" in log)
        // Permissions are part of the diagnosis: half the failures here are a missing grant.
        assertTrue(log, "network" in log && "display" in log)
    }

    @Test fun `an app that printed nothing says so, rather than leaving an empty log`() {
        Diagnostics(dir()).recordSession(code, session, "PRoot", 1, "")
        assertTrue("the app printed nothing" in sessions().single().readText())
    }

    @Test fun `nothing is written while diagnostics are off`() {
        Diagnostics(dir(), enabled = { false }).recordSession(code, session, "PRoot", 1, "boom")
        assertFalse(File(dir(), "sessions").exists())
    }

    @Test fun `turning them off takes the logs with it`() {
        val diagnostics = Diagnostics(dir())
        diagnostics.recordSession(code, session, "PRoot", 1, "boom")
        diagnostics.clear()
        assertFalse(dir().exists())
    }

    @Test fun `the report carries the state, the logs and the architecture notes`() {
        val diagnostics = Diagnostics(dir())
        diagnostics.recordSession(code, session, "PRoot", 1, "first failure")
        // Stands in for the notes install() copies out of the APK.
        dir().mkdirs()
        diagnostics.contextFile.writeText("<!-- TESSERA-CONTEXT -->\nhow Tessera works")

        val report = diagnostics.report("## This phone\n\n- Engine: PRoot")
        assertTrue(report, "Tessera diagnostics" in report)
        assertTrue(report, "Engine: PRoot" in report)
        assertTrue(report, "first failure" in report)
        assertTrue(report, "how Tessera works" in report)
    }

    @Test fun `a report with no sessions yet says what to do`() {
        val report = Diagnostics(dir()).report("- Engine: PRoot")
        assertTrue(report, "None recorded yet" in report)
    }
}
