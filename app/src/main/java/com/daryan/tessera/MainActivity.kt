// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.daryan.tessera.files.SharedIn
import com.daryan.tessera.ui.LauncherApp
import com.daryan.tessera.ui.theme.LauncherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Not again when the activity is recreated with the intent that already started the app.
        if (savedInstanceState == null) handleLaunch(intent)
        setContent {
            LauncherTheme(systemBars = true) {
                LauncherApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(intent: Intent?) {
        val container = (application as LauncherApplication).container
        if (intent?.action == ACTION_LAUNCH_APP) {
            container.launchRequests.value = intent.getStringExtra(EXTRA_APP_ID) ?: return
            return
        }
        if (intent?.action == ACTION_SHOW_SESSION) {
            // Tapped on a widget; one that has since closed just leaves Tessera open.
            val session = intent.getStringExtra(EXTRA_SESSION_ID)?.let(container.sessions::byId)?.takeIf { it.running } ?: return
            if (session.terminal != null) TerminalActivity.open(this, session.session.id) else container.sessions.showWindow(session.session.id)
            return
        }
        // Only through "Open in Linux": a plain start of Tessera is never about a file.
        if (intent?.component?.className != OPEN_IN_LINUX) return
        container.sharedRequests.value = SharedIn.of(intent.action, intent.dataString, streams(intent), intent.getStringExtra(Intent.EXTRA_TEXT), packageName)
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<String> = runCatching {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.map { it.toString() }
    }.getOrNull() ?: runCatching {
        listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString())
    }.getOrDefault(emptyList())

    companion object {
        /** The manifest's alias that puts Tessera in Android's "Open with" and "Share" menus. */
        const val OPEN_IN_LINUX = "com.daryan.tessera.OpenInLinux"

        /** Starts the app named by [EXTRA_APP_ID]; what home screen icons ([HomeShortcuts]) send. */
        const val ACTION_LAUNCH_APP = "com.daryan.tessera.LAUNCH_APP"
        const val EXTRA_APP_ID = "app_id"

        /** Brings the running session named by [EXTRA_SESSION_ID] to the front. */
        const val ACTION_SHOW_SESSION = "com.daryan.tessera.SHOW_SESSION"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
