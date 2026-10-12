// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.daryan.linuxlauncher.container.X11Server
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.daryan.linuxlauncher.ui.X11Overlay
import com.daryan.linuxlauncher.ui.theme.LauncherTheme
import com.termux.x11.MainActivity
import com.termux.x11.input.InputStub

/**
 * A GUI app's window: Termux:X11's tagged window (X server connection, touch and keyboard
 * handling) with our Material 3 controls laid over it instead of its own toolbar.
 *
 * An app's first main window is shown by the session's own window. Each further one (Firefox's
 * Library, a second browser window) gets an X11Window of its own, in its own task, named by
 * [windowId] in the intent data; all of them share the session's X server, and whichever is in
 * front brings its X window forward.
 */
class X11Window : MainActivity.MainActivityTagged() {
    private var lastTouch = 0L
    private val container get() = (application as LauncherApplication).container
    private val windowId get() = X11Server.windowId(intent?.data)
    private val sessionTag get() = intent?.data?.let { it.host ?: it.toString() }

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
        followSession()
        followFocus()
    }

    /** While in front, opens and closes the keyboard as the app's focus enters and leaves text fields. */
    private fun followFocus() {
        val tag = sessionTag ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                container.sessions.focusChanges.collect { (sessionId, text) ->
                    if (container.sessions.byId(sessionId)?.let { X11Server.windowTag(it.session) } == tag) onFocusChanged(text)
                }
            }
        }
    }

    /** Ends with what it shows: the session, or for a window of its own, that X window. */
    private fun followSession() {
        val tag = sessionTag ?: return
        val id = windowId
        lifecycleScope.launch {
            container.sessions.sessions
                .map { list -> list.firstOrNull { X11Server.windowTag(it.session) == tag } }
                .distinctUntilChanged()
                .collect { s ->
                    val gone = s == null || !s.running || (id != null && s.windows.main.none { it.id == id })
                    // Every window of the session, not just the one Termux:X11's stop finds by tag.
                    if (gone) return@collect finishAndRemoveTask()
                    val title = s?.windows?.windows?.firstOrNull { it.id == id }?.title
                    if (id != null && s != null && !title.isNullOrBlank()) brandForRecents(this@X11Window, s, container, title)
                }
        }
    }

    override fun onResume() {
        super.onResume()
        val session = sessionTag?.let { container.sessions.forWindowTag(it) } ?: return
        // In front now: show our X window on the shared screen.
        container.sessions.windowFor(session.session.id, windowId)?.let { container.sessions.activateWindow(session.session.id, it) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) lastTouch = SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * The app's focus moved into ([text]) or out of a text field: show or hide the keyboard,
     * as a native app would. Only right after a tap, so an app focusing a field by itself (a
     * browser's address bar at startup) doesn't throw the keyboard up unasked.
     */
    private fun onFocusChanged(text: Boolean) {
        if (SystemClock.uptimeMillis() - lastTouch > TAP_WINDOW_MS) return
        val hardKeyboard = resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        if (text && hardKeyboard) return
        lorieView.setKeyboardVisible(text)
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
            val id = windowId
            if (id != null && sessionId != null) {
                // Just this window, the way its own close button would; the task follows.
                container.sessions.closeWindow(sessionId, id)
                return
            }
            sessionId?.let { container.sessions.stop(it) }
            finishAndRemoveTask()
        }
    }
}

private const val TAP_WINDOW_MS = 1500L

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
