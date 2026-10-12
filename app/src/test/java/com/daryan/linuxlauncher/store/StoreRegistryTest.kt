// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreRegistryTest {
    @Test fun `dpkg status lists only fully installed packages`() {
        val status = """
            Package: gimp
            Status: install ok installed
            Version: 3.0

            Package: vlc
            Status: deinstall ok config-files

            Package: firefox-esr
            Status: install ok installed
        """.trimIndent()
        assertEquals(setOf("gimp", "firefox-esr"), StoreRegistry.installedPackages(status))
    }

    @Test fun `a browser gets network and Downloads, a GUI window and sound`() {
        val app = StoreApp("org.example.browser.desktop", "browser", "Browser", "Browse", "", listOf("Network", "WebBrowser"), "browser_browser.png", "main", "browser.desktop")
        val m = StoreRegistry.manifestFor(app, DesktopEntry("Browser", listOf("browser", "--new"), terminal = false, icon = null), "/x/icon.png")
        assertEquals("Internet", m.category)
        assertEquals(listOf("browser", "--new"), m.exec)
        assertTrue(m.gui && m.permissions.display && m.permissions.audio && m.permissions.network)
        assertEquals(listOf("/home/user/Downloads"), m.permissions.filesystem.map { it.mountAt })
        assertEquals("/x/icon.png", m.iconFile)
        assertEquals(emptyList<String>(), m.validate())
    }

    @Test fun `an offline image editor gets Pictures but no network`() {
        val app = StoreApp("gimp.desktop", "gimp", "GIMP", "", "", listOf("Graphics", "2DGraphics"), null, "main", "gimp.desktop")
        val m = StoreRegistry.manifestFor(app, null, null)
        assertFalse(m.permissions.network)
        assertEquals(listOf("/home/user/Pictures"), m.permissions.filesystem.map { it.mountAt })
        // Without a .desktop file it falls back to the package name.
        assertEquals(listOf("/usr/bin/gimp"), m.exec)
    }

    @Test fun `terminal apps run in a terminal without a display`() {
        val app = StoreApp("htop.desktop", "htop", "htop", "", "", listOf("System", "Monitor"), null, "main", "htop.desktop")
        val m = StoreRegistry.manifestFor(app, DesktopEntry("htop", listOf("htop"), terminal = true, icon = null), null)
        assertFalse(m.gui || m.permissions.display)
        assertEquals("System", m.category)
    }
}
