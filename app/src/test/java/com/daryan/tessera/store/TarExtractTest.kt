// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class TarExtractTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun header(name: String, type: Char, size: Int = 0, mode: Int = 420, link: String = ""): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
        put(0, name)
        put(100, "%07o".format(mode))
        put(124, "%011o".format(size))
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar")
        return h
    }

    private fun archive(vararg entries: Pair<ByteArray, ByteArray>) = ByteArrayOutputStream().apply {
        entries.forEach { (h, data) ->
            write(h); write(data)
            val pad = (512 - data.size % 512) % 512
            write(ByteArray(pad))
        }
        write(ByteArray(1024))
    }.toByteArray()

    @Test fun `keeps layout and links, refuses escapes`() {
        val data = "lib".toByteArray()
        val tar = archive(
            header("opt/gpu/lib/", '5') to ByteArray(0),
            header("opt/gpu/lib/libx.so.1.0", '0', data.size, mode = 493) to data,
            header("opt/gpu/lib/libx.so.1", '2', link = "libx.so.1.0") to ByteArray(0),
            header("../evil", '0', data.size) to data,
        )
        val dest = tmp.newFolder("root")
        val links = mutableListOf<Pair<String, File>>()
        val n = Tar.extract(tar.inputStream(), dest) { target, link -> links += target to link }

        assertEquals(3, n)
        assertTrue(File(dest, "opt/gpu/lib/libx.so.1.0").readText() == "lib")
        assertEquals(listOf("libx.so.1.0" to File(dest, "opt/gpu/lib/libx.so.1").canonicalFile), links.map { it.first to it.second.canonicalFile })
        assertFalse(File(tmp.root, "evil").exists())
    }
}
