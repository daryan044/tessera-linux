// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.files

import com.daryan.tessera.container.Capability
import com.daryan.tessera.container.ContainerBackend
import com.daryan.tessera.container.ContainerPaths
import com.daryan.tessera.container.Enforcement
import com.daryan.tessera.container.GuestShell
import com.daryan.tessera.container.LaunchSpec
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GuestFilesTest {
    @Test fun `free names count up before the extension`() {
        assertEquals("a.txt", FileBrowser.freeName("a.txt", setOf("b.txt")))
        assertEquals("a (2).txt", FileBrowser.freeName("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", FileBrowser.freeName("a.txt", setOf("a.txt", "a (2).txt")))
        assertEquals("backup (2).tar.gz", FileBrowser.freeName("backup.tar.gz", setOf("backup.tar.gz")))
        // A leading dot is part of the name, not an extension.
        assertEquals(".bashrc (2)", FileBrowser.freeName(".bashrc", setOf(".bashrc")))
        assertEquals("Music (2)", FileBrowser.freeName("Music", setOf("Music")))
    }

    @Test fun `parses find's fields, links included`() {
        val out = listOf(
            "d", "d", "4096", "1700000000.5", "755", "user", "user", "/home/user/Music", "",
            "l", "f", "3", "1700000000", "777", "root", "root", "/home/user/link name", "/etc/hosts",
            "l", "N", "4", "1700000000", "777", "root", "root", "/home/user/gone", "/nowhere",
        ).joinToString("\u0000", postfix = "\u0000").toByteArray()
        val (dir, link, broken) = GuestFiles.parse(out)
        assertTrue(dir.isDir)
        assertEquals("Music", dir.name)
        assertEquals(1_700_000_000_500L, dir.modified)
        assertEquals(0b111_101_101, dir.mode)
        assertEquals("link name", link.name)
        assertTrue(link.isLink && !link.isDir && !link.broken)
        assertEquals("/etc/hosts", link.linkTarget)
        assertTrue(broken.broken)
    }

    @Test fun `android paths are told apart`() {
        assertTrue(GuestFiles.onAndroid("/storage/emulated/0/Download"))
        assertTrue(GuestFiles.onAndroid("/storage/1234-ABCD"))
        assertFalse(GuestFiles.onAndroid("/home/user/storage"))
    }

    /** The shell protocol against a real bash, where there is one (Linux CI, Git Bash). */
    @Test fun `shell keeps output, errors and status apart`() = runBlocking {
        val bash = listOf("/bin/bash", "C:/Program Files/Git/bin/bash.exe").firstOrNull { File(it).canExecute() }
        assumeTrue("no bash here", bash != null)
        val tmp = Files.createTempDirectory("guestshell").toFile()
        val paths = ContainerPaths(tmp, tmp, tmp, File(tmp, "rootfs"), File(tmp, "chroot"))
        val backend = object : ContainerBackend {
            override val name = "test"
            override fun enforcement(capability: Capability, app: PermissionManifest) = Enforcement.UNAVAILABLE
            override fun buildLaunch(app: PermissionManifest, session: Session) =
                LaunchSpec(listOf(bash!!, "--noprofile", "--norc"), emptyMap(), tmp.path)
        }
        val shell = GuestShell.start(backend, paths, PermissionManifest(id = "t", name = "t", exec = listOf("x")), "t1")
        try {
            val r = shell.run("printf 'a\\0b'; echo oops >&2; exit 3")
            assertEquals(3, r.status)
            assertEquals("a\u0000b", r.text)
            assertEquals("oops", r.err.trim())
            // Reading stdin must not eat the next command.
            assertEquals("", shell.run("cat").text)
            assertEquals("still here\n", shell.run("echo still here").text)
        } finally {
            shell.close()
            tmp.deleteRecursively()
        }
    }
}
