// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import com.termux.x11.LorieApp
import kotlin.math.roundToInt
import com.daryan.tessera.container.ChrootBackend
import com.daryan.tessera.container.FakeProc
import com.daryan.tessera.container.HostProbe
import com.daryan.tessera.container.OciRegistry
import com.daryan.tessera.container.ProcessTree
import com.daryan.tessera.container.ProotBackend
import com.daryan.tessera.container.RootfsInstaller
import com.daryan.tessera.container.EndReason
import com.daryan.tessera.container.RunningSession
import com.daryan.tessera.container.SessionManager
import com.daryan.tessera.container.X11Server
import com.daryan.tessera.store.InstallQueue
import com.daryan.tessera.store.StoreCatalog
import com.daryan.tessera.store.StoreRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Extends Termux:X11's application class, which its windows and receivers expect. */
class LauncherApplication : LorieApp() {
    lateinit var container: LauncherContainer
        private set

    override fun onCreate() {
        applyX11Defaults()
        super.onCreate()
        container = LauncherContainer(this)
        registerActivityLifecycleCallbacks(SessionWindows())
    }

    /** Dark mode or the wallpaper's colors changed: running apps follow. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::container.isInitialized) container.sessions.refreshThemes()
    }

    /**
     * Ties each session's own window (Termux:X11 or terminal) to the session: labels it for
     * Recents, and stops the session when its card is swiped away there.
     */
    private inner class SessionWindows : ActivityLifecycleCallbacks {
        private fun sessionOf(activity: Activity): RunningSession? {
            val data = activity.intent?.data ?: return null
            return when (activity.javaClass.name) {
                X11Server.TAGGED_WINDOW -> container.sessions.forWindowTag(data.host ?: data.toString())
                TerminalActivity::class.java.name -> container.sessions.byId(data.host)
                else -> null
            }
        }

        override fun onActivityResumed(activity: Activity) {
            // Terminal windows brand themselves once their session is known.
            if (activity.javaClass.name != X11Server.TAGGED_WINDOW) return
            // On every resume: Recents takes the icon from what's set while the window is in
            // front; set only during startup, MIUI keeps the launcher's own icon instead.
            val session = sessionOf(activity) ?: return
            val windowId = X11Server.windowId(activity.intent?.data)
            val title = windowId?.let { id -> session.windows.windows.firstOrNull { it.id == id }?.title?.takeIf(String::isNotBlank) }
            brandForRecents(activity, session, container, title)
        }

        override fun onActivityDestroyed(activity: Activity) {
            // Finishing without a config change = the window was closed for good. Back only
            // moves our windows to the background, and when an app exits by itself we close
            // its window after the session has already ended, so a running session here means
            // the user closed its card in Recents.
            if (!activity.isFinishing || activity.isChangingConfigurations) return
            val session = sessionOf(activity)?.takeIf { it.running } ?: return
            // A window of its own closed in Recents: close that X window, not the whole app.
            X11Server.windowId(activity.intent?.data)?.let { return container.sessions.closeWindow(session.session.id, it) }
            container.sessions.stop(session.session.id, EndReason.CLOSED_FROM_RECENTS)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    /**
     * Termux:X11 defaults suit a desktop-in-a-phone; ours open one app per window. Applied once,
     * before LorieApp reads them, so changes made in the X11 window's settings stick.
     */
    private fun applyX11Defaults() {
        val prefs = getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)
        val applied = prefs.getInt(X11_DEFAULTS_VERSION, if (prefs.getBoolean(X11_DEFAULTS_APPLIED, false)) 1 else 0)
        val edit = prefs.edit()
        if (applied < 1) {
            // Aim for roughly desktop-sized UI: 3.5x density -> 250%, i.e. 1440x3000 shown as 576x1200.
            val scale = ((resources.displayMetrics.density / 1.4f * 10).roundToInt() * 10).coerceIn(100, 300)
            edit.putString("displayResolutionMode", "scaled")
                .putInt("displayScale", scale)
                .putString("touchMode", "3") // direct touch: tap where you want to click
        }
        if (applied < 2) {
            // Our swipe-in panel replaces Termux:X11's extra-keys bar.
            edit.putBoolean("showAdditionalKbd", false)
        }
        edit.putInt(X11_DEFAULTS_VERSION, CURRENT_X11_DEFAULTS).apply()
    }

    private companion object {
        const val X11_DEFAULTS_APPLIED = "tessera_x11_defaults" // version 1, before versioning
        const val X11_DEFAULTS_VERSION = "tessera_x11_defaults_version"
        const val CURRENT_X11_DEFAULTS = 2
    }
}

/**
 * Process-wide state: sessions and installs outlive any one screen, and [ContainerService]
 * keeps the process (and its network access) alive while [busy].
 */
class LauncherContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val paths = HostProbe.pathsFor(app)
    private val prefs = app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    /**
     * What Tessera knows about itself, for the user and for whoever helps them: bound into the
     * Claude Code shortcut, and copied out of Setup for any other assistant.
     */
    val diagnostics = com.daryan.tessera.container.Diagnostics(java.io.File(app.filesDir, "diagnostics")) {
        prefs.getBoolean(KEY_DIAGNOSTICS, true)
    }
    /** How Linux apps look: themed with the phone's colors, and which apps are left out. */
    val appearance = com.daryan.tessera.container.AppearanceSettings(java.io.File(app.filesDir, "appearance.json"))
    /** Linux themes the user imported (Settings, Appearance). */
    val linuxThemes = com.daryan.tessera.container.LinuxThemes(java.io.File(app.filesDir, "themes"))
    val sessions = SessionManager(app, scope, paths, diagnostics, appearance, linuxThemes)
    val catalog = StoreCatalog(java.io.File(app.filesDir, "store"), scope)
    /** Flathub's catalog, for the Store tab's Flathub side (once Flathub is set up). */
    val flathub = com.daryan.tessera.store.FlathubCatalog(java.io.File(app.filesDir, "store"), scope)
    val screenshots = com.daryan.tessera.store.Screenshots(java.io.File(app.cacheDir, "screenshots"))
    val registry = StoreRegistry(java.io.File(app.filesDir, "apps"), catalog)
    val installer = RootfsInstaller(paths, OciRegistry(), scope, Process.myUid(), Process.myUid())

