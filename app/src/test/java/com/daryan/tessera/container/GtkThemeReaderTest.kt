// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GtkThemeReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun theme(name: String, css: String, extra: Map<String, String> = emptyMap()): File {
        val dir = File(tmp.root, name)
        File(dir, "gtk-3.0").mkdirs()
        File(dir, "gtk-3.0/gtk.css").writeText(css)
        extra.forEach { (path, text) -> File(dir, path).apply { parentFile.mkdirs() }.writeText(text) }
        return dir
    }

    @Test fun `reads a theme that defines its colors directly (like Nordic)`() {
        val t = GtkThemeReader.read(
            theme(
                "Nordic-bluish-accent",
                """
                @define-color theme_fg_color #d8dee9;
                @define-color theme_bg_color #434c5e;
                @define-color theme_base_color #3b4252;
                @define-color theme_selected_bg_color #81a1c1;
                @define-color theme_selected_fg_color #fefefe;
                button { border-radius: 4px; }
                button.flat { border-radius: 4px; }
                .entry { border-radius: 2px; }
                """.trimIndent(),
            ),
        )!!
        assertEquals("Nordic-bluish-accent", t.id)
        assertEquals("Nordic-bluish-accent", t.family)
        assertTrue(t.dark)
        assertEquals(0x434c5e, t.colors.background)
        assertEquals(0xd8dee9, t.colors.foreground)
        assertEquals(0x3b4252, t.colors.view)
        assertEquals(0x81a1c1, t.colors.accent)
        assertEquals(0xfefefe, t.colors.onAccent)
        assertEquals(4, t.radius)
    }

    @Test fun `follows references and local imports (like Dracula)`() {
        val t = GtkThemeReader.read(
            theme(
                "Dracula",
                """
                @define-color bg_color #282a36;
                @define-color fg_color #f8f8f2;
                @define-color base_color #282a36;
                @define-color selected_bg_color #bd93f9;
                @define-color theme_bg_color @bg_color;
                @define-color theme_fg_color @fg_color;
                @define-color theme_base_color shade(@base_color, 0.9);
                @define-color theme_selected_bg_color @selected_bg_color;
                @import url("gtk-widgets.css");
                @import url("resource:///org/gtk/libgtk/theme/Adwaita/gtk-contained.css");
                """.trimIndent(),
                mapOf("gtk-3.0/gtk-widgets.css" to "button { border-radius: 6px; }\nbutton:hover { border-radius: 6px; }"),
            ),
        )!!
        assertEquals(0x282a36, t.colors.background)
        assertEquals(0xbd93f9, t.colors.accent)
        assertEquals(0x242530, t.colors.view)
        // Light text on a light accent isn't guessed: dark text, from the accent's brightness.
        assertEquals(0x000000, t.colors.onAccent)
        assertEquals(6, t.radius)
        assertTrue(t.dark)
    }

    @Test fun `libadwaita-style names win, and a light theme is light`() {
        val t = GtkThemeReader.read(
            theme(
                "Fluent-Light",
                """
                @define-color window_bg_color #fafafa;
                @define-color window_fg_color rgba(0, 0, 0, 0.8);
                @define-color accent_bg_color #1a73e8;
                @define-color accent_fg_color white;
                @define-color headerbar_bg_color #ebebeb;
                @define-color theme_bg_color #000000;
                """.trimIndent(),
            ),
        )!!
        assertFalse(t.dark)
        assertEquals("Fluent", t.family)
        assertEquals(0xfafafa, t.colors.background)
        assertEquals(0x000000, t.colors.foreground)
        assertEquals(0xffffff, t.colors.onAccent)
        assertEquals(0xebebeb, t.colors.headerbar)
        assertNull(t.radius)
    }

    @Test fun `variants pair up by family`() {
        assertEquals("Orchis", GtkThemeReader.read(theme("Orchis-Dark", DARK))!!.family)
        assertEquals("Orchis", GtkThemeReader.read(theme("Orchis_light", DARK))!!.family)
    }

    @Test fun `no colors, no theme`() {
        assertNull(GtkThemeReader.read(theme("Broken", "button { border-radius: 3px; }")))
    }

    @Test fun `parses the color forms themes use`() {
        val refs = mapOf("a" to 0x102030)
        assertEquals(0xaabbcc, GtkThemeReader.parse("#abc") { null })
        assertEquals(0x112233, GtkThemeReader.parse("#11223344") { null })
        assertEquals(0x0a141e, GtkThemeReader.parse("rgb(10, 20, 30)") { null })
        assertEquals(0x102030, GtkThemeReader.parse("alpha(@a, 0.5)") { refs[it] })
        assertEquals(0x081018, GtkThemeReader.parse("shade(@a, 0.5)") { refs[it] })
        assertEquals(0x081018, GtkThemeReader.parse("mix(@a, #000000, 0.5)") { refs[it] })
        assertNull(GtkThemeReader.parse("rgb(1, 2)") { null })
    }

    private companion object {
        const val DARK = "@define-color theme_bg_color #222222;\n@define-color theme_fg_color #eeeeee;\n@define-color theme_selected_bg_color #3584e4;"
    }
}
