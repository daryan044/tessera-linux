// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.input

import android.view.KeyEvent
import com.daryan.tessera.MouseButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsTest {

    @Test fun `a mouse report is buttons then the three signed axes`() {
        val report = HidReports.mouse(HidReports.buttonBits(setOf(MouseButton.LEFT)), 5, -3, 1)
        assertEquals(HidReports.MOUSE_REPORT_LENGTH, report.size)
        assertEquals(0x01, report[0].toInt())
        assertEquals(5, report[1].toInt())
        assertEquals(-3, report[2].toInt())
        assertEquals(1, report[3].toInt())
    }

    @Test fun `movement faster than a byte can hold is clamped, not wrapped`() {
        // Wrapping would send the pointer the other way, which is worse than a slower flick.
        val report = HidReports.mouse(0, 5000, -5000)
        assertEquals(127, report[1].toInt())
        assertEquals(-127, report[2].toInt())
    }

    @Test fun `each button has its own bit, and they combine`() {
        assertEquals(0x01, HidReports.buttonBits(setOf(MouseButton.LEFT)))
        assertEquals(0x02, HidReports.buttonBits(setOf(MouseButton.RIGHT)))
        assertEquals(0x04, HidReports.buttonBits(setOf(MouseButton.MIDDLE)))
        assertEquals(0x05, HidReports.buttonBits(setOf(MouseButton.LEFT, MouseButton.MIDDLE)))
        assertEquals(0, HidReports.buttonBits(emptySet()))
    }

    @Test fun `a keyboard report puts modifiers first and keys from the third byte`() {
        val report = HidReports.keyboard(HidModifier.LEFT_CTRL, listOf(0x06))
        assertEquals(HidReports.KEYBOARD_REPORT_LENGTH, report.size)
        assertEquals(HidModifier.LEFT_CTRL, report[0].toInt())
        assertEquals(0, report[1].toInt()) // the reserved byte
        assertEquals(0x06, report[2].toInt())
    }

    @Test fun `no more than the six keys a boot keyboard can hold`() {
        val report = HidReports.keyboard(0, List(10) { 0x04 })
        assertEquals(HidReports.KEYBOARD_REPORT_LENGTH, report.size)
        assertTrue(report.drop(2).all { it.toInt() == 0x04 })
    }

    @Test fun `the combined descriptor gives each device a report id`() {
        val combined = HidReports.COMBINED_DESCRIPTOR
        // Longer than the two apart, by exactly the two id items it inserted.
        assertEquals(
            HidReports.MOUSE_DESCRIPTOR.size + HidReports.KEYBOARD_DESCRIPTOR.size + 4,
            combined.size,
        )
        // Report ID (0x85) 1 appears for the mouse, 2 for the keyboard.
        val ids = combined.indices.filter { combined[it] == 0x85.toByte() }.map { combined[it + 1].toInt() }
        assertEquals(listOf(1, 2), ids)
    }

    @Test fun `the usb descriptors carry no report id, having an interface each`() {
        assertTrue(HidReports.MOUSE_DESCRIPTOR.none { it == 0x85.toByte() })
        assertTrue(HidReports.KEYBOARD_DESCRIPTOR.none { it == 0x85.toByte() })
    }
}

class HidKeymapTest {

    @Test fun `the side menu's shortcuts become a modifier plus a key`() {
        // Exactly how X11Actions.SHORTCUTS writes them: modifiers first, the key last.
        val ctrlC = HidKeymap.stroke(intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C))
        assertNotNull(ctrlC)
        assertEquals(0x06, ctrlC!!.usage) // c
        assertEquals(HidModifier.LEFT_CTRL, ctrlC.modifiers)

        val altTab = HidKeymap.stroke(intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB))!!
        assertEquals(0x2B, altTab.usage)
        assertEquals(HidModifier.LEFT_ALT, altTab.modifiers)
    }

    @Test fun `a lone key has no modifiers`() {
        val esc = HidKeymap.stroke(intArrayOf(KeyEvent.KEYCODE_ESCAPE))!!
        assertEquals(0x29, esc.usage)
        assertEquals(0, esc.modifiers)
    }

    @Test fun `keys a keyboard hasn't got are refused rather than mis-sent`() {
        assertNull(HidKeymap.stroke(intArrayOf(KeyEvent.KEYCODE_CAMERA)))
        assertNull(HidKeymap.stroke(intArrayOf()))
        // Modifiers with nothing to modify are not a keystroke.
        assertNull(HidKeymap.stroke(intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT)))
    }

    @Test fun `typed characters carry shift where the character needs it`() {
        assertEquals(0x04, HidKeymap.stroke('a')!!.usage)
        assertEquals(0, HidKeymap.stroke('a')!!.modifiers)
        assertEquals(0x04, HidKeymap.stroke('A')!!.usage)
        assertEquals(HidModifier.LEFT_SHIFT, HidKeymap.stroke('A')!!.modifiers)
        // The digit row, where 0 does not follow 9.
        assertEquals(0x1E, HidKeymap.stroke('1')!!.usage)
        assertEquals(0x27, HidKeymap.stroke('0')!!.usage)
        // Shifted punctuation shares its key with the unshifted character.
        assertEquals(HidKeymap.stroke('1')!!.usage, HidKeymap.stroke('!')!!.usage)
        assertEquals(HidModifier.LEFT_SHIFT, HidKeymap.stroke('!')!!.modifiers)
        assertEquals(HidKeymap.stroke('/')!!.usage, HidKeymap.stroke('?')!!.usage)
    }

    @Test fun `typing a line gives one stroke per character, newline included`() {
        val strokes = HidKeymap.strokes("Hi!\n")
        assertEquals(4, strokes.size)
        assertEquals(HidModifier.LEFT_SHIFT, strokes[0].modifiers) // H
        assertEquals(0, strokes[1].modifiers) // i
        assertEquals(0x28, strokes[3].usage) // Enter
    }

    @Test fun `characters with no key on a US layout are dropped, not guessed at`() {
        assertEquals(emptyList<HidKeymap.Stroke>(), HidKeymap.strokes("é€"))
        assertEquals(2, HidKeymap.strokes("aé b".filter { it != ' ' }).size)
    }
}
