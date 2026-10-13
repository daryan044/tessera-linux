// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.input

import android.view.KeyEvent
import com.daryan.tessera.MouseButton

/**
 * The bytes a USB or Bluetooth host expects from a mouse and a keyboard.
 *
 * Both use the HID boot protocol, the one every BIOS and OS already understands, so nothing has
 * to be installed at the other end: a 4-byte mouse report (buttons, relative X, Y, wheel) and the
 * 8-byte keyboard report (modifiers, a reserved byte, and up to six held keys).
 */
object HidReports {
    /** Mouse: three buttons, relative X/Y and a wheel, all in one 4-byte report. */
    val MOUSE_DESCRIPTOR = byteArrayOf(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x02, // Usage (Mouse)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x09, 0x01, //   Usage (Pointer)
        0xA1.toByte(), 0x00, //   Collection (Physical)
        0x05, 0x09, //     Usage Page (Button)
        0x19, 0x01, //     Usage Minimum (1)
        0x29, 0x03, //     Usage Maximum (3)
        0x15, 0x00, //     Logical Minimum (0)
        0x25, 0x01, //     Logical Maximum (1)
        0x95, 0x03, //     Report Count (3)
        0x75, 0x01, //     Report Size (1)
        0x81.toByte(), 0x02, //     Input (Data, Variable, Absolute)
        0x95, 0x01, //     Report Count (1)
        0x75, 0x05, //     Report Size (5)
        0x81.toByte(), 0x01, //     Input (Constant) — padding out the byte
        0x05, 0x01, //     Usage Page (Generic Desktop)
        0x09, 0x30, //     Usage (X)
        0x09, 0x31, //     Usage (Y)
        0x09, 0x38, //     Usage (Wheel)
        0x15, 0x81.toByte(), //     Logical Minimum (-127)
        0x25, 0x7F, //     Logical Maximum (127)
        0x75, 0x08, //     Report Size (8)
        0x95, 0x03, //     Report Count (3)
        0x81.toByte(), 0x06, //     Input (Data, Variable, Relative)
        0xC0.toByte(), //   End Collection
        0xC0.toByte(), // End Collection
    )

    /** Keyboard: the boot protocol, plus the host's LED states coming back. */
    val KEYBOARD_DESCRIPTOR = byteArrayOf(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x05, 0x07, //   Usage Page (Keyboard)
        0x19, 0xE0.toByte(), //   Usage Minimum (Left Control)
        0x29, 0xE7.toByte(), //   Usage Maximum (Right GUI)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x08, //   Report Count (8)
        0x81.toByte(), 0x02, //   Input (Data, Variable, Absolute) — the modifier byte
        0x95, 0x01, //   Report Count (1)
        0x75, 0x08, //   Report Size (8)
        0x81.toByte(), 0x01, //   Input (Constant) — the reserved byte
        0x95, 0x05, //   Report Count (5)
        0x75, 0x01, //   Report Size (1)
        0x05, 0x08, //   Usage Page (LED)
        0x19, 0x01, //   Usage Minimum (Num Lock)
        0x29, 0x05, //   Usage Maximum (Kana)
        0x91.toByte(), 0x02, //   Output (Data, Variable, Absolute) — the host's LEDs
        0x95, 0x01, //   Report Count (1)
        0x75, 0x03, //   Report Size (3)
        0x91.toByte(), 0x01, //   Output (Constant) — padding
        0x95, 0x06, //   Report Count (6)
        0x75, 0x08, //   Report Size (8)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x65, //   Logical Maximum (101)
        0x05, 0x07, //   Usage Page (Keyboard)
        0x19, 0x00, //   Usage Minimum (0)
        0x29, 0x65, //   Usage Maximum (101)
        0x81.toByte(), 0x00, //   Input (Data, Array) — the six held keys
        0xC0.toByte(), // End Collection
    )

    const val MOUSE_REPORT_LENGTH = 4
    const val KEYBOARD_REPORT_LENGTH = 8

    /** Report ids, for Bluetooth, where both devices share one descriptor. */
    const val REPORT_ID_MOUSE = 1
    const val REPORT_ID_KEYBOARD = 2

    /**
     * Mouse and keyboard in one descriptor, each behind a report id. USB gives them an interface
     * apiece instead, so it uses the two descriptors above; Bluetooth has only one pipe.
     */
    val COMBINED_DESCRIPTOR: ByteArray = withReportId(MOUSE_DESCRIPTOR, REPORT_ID_MOUSE) +
        withReportId(KEYBOARD_DESCRIPTOR, REPORT_ID_KEYBOARD)

