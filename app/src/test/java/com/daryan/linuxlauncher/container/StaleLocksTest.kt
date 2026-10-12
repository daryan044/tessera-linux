// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class StaleLocksTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun link(dir: File, target: String): File? = runCatching {
        dir.mkdirs()
        Files.createSymbolicLink(File(dir, "lock").toPath(), Paths.get(target)).toFile()
    }.getOrNull()

    private fun process(proc: File, pid: Int, uid: Int, cmd: String) = File(proc, "$pid").apply {
        mkdirs()
        File(this, "status").writeText("Name:\tx\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        File(this, "cmdline").writeText(cmd.replace(' ', '\u0000'))
    }

    @Test fun `removes locks naming dead or foreign pids, keeps live Firefox`() {
        val rootfs = tmp.newFolder("rootfs")
        val proc = tmp.newFolder("proc")
        val profiles = File(rootfs, "home/user/.config/mozilla/firefox")
        val stale = link(File(profiles, "a.default-esr"), "127.0.0.1:+7810")
        // Symlinks need privileges on some Windows setups; skip there rather than fail.
        assumeTrue(stale != null)
        val reused = link(File(profiles, "b.default"), "127.0.0.1:+200")!!
        val live = link(File(profiles, "c.default"), "127.0.0.1:+300")!!
        process(proc, 200, 1000, "/system/bin/surfaceflinger") // pid reused by another uid
        process(proc, 300, 10484, "/usr/lib/firefox-esr/firefox-esr")

        val removed = StaleLocks.clean(rootfs, 10484, proc)

        assertEquals(2, removed.size)
        assertFalse(Files.isSymbolicLink(stale!!.toPath()))
        assertFalse(Files.isSymbolicLink(reused.toPath()))
        assertTrue(Files.isSymbolicLink(live.toPath()))
    }
}
