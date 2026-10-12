// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher

import android.app.Application
import android.os.Process
import com.termux.x11.LorieApp
import com.daryan.linuxlauncher.container.ChrootBackend
import com.daryan.linuxlauncher.container.FakeProc
import com.daryan.linuxlauncher.container.HostProbe
import com.daryan.linuxlauncher.container.OciRegistry
import com.daryan.linuxlauncher.container.ProotBackend
import com.daryan.linuxlauncher.container.RootfsInstaller
import com.daryan.linuxlauncher.container.SessionManager
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
        super.onCreate()
        container = LauncherContainer(this)
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