    /**
     * [descriptor] with a Report ID item inserted, which must come after the collection it
     * belongs to opens: the first `A1 01` (Collection (Application)) in a boot descriptor.
     */
    private fun withReportId(descriptor: ByteArray, id: Int): ByteArray {
        val open = (0 until descriptor.size - 1).first {
            descriptor[it] == 0xA1.toByte() && descriptor[it + 1] == 0x01.toByte()
        } + 2
        return descriptor.copyOfRange(0, open) +
            byteArrayOf(0x85.toByte(), id.toByte()) +
            descriptor.copyOfRange(open, descriptor.size)
    }

    /** Nothing held: sent after every keystroke, and when a sink shuts down. */
    val KEYS_UP = ByteArray(KEYBOARD_REPORT_LENGTH)
    val BUTTONS_UP = ByteArray(MOUSE_REPORT_LENGTH)

    private fun MouseButton.bit() = when (this) {
        MouseButton.LEFT -> 0x01
        MouseButton.RIGHT -> 0x02
        MouseButton.MIDDLE -> 0x04
    }

    /** Which buttons [held] amounts to, as the report's first byte. */
    fun buttonBits(held: Set<MouseButton>): Int = held.fold(0) { bits, b -> bits or b.bit() }

    /**
     * A mouse report. The host takes one signed byte per axis, so anything faster than that is
     * clamped; the trackpad sends often enough that it never shows.
     */
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) = byteArrayOf(
        buttons.toByte(),
        dx.coerceIn(-127, 127).toByte(),
        dy.coerceIn(-127, 127).toByte(),
        wheel.coerceIn(-127, 127).toByte(),
    )

    /** A keyboard report holding [usages] (at most six) with [modifiers] down. */
    fun keyboard(modifiers: Int, usages: List<Int>) = ByteArray(KEYBOARD_REPORT_LENGTH).also {
        it[0] = modifiers.toByte()
        usages.take(6).forEachIndexed { i, usage -> it[2 + i] = usage.toByte() }
    }
}

/** HID modifier bits, in the order the report's first byte has them. */
object HidModifier {
    const val LEFT_CTRL = 0x01
    const val LEFT_SHIFT = 0x02
    const val LEFT_ALT = 0x04
    const val LEFT_META = 0x08
    const val RIGHT_CTRL = 0x10
    const val RIGHT_SHIFT = 0x20
    const val RIGHT_ALT = 0x40
    const val RIGHT_META = 0x80
}

/**
 * Android key codes and typed characters, as the HID usage codes a keyboard would send.
 *
 * The trackpad and the side menu have always spoken [KeyEvent] codes, so this is the only piece
 * that has to know anything about HID numbering.
 */
object HidKeymap {
    /** A keystroke at the far end: a usage code with modifiers held down for it. */
    data class Stroke(val usage: Int, val modifiers: Int = 0)

    /** The modifier a key code is, if it is one at all. */
    fun modifierOf(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT -> HidModifier.LEFT_CTRL
        KeyEvent.KEYCODE_CTRL_RIGHT -> HidModifier.RIGHT_CTRL
        KeyEvent.KEYCODE_SHIFT_LEFT -> HidModifier.LEFT_SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> HidModifier.RIGHT_SHIFT
        KeyEvent.KEYCODE_ALT_LEFT -> HidModifier.LEFT_ALT
        KeyEvent.KEYCODE_ALT_RIGHT -> HidModifier.RIGHT_ALT
        KeyEvent.KEYCODE_META_LEFT -> HidModifier.LEFT_META
        KeyEvent.KEYCODE_META_RIGHT -> HidModifier.RIGHT_META
        else -> null
    }

    /** The usage code for [keyCode], or null for keys a keyboard has no place for. */
    fun usageOf(keyCode: Int): Int? = USAGES[keyCode]

    /**
     * [keyCodes] as one keystroke: everything but the last is held as a modifier, which is how
     * the side menu's `Ctrl+C` and `Alt+Tab` are written. Null when the last code is not a key
     * a keyboard can send.
     */
    fun stroke(keyCodes: IntArray): Stroke? {
        if (keyCodes.isEmpty()) return null
        var modifiers = 0
        var usage: Int? = null
        keyCodes.forEach { code ->
            val mod = modifierOf(code)
            if (mod != null) modifiers = modifiers or mod else usage = usageOf(code) ?: usage
        }
        return usage?.let { Stroke(it, modifiers) }
    }

    /** [char] as a keystroke, shift included where the character needs it. */
    fun stroke(char: Char): Stroke? = when {
        char in 'a'..'z' -> Stroke(0x04 + (char - 'a'))
        char in 'A'..'Z' -> Stroke(0x04 + (char - 'A'), HidModifier.LEFT_SHIFT)
        char in '1'..'9' -> Stroke(0x1E + (char - '1'))
        char == '0' -> Stroke(0x27)
        else -> PUNCTUATION[char]
    }

