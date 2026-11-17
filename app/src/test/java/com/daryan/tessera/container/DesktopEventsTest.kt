// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopEventsTest {
    @Test fun `taps in the dock's room and on icons are read`() {
        // #22: a click of the X screen's own pointer over the dock is the dock's.
        assertEquals(DesktopEvent("", menu = false, x = 182, y = 2593, dock = true), parseEvent("dock {\"x\": 182, \"y\": 2593}"))
        assertEquals(DesktopEvent("/home/user/Desktop/a.desktop", menu = false), parseEvent("open \"/home/user/Desktop/a.desktop\""))
        assertNull(parseEvent("pointer {\"x\": 40, \"y\": 2600}"))
        assertNull(parseEvent("dock"))
    }
}
