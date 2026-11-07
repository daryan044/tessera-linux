// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import org.junit.Assert.assertEquals
import org.junit.Test

class X11ScaleTest {
    @Test fun `at the size it started with, a full-resolution app's screen is the window's`() {
        for (uiScale in listOf(100, 180, 250, 290, 400)) assertEquals(100, X11Scale.screenOverride(uiScale, uiScale))
    }

    @Test fun `the slider stretches only what's left over`() {
        assertEquals(200, X11Scale.screenOverride(360, 180))
        assertEquals(125, X11Scale.screenOverride(250, 200))
    }

    @Test fun `below the size it started with, the screen is larger than the window`() {
        assertEquals(50, X11Scale.screenOverride(125, 250))
        assertEquals(80, X11Scale.screenOverride(200, 250))
    }

    @Test fun `the slider can't ask for a screen over twice the window's size`() {
        assertEquals(50, X11Scale.screenOverride(100, 400))
        assertEquals(200, X11Scale.minLive(400))
        assertEquals(125, X11Scale.minLive(250))
        // Rounded up: half of it, rounded down, would ask for a little over twice.
        assertEquals(145, X11Scale.minLive(290))
        assertEquals(50, X11Scale.screenOverride(X11Scale.minLive(290), 290))
    }

    @Test fun `a stretched app's slider covers the whole range`() {
        assertEquals(X11Scale.MIN, X11Scale.minLive(null))
        // Never under 100%, however small the size it started with.
        assertEquals(X11Scale.MIN, X11Scale.minLive(110))
    }
}
