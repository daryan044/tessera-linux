// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toBitmap
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.DesktopItem
import com.daryan.tessera.container.DesktopEvent
import android.net.Uri
import android.provider.OpenableColumns
import com.daryan.tessera.container.RunningSession
import com.daryan.tessera.ui.DesktopActions
import com.daryan.tessera.ui.DesktopShell
import com.daryan.tessera.ui.DesktopShortcut
import com.daryan.tessera.container.LinuxNotifier
import com.daryan.tessera.ui.GameControlsPrefs
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
    /** A desktop's title bars waiting to be redrawn for a new scale; see setScale. */
    private var rescale: kotlinx.coroutines.Job? = null
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
    /** The app this window shows, for its own settings such as [X11Scale]. */
    private val appId get() = sessionTag?.let { container.sessions.forWindowTag(it) }?.app?.id

    /**
     * Sizes the app's X screen at its own scale, if it has one. Its windows share that screen,
     * so each applies the app's scale when it comes to the front.
     */
    private fun applyScale() {
        // At full resolution the app enlarges itself (it started that way); the screen is ours.
        val session = sessionTag?.let { container.sessions.forWindowTag(it) }
        if (session?.session?.uiScale != null) return lorieView.setScaleOverride(100)
        lorieView.setScaleOverride(appId?.let { X11Scale.forApp(this, it) } ?: 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Our floating button replaces Termux:X11's extra-keys bar.
        toggleExtraKeys(false, false)
        val container = (application as LauncherApplication).container
        // Behind everything, a pixel wide: only there to take the keyboard while casting.
        addContentView(keyboardProxy, ViewGroup.LayoutParams(1, 1))
        addContentView(
            ComposeView(this).also { shell = it }.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    LauncherTheme {
                        val session = intent?.data?.let { container.sessions.forWindowTag(it.host ?: it.toString()) }
                        Box(Modifier.fillMaxSize()) {
                            // A desktop has its own taskbar under the window panel.
                            if (session?.app?.desktop == true) DesktopShell(desktopActions)
                            X11Overlay(session = session, actions = actions)
                        }
                    }
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        applyScale()
        followSession()
        followFocus()
        followDisplays()
        followUsb()
        followDesktopIcons()
        desktopFullscreen()
        takeNotificationAction(intent)
        customWallpaper.value = wallpaperFile.isFile
    }

    /** A desktop's icon taps and menus, as its helper hears them on the desktop window. */
    private fun followDesktopIcons() {
        if (!isDesktop) return
        val actions = desktopActions
        lifecycleScope.launch {
            container.sessions.desktopEvents.collect { (sessionId, event) ->
                if (sessionId == actions.session.value?.session?.id) actions.onIconEvent(event)
            }
        }
    }

    private val isDesktop get() = sessionTag?.let { container.sessions.forWindowTag(it) }?.app?.desktop == true

    /**
     * A desktop takes the whole screen, under the camera cutout too, whatever the display
     * setting says: its dock has the clock and battery, and a swipe from an edge brings the
     * phone's bars back for a moment. Termux:X11 applies its own setting on resume and on every
     * focus change, so this follows it each time.
     */
    private fun desktopFullscreen() {
        if (!isDesktop) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        findViewById<android.widget.FrameLayout>(android.R.id.content).getChildAt(0)?.let { content ->
            if (content.fitsSystemWindows) {
                content.fitsSystemWindows = false
                ViewCompat.requestApplyInsets(content)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val cutout = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (window.attributes.layoutInDisplayCutoutMode != cutout) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = cutout }
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) desktopFullscreen()
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

    /**
     * Split screen and floating windows keep several windows resumed, so turning to another of
     * them resumes nothing; it still has to show its X window, as [onResume] does.
     */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) showOwnXWindow()
    }

    /** In front now: show our X window on the shared screen, at the app's scale. */
    private fun showOwnXWindow() {
        val session = sessionTag?.let { container.sessions.forWindowTag(it) } ?: return
        container.sessions.windowFor(session.session.id, windowId)?.let { container.sessions.activateWindow(session.session.id, it) }
        applyScale()
    }

    override fun onResume() {
        super.onResume()
        sessionTag?.let { container.sessions.forWindowTag(it) } ?: return
        showOwnXWindow()
        // Termux:X11 focuses its own view on resume; while casting, typing goes to the cast.
        if (cast != null) keyboardProxy.requestFocus()
        usbDevices.value = currentUsb()
        desktopFullscreen()
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

    /** What a desktop session's taskbar and Start menu do, through its session. */
    private val desktopActions by lazy {
        object : DesktopActions {
            override val session: StateFlow<RunningSession?> = container.sessions.sessions
                .map { list -> list.firstOrNull { X11Server.windowTag(it.session) == sessionTag } }
                .stateIn(lifecycleScope, SharingStarted.Eagerly, sessionTag?.let { container.sessions.forWindowTag(it) })
            override val apps: StateFlow<List<PermissionManifest>> get() = container.launcherApps
            private val id get() = session.value?.session?.id
            private val desktop get() = id?.let { container.sessions.desktop(it) }
            private val prefs = getSharedPreferences(DESKTOP_PREFS, MODE_PRIVATE)
            private fun list(key: String) = prefs.getString(key, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
            private fun keep(key: String, ids: List<String>) = prefs.edit().putString(key, ids.joinToString("\n")).apply()

            // A new desktop's dock starts empty: apps join it as they open, and stay when pinned.
            override val pins = MutableStateFlow(list(KEY_PINS))

            override fun setPinned(appId: String, pinned: Boolean) {
                pins.value = if (pinned) (pins.value - appId) + appId else pins.value - appId
                keep(KEY_PINS, pins.value)
            }

            override val recent = MutableStateFlow(list(KEY_RECENT))
            override val lastOpen: List<String> = list(KEY_LAST_OPEN)

            override fun rememberOpen(appIds: List<String>) {
                if (appIds.isNotEmpty()) keep(KEY_LAST_OPEN, appIds)
            }

            /** Command-line apps get a terminal of their own in the desktop. */
            private fun argv(app: PermissionManifest) = if (app.gui) app.exec else listOf("x-terminal-emulator", "-e") + app.exec

            override fun launch(app: PermissionManifest): Boolean = when {
                app.flatpak != null -> id?.let { container.sessions.launchFlatpakInDesktop(it, app) } ?: false
                else -> desktop?.launch(argv(app)) ?: false
            }.also { ok ->
                if (ok) {
                    recent.value = (listOf(app.id) + (recent.value - app.id)).take(RECENT_COUNT)
                    keep(KEY_RECENT, recent.value)
                }
            }

            override fun openTerminal(): Boolean = desktop?.launch(listOf("x-terminal-emulator")) ?: false

            // The file manager the desktop has, at home; xdg-open finds it.
            // Thunar, the desktop's file manager; whatever opens folders where it isn't installed.
            override fun openFiles(): Boolean = desktop?.launch(
                listOf("sh", "-c", "command -v thunar >/dev/null && exec thunar \"\$HOME\"; exec xdg-open \"\$HOME\""),
            ) ?: false

            override fun makeFolder(): Boolean = desktop?.makeFolder("New folder") ?: false

            private fun items(): List<DesktopItem> = session.value?.desktopItems.orEmpty()

            override fun open(item: DesktopItem) { desktop?.open(item.path) }

            // A Flathub app's shortcut opens through Tessera (by its id), which gives it its root;
            // run from inside Linux, it says so.
            override fun addToDesktop(app: PermissionManifest): Boolean = desktop?.addShortcut(
                app.id, app.name,
                if (app.flatpak != null) listOf("echo", "${app.name} opens from Tessera's desktop") else argv(app),
            ) ?: false

            override fun removeFromDesktop(item: DesktopItem) { desktop?.remove(item.path) }

            override suspend fun importDropped(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
                val desktop = desktop ?: return@withContext 0
                uris.count { uri ->
                    runCatching {
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        } ?: uri.lastPathSegment ?: "Dropped file"
                        contentResolver.openInputStream(uri)!!.use { desktop.import(name, it) }
                    }.getOrDefault(false)
                }
            }

            override fun activate(windowId: Long) { id?.let { container.sessions.activateWindow(it, windowId) } }
            override fun minimize(windowId: Long) { id?.let { container.sessions.minimizeWindow(it, windowId) } }
            override fun toggleMaximize(windowId: Long) { id?.let { container.sessions.toggleMaximizeWindow(it, windowId) } }
            override fun snap(windowId: Long, left: Boolean) { id?.let { container.sessions.snapWindow(it, windowId, left) } }
            override fun close(windowId: Long) { id?.let { container.sessions.closeWindow(it, windowId) } }
            override fun showWorkspace(n: Int) { id?.let { container.sessions.showWorkspace(it, n) } }
            override fun moveToWorkspace(windowId: Long, n: Int) { id?.let { container.sessions.moveToWorkspace(it, windowId, n) } }
            override fun reserve(left: Boolean, px: Int): Boolean = desktop?.reserve(left, px) ?: false

            override val customWallpaper: StateFlow<Boolean> get() = this@X11Window.customWallpaper
            override val wallpaperVersion: StateFlow<Int> get() = this@X11Window.wallpaperVersion

            override fun pickWallpaper() {
                runCatching { pickWallpaper.launch("image/*") }
            }

            override fun usePhoneWallpaper() {
                wallpaperFile.delete()
                wallpaperChanged()
            }

            /** The background as last read (the user's picture or the phone's wallpaper), and why. */
            private var background: Pair<String, Bitmap?>? = null
            /** The background as the desktop's helper has it, for the icons to be laid over; and for which screen. */
            private var sent: Pair<String, Bitmap>? = null

            override suspend fun wallpaper(
                width: Int,
                height: Int,
                top: Int,
                bottom: Int,
                icons: Bitmap?,
                places: Map<String, Rect>,
                viewWidth: Int,
                viewHeight: Int,
            ): Boolean = withContext(Dispatchers.IO) {
                val desktop = desktop ?: return@withContext false
                // The user's own picture, else the phone's wallpaper (reading it takes the storage
                // permission), else a gradient in the theme's colors.
                val key = "${id}:${wallpaperVersion.value}:${width}x$height:$top:$bottom"
                val picture = background?.takeIf { it.first == key }?.second ?: run {
                    val read = loadWallpaper(width, height) ?: runCatching {
                        @Suppress("MissingPermission")
                        WallpaperManager.getInstance(this@X11Window).drawable?.toBitmap()
                    }.getOrNull()
                    background = key to read
                    read
                }
                // The whole screen only when the background changed; the icons are a strip of it.
                val paper = sent?.takeIf { it.first == key }?.second
                    ?: desktop.paper(picture ?: gradientWallpaper(top, bottom), width, height)?.also { fresh ->
                        sent?.second?.recycle()
                        sent = key to fresh
                    }
                    ?: return@withContext false
                // A picture of the view may come as a hardware bitmap, which a canvas can't draw.
                val overlay = icons?.copy(Bitmap.Config.ARGB_8888, false)
                val sx = width.toFloat() / viewWidth.coerceAtLeast(1)
                val sy = height.toFloat() / viewHeight.coerceAtLeast(1)
                val spots = places.mapValues { (_, r) ->
                    Rect((r.left * sx).toInt(), (r.top * sy).toInt(), (r.right * sx).toInt(), (r.bottom * sy).toInt())
                }
                // Where the icons are, and a little more for their labels' shadows.
                val area = spots.values.fold(null as Rect?) { acc, r -> acc?.apply { union(r) } ?: Rect(r) }
                    ?.apply { inset(-(8 * sx).toInt(), -(8 * sy).toInt()) }
                desktop.icons(paper, overlay, area, spots).also { overlay?.recycle() }
            }

            override val iconMenu = MutableStateFlow<DesktopEvent?>(null)

            override fun dismissIconMenu() { iconMenu.value = null }

            /**
             * A tap on a desktop icon (opened here, so Flathub apps get their root), a menu
             * request, or a tap the X screen's own pointer made in the dock's room: the dock
             * gets it as a touch where it was.
             */
            fun onIconEvent(event: DesktopEvent) {
                if (event.dock) return tapDock(event.x, event.y)
                if (event.menu) {
                    iconMenu.value = event
                    return
                }
                val item = items().firstOrNull { it.path == event.path } ?: return
                val app = container.launcherApps.value.firstOrNull { it.id == item.app }.takeIf { item.isShortcut }
                if (app != null) launch(app) else open(item)
            }

            private fun tapDock(x: Int, y: Int) {
                val view = shell ?: return
                val screen = session.value?.windows?.screen?.takeIf { it.size == 2 && it[0] > 0 } ?: return
                val ratio = view.width.toFloat() / screen[0]
                val at = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val e = MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, x * ratio, y * ratio, 0)
                    e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    view.dispatchTouchEvent(e)
                    e.recycle()
                }
            }

            override val shortcuts = kotlinx.coroutines.flow.MutableSharedFlow<DesktopShortcut>(extraBufferCapacity = 4)

            override fun toggleKeyboard() = actions.toggleKeyboard()
            override fun keepFullscreen() = desktopFullscreen()
            override fun returnFocus() {
                if (cast == null) lorieView.requestFocus()
            }
            override fun scale(): Int = actions.scale()
            override fun setScale(percent: Int?) = actions.setScale(percent)
            override fun openSettings() { appId?.let(actions::openPermissions) }
            override fun end() = actions.close(id)
        }
    }

    /** The user's own desktop picture, if they chose one ([DesktopActions.pickWallpaper]). */
    private val wallpaperFile get() = java.io.File(filesDir, "desktop-wallpaper")
    private val customWallpaper = MutableStateFlow(false)
    /** Bumped when the background changes; the desktop's shell sends it again. */
    private val wallpaperVersion = MutableStateFlow(0)

    private fun wallpaperChanged() {
        customWallpaper.value = wallpaperFile.isFile
        wallpaperVersion.value++
    }

    private val pickWallpaper = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = java.io.File(filesDir, "desktop-wallpaper.tmp")
                    contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    // Only a picture Android can read takes the old one's place.
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(tmp.path, bounds)
                    bounds.outWidth > 0 && tmp.renameTo(wallpaperFile)
                }.getOrDefault(false)
            }
            if (ok) wallpaperChanged()
            else Toast.makeText(this@X11Window, "Couldn't use that picture", Toast.LENGTH_SHORT).show()
        }
    }

    /** The user's picture, read at about the size it's shown at; null when there's none. */
    private fun loadWallpaper(width: Int, height: Int): Bitmap? {
        val file = wallpaperFile.takeIf { it.isFile } ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        return runCatching {
            android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    /** The desktop's shell over the X view, for taps from the X screen's pointer to reach its dock. */
    private var shell: android.view.View? = null

    /**
     * A hardware keyboard's Super key, as on a desktop: alone, the Start menu; with the arrows,
     * snapping (left, right), maximizing (up) and minimizing (down) the focused window; with D,
     * the desktop; with Tab, every window. It stays out of Linux's way otherwise.
     */
    private var superAlone = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isDesktop && desktopKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun desktopKey(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_META_LEFT || e.keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) superAlone = true
            else if (e.action == KeyEvent.ACTION_UP && superAlone) {
                superAlone = false
                desktopActions.shortcuts.tryEmit(DesktopShortcut.START)
            }
            return true
        }
        if (!e.isMetaPressed) return false
        superAlone = false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val windows = desktopActions.session.value?.windows ?: return true
        val active = windows.active.takeIf { a -> windows.main.any { it.id == a } }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> active?.let { desktopActions.snap(it, left = true) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> active?.let { desktopActions.snap(it, left = false) }
            KeyEvent.KEYCODE_DPAD_UP -> active?.let(desktopActions::toggleMaximize)
            KeyEvent.KEYCODE_DPAD_DOWN -> active?.let(desktopActions::minimize)
            KeyEvent.KEYCODE_D -> desktopActions.shortcuts.tryEmit(DesktopShortcut.SHOW_DESKTOP)
            KeyEvent.KEYCODE_TAB -> desktopActions.shortcuts.tryEmit(DesktopShortcut.OVERVIEW)
            else -> return false
        }
        return true
    }

    /** A tap on a Linux notification, or one of its buttons: the app hears which. */
    private fun takeNotificationAction(intent: Intent?) {
        val action = intent?.getStringExtra(LinuxNotifier.ACTION_EXTRA) ?: return
        val id = intent.getIntExtra(LinuxNotifier.ID_EXTRA, -1)
        intent.removeExtra(LinuxNotifier.ACTION_EXTRA)
        val session = sessionTag?.let { container.sessions.forWindowTag(it) } ?: return
        if (id >= 0) container.sessions.notificationAction(session.session.id, id, action)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeNotificationAction(intent)
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

        override fun key(keyCode: Int, down: Boolean) {
            input.sendKeyEvent(0, keyCode, down)
        }

        override fun gameControls(): Boolean = appId?.let { GameControlsPrefs.on(this@X11Window, it) } ?: false

        override fun setGameControls(on: Boolean) {
            appId?.let { GameControlsPrefs.set(this@X11Window, it, on) }
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

        override fun setTouchMode(mode: TouchMode) = X11Input.setMode(this@X11Window, mode)

        override fun touchMode(): TouchMode = X11Input.mode(this@X11Window)

        override fun setLongPressRightClick(on: Boolean) = X11Input.setLongPressRightClick(this@X11Window, on)

        override fun longPressRightClick(): Boolean = X11Input.longPressRightClick(this@X11Window)

        override fun scale(): Int = appId?.let { X11Scale.forApp(this@X11Window, it) } ?: X11Scale.default(this@X11Window)

        override fun defaultScale(): Int = X11Scale.default(this@X11Window)

        override fun setScale(percent: Int?) {
            val id = appId ?: return
            X11Scale.set(this@X11Window, id, percent)
            applyScale()
            // A desktop's title bars are drawn in X pixels: redrawn for the new scale once the
            // slider rests, they keep their size on the phone.
            val session = sessionTag?.let { container.sessions.forWindowTag(it) }
            if (session?.app?.desktop == true) {
                rescale?.cancel()
                rescale = lifecycleScope.launch {
                    kotlinx.coroutines.delay(400)
                    container.sessions.rescaleDesktop(session.session.id)
                }
            }
        }

        override fun fullResolution(): Boolean = appId?.let { X11Scale.fullResolution(this@X11Window, it) } ?: false

        override fun setFullResolution(on: Boolean) {
            X11Scale.setFullResolution(this@X11Window, appId ?: return, on)
        }

        override fun runningUiScale(): Int? = sessionTag?.let { container.sessions.forWindowTag(it) }?.session?.uiScale

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
private const val DESKTOP_PREFS = "desktop"
private const val KEY_PINS = "dock_pins"
private const val KEY_RECENT = "recent_apps"
private const val KEY_LAST_OPEN = "last_open"
private const val RECENT_COUNT = 6

/** A soft gradient for a desktop's background, where the phone's wallpaper can't be read. */
private fun gradientWallpaper(top: Int, bottom: Int): Bitmap =
    Bitmap.createBitmap(270, 540, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 540f, top, bottom, Shader.TileMode.CLAMP) })
    }

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
enum class TouchMode(val pref: String, val label: String, val detail: String) {
    DIRECT("3", "Direct touch", "Like a phone app: tap, swipe to scroll, pinch to zoom."),
    TOUCHPAD("1", "Touchpad", "The screen is a touchpad: move a pointer, tap to click, two fingers to scroll."),
    SIMULATED("2", "Touchscreen mouse", "Taps click where you touch, drag to drag. For apps without touch support."),
}

