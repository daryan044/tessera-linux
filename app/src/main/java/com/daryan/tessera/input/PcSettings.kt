// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.input

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the trackpad behaves when it is driving a PC, kept between sessions.
 *
 * Everything here is a preference rather than a guess on our part: how fast a pointer should move
 * and whether two fingers zoom are matters of taste, and what suits one desk does not suit
 * another. Assigning any of them writes it straight through.
 */
class PcSettings(private val prefs: SharedPreferences) {

    private var pointer by mutableFloatStateOf(prefs.getFloat(KEY_POINTER, 1f))
    private var scroll by mutableFloatStateOf(prefs.getFloat(KEY_SCROLL, 1f))
    private var natural by mutableStateOf(prefs.getBoolean(KEY_NATURAL, true))
    private var pinch by mutableStateOf(prefs.getBoolean(KEY_PINCH, true))
    private var keyboard by mutableStateOf(prefs.getBoolean(KEY_AUTO_KEYBOARD, true))

    /** Pointer movement, as a multiple of the default. */
    var pointerSpeed: Float
        get() = pointer
        set(value) {
            pointer = value
            prefs.edit().putFloat(KEY_POINTER, value).apply()
        }

    /** Scrolling, as a multiple of the default. */
    var scrollSpeed: Float
        get() = scroll
        set(value) {
            scroll = value
            prefs.edit().putFloat(KEY_SCROLL, value).apply()
        }

    /** Content follows the fingers, the way a phone scrolls, rather than the other way. */
    var naturalScroll: Boolean
        get() = natural
        set(value) {
            natural = value
            prefs.edit().putBoolean(KEY_NATURAL, value).apply()
        }

    /** Two fingers spreading zooms; off, they always scroll. */
    var pinchZoom: Boolean
        get() = pinch
        set(value) {
            pinch = value
            prefs.edit().putBoolean(KEY_PINCH, value).apply()
        }

    /** The keyboard opens as soon as a PC is connected. */
    var autoKeyboard: Boolean
        get() = keyboard
        set(value) {
            keyboard = value
            prefs.edit().putBoolean(KEY_AUTO_KEYBOARD, value).apply()
        }

    companion object {
        const val MIN_SPEED = 0.3f
        const val MAX_SPEED = 3f

        private const val KEY_POINTER = "pc_pointer_speed"
        private const val KEY_SCROLL = "pc_scroll_speed"
        private const val KEY_NATURAL = "pc_natural_scroll"
        private const val KEY_PINCH = "pc_pinch_zoom"
        private const val KEY_AUTO_KEYBOARD = "pc_auto_keyboard"

        fun of(context: Context) = PcSettings(
            context.getSharedPreferences("launcher", Context.MODE_PRIVATE),
        )
    }
}
