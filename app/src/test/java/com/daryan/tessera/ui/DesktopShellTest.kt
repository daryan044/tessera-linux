// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import com.daryan.tessera.container.FlatpakRef
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.Permissions
import com.daryan.tessera.container.XWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopShellTest {
    private fun app(id: String, exec: String, name: String = id) = PermissionManifest(id = id, name = name, exec = listOf(exec), gui = true)
    private val apps = listOf(app("firefox-esr", "/usr/bin/firefox-esr", "Firefox"), app("gimp", "/usr/bin/gimp", "GIMP"), app("xterm", "/usr/bin/xterm"))

    @Test fun `windows find their app by class`() {
        assertEquals("firefox-esr", appForWindow(XWindow(1, "Mozilla Firefox", windowClass = "firefox-esr"), apps).id)
        assertEquals("gimp", appForWindow(XWindow(2, "GNU Image Manipulation Program", windowClass = "Gimp-2.10"), apps).id)
        assertEquals("xterm", appForWindow(XWindow(3, "bash", windowClass = "XTerm"), apps).id)
    }

    @Test fun `a window of no known app gets a stand-in named after it`() {
        val stray = appForWindow(XWindow(4, "Calculator", windowClass = "Galculator"), apps)
        assertEquals("galculator", stray.id)
        assertEquals("Calculator", stray.name)
        assertEquals("window-5", appForWindow(XWindow(5, ""), apps).id)
    }

    @Test fun `the start menu has flathub apps but not desktops or root`() {
        val desktop = app("desktop", "/usr/bin/xfwm4").copy(desktop = true)
        val flathub = app("org.gnome.Calculator", "calc").copy(flatpak = FlatpakRef("org.gnome.Calculator", appDir = "/app", runtimeDir = "/runtime", command = "gnome-calculator"))
        val root = app("root-shell", "/bin/bash").copy(gui = false, permissions = Permissions(root = true))
        assertEquals(apps + flathub, desktopApps(apps + desktop + flathub + root))
    }

    @Test fun `the dock has pinned apps first, then each running app once with its windows`() {
        val firefox = XWindow(1, "Mozilla Firefox", windowClass = "firefox-esr")
        val firefox2 = XWindow(2, "Library", windowClass = "firefox-esr")
        val gimp = XWindow(3, "GIMP", windowClass = "Gimp-2.10")
        val entries = dockEntries(pins = listOf("xterm", "firefox-esr", "gone"), windows = listOf(gimp, firefox, firefox2), apps = apps)
        assertEquals(listOf("xterm", "firefox-esr", "gimp"), entries.map { it.app.id })
        assertEquals(listOf(true, true, false), entries.map { it.pinned })
        assertEquals(emptyList<XWindow>(), entries[0].windows)
        assertEquals(listOf(firefox, firefox2), entries[1].windows)
    }
}
