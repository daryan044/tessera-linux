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
    status: HostStatus?,
    mode: BackendMode,
    linuxInstall: InstallState,
    onMode: (BackendMode) -> Unit,
    onInstallLinux: () -> Unit,
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
            Group("Linux engine") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    BackendMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { onMode(m) },
                            enabled = m == BackendMode.PROOT || status?.su != null,
                            shape = SegmentedButtonDefaults.itemShape(i, BackendMode.entries.size),
                        ) { Text(m.label) }
                    }
                }
                Row(LauncherIcons.Terminal, "PRoot engine", if (status?.prootBinary == true) "Bundled" else "Missing from this build")
                Row(
                    LauncherIcons.Apps, "Linux system",
                    when {
                        status?.prootRootfs == true -> "Debian is installed"
                        linuxInstall.running -> "Installing…"
                        linuxInstall is InstallState.Failed -> "Install failed: ${linuxInstall.message}"
                        else -> "Not installed"
                    },
                    action = if (status?.prootRootfs == false && !linuxInstall.running) ({ TextButton(onClick = onInstallLinux) { Text("Install") } }) else null,
                )
                Row(Icons.Rounded.Settings, "Root access", status?.su?.let { "Found $it" } ?: "Not rooted; chroot mode unavailable")
            }

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
            }

            Group("Sessions") {
                Row(
                    LauncherIcons.Terminal, "Running apps", if (runningSessions == 0) "Nothing running" else "$runningSessions running",
                    action = if (runningSessions > 0) ({ TextButton(onClick = onStopAll) { Text("Stop all") } }) else null,
                )
            }

            if (Build.VERSION.SDK_INT >= 31) {
                Group("Android") { PhantomLimitRow() }
            }

            Group("Linux Launcher") {
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

@Composable
private fun PhantomLimitRow() {
    val context = LocalContext.current
    // Android 14 has a settings switch; 12 and 13 only the device_config knob.
    val command = if (Build.VERSION.SDK_INT >= 34) "adb shell settings put global settings_enable_monitor_phantom_procs false"
    else "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent; " +
        "/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""
    Row(
        Icons.Rounded.Warning, "Background process limit",
        "Android kills apps with more than 32 processes. Run the copied command once from a computer.",
        action = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb command", command))
            }) { Text("Copy") }
        },
    )
}
