// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import com.termux.x11.LorieApp
import kotlin.math.roundToInt
import com.daryan.linuxlauncher.container.ChrootBackend
import com.daryan.linuxlauncher.container.FakeProc
import com.daryan.linuxlauncher.container.HostProbe
import com.daryan.linuxlauncher.container.OciRegistry
import com.daryan.linuxlauncher.container.ProotBackend
import com.daryan.linuxlauncher.container.RootfsInstaller
import com.daryan.linuxlauncher.container.EndReason
import com.daryan.linuxlauncher.container.RunningSession
import com.daryan.linuxlauncher.container.SessionManager
import com.daryan.linuxlauncher.container.X11Server
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
            sessionOf(activity)?.let { brandForRecents(activity, it, container) }
        }

        override fun onActivityDestroyed(activity: Activity) {
            // Finishing without a config change = the window was closed for good. Back only
            // moves our windows to the background, and when an app exits by itself we close
            // its window after the session has already ended, so a running session here means
            // the user closed its card in Recents.
            if (!activity.isFinishing || activity.isChangingConfigurations) return
            val session = sessionOf(activity)?.takeIf { it.running } ?: return
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
        if (prefs.getBoolean(X11_DEFAULTS_APPLIED, false)) return
        // Aim for roughly desktop-sized UI: 3.5x density -> 250%, i.e. 1440x3000 shown as 576x1200.
        val scale = ((resources.displayMetrics.density / 1.4f * 10).roundToInt() * 10).coerceIn(100, 300)
        prefs.edit()
            .putString("displayResolutionMode", "scaled")
            .putInt("displayScale", scale)
            .putString("touchMode", "3") // direct touch: tap where you want to click
            .putBoolean(X11_DEFAULTS_APPLIED, true)
            .apply()
    }

    private companion object {
        const val X11_DEFAULTS_APPLIED = "linuxlauncher_x11_defaults"
    }
}

/**
 * Process-wide state: sessions and installs outlive any one screen, and [ContainerService]
 * keeps the process (and its network access) alive while [busy].
 */
class LauncherContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val paths = HostProbe.pathsFor(app)
    val sessions = SessionManager(app, scope, paths)
    val installer = RootfsInstaller(paths, OciRegistry(), scope, Process.myUid(), Process.myUid())

    val busy: StateFlow<Boolean> = combine(installer.state, sessions.sessions) { install, list ->
        install.running || list.any { it.exitCode == null }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch(Dispatchers.IO) {
            HostProbe.installChrootScript(app, paths)
            // Refreshed every start so btime and the CPU count stay current.
            FakeProc.write(paths.fakeProcDir)
        }
        scope.launch {
            busy.collect { if (it) ContainerService.start(app) }
        }
    }

    fun proot() = ProotBackend(paths, FakeProc.NAMES, appId = Process.myUid() to Process.myUid())
    fun chroot(su: String) = ChrootBackend(paths, su)
}
