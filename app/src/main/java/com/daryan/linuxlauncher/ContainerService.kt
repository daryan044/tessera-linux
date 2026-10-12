// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.daryan.linuxlauncher.container.InstallState
import com.daryan.linuxlauncher.store.JobState
import com.daryan.linuxlauncher.store.QueueAction
import com.daryan.linuxlauncher.store.QueueJob
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service held while anything Linux is running. Without it Android cuts the app's
 * network as soon as it leaves the screen, and may kill the process outright.
 */
class ContainerService : Service() {
    private val scope = MainScope()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Running Linux", NotificationManager.IMPORTANCE_LOW),
            )
        }
        startForeground(NOTIFICATION_ID, notification("Starting"))
        // apt and builds keep running with the screen off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linuxlauncher:container")
            .apply { acquire() }

        val container = (application as LauncherApplication).container
        scope.launch {
            combine(container.installer.state, container.sessions.sessions, container.queue.jobs) { install, sessions, jobs ->
                Triple(install, sessions.count { it.exitCode == null }, jobs)
            }.collect { (install, running, jobs) ->
                if (!install.running && running == 0 && jobs.none { it.active }) {
                    stopSelf()
                    return@collect
                }
                nm.notify(NOTIFICATION_ID, notification(describe(install, running, jobs)))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val container = (application as LauncherApplication).container
            container.installer.cancel()
            container.queue.jobs.value.filter { it.active }.forEach { container.queue.cancel(it.id) }
            container.sessions.stopAll()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun describe(install: InstallState, running: Int, jobs: List<QueueJob>): String = buildList {
        jobs.firstOrNull { it.state is JobState.Running }?.let { job ->
            val p = (job.state as JobState.Running).progress
            val verb = if (job.action == QueueAction.INSTALL) "Installing" else "Removing"
            add("$verb ${job.title}" + (p?.let { " ${(it * 100).toInt()}%" } ?: ""))
        }
        jobs.count { it.state == JobState.Queued }.takeIf { it > 0 }?.let { add("$it waiting") }
        when (install) {
            is InstallState.Downloading -> add("Downloading Linux ${install.done * 100 / install.total.coerceAtLeast(1)}%")
            InstallState.Resolving, InstallState.Extracting, InstallState.Configuring -> add("Installing Linux")
            else -> {}
        }
        if (running > 0) add(if (running == 1) "1 session running" else "$running sessions running")
    }.joinToString(" · ")

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_terminal)
        .setContentTitle("Linux")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0, "Stop all",
            PendingIntent.getService(
                this, 1, Intent(this, ContainerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val CHANNEL = "container"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.daryan.linuxlauncher.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ContainerService::class.java))
        }
    }
}