    val queue = InstallQueue(scope, paths)
    /** Commands the user put on the launcher as icons (the Shortcuts tab). */
    val shortcuts = ShortcutStore(java.io.File(app.filesDir, "shortcuts.json"))
    /** Apps installed in the container outside the Store (apt in a terminal, a .deb). */
    val systemApps = com.daryan.tessera.store.SystemApps()
    /** Permissions the user changed in an app's sheet; applied over its manifest. */
    val permissions = com.daryan.tessera.container.PermissionOverrides(java.io.File(app.filesDir, "permissions.json"))
    /** Commands the user changed from an app's long-press menu; applied over its manifest. */
    val exec = com.daryan.tessera.container.ExecOverrides(java.io.File(app.filesDir, "exec.json"))
    /** Whether this run already looked for GUI helper packages missing from the container. */
    /** The modes (each has its own system) already checked for missing GUI helpers this run. */
    val guiHelpersChecked: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Whether this run already ended what earlier runs left running in chroot mode. */
    @Volatile var chrootSwept = false

    /** The launcher's apps as it last listed them, for a desktop's Start menu (in its own window). */
    val launcherApps = kotlinx.coroutines.flow.MutableStateFlow<List<com.daryan.tessera.container.PermissionManifest>>(emptyList())

    /** An app whose sheet the launcher should open (set from an X11 window's Permissions tile). */
    val showAppRequests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** An app the launcher should start (set from its icon on the phone's home screen). */
    val launchRequests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val busy: StateFlow<Boolean> = combine(installer.state, sessions.sessions, queue.jobs) { install, list, jobs ->
        install.running || list.any { it.exitCode == null } || jobs.any { it.active }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch(Dispatchers.IO) {
            // Whatever a previous run of the app left behind can't be in use any more.
            ProcessTree.killLeftovers(Process.myPid(), Process.myUid())
            HostProbe.installChrootScript(app, paths)
            // Refreshed every start so btime and the CPU count stay current.
            FakeProc.write(paths.fakeProcDir)
            runCatching { FakeProc.clearSveShaders(paths.prootRootfs) }
            // So the notes beside the logs are always this version's.
            diagnostics.install(app)
        }
        scope.launch {
            busy.collect { if (it) ContainerService.start(app) }
        }
    }

    fun proot() = ProotBackend(paths, FakeProc.NAMES, appId = Process.myUid() to Process.myUid())
    fun chroot(su: String) = ChrootBackend(paths, su)

    companion object {
        const val PREFS = "launcher"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val KEY_INTRO_SEEN = "intro_seen"
    }
}