/**
 * How fingers drive every Linux window: Termux:X11's settings, which each window reads (and
 * follows live). Set from the launcher's side menu or a window's own panel.
 */
object X11Input {
    private fun prefs(c: android.content.Context) =
        c.getSharedPreferences("${c.packageName}_preferences", android.content.Context.MODE_PRIVATE)

    fun mode(c: android.content.Context): TouchMode {
        val v = prefs(c).getString("touchMode", TouchMode.DIRECT.pref)
        return TouchMode.entries.firstOrNull { it.pref == v } ?: TouchMode.DIRECT
    }

    fun setMode(c: android.content.Context, mode: TouchMode) = prefs(c).edit().putString("touchMode", mode.pref).apply()

    /** Touch and hold right-clicks in the pointer modes (Termux:X11's longPressRightClick). */
    fun longPressRightClick(c: android.content.Context) = prefs(c).getBoolean("longPressRightClick", true)

    fun setLongPressRightClick(c: android.content.Context, on: Boolean) =
        prefs(c).edit().putBoolean("longPressRightClick", on).apply()
}

/**
 * How big each app draws: the X screen is the window's size divided by a scale, in percent (250
 * makes a 1440-pixel-wide window a 576-pixel screen, so everything is 2.5 times as big). Each
 * app keeps its own, set from its window's panel; without one it follows Termux:X11's display
 * setting, which the launcher sets from the phone's density.
 */
