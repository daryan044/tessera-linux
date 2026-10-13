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
    }
}
