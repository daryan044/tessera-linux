// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.daryan.tessera.container.RunningSession

private val TerminalBackground = Color(0xFF0E0F12)

/** Full-screen interactive terminal for one session. */
@Composable
fun TerminalScreen(
    session: RunningSession,
    onBack: () -> Unit,
    onStop: () -> Unit,
    setScreenListener: (String, (() -> Unit)?) -> Unit,
) {
    val terminal = session.terminal ?: return
    BackHandler(onBack = onBack)

    val density = LocalDensity.current
    var textPx by remember { mutableStateOf(with(density) { 13.dp.roundToPx() }) }
    val modifiers = remember { Modifiers() }
    var view by remember { mutableStateOf<TerminalView?>(null) }

    DisposableEffect(session.session.id, view) {
        val v = view
        setScreenListener(session.session.id) { v?.onScreenUpdated() }
        onDispose { setScreenListener(session.session.id, null) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(session.app.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    session.title ?: if (session.running) session.backend else session.statusLabel,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            if (session.running) {
                FilledTonalButton(onClick = onStop, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Rounded.Close, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setTextSize(textPx)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTerminalViewClient(ViewClient(this, modifiers) { scale ->
                        val next = (textPx * scale).toInt().coerceIn(with(density) { 8.dp.roundToPx() }, with(density) { 32.dp.roundToPx() })
                        if (next != textPx) {
                            textPx = next
                            setTextSize(next)
                        }
                    })
                    attachSession(terminal)
                    view = this
                    post {
                        requestFocus()
                        ctx.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
                    }
                }
            },
            update = { if (it.currentSession !== terminal) it.attachSession(terminal) },
        )

        ExtraKeys(modifiers, onKey = { code -> view?.let { sendKey(it, code) } })
    }
}

/** Sticky Ctrl/Alt from the extra-keys row; consumed by the next key typed. */
private class Modifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
}

private fun sendKey(view: TerminalView, keyCode: Int) {
    view.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    view.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
}

private val Keys = listOf(
    "ESC" to KeyEvent.KEYCODE_ESCAPE, "TAB" to KeyEvent.KEYCODE_TAB,
    "←" to KeyEvent.KEYCODE_DPAD_LEFT, "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
    "↑" to KeyEvent.KEYCODE_DPAD_UP, "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "HOME" to KeyEvent.KEYCODE_MOVE_HOME, "END" to KeyEvent.KEYCODE_MOVE_END,
    "PGUP" to KeyEvent.KEYCODE_PAGE_UP, "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
)

@Composable
private fun ExtraKeys(modifiers: Modifiers, onKey: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Key("CTRL", toggled = modifiers.ctrl) { modifiers.ctrl = !modifiers.ctrl }
        Key("ALT", toggled = modifiers.alt) { modifiers.alt = !modifiers.alt }
        Keys.forEach { (label, code) -> Key(label) { onKey(code) } }
    }
}

@Composable
private fun Key(label: String, toggled: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (toggled) c.primary else Color.White.copy(alpha = 0.08f),
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "key",
    )
    Surface(onClick = onClick, shape = CircleShape, color = bg, contentColor = if (toggled) c.onPrimary else Color.White) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(min = 44.dp).height(36.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Minimal TerminalViewClient: keyboard on tap, pinch to zoom, sticky modifiers. */
private class ViewClient(
    private val view: TerminalView,
    private val modifiers: Modifiers,
    private val onZoom: (Float) -> Unit,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            onZoom(scale)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        view.context.getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false

    // Read once per key, then released, like Termux's extra keys.
    override fun readControlKey() = modifiers.ctrl.also { if (it) modifiers.ctrl = false }
    override fun readAltKey() = modifiers.alt.also { if (it) modifiers.alt = false }
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
