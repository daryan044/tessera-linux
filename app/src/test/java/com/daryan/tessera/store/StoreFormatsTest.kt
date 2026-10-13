// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class StoreFormatsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `desktop entry exec drops field codes and keeps quoted args`() {
        val entry = DesktopEntry.parse(
            """
            [Desktop Entry]
            Name=GIMP
            Exec=gimp-3.0 %U
            Terminal=false
            Icon=gimp
            [Desktop Action new]
            Exec=gimp-3.0 --new
            """.trimIndent(),
        )!!
        assertEquals(listOf("gimp-3.0"), entry.exec)
        assertFalse(entry.terminal)
        assertEquals("gimp", entry.icon)
        assertEquals(listOf("sh", "-c", "echo 50%"), DesktopEntry.splitExec("sh -c \"echo 50%%\""))
        assertTrue(DesktopEntry.parse("[Desktop Entry]\nExec=htop\nTerminal=true")!!.terminal)
        assertNull(DesktopEntry.parse("[Desktop Entry]\nName=No exec"))
    }

    @Test fun `tar extracts regular files flat and stops at the end marker`() {
        val tar = ByteArrayOutputStream().apply {
            entry("icons/firefox-esr_firefox-esr.png", "PNG1".toByteArray())
            entry("icons/", ByteArray(0), type = '5')
            entry("gimp_gimp.png", ByteArray(700) { 7 })
            write(ByteArray(1024))
        }.toByteArray()
        val dest = tmp.newFolder("icons")
        val n = Tar.extractFlat(ByteArrayInputStream(tar), dest)
        assertEquals(2, n)
        assertEquals("PNG1", File(dest, "firefox-esr_firefox-esr.png").readText())
        assertEquals(700L, File(dest, "gimp_gimp.png").length())
    }

    private fun ByteArrayOutputStream.entry(name: String, data: ByteArray, type: Char = '0') {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        String.format("%011o", data.size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        write(h)
        write(data)
        val pad = (512 - data.size % 512) % 512
        write(ByteArray(pad))
    }
}
