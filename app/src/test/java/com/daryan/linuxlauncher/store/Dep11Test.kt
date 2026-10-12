// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Dep11Test {
    // Real entries from Debian trixie's main/dep11/Components-arm64.yml.
    private val apps = Dep11.parse(
        javaClass.classLoader!!.getResource("dep11-sample.yml")!!.readText().lineSequence(),
        component = "main",
    )

    @Test fun `keeps desktop applications only`() {
        assertEquals(listOf("firefox-esr", "gimp", "starfighter"), apps.map { it.pkg })
    }

    @Test fun `reads untranslated name, summary and categories`() {
        val fx = apps.first { it.pkg == "firefox-esr" }
        assertEquals("firefox-esr.desktop", fx.id)
        assertEquals("Firefox ESR", fx.name)
        assertEquals("Browse the World Wide Web", fx.summary)
        assertEquals(listOf("Network", "WebBrowser"), fx.categories)
        assertEquals("main", fx.component)
    }

    @Test fun `description is the English block, as plain text`() {
        val fx = apps.first { it.pkg == "firefox-esr" }
        assertTrue(fx.description, fx.description.startsWith("Firefox ESR is a powerful, extensible web browser"))
        assertFalse(fx.description.contains("<p>"))
        // Translations of the same text must not leak in.
        assertFalse(fx.description.contains("navegador"))
    }

    @Test fun `picks the largest cached icon and the desktop file`() {
        val gimp = apps.first { it.pkg == "gimp" }
        assertEquals("gimp_gimp.png", gimp.icon)
        assertEquals("gimp.desktop", gimp.desktopId)
        assertEquals("firefox-esr_firefox-esr.png", apps.first { it.pkg == "firefox-esr" }.icon)
    }

    @Test fun `unquotes quoted names`() {
        assertEquals("Project: Starfighter", apps.first { it.pkg == "starfighter" }.name)
    }

    @Test fun `html descriptions become paragraphs and bullets`() {
        val text = Dep11.htmlToText("<p>Edit &amp; paint.</p><ul><li>Layers</li><li>Filters</li></ul><p>Done.</p>")
        assertEquals("Edit & paint.\n\n• Layers\n• Filters\n\nDone.", text)
    }
}