    /** [text] as the keystrokes that would type it. Characters with no key are dropped. */
    fun strokes(text: String): List<Stroke> = text.mapNotNull(::stroke)

    /** Shifted and unshifted punctuation, by the character it produces on a US layout. */
    private val PUNCTUATION: Map<Char, Stroke> = buildMap {
        fun put(plain: Char, shifted: Char, usage: Int) {
            put(plain, Stroke(usage))
            put(shifted, Stroke(usage, HidModifier.LEFT_SHIFT))
        }
        put(' ', Stroke(0x2C))
        put('\n', Stroke(0x28))
        put('\t', Stroke(0x2B))
        put('-', '_', 0x2D)
        put('=', '+', 0x2E)
        put('[', '{', 0x2F)
        put(']', '}', 0x30)
        put('\\', '|', 0x31)
        put(';', ':', 0x33)
        put('\'', '"', 0x34)
        put('`', '~', 0x35)
        put(',', '<', 0x36)
        put('.', '>', 0x37)
        put('/', '?', 0x38)
        // The digits' shifted forms, which don't follow the '1'..'9' run.
        put('!', Stroke(0x1E, HidModifier.LEFT_SHIFT))
        put('@', Stroke(0x1F, HidModifier.LEFT_SHIFT))
        put('#', Stroke(0x20, HidModifier.LEFT_SHIFT))
        put('$', Stroke(0x21, HidModifier.LEFT_SHIFT))
        put('%', Stroke(0x22, HidModifier.LEFT_SHIFT))
        put('^', Stroke(0x23, HidModifier.LEFT_SHIFT))
        put('&', Stroke(0x24, HidModifier.LEFT_SHIFT))
        put('*', Stroke(0x25, HidModifier.LEFT_SHIFT))
        put('(', Stroke(0x26, HidModifier.LEFT_SHIFT))
        put(')', Stroke(0x27, HidModifier.LEFT_SHIFT))
    }

    private val USAGES: Map<Int, Int> = buildMap {
        // Letters and digits, so a hardware keyboard's codes work as well as typed text.
        ('a'..'z').forEach { put(KeyEvent.KEYCODE_A + (it - 'a'), 0x04 + (it - 'a')) }
        ('1'..'9').forEach { put(KeyEvent.KEYCODE_1 + (it - '1'), 0x1E + (it - '1')) }
        put(KeyEvent.KEYCODE_0, 0x27)
        put(KeyEvent.KEYCODE_ENTER, 0x28)
        put(KeyEvent.KEYCODE_ESCAPE, 0x29)
        put(KeyEvent.KEYCODE_DEL, 0x2A) // Android's DEL is Backspace
        put(KeyEvent.KEYCODE_TAB, 0x2B)
        put(KeyEvent.KEYCODE_SPACE, 0x2C)
        put(KeyEvent.KEYCODE_MINUS, 0x2D)
        put(KeyEvent.KEYCODE_EQUALS, 0x2E)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2F)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30)
        put(KeyEvent.KEYCODE_BACKSLASH, 0x31)
        put(KeyEvent.KEYCODE_SEMICOLON, 0x33)
        put(KeyEvent.KEYCODE_APOSTROPHE, 0x34)
        put(KeyEvent.KEYCODE_GRAVE, 0x35)
        put(KeyEvent.KEYCODE_COMMA, 0x36)
        put(KeyEvent.KEYCODE_PERIOD, 0x37)
        put(KeyEvent.KEYCODE_SLASH, 0x38)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 0x39)
        (1..12).forEach { put(KeyEvent.KEYCODE_F1 + (it - 1), 0x3A + (it - 1)) }
        put(KeyEvent.KEYCODE_SYSRQ, 0x46) // Print Screen
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 0x47)
        put(KeyEvent.KEYCODE_BREAK, 0x48) // Pause
        put(KeyEvent.KEYCODE_INSERT, 0x49)
        put(KeyEvent.KEYCODE_MOVE_HOME, 0x4A)
        put(KeyEvent.KEYCODE_PAGE_UP, 0x4B)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 0x4C) // Delete
        put(KeyEvent.KEYCODE_MOVE_END, 0x4D)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 0x4E)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 0x4F)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 0x50)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 0x51)
        put(KeyEvent.KEYCODE_DPAD_UP, 0x52)
        put(KeyEvent.KEYCODE_NUM_LOCK, 0x53)
        put(KeyEvent.KEYCODE_MENU, 0x65)
    }
}
