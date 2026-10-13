// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.linuxlauncher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.daryan.linuxlauncher.BackendMode
import com.daryan.linuxlauncher.CustomTab
import com.daryan.linuxlauncher.container.ConfigCatalog
import com.daryan.linuxlauncher.container.ConfigFile
import com.daryan.linuxlauncher.container.HostStatus
import com.daryan.linuxlauncher.container.InstallState
import com.daryan.linuxlauncher.store.CatalogState

/** Everything the launcher lets you configure, in one place. Each row hands off to its owner. */
@Composable
fun SettingsScreen(
    /** Why the system files can't be edited, or null when they can. */
    filesUnavailable: String?,
    onOpenFile: (ConfigFile) -> Unit,
    customTabs: List<CustomTab>,
    onAddTab: () -> Unit,
    onRemoveTab: (String) -> Unit,
    catalog: CatalogState,
    onRefreshCatalog: () -> Unit,
    activeInstalls: Int,
    onOpenQueue: () -> Unit,
    onDisplaySettings: () -> Unit,
    /** Null when this phone or build can't use hardware graphics. */
    gpuEnabled: Boolean?,
    onGpuEnabled: (Boolean) -> Unit,
    runningSessions: Int,
    onStopAll: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            Group("System files") {
                if (filesUnavailable != null) Note(filesUnavailable)
                ConfigCatalog.files.forEach { f ->
                    Row(LauncherIcons.Folder, f.title, f.path, onClick = if (filesUnavailable == null) ({ onOpenFile(f) }) else null)
                }
            }

            Group("Launcher") {
                if (customTabs.isEmpty()) Note("No custom tabs yet. Tabs group apps you pick, next to the category tabs.")
                customTabs.forEach { tab ->
                    Row(LauncherIcons.Apps, tab.name, "${tab.appIds.size} apps", action = {
                        IconButton(onClick = { onRemoveTab(tab.name) }) { Icon(Icons.Rounded.Delete, "Remove tab") }
                    })
                }
                Row(Icons.Rounded.Add, "New tab", "Group apps your way", onClick = onAddTab)
            }

            Group("App store") {
                Row(
                    LauncherIcons.Store, "App catalog",
                    when (catalog) {
                        is CatalogState.Ready -> "${catalog.apps.size} apps · updated ${DateUtils.getRelativeTimeSpanString(catalog.updated)}"
                        is CatalogState.Loading -> catalog.step
                        is CatalogState.Failed -> "Couldn't load: ${catalog.message}"
                        CatalogState.Empty -> "Not downloaded yet (about 20 MB)"
                    },
                    action = { IconButton(onClick = onRefreshCatalog) { Icon(Icons.Rounded.Refresh, "Refresh catalog") } },
                )
                Row(LauncherIcons.Store, "Install queue", if (activeInstalls == 0) "Nothing installing" else "$activeInstalls installing or waiting", onClick = onOpenQueue)
            }

            Group("Display") {
                Row(Icons.Rounded.Settings, "X11 display settings", "Resolution, touch mode, keyboard", onClick = onDisplaySettings)
                Row(
                    LauncherIcons.Monitor, "GPU acceleration",
                    when (gpuEnabled) {
                        null -> "Needs an Adreno GPU (Snapdragon); apps draw in software here"
                        true -> "Windowed apps draw with the phone's GPU (OpenGL and Vulkan, through Turnip and Zink). Applies to apps opened from now on"
                        false -> "Off: apps draw in software"
                    },
                    action = gpuEnabled?.let { on -> { androidx.compose.material3.Switch(checked = on, onCheckedChange = onGpuEnabled) } },
                )
            }

            Group("Sessions") {
                Row(
                    LauncherIcons.Terminal, "Running apps", if (runningSessions == 0) "Nothing running" else "$runningSessions running",
                    action = if (runningSessions > 0) ({ TextButton(onClick = onStopAll) { Text("Stop all") } }) else null,
                )
            }


            Group("Tessera") {
                Row(Icons.Rounded.Info, "About", "Version, creator and licenses", onClick = onAbout)
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun Row(icon: ImageVector, title: String, detail: String, onClick: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    if (onClick != null) {
        ListItem(
            onClick = onClick,
            leadingContent = { Icon(icon, null) },
            trailingContent = action,
            supportingContent = { Text(detail) },
            colors = colors,
        ) { Text(title) }
    } else {
        ListItem(
            leadingContent = { Icon(icon, null) },
            trailingContent = action,
            supportingContent = { Text(detail) },
            colors = colors,
        ) { Text(title) }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}