object X11Scale {
    const val MIN = 100
    const val MAX = 400
    private const val PREFS = "x11_scale"

    /** The display setting's scale, which apps without their own use. */
    fun default(c: android.content.Context): Int =
        c.getSharedPreferences("${c.packageName}_preferences", android.content.Context.MODE_PRIVATE).getInt("displayScale", 100)

    /** [appId]'s own scale, or null when it follows the default. */
    fun forApp(c: android.content.Context, appId: String): Int? =
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt(appId, 0).takeIf { it > 0 }

    /** Sets [appId]'s scale; null goes back to the default. */
    fun set(c: android.content.Context, appId: String, percent: Int?) =
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().apply {
            if (percent == null) remove(appId) else putInt(appId, percent.coerceIn(MIN, MAX))
        }.apply()

    /**
     * Whether [appId] draws at the window's full resolution, its interface enlarged by its scale
     * ([com.daryan.tessera.container.Session.uiScale]), rather than on a smaller screen that is
     * stretched. Sharper, but the app must start again for it, and not every app enlarges all
     * of itself.
     */
    fun fullResolution(c: android.content.Context, appId: String): Boolean =
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(FULL + appId, false)

    fun setFullResolution(c: android.content.Context, appId: String, on: Boolean) =
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(FULL + appId, on).apply()

