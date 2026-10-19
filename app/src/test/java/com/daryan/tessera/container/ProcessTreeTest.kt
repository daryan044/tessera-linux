// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A fake /proc: pid -> (ppid, uid, comm). */
    private fun proc(vararg procs: Triple<Int, Int, Pair<Int, String>>): File {
        val root = tmp.newFolder("proc")
        procs.forEach { (pid, ppid, rest) ->
            val (uid, comm) = rest
            File(root, "$pid").apply { mkdirs() }.also { d ->
                File(d, "stat").writeText("$pid ($comm) S $ppid $pid $pid 0 -1 4194560 0 0")
                File(d, "status").writeText("Name:\t$comm\nUid:\t$uid\t$uid\t$uid\t$uid\n")
            }
        }
        File(root, "self").mkdirs() // non-numeric entries are ignored
        return root
    }

    @Test fun `collects the whole tree, children before parents`() {
        val p = proc(
            Triple(100, 1, 10484 to "launcher"),
            Triple(200, 100, 10484 to "libproot.so"),
            Triple(201, 200, 10484 to "firefox-esr"),
            Triple(202, 201, 10484 to "Web Content"), // a command name with a space
            Triple(300, 100, 10484 to "app_process"),
        )
        assertEquals(listOf(202, 201, 200), ProcessTree.descendants(200, p))
    }

    @Test fun `leftovers are our uid's processes outside our own tree`() {
        val p = proc(
            Triple(100, 1, 10484 to "launcher"),
            Triple(200, 100, 10484 to "libproot.so"),
            Triple(500, 1, 10484 to "firefox-esr"), // orphaned by an earlier run
            Triple(501, 500, 10484 to "Web Content"),
            Triple(600, 1, 10000 to "someone else"),
        )
        assertEquals(setOf(500, 501), ProcessTree.leftovers(100, 10484, p).toSet())
    }

    @Test fun `the root kill script finds a session's processes by their marker`() {
        val one = ProcessTree.rootKillScript("s3")
        assertTrue("grep -q '^LAUNCHER_SESSION=s3\$'" in one)
        assertTrue("tr '\\0' '\\n' < \$e" in one)
        assertTrue("grep -q '^LAUNCHER_SESSION='" in ProcessTree.rootKillScript(null))
        java.io.File(System.getProperty("java.io.tmpdir"), "tessera-rootkill.sh").writeText(one)
    }
}
