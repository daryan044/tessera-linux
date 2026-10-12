// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
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

/** The hamburger menu: Settings (which holds everything else) and About. */
@Composable
fun SideMenu(onSettings: () -> Unit, onAbout: () -> Unit) {
    ModalDrawerSheet {
        Column {
            Text(
                "Linux Launcher",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 28.dp, top = 24.dp, end = 28.dp, bottom = 16.dp),
            )
            Item("Settings", "System files, installs, display and more", Icons.Rounded.Settings, onSettings)
            Item("About", "Version, creator and licenses", Icons.Rounded.Info, onAbout)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Item(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(title)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        icon = { Icon(icon, null) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
