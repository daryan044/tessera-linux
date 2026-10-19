// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThemeFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dark = Palette(
        dark = true, accent = 0xA8C7FA, onAccent = 0x062E6F, window = 0x111318, onWindow = 0xE2E2E9,
        view = 0x0C0E13, headerbar = 0x1D2024, sidebar = 0x191C20, card = 0x282A2F, popover = 0x282A2F,
        border = 0x44474E, error = 0xFFB4AB, onError = 0x690005,
    )
    private val light = dark.copy(dark = false, accent = 0x0B57D0, onAccent = 0xFFFFFF, window = 0xF9F9FF)

    @Test fun `gtk 3 recolors adw-gtk3 from the pack, dark or light`() {
        val css = ThemeFiles.files(dark, 4, pack = true).getValue("share/themes/Tessera-4/gtk-3.0/gtk.css")
        assertTrue(css.startsWith("@import url(\"file:///opt/tessera-theme/share/themes/adw-gtk3-dark/gtk-3.0/gtk.css\");"))
        assertTrue("@define-color accent_bg_color #a8c7fa;" in css)
        assertTrue("@define-color window_bg_color #111318;" in css)
        val lightCss = ThemeFiles.files(light, 4, pack = true).getValue("share/themes/Tessera-4/gtk-3.0/gtk.css")
        assertTrue("adw-gtk3/gtk-3.0/gtk.css" in lightCss)
    }

    @Test fun `without the pack gtk 3 falls back to GTK's own Adwaita`() {
        val css = ThemeFiles.files(dark, 1, pack = false).getValue("share/themes/Tessera-1/gtk-3.0/gtk.css")
        assertTrue(css.startsWith("@import url(\"resource:///org/gtk/libgtk/theme/Adwaita/gtk-contained-dark.css\");"))
    }

    @Test fun `gtk 4 gets libadwaita's names as variables too`() {
        val css = ThemeFiles.files(dark, 1, pack = true).getValue("share/themes/Tessera-1/gtk-4.0/gtk.css")
        assertTrue("--accent-bg-color: #a8c7fa;" in css)
        assertTrue("@define-color view_bg_color #0c0e13;" in css)
    }

    @Test fun `xsettings names the generation's theme and the portal says dark with the accent`() {
        val files = ThemeFiles.files(dark, 7, pack = true)
        assertTrue("Net/ThemeName \"Tessera-7\"" in files.getValue(ThemeFiles.XSETTINGS_FILE))
        assertEquals("color-scheme=1\naccent=0.6588,0.7804,0.9804\n", files.getValue(ThemeFiles.PORTAL_FILE))
        assertTrue("color-scheme=2" in ThemeFiles.files(light, 7, pack = true).getValue(ThemeFiles.PORTAL_FILE))
    }

    @Test fun `each write is a new generation and old themes go`() {
        val dir = File(tmp.root, "theme")
        assertEquals(1, ThemeFiles.write(dir, dark, pack = true))
        assertEquals(2, ThemeFiles.write(dir, light, pack = true))
        assertEquals(3, ThemeFiles.write(dir, dark, pack = true))
        assertEquals(setOf("Tessera-2", "Tessera-3"), File(dir, "share/themes").list()!!.toSet())
        assertTrue(File(dir, ThemeFiles.XSETTINGS_FILE).readText().contains("Tessera-3"))
        assertFalse(dir.walk().any { it.name.endsWith(".tmp") })
    }

    @Test fun `apps on a desktop get their window buttons, apps alone on a screen none`() {
        assertTrue("Gtk/DecorationLayout \"menu:\"" in ThemeFiles.files(dark, 1, pack = true).getValue(ThemeFiles.XSETTINGS_FILE))
        assertTrue(
            "Gtk/DecorationLayout \"menu:minimize,maximize,close\"" in
                ThemeFiles.files(dark, 1, pack = true, desktop = true).getValue(ThemeFiles.XSETTINGS_FILE),
        )
    }

    @Test fun `a desktop's title font and edges follow its title bar`() {
        assertEquals("Sans Bold 9", DesktopFiles.titleFont(40))
        assertEquals("Sans Bold 18", DesktopFiles.titleFont(80))
        assertEquals(8, DesktopFiles.edgeFor(40))
        assertEquals(4, DesktopFiles.edgeFor(10))
    }
}
