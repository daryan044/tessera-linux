// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daryan.tessera.LauncherViewModel
import com.daryan.tessera.WidgetKind
import com.daryan.tessera.WidgetTarget
import com.daryan.tessera.ui.WidgetTargetPicker
import com.daryan.tessera.ui.theme.LauncherTheme

/**
 * Sets up one App or Quick command widget on the phone's home screen, when it's added from the
 * phone's own widget list, or changed later. Widgets added from inside Tessera come set up.
 */
class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Backing out of it cancels adding the widget.
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        val provider = manager.getAppWidgetInfo(id)?.provider?.className
        val kind = WidgetKind.entries.firstOrNull { PhoneWidgets.providerFor(it).name == provider }
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || kind == null) return finish()
        setContent {
            LauncherTheme(systemBars = true) {
                val vm: LauncherViewModel = viewModel()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text(if (kind == WidgetKind.COMMAND) "Choose a shortcut" else "Choose an app", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        WidgetTargetPicker(kind, vm.widgetTargets(kind)) { app, icon ->
                            PhoneWidgets.saveIcon(this@WidgetSetupActivity, app.id, icon)
                            PhoneWidgets.save(this@WidgetSetupActivity, id, PhoneWidget(kind, WidgetTarget(app.id, app.name)))
                            PhoneWidgets.draw(this@WidgetSetupActivity, manager, id, kind)
                            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        }
                    }
                }
            }
        }
    }
}
