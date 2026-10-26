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

    @Test fun `a failed install is in the report, with its error and output`() {
        val diagnostics = Diagnostics(dir())
        diagnostics.recordFailure("Installing Desktop: xfwm4 xterm", "Killed (signal 9)", "")
        diagnostics.recordFailure("Installing Linux", "extract failed (1): proot error: execve(\"/system/bin/tar\"): Operation not permitted")

        val report = diagnostics.report("- Engine: PRoot")
        assertTrue(report, "Installing Desktop: xfwm4 xterm" in report)
        assertTrue(report, "Killed (signal 9)" in report)
        assertTrue(report, "Operation not permitted" in report)
        // Oldest first, like the sessions.
        assertTrue(report.indexOf("Installing Desktop") < report.indexOf("Installing Linux"))
    }

    @Test fun `failures and sessions share one timeline`() {
        val diagnostics = Diagnostics(dir())
        diagnostics.recordSession(code, session, "PRoot", 1, "session output")
        diagnostics.recordFailure("Loading the Store catalog", "timeout")
        val report = diagnostics.report("")
        assertTrue(report, "session output" in report && "timeout" in report)
    }

    @Test fun `a crash is recorded with its stack trace`() {
        val diagnostics = Diagnostics(dir())
        val error = IllegalStateException("boom")
        diagnostics.recordFailure("Tessera crashed (thread main)", error.toString(), error.stackTraceToString())
        val report = diagnostics.report("")
        assertTrue(report, "IllegalStateException: boom" in report)
        assertTrue(report, "DiagnosticsTest" in report)
    }

    @Test fun `the report fits a GitHub issue however much was logged, dropping the oldest logs`() {
        val diagnostics = Diagnostics(dir())
        repeat(30) { i ->
            diagnostics.recordFailure("failure $i", "error $i", "x".repeat(50_000))
            diagnostics.recordSession(code, Session(id = "s$i"), "PRoot", 1, "y".repeat(50_000))
        }
        dir().mkdirs()
        diagnostics.contextFile.writeText("notes ".repeat(2_000))
        val state = "- app\n".repeat(20_000)

        val report = diagnostics.report(state)
        assertTrue("${report.length}", report.length <= 60_000)
        assertTrue(report, "failure 29" in report) // the newest stays
        assertFalse(report, "failure 0\n" in report) // the oldest gives way
        assertTrue(report, "notes notes" in report)
        assertTrue(report, "left out to keep this short" in report)
    }

    @Test fun `failures aren't written while diagnostics are off`() {
        Diagnostics(dir(), enabled = { false }).recordFailure("Installing Linux", "boom")
        assertFalse(File(dir(), "failures").exists())
    }

    @Test fun `a report with no sessions yet says what to do`() {
        val report = Diagnostics(dir()).report("- Engine: PRoot")
        assertTrue(report, "None recorded yet" in report)
    }
}