    /** The interface scale a session of [appId] starts with: its scale at full resolution, else null. */
    fun uiScaleFor(c: android.content.Context, appId: String): Int? =
        if (fullResolution(c, appId)) forApp(c, appId) ?: default(c) else null

    /** Key prefix of the full-resolution switches, apart from the apps' scales (ints). */
    private const val FULL = "full/"
}

/**
 * What an X11 window's side menu and the trackpad can ask of the session. The pointer and
 * keyboard half is [InputSink], shared with the sinks that drive a PC over USB or Bluetooth, so
 * the trackpad works the same against any of them.
 */
interface X11Actions : com.daryan.tessera.input.InputSink {
    fun toggleKeyboard()
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
    fun setLongPressRightClick(on: Boolean)
    fun longPressRightClick(): Boolean
    /** Whether the app shows [com.daryan.tessera.ui.GameControls]; kept per app. */
    fun gameControls(): Boolean
    fun setGameControls(on: Boolean)
    /** The app's scale in percent ([X11Scale]), and the one it would have without its own. */
    fun scale(): Int
    fun defaultScale(): Int
    /** Resizes the app's screen to [percent] at once, and keeps it for the app; null resets it. */
    fun setScale(percent: Int?)
    /** The app's full-resolution switch ([X11Scale.fullResolution]), from its next start. */
    fun fullResolution(): Boolean
    fun setFullResolution(on: Boolean)
    /** The interface scale the running app started with at full resolution, or null when stretched. */
    fun runningUiScale(): Int?
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
            "Enter" to intArrayOf(KeyEvent.KEYCODE_ENTER),
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
