// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowReportsTest {
    @Test fun `parses the watcher's report`() {
        // As the watcher wrote it for Firefox with its Library window open.
        val w = SessionFiles.parseWindows(
            """{"active": 4194341, "windows": [{"id": 4194306, "title": "Restore Session — Mozilla Firefox", "main": true}, """ +
                """{"id": 4194341, "title": "Library", "main": true}, {"id": 4194400, "title": "Save As", "main": false}]}""",
        )
        assertEquals(4194341L, w.active)
        assertEquals(listOf(4194306L, 4194341L), w.main.map { it.id })
        assertEquals("Restore Session — Mozilla Firefox", w.windows.first().title)
        // Older reports had none of the desktop's fields.
        assertEquals(emptyList<Int>(), w.screen)
        assertEquals("", w.windows.first().windowClass)
    }

    @Test fun `parses what a desktop's taskbar needs`() {
        val w = SessionFiles.parseWindows(
            """{"active": 2, "windows": [{"id": 2, "title": "xterm", "main": true, "class": "XTerm", "hidden": false, "maximized": true}, """ +
                """{"id": 3, "title": "GIMP", "main": true, "class": "Gimp-2.10", "hidden": true, "maximized": false}], "screen": [1080, 2280]}""",
        )
        assertEquals(listOf(1080, 2280), w.screen)
        assertEquals("XTerm", w.windows[0].windowClass)
        assertEquals(true, w.windows[0].maximized)
        assertEquals(true, w.windows[1].hidden)
    }
}
