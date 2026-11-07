// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedInTest {
    private val own = "com.daryan.tessera"
    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"
    private val sendMany = "android.intent.action.SEND_MULTIPLE"
    private val photo = "content://media/external/images/1"

    private fun of(action: String?, data: String? = null, streams: List<String> = emptyList(), text: String? = null) =
        SharedIn.of(action, data, streams, text, own)

    @Test fun `opening a file or a link`() {
        assertEquals(SharedIn.Files(listOf(photo)), of(view, data = photo))
        assertEquals(SharedIn.Link("https://example.org/a?b=c"), of(view, data = "https://example.org/a?b=c"))
        assertEquals(SharedIn.Link("HTTP://example.org"), of(view, data = "HTTP://example.org"))
    }

    @Test fun `sharing files, a link or text`() {
        assertEquals(SharedIn.Files(listOf(photo)), of(send, streams = listOf(photo)))
        assertEquals(SharedIn.Files(listOf(photo, "$photo-2")), of(sendMany, streams = listOf(photo, "$photo-2")))
        assertEquals(SharedIn.Link("https://example.org"), of(send, text = "  https://example.org\n"))
        assertEquals(SharedIn.Text("a note"), of(send, text = " a note "))
        // A title and a link is text: only a bare address goes on a command line.
        assertEquals(SharedIn.Text("Title https://example.org"), of(send, text = "Title https://example.org"))
    }

    @Test fun `a file from another app is the only kind let in`() {
        assertNull(of(view, data = "file:///data/data/com.daryan.tessera/files/permissions.json"))
        assertNull(of(send, streams = listOf("file:///data/data/com.daryan.tessera/shared_prefs/launcher.xml")))
        assertNull(of(view, data = "content://com.daryan.tessera.files/transfer/x"))
        assertNull(of(view, data = "content://10@com.daryan.tessera.files/transfer/x"))
        assertNull(of(view, data = "CONTENT://COM.DARYAN.TESSERA.files/transfer/x"))
        assertEquals(SharedIn.Files(listOf(photo)), of(sendMany, streams = listOf("file:///etc/hosts", photo)))
    }

    @Test fun `what isn't a link can't reach a command line as one`() {
        assertNull(of(view, data = "javascript:alert(1)"))
        assertNull(of(view, data = "--no-sandbox"))
        assertNull(of(view, data = "https://example.org --no-sandbox"))
        assertEquals(SharedIn.Text("--no-sandbox"), of(send, text = "--no-sandbox"))
    }

    @Test fun `nothing to act on`() {
        assertNull(of(null))
        assertNull(of("android.intent.action.MAIN", data = photo))
        assertNull(of(send))
        assertNull(of(send, text = "   "))
        assertNull(of(sendMany))
    }

    @Test fun `no app is granted a phone folder over where shared files go`() {
        val manifests = java.io.File("src/main/assets/apps").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assert(manifests.isNotEmpty()) { "no bundled manifests found" }
        val mounts = manifests.flatMap { Regex(""""mountAt"\s*:\s*"([^"]+)"""").findAll(it.readText()).map { m -> m.groupValues[1] } }
        assert(mounts.isNotEmpty()) { "no grants found: the pattern is stale" }
        // A grant at, above or below it would hide the copy, or leave PRoot's placeholder there.
        mounts.forEach { m ->
            assert(m != FileBrowser.SHARED_DIR && !m.startsWith(FileBrowser.SHARED_DIR + "/") && !FileBrowser.SHARED_DIR.startsWith("$m/")) {
                "$m is over ${FileBrowser.SHARED_DIR}"
            }
        }
    }

    @Test fun `names can't leave the folder`() {
        assertEquals("file", SharedIn.safeName(null))
        assertEquals("file", SharedIn.safeName(""))
        assertEquals("file", SharedIn.safeName(".."))
        assertEquals("file", SharedIn.safeName("."))
        assertEquals("file", SharedIn.safeName("../.."))
        assertEquals("passwd", SharedIn.safeName("../../etc/passwd"))
        assertEquals("ab.txt", SharedIn.safeName("a\nb.txt"))
        assertEquals("photo 1.jpg", SharedIn.safeName(" photo 1.jpg "))
        assertEquals(200, SharedIn.safeName("x".repeat(300)).length)
    }
}
