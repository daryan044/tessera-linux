// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daryan.linuxlauncher.container.AppIcons
import com.daryan.linuxlauncher.container.RunningSession
import com.daryan.linuxlauncher.ui.TerminalScreen
import com.daryan.linuxlauncher.ui.theme.LauncherTheme

/**
 * One terminal session in its own task, so it has its own card in Recents, like the X11
 * windows of GUI apps. Back sends the card to the background: the session keeps running.
 */
class TerminalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as LauncherApplication).container
        val id = intent.data?.host

        setContent {
            LauncherTheme {
                val sessions by container.sessions.sessions.collectAsStateWithLifecycle()
                val session = sessions.firstOrNull { it.session.id == id && it.terminal != null }
                if (session == null) {
                    // Dismissed, or the process restarted and the session is gone.
                    LaunchedEffect(Unit) { finishAndRemoveTask() }
                } else {
                    LaunchedEffect(session.session.id) { brandForRecents(this@TerminalActivity, session, container) }
                    TerminalScreen(
                        session = session,
                        onBack = { moveTaskToBack(true) },
                        onStop = { container.sessions.stop(session.session.id) },
                        setScreenListener = container.sessions::setScreenListener,
                    )
                }
            }
        }
    }

    companion object {
        fun open(context: Context, sessionId: String) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java)
                    .setData(Uri.parse("linux-session://$sessionId"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
            )
        }
    }
}

/** Names a session's window "<App> (Linux)" in Recents, with the app's own icon. */
fun brandForRecents(activity: Activity, session: RunningSession, container: LauncherContainer) {
    // Only PRoot rootfs is readable by the app; chroot sessions get a generated tile.
    val rootfs = if (session.backend == BackendMode.PROOT.label) container.paths.prootRootfs else null
    @Suppress("DEPRECATION")
    activity.setTaskDescription(
        ActivityManager.TaskDescription("${session.app.name} (Linux)", AppIcons.forApp(session.app, rootfs)),
    )
}
