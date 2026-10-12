// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.daryan.linuxlauncher.container.ConfigCatalog
import com.daryan.linuxlauncher.container.ConfigFile

/** The hamburger menu: the container's config files, grouped, plus display settings. */
@Composable
fun SideMenu(
    engine: String,
    /** Why the files can't be edited right now, or null when they can. */
    unavailable: String?,
    onOpenFile: (ConfigFile) -> Unit,
    onDisplaySettings: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Configuration",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 28.dp, top = 24.dp, end = 28.dp),
            )
            Text(
                unavailable ?: "Editing the $engine container",
                style = MaterialTheme.typography.bodyMedium,
                color = if (unavailable != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
            )

            ConfigCatalog.files.groupBy { it.group }.forEach { (group, files) ->
                GroupLabel(group)
                files.forEach { f ->
                    Item(f.title, f.path, iconFor(group), enabled = unavailable == null) { onOpenFile(f) }
                }
            }

            GroupLabel("Display")
            Item("X11 display settings", "Resolution, touch mode, keyboard", Icons.Rounded.Settings, enabled = true, onClick = onDisplaySettings)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Item(title: String, detail: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(title)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        icon = { Icon(icon, null) },
        selected = false,
        onClick = { if (enabled) onClick() },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

private fun iconFor(group: String): ImageVector = when (group) {
    "Shell" -> LauncherIcons.Terminal
    "Network" -> LauncherIcons.Globe
    "Packages" -> LauncherIcons.Apps
    else -> LauncherIcons.Folder
}
