// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.widgets

import com.daryan.tessera.WidgetKind
import com.daryan.tessera.WidgetTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStatsTest {
    @Test fun `sizes read the way the phone's settings do`() {
        assertEquals("512 GB", WidgetStats.size(512_000_000_000))
        assertEquals("7.3 GB", WidgetStats.size(7_300_000_000))
        assertEquals("900 MB", WidgetStats.size(900_000_000))
    }

    @Test fun `what it says, and how full it draws`() {
        val stats = WidgetStats(memoryUsed = 6_000_000_000, memoryTotal = 12_000_000_000, storageFree = 100_000_000_000, storageTotal = 400_000_000_000, running = 2)
        assertEquals("6.0 GB of 12.0 GB used", stats.memoryText)
        assertEquals("100 GB free of 400 GB", stats.storageText)
        assertEquals("2 Linux apps running", stats.runningText)
        assertEquals(0.5f, stats.memoryFraction, 0.001f)
        assertEquals(0.75f, stats.storageFraction, 0.001f)
    }

    @Test fun `counts read as sentences, and no size is no crash`() {
        val empty = WidgetStats(0, 0, 0, 0, running = 0)
        assertEquals("No Linux apps running", empty.runningText)
        assertEquals("1 Linux app running", empty.copy(running = 1).runningText)
        assertEquals(0f, empty.memoryFraction, 0f)
        assertEquals(0f, empty.storageFraction, 0f)
    }

    @Test fun `rings get whole percentages, and short text under them`() {
        val stats = WidgetStats(memoryUsed = 7_300_000_000, memoryTotal = 12_000_000_000, storageFree = 100_000_000_000, storageTotal = 400_000_000_000, running = 2)
        assertEquals(61, stats.memoryPercent)
        assertEquals(75, stats.storagePercent)
        assertEquals("7.3 GB used", stats.memoryShort)
        assertEquals("100 GB free", stats.storageShort)
        assertEquals("2 running", stats.runningShort)
    }

    @Test fun `a full or empty phone stays within the ring`() {
        assertEquals(100, WidgetStats(20, 10, 0, 10, 0).memoryPercent)
        assertEquals(0, WidgetStats(0, 10, 10, 10, 0).storagePercent)
    }

    @Test fun `the Running widget says how many, and when it can't show them all`() {
        assertEquals("Nothing's running", PhoneWidgets.runningTitle(0, shown = 4))
        assertEquals("3 running", PhoneWidgets.runningTitle(3, shown = 4))
        assertEquals("6 running, 4 shown", PhoneWidgets.runningTitle(6, shown = 4))
    }

    @Test fun `an app with no picture gets its first letter`() {
        assertEquals("F", WidgetIcons.initial("firefox"))
        assertEquals("0", WidgetIcons.initial("0 A.D."))
        assertEquals("G", WidgetIcons.initial("  (GIMP)"))
        assertEquals("?", WidgetIcons.initial("   "))
    }

    @Test fun `each phone widget's settings are its own, and survive saving`() {
        val json = Json { ignoreUnknownKeys = true }
        val a = PhoneWidget(WidgetKind.APP, WidgetTarget("gimp", "GIMP"))
        val b = PhoneWidget(WidgetKind.APP, WidgetTarget("firefox-esr", "Firefox"))
        val saved = listOf(a, b).map { json.encodeToString(PhoneWidget.serializer(), it) }
        assertEquals(listOf(a, b), saved.map { json.decodeFromString(PhoneWidget.serializer(), it) })
        assertEquals(PhoneWidget(WidgetKind.STATS), json.decodeFromString(PhoneWidget.serializer(), """{"kind":"STATS"}"""))
    }
}
