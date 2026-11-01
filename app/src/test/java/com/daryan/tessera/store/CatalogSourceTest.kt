// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.GuestRelease
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSourceTest {
    private fun release(text: String) = GuestRelease.parse(text)

    @Test fun `debian uses its own suite, and no release means Tessera's Debian`() {
        val s = CatalogSource.forRelease(release("ID=debian\nVERSION_CODENAME=bookworm\n"), "arm64")
        assertEquals("debian-bookworm", s.key)
        assertEquals("https://deb.debian.org/debian", s.mirror)
        assertEquals(listOf("main", "contrib", "non-free"), s.components)
        assertEquals("trixie", CatalogSource.forRelease(null, "arm64").suite)
    }

    @Test fun `ubuntu uses its ports archive on arm and its main archive on pcs`() {
        val noble = release("ID=ubuntu\nID_LIKE=debian\nVERSION_CODENAME=noble\nUBUNTU_CODENAME=noble\n")
        val arm = CatalogSource.forRelease(noble, "arm64")
        assertEquals("ubuntu-noble", arm.key)
        assertEquals("https://ports.ubuntu.com/ubuntu-ports", arm.mirror)
        assertEquals(listOf("main", "universe", "restricted", "multiverse"), arm.components)
        assertEquals("https://archive.ubuntu.com/ubuntu", CatalogSource.forRelease(noble, "amd64").mirror)
    }

    @Test fun `derivatives use what they derive from`() {
        // Mint names its own release, and Ubuntu's beside it.
        val mint = CatalogSource.forRelease(release("ID=linuxmint\nID_LIKE=\"ubuntu debian\"\nVERSION_CODENAME=wilma\nUBUNTU_CODENAME=noble\n"), "arm64")
        assertEquals("noble", mint.suite)
        // Kali publishes no catalog; it rolls with Debian testing.
        val kali = CatalogSource.forRelease(release("ID=kali\nID_LIKE=debian\nVERSION_CODENAME=kali-rolling\n"), "arm64")
        assertEquals("debian-testing", kali.key)
    }
}
