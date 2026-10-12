// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.daryan.linuxlauncher.ui.X11Overlay
import com.daryan.linuxlauncher.ui.theme.LauncherTheme
import com.termux.x11.MainActivity
import com.termux.x11.input.InputStub

/**
 * A GUI app's window: Termux:X11's tagged window (X server connection, touch and keyboard
 * handling) with our Material 3 controls laid over it instead of its own toolbar.
 */
class X11Window : MainActivity.MainActivityTagged() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Our floating button replaces Termux:X11's extra-keys bar.
        toggleExtraKeys(false, false)
        val container = (application as LauncherApplication).container
        addContentView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    LauncherTheme {
                        X11Overlay(
                            session = intent?.data?.let { container.sessions.forWindowTag(it.host ?: it.toString()) },
                            actions = actions,
                        )
                    }
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** What the floating button can do to this window and its app. */
    private val actions = object : X11Actions {
        override fun toggleKeyboard() = toggleKeyboardVisibility()

        override fun click(button: MouseButton) {
            val b = when (button) {
                MouseButton.RIGHT -> InputStub.BUTTON_RIGHT
                MouseButton.MIDDLE -> InputStub.BUTTON_MIDDLE
            }
            // Relative (0, 0): click where the pointer already is, i.e. where you last touched.
            lorieView.sendMouseEvent(0f, 0f, b, true, true)
            lorieView.sendMouseEvent(0f, 0f, b, false, true)
        }

        override fun keys(vararg keyCodes: Int) {
            // Press in order, release in reverse: Ctrl+C is Ctrl down, C down, C up, Ctrl up.
            keyCodes.forEach { lorieView.sendKeyEvent(0, it, true) }
            keyCodes.reversed().forEach { lorieView.sendKeyEvent(0, it, false) }
        }

        override fun setTouchMode(mode: TouchMode) {
            getSharedPreferences("${packageName}_preferences", MODE_PRIVATE).edit()
                .putString("touchMode", mode.pref).apply()
        }

        override fun touchMode(): TouchMode {
            val v = getSharedPreferences("${packageName}_preferences", MODE_PRIVATE).getString("touchMode", "3")
            return TouchMode.entries.firstOrNull { it.pref == v } ?: TouchMode.DIRECT
        }

        override fun openPermissions(appId: String) {
            (application as LauncherApplication).container.showAppRequests.value = appId
            startActivity(Intent(this@X11Window, com.daryan.linuxlauncher.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        override fun displaySettings() {
            startActivity(Intent().setClassName(this@X11Window, "com.termux.x11.LoriePreferences"))
        }

        override fun close(sessionId: String?) {
            sessionId?.let { (application as LauncherApplication).container.sessions.stop(it) }
            finishAndRemoveTask()
        }
    }
}

enum class MouseButton { RIGHT, MIDDLE }

/** Termux:X11's touchMode preference values. */
enum class TouchMode(val pref: String, val label: String) {
    DIRECT("3", "Direct touch"),
    TOUCHPAD("1", "Touchpad"),
    SIMULATED("2", "Touchscreen mouse"),
}

interface X11Actions {
    fun toggleKeyboard()
    fun click(button: MouseButton)
    fun keys(vararg keyCodes: Int)
    fun setTouchMode(mode: TouchMode)
    fun touchMode(): TouchMode
    fun openPermissions(appId: String)
    fun displaySettings()
    fun close(sessionId: String?)

    companion object {
        /** Key combinations the button offers, in Android key codes. */
        val SHORTCUTS = listOf(
            "Esc" to intArrayOf(KeyEvent.KEYCODE_ESCAPE),
            "Tab" to intArrayOf(KeyEvent.KEYCODE_TAB),
            "Ctrl+C" to intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C),
            "Ctrl+V" to intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V),
            "Ctrl+Z" to intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z),
            "Ctrl+A" to intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A),
            "Alt+Tab" to intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB),
            "Alt+F4" to intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_F4),
            "F11" to intArrayOf(KeyEvent.KEYCODE_F11),
            "←" to intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT),
            "→" to intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT),
            "↑" to intArrayOf(KeyEvent.KEYCODE_DPAD_UP),
            "↓" to intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN),
        )
    }
}
