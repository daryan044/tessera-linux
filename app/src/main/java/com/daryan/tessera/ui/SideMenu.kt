// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.tessera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.daryan.tessera.BackendMode

/** What the side menu's header and badges show. */
data class MenuStatus(
    val mode: BackendMode,
    /** Why Linux can't run apps yet, or null when it can. */
    val blocker: String?,
    val running: Int,
    val activeInstalls: Int,
    /** A newer Tessera's version, when one is out. */
    val update: String? = null,
)

/** Everything the side menu can do; each closes the menu first. */
class MenuActions(
    val terminal: () -> Unit,
    val rootShell: () -> Unit,
    val installFile: () -> Unit,
    val installs: () -> Unit,
    val trackpad: () -> Unit,
    val copyDiagnostics: () -> Unit,
    val settings: () -> Unit,
    val about: () -> Unit,
    /** Opens the newer version's page; only shown with [MenuStatus.update]. */
    val update: () -> Unit,
)

/**
 * The hamburger menu, kept to what isn't a tap away already: how Linux is doing, a shell, the
 * things that have no tab of their own, and the way to Settings, where everything that is set
 * once lives (the engine is Setup's, the tabs are the dock's).
 */
@Composable
fun SideMenu(status: MenuStatus, actions: MenuActions) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Header(status)

            status.update?.let { version ->
                Item("Tessera $version is out", "See what's new and get it", LauncherIcons.History, onClick = actions.update)
            }

            Heading("Open")
            Item("Terminal", "A Linux command line", LauncherIcons.Terminal, enabled = status.blocker == null, onClick = actions.terminal)
            Item("Root shell", "For apt and changes to the system", LauncherIcons.Shield, enabled = status.blocker == null, onClick = actions.rootShell)
            Item("PC trackpad", "Use this phone as a computer's mouse and keyboard", LauncherIcons.Mouse, onClick = actions.trackpad)

            Divider()
            Heading("Install")
            Item("Install from a file", "A .deb, a .flatpak or an AppImage you downloaded", Icons.Rounded.Add, enabled = status.blocker == null, onClick = actions.installFile)
            Item(
                "Installs",
                if (status.activeInstalls > 0) "${status.activeInstalls} going now" else "What was installed and removed, with its output",
                LauncherIcons.History,
                badge = status.activeInstalls.takeIf { it > 0 },
                onClick = actions.installs,
            )

            Divider()
            Item("Settings", "Look, display, touch, store, system files", Icons.Rounded.Settings, onClick = actions.settings)
            Item("Copy diagnostics", "For reporting a problem: paste it where you ask for help", LauncherIcons.TextFile, onClick = actions.copyDiagnostics)
            Item("About", "Version, creator and licenses", Icons.Rounded.Info, onClick = actions.about)
        }
    }
}

/** The app's name over how Linux is doing right now. */
@Composable
private fun Header(status: MenuStatus) {
    val colors = MaterialTheme.colorScheme
    val ready = status.blocker == null
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (ready) colors.primaryContainer else colors.errorContainer,
        contentColor = if (ready) colors.onPrimaryContainer else colors.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Tessera", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(status.blocker ?: "Linux is ready", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Fact(status.mode.label)
                Spacer(Modifier.width(16.dp))
                Fact(if (status.running == 0) "Nothing running" else "${status.running} running")
            }
        }
    }
}

@Composable
private fun Fact(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

@Composable
private fun Item(
    title: String,
    detail: String,
    icon: ImageVector,
    enabled: Boolean = true,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        icon = { Icon(icon, null, Modifier.size(24.dp)) },
        badge = badge?.let { { Badge { Text("$it") } } },
        selected = false,
        onClick = { if (enabled) onClick() },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
