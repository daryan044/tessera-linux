// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.daryan.tessera.container.ChrootBackend
import com.daryan.tessera.container.HostDevices
import com.daryan.tessera.container.UsbBridge
import com.daryan.tessera.container.X11Server
import com.daryan.tessera.container.XWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.daryan.tessera.ui.X11Overlay
import com.daryan.tessera.ui.theme.LauncherTheme
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
    /** The cast to another display, while there is one; the phone is its trackpad meanwhile. */
    private var cast: CastPresentation? = null
    private val castState = MutableStateFlow<CastState?>(null)
    private val castTargets = MutableStateFlow<List<CastTarget>>(emptyList())
    private val usbDevices = MutableStateFlow<List<UsbEntry>>(emptyList())
    /** Where the phone's keyboard types while casting; see [KeyboardProxy]. */
    private val keyboardProxy by lazy { KeyboardProxy(this) { cast?.view } }
    private var proxyKeyboardShown = false
    /** Where input goes: the cast picture's view while casting, else this window's own. */
    private val input get() = cast?.view ?: lorieView
    private val displays get() = getSystemService(DisplayManager::class.java)
    private val container get() = (application as LauncherApplication).container
    private val windowId get() = X11Server.windowId(intent?.data)
    private val sessionTag get() = intent?.data?.let { it.host ?: it.toString() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Our floating button replaces Termux:X11's extra-keys bar.
        toggleExtraKeys(false, false)
        val container = (application as LauncherApplication).container
        // Behind everything, a pixel wide: only there to take the keyboard while casting.
        addContentView(keyboardProxy, ViewGroup.LayoutParams(1, 1))
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
        followDisplays()
        followUsb()
    }

    override fun onDestroy() {
        endCast()
        runCatching { displays.unregisterDisplayListener(displayListener) }
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }

    /** Keeps [castTargets] to the displays there are to cast to; a cast ends with its display. */
    private fun followDisplays() {
        castTargets.value = currentTargets()
        displays.registerDisplayListener(displayListener, null)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { castTargets.value = currentTargets() }
        override fun onDisplayRemoved(displayId: Int) { castTargets.value = currentTargets() }
        override fun onDisplayChanged(displayId: Int) { castTargets.value = currentTargets() }
    }

    private fun currentTargets(): List<CastTarget> =
        displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .map { d ->
                val mode = d.mode
                CastTarget(d.displayId, d.name, "${mode.physicalWidth}×${mode.physicalHeight}")
            }

    /** Keeps [usbDevices] to what's plugged in, and what this app may use of it. */
    private fun followUsb() {
        usbDevices.value = currentUsb()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { usbDevices.value = currentUsb() }
    }

    private fun currentUsb(): List<UsbEntry> {
        val running = sessionTag?.let { container.sessions.forWindowTag(it) }
        val granted = running?.app?.permissions?.devices.orEmpty()
        return HostDevices.list(this).filter { it.path.startsWith(UsbBridge.USB_PATH) }.map { d ->
            val state = when {
                running == null || d.path !in granted -> UsbState.NOT_SHARED
                // PRoot apps get what was opened for them at launch; chroot apps the device itself.
                d.path in running.session.usbFds || running.backend == ChrootBackend.NAME -> UsbState.IN_USE
                else -> UsbState.NEXT_LAUNCH
            }
            UsbEntry(d.path, d.label, d.detail, state)
        }
    }

    private fun startCast(displayId: Int) {
        if (cast != null) return
        val display = displays.getDisplay(displayId) ?: return
        val connection = takeDisplayConnection() ?: run {
            Toast.makeText(this, "The app's display isn't ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        val presentation = CastPresentation(this, display, connection)
        presentation.setOnDismissListener { if (cast === presentation) endCast() }
        try {
            presentation.show()
        } catch (e: Exception) {
            Log.w("X11Window", "couldn't cast to ${display.name}", e)
            runCatching { connection.close() }
            reclaimDisplayConnection()
            Toast.makeText(this, "Couldn't show the app on ${display.name}", Toast.LENGTH_SHORT).show()
            return
        }
        cast = presentation
        lorieView.setKeyboardVisible(false)
        castState.value = CastState(display.name)
    }

    /** Ends the cast, if there is one, and shows the app on the phone again. */
    private fun endCast() {
        val presentation = cast ?: return
        cast = null
        setProxyKeyboard(false)
        runCatching { presentation.dismiss() }
        reclaimDisplayConnection()
        castState.value = null
    }

    private fun setProxyKeyboard(visible: Boolean) {
        val imm = getSystemService(InputMethodManager::class.java)
        proxyKeyboardShown = visible
        if (visible) {
            keyboardProxy.requestFocus()
            imm.restartInput(keyboardProxy)
            imm.showSoftInput(keyboardProxy, 0)
        } else {
            imm.hideSoftInputFromWindow(keyboardProxy.windowToken, 0)
        }
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
        // Termux:X11 focuses its own view on resume; while casting, typing goes to the cast.
        if (cast != null) keyboardProxy.requestFocus()
        usbDevices.value = currentUsb()
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
        if (cast != null) setProxyKeyboard(text) else lorieView.setKeyboardVisible(text)
    }

    /** What the floating button can do to this window and its app. */
    private val actions = object : X11Actions {
        override fun toggleKeyboard() {
            if (cast != null) setProxyKeyboard(!proxyKeyboardShown) else toggleKeyboardVisibility()
        }

        override fun click(button: MouseButton) {
            // Relative (0, 0): click where the pointer already is, i.e. where you last touched.
            input.sendMouseEvent(0f, 0f, button.stub, true, true)
            input.sendMouseEvent(0f, 0f, button.stub, false, true)
        }

        override fun press(button: MouseButton, down: Boolean) = input.sendMouseEvent(0f, 0f, button.stub, down, true)

        override fun move(dx: Float, dy: Float) = input.sendMouseEvent(dx, dy, InputStub.BUTTON_UNDEFINED, false, true)

        override fun scroll(dx: Float, dy: Float) = input.sendMouseWheelEvent(dx, dy)

        override fun keys(vararg keyCodes: Int) {
            // Press in order, release in reverse: Ctrl+C is Ctrl down, C down, C up, Ctrl up.
            keyCodes.forEach { input.sendKeyEvent(0, it, true) }
            keyCodes.reversed().forEach { input.sendKeyEvent(0, it, false) }
        }

        override val cast: StateFlow<CastState?> get() = castState
        override val castTargets: StateFlow<List<CastTarget>> get() = this@X11Window.castTargets
        override fun startCast(displayId: Int) = this@X11Window.startCast(displayId)
        override fun stopCast() = endCast()

        override fun wirelessDisplays() {
            // Android's own cast screen connects wireless displays; once connected, they're listed.
            val tries = listOf(Intent("android.settings.CAST_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            tries.firstOrNull { runCatching { startActivity(it) }.isSuccess }
        }

        override fun windows(): List<XWindow> =
            sessionTag?.let { container.sessions.forWindowTag(it) }?.windows?.main.orEmpty()

        override fun showWindow(id: Long) {
            val session = sessionTag?.let { container.sessions.forWindowTag(it) } ?: return
            container.sessions.activateWindow(session.session.id, id)
        }

        override val usbDevices: StateFlow<List<UsbEntry>> get() = this@X11Window.usbDevices

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
            startActivity(Intent(this@X11Window, com.daryan.tessera.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        override fun brightnessBehind(area: Rect, result: (Float) -> Unit) {
            val view = lorieView
            // Only for what's on the phone, while it's in front and drawing.
            if (cast != null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            if (view.width == 0 || !view.holder.surface.isValid) return
            val at = IntArray(2).also(view::getLocationInWindow)
            val src = Rect(area).apply { offset(-at[0], -at[1]) }
            if (!src.intersect(0, 0, view.width, view.height)) return
            // A few pixels are enough: the copy is scaled down to fit.
            val sample = Bitmap.createBitmap(4, 12, Bitmap.Config.ARGB_8888)
            PixelCopy.request(view, src, sample, { status ->
                val brightness = if (status == PixelCopy.SUCCESS) luminance(sample) else null
                Log.d("X11Window", "brightness behind the handle: copy status $status, brightness $brightness")
                brightness?.let(result)
                sample.recycle()
            }, Handler(Looper.getMainLooper()))
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

/**
 * Average perceived brightness of [bitmap], 0 (black) to 1 (white); null when it holds nothing
 * to go by. A copy of a surface whose pixels carry no alpha comes back fully transparent, with
 * its colors gone, which would read as black.
 */
private fun luminance(bitmap: Bitmap): Float? {
    val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    if (pixels.all { it ushr 24 == 0 }) return null
    return pixels.map { c ->
        (0.2126f * (c shr 16 and 0xff) + 0.7152f * (c shr 8 and 0xff) + 0.0722f * (c and 0xff)) / 255f
    }.average().toFloat()
}

enum class MouseButton(val stub: Int) {
    LEFT(InputStub.BUTTON_LEFT),
    RIGHT(InputStub.BUTTON_RIGHT),
    MIDDLE(InputStub.BUTTON_MIDDLE),
}

/** A cast in progress, to the display named [display]. */
data class CastState(val display: String)

enum class UsbState(val label: String) {
    IN_USE("Shared with this app"),
    NEXT_LAUNCH("Shared from the app's next start"),
    NOT_SHARED("Not shared with this app"),
}

/** A plugged-in USB device, as the window's side menu lists it. */
data class UsbEntry(val path: String, val label: String, val detail: String, val state: UsbState)

/** Termux:X11's touchMode preference values. */
enum class TouchMode(val pref: String, val label: String) {
    DIRECT("3", "Direct touch"),
    TOUCHPAD("1", "Touchpad"),
    SIMULATED("2", "Touchscreen mouse"),
}

interface X11Actions {
    fun toggleKeyboard()
    fun click(button: MouseButton)
    /** Holds [button] down, or lets it go: dragging from the trackpad. */
    fun press(button: MouseButton, down: Boolean)
    /** Moves the pointer by ([dx], [dy]) X screen pixels. */
    fun move(dx: Float, dy: Float)
    fun scroll(dx: Float, dy: Float)
    fun keys(vararg keyCodes: Int)
    /** The cast in progress, or null; while there is one, the phone is a trackpad. */
    val cast: StateFlow<CastState?>
    /** Displays there are to cast to, as they come and go. */
    val castTargets: StateFlow<List<CastTarget>>
    fun startCast(displayId: Int)
    fun stopCast()
    /** Opens Android's screen for connecting a wireless display. */
    fun wirelessDisplays()
    /** The app's own windows, to pick which one the cast shows. */
    fun windows(): List<XWindow>
    fun showWindow(id: Long)
    val usbDevices: StateFlow<List<UsbEntry>>
    fun setTouchMode(mode: TouchMode)
    fun touchMode(): TouchMode
    fun openPermissions(appId: String)
    fun displaySettings()
    fun close(sessionId: String?)
    /**
     * How bright the app's picture is in [area] (window pixels), 0 to 1, given to [result] when
     * it's known; nothing comes back while the picture isn't on the phone.
     */
    fun brightnessBehind(area: Rect, result: (Float) -> Unit)

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
