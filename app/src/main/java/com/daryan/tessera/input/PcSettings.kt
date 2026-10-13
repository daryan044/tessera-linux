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
 * another.
 */
class PcSettings(private val prefs: SharedPreferences) {

    /** Pointer movement, as a multiple of the default. */
    var pointerSpeed by mutableFloatStateOf(prefs.getFloat(KEY_POINTER, 1f))

    /** Scrolling, as a multiple of the default. */
    var scrollSpeed by mutableFloatStateOf(prefs.getFloat(KEY_SCROLL, 1f))

    /** Content follows the fingers, the way a phone scrolls, rather than the other way. */
    var naturalScroll by mutableStateOf(prefs.getBoolean(KEY_NATURAL, true))

    /** Two fingers spreading zooms; off, they always scroll. */
    var pinchZoom by mutableStateOf(prefs.getBoolean(KEY_PINCH, true))

    /** The keyboard opens as soon as a PC is connected. */
    var autoKeyboard by mutableStateOf(prefs.getBoolean(KEY_AUTO_KEYBOARD, true))

    fun setPointerSpeed(value: Float) {
        pointerSpeed = value
        prefs.edit().putFloat(KEY_POINTER, value).apply()
    }

    fun setScrollSpeed(value: Float) {
        scrollSpeed = value
        prefs.edit().putFloat(KEY_SCROLL, value).apply()
    }

    fun setNaturalScroll(on: Boolean) {
        naturalScroll = on
        prefs.edit().putBoolean(KEY_NATURAL, on).apply()
    }

    fun setPinchZoom(on: Boolean) {
        pinchZoom = on
        prefs.edit().putBoolean(KEY_PINCH, on).apply()
    }

    fun setAutoKeyboard(on: Boolean) {
        autoKeyboard = on
        prefs.edit().putBoolean(KEY_AUTO_KEYBOARD, on).apply()
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
