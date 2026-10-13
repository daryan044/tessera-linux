// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.daryan.linuxlauncher.ui.LauncherApp
import com.daryan.linuxlauncher.ui.theme.LauncherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Not again when the activity is recreated with the intent that already started the app.
        if (savedInstanceState == null) handleLaunch(intent)
        setContent {
            LauncherTheme {
                LauncherApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(intent: Intent?) {
        if (intent?.action != ACTION_LAUNCH_APP) return
        val id = intent.getStringExtra(EXTRA_APP_ID) ?: return
        (application as LauncherApplication).container.launchRequests.value = id
    }

    companion object {
        /** Starts the app named by [EXTRA_APP_ID]; what home screen icons ([HomeShortcuts]) send. */
        const val ACTION_LAUNCH_APP = "com.daryan.linuxlauncher.LAUNCH_APP"
        const val EXTRA_APP_ID = "app_id"
    }
}
