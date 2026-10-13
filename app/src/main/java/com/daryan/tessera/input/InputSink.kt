// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.input

import com.daryan.tessera.MouseButton

/**
 * Somewhere a pointer and a keyboard can go: the X server of a cast app, or a PC the phone is
 * plugged into as a USB mouse and keyboard ([UsbHidSink]) or paired with over Bluetooth
 * ([BtHidSink]).
 *
 * The trackpad talks to this and nothing else, so the same gestures drive any of them. Key codes
 * are Android's [android.view.KeyEvent] constants, which is what the side menu's shortcuts have
 * always been; each sink translates them to whatever its far end wants.
 */
interface InputSink {
    /** Moves the pointer by ([dx], [dy]), in pixels at the far end. */
    fun move(dx: Float, dy: Float)

    /** Scrolls by ([dx], [dy]) notches; positive [dy] scrolls down. */
    fun scroll(dx: Float, dy: Float)

    /** Holds [button] down, or lets it go: dragging from the trackpad. */
    fun press(button: MouseButton, down: Boolean)

    fun click(button: MouseButton)

    /**
     * Sends one keystroke: every code but the last is held as a modifier while the last is
     * pressed, so `keys(CTRL_LEFT, C)` is Ctrl+C.
     */
    fun keys(vararg keyCodes: Int)

    /**
     * Types [text] as keystrokes. Only the sinks that pretend to be a keyboard do anything with
     * it: an X11 session already receives what the Android keyboard sends.
     */
    fun type(text: String) {}

    /** False once the far end has gone away (unplugged, unpaired, or the gadget was reset). */
    val connected: Boolean get() = true
}
