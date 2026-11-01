// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerMenuTest {
    private fun app(id: String, exec: String, vararg packages: String, flatpak: FlatpakRef? = null) =
        PermissionManifest(id = id, name = id, exec = listOf(exec), packages = packages.toList(), flatpak = flatpak)

    private val firefox = app("firefox-esr", "/usr/bin/firefox-esr", "firefox-esr")
    private val gimp = app("gimp", "/usr/bin/gimp", "gimp")
    private val terminal = app("bash", "/bin/bash")
    private val relative = app("tool", "tool", "tool-pkg")
    private val mpv = app("flathub-io.mpv.mpv", "mpv", flatpak = FlatpakRef(app = "io.mpv.Mpv", runtimeDir = "/r", appDir = "/a", command = "mpv"))
    private val shortcut = app("shortcut-hello", "/bin/bash", "cowsay")
    private val bare = app("shortcut-ls", "/bin/bash")

    @Test fun `an entry shows when its program is in the container`() {
        val menu = ContainerMenu(programs = setOf("/usr/bin/gimp", "/bin/bash"), packages = setOf("firefox-esr"), flatpaks = emptySet())
        assertTrue(menu.has(gimp))
        assertTrue(menu.has(terminal))
        // dpkg says installed, but the program isn't there: the program decides.
        assertFalse(menu.has(firefox))
    }

    @Test fun `until programs are checked, packages decide, and unknown is absent`() {
        val menu = ContainerMenu(programs = null, packages = setOf("gimp", "tool-pkg"), flatpaks = null)
        assertTrue(menu.has(gimp))
        assertFalse(menu.has(firefox))
        assertTrue(menu.has(relative))
        assertTrue(menu.has(terminal))
        assertFalse(menu.has(mpv))
        assertFalse(ContainerMenu(null, null, null).has(gimp))
    }

    @Test fun `flathub apps and shortcuts go by their own files and packages`() {
        val menu = ContainerMenu(programs = emptySet(), packages = setOf("cowsay"), flatpaks = setOf("io.mpv.Mpv"))
        assertTrue(menu.has(mpv))
        assertTrue(menu.has(shortcut))
        assertTrue(menu.has(bare))
        assertFalse(ContainerMenu(emptySet(), emptySet(), emptySet()).has(shortcut))
    }

    @Test fun `only plain programs are checked for`() {
        assertEquals(setOf("/usr/bin/gimp", "/bin/bash"), ContainerMenu.programsOf(listOf(gimp, terminal, relative, mpv, shortcut)))
    }

    @Test fun `os-release names the system and its family`() {
        val kali = GuestRelease.parse("ID=kali\nID_LIKE=debian\nVERSION_CODENAME=kali-rolling\nPRETTY_NAME=\"Kali GNU/Linux Rolling\"\n")!!
        assertTrue(kali.isDebianFamily)
        assertFalse(kali.isUbuntu)
        assertEquals("Kali GNU/Linux Rolling", kali.label)
        val mint = GuestRelease.parse("ID=linuxmint\nID_LIKE=\"ubuntu debian\"\nVERSION_CODENAME=wilma\nUBUNTU_CODENAME=noble\n")!!
        assertTrue(mint.isUbuntu)
        assertEquals("noble", mint.ubuntuCodename)
        val arch = GuestRelease.parse("NAME=\"Arch Linux\"\nID=arch\n# comment\n")!!
        assertFalse(arch.isDebianFamily)
        assertEquals("Arch", arch.label)
        assertNull(GuestRelease.parse("NAME=nothing"))
        assertNull(GuestRelease.parse(null))
    }

    @Test fun `packages renamed between releases count as installed under either name`() {
        val have = setOf("libfuse2", "libnss3")
        assertEquals(listOf("libgtk-3-0t64|libgtk-3-0"), missingPackages(listOf("libfuse2t64|libfuse2", "libnss3", "libgtk-3-0t64|libgtk-3-0"), have))
    }
}
