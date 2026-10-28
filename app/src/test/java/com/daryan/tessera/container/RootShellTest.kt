// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootShellTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * A busybox with two applets, touch and printf, that runs the one its link is called by, the way
     * busybox does. Its touch says it's busybox's.
     */
    private fun fakeBusybox(): File = File(tmp.root, "adb/busybox").apply {
        parentFile?.mkdirs()
        writeText(
            """
            #!/bin/sh
            n=${'$'}{0##*/}; if [ "${'$'}n" = busybox ]; then n=${'$'}1; shift; fi
            case "${'$'}n" in
              --list) echo touch; echo printf ;;
              mkdir) exec /bin/mkdir "${'$'}@" ;;
              ln) exec /bin/ln "${'$'}@" ;;
              touch) echo busybox-touch ;;
              *) exit 127 ;;
            esac
            """.trimIndent() + "\n",
        )
        setExecutable(true)
    }

    /** [command] after the prelude, with only [path] on PATH; its exit status and output. */
    private fun run(busyboxes: List<String>, dir: File, path: String, command: String): Pair<Int, String> {
        val sh = File("/bin/sh")
        assumeTrue(sh.canExecute())
        val pb = ProcessBuilder(sh.path, "-c", RootShell.prelude(busyboxes, dir.path) + command).redirectErrorStream(true)
        pb.environment().apply { clear(); put("PATH", path) }
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        return proc.waitFor() to out
    }

    @Test fun `a tool the phone lacks comes from busybox`() {
        val bb = fakeBusybox()
        val dir = File(tmp.root, "links")
        assertEquals(0 to "busybox-touch", run(listOf("/nonexistent/busybox", bb.path), dir, "/nonexistent", "touch"))
        // Linked once, and named after the busybox they point to.
        assertEquals(bb.path, File(dir, ".busybox").readText().trim())
        assertEquals(bb.path, File(dir, "printf").toPath().let { java.nio.file.Files.readSymbolicLink(it).toString() })
        assertEquals(0 to "busybox-touch", run(listOf(bb.path), dir, "/nonexistent", "touch"))
    }

    @Test fun `a tool the phone has stays the phone's`() {
        val phone = File(tmp.root, "system/bin").apply { mkdirs() }
        File(phone, "touch").apply { writeText("#!/bin/sh\necho phone-touch\n"); setExecutable(true) }
        assertEquals(0 to "phone-touch", run(listOf(fakeBusybox().path), File(tmp.root, "links"), phone.path, "touch"))
    }

    @Test fun `links to another busybox are made again`() {
        val dir = File(tmp.root, "links").apply { mkdirs() }
        File(dir, ".busybox").writeText("/data/adb/magisk/busybox\n")
        java.nio.file.Files.createSymbolicLink(File(dir, "touch").toPath(), File("/data/adb/magisk/busybox").toPath())
        val bb = fakeBusybox()
        assertEquals(0 to "busybox-touch", run(listOf(bb.path), dir, "/nonexistent", "touch"))
        assertEquals(bb.path, java.nio.file.Files.readSymbolicLink(File(dir, "touch").toPath()).toString())
    }

    @Test fun `without a busybox PATH is left as it is`() {
        assertEquals(0 to "/nonexistent", run(listOf("/nonexistent/busybox"), File(tmp.root, "links"), "/nonexistent", "echo \"\$PATH\""))
    }
}
