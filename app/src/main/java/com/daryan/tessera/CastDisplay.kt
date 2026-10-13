// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.termux.x11.LorieView
import com.termux.x11.MainActivity

/** A display a window can be cast to: a TV or monitor on a cable, or a connected wireless display. */
data class CastTarget(val displayId: Int, val name: String, val detail: String)

/**
 * The cast picture: a session's X screen on another display, through a view of its own that
 * takes over the server's display connection from [host]'s (see MainActivity.takeDisplayConnection).
 * The phone meanwhile works as a trackpad for it.
 */
class CastPresentation(
    private val host: MainActivity,
    display: Display,
    private val connection: ParcelFileDescriptor,
) : Presentation(host, display) {
    lateinit var view: LorieView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = LorieView(HostContext(context, host), null)
        setContentView(view)
        // The phone's scale (set from its density) would make a TV's X screen tiny: size it
        // from the display's own density instead, 100% at a monitor's 160 dpi.
        view.setScaleOverride(scaleFor(display))
        view.reloadPreferences(host.prefs)
        view.connect(connection.detachFd())
    }

    private fun scaleFor(display: Display): Int {
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            display.getRealMetrics(it)
        }
        return (metrics.densityDpi * 100 / DisplayMetrics.DENSITY_DEFAULT).coerceIn(100, 300)
    }
}

/**
 * The cast view's context: the other display's (its size, density and resources), but leading
 * Termux:X11's lookup of "its" activity, which walks base contexts, to [host].
 */
private class HostContext(base: Context, private val host: Activity) : ContextWrapper(base) {
    override fun getBaseContext(): Context = host
}

/**
 * What the phone's keyboard types into while a window is cast: the soft keyboard needs a view
 * in the phone's own window, but the text belongs to the cast view, the one the server listens
 * to. So this view's input connection and keys are [target]'s.
 */
class KeyboardProxy(context: Context, private val target: () -> LorieView?) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        target()?.onCreateInputConnection(outAttrs)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        target()?.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)
}
