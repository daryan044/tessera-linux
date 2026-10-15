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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.daryan.tessera.BackendMode
import com.daryan.tessera.X11Input

/** What the side menu's header and badges show. */
data class MenuStatus(
    val mode: BackendMode,
    /** Why Linux can't run apps yet, or null when it can. */
    val blocker: String?,
    val running: Int,
    val activeInstalls: Int,
    /** Root was found, so chroot mode is on offer. */
    val rootAvailable: Boolean,
    val destination: Destination,
)

/** Everything the side menu can do; each closes the menu first. */
class MenuActions(
    val go: (Destination) -> Unit,
    val terminal: () -> Unit,
    val rootShell: () -> Unit,
    val newShortcut: () -> Unit,
    val trackpad: () -> Unit,
    val installs: () -> Unit,
    val systemFiles: () -> Unit,
    val displaySettings: () -> Unit,
    val copyDiagnostics: () -> Unit,
    val setMode: (BackendMode) -> Unit,
    val settings: () -> Unit,
    val about: () -> Unit,
    val intro: () -> Unit,
)

/**
 * The hamburger menu: how Linux is doing, quick launches, every tab, the tools that live
 * elsewhere, the engine, how Linux windows take touch, and the rest.
 */
@Composable
fun SideMenu(status: MenuStatus, actions: MenuActions) {
    val context = LocalContext.current
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Header(status)

            Heading("Quick launch")
            Item("Terminal", "A Debian shell", LauncherIcons.Terminal, enabled = status.blocker == null, onClick = actions.terminal)
            Item("Root shell", "For apt and system changes", LauncherIcons.Shield, enabled = status.blocker == null, onClick = actions.rootShell)
            Item("New shortcut", "A command as an icon", Icons.Rounded.Add, onClick = actions.newShortcut)

            Divider()
            Heading("Go to")
            Destination.entries.forEach { d ->
                NavigationDrawerItem(
                    label = { Text(d.label) },
                    icon = { Icon(d.icon, null) },
                    selected = status.destination == d,
                    onClick = { actions.go(d) },
                    badge = {
                        when {
                            d == Destination.Sessions && status.running > 0 -> Badge { Text("${status.running}") }
                            d == Destination.Setup && status.blocker != null -> Badge()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Divider()
            Heading("Tools")
            Item("PC trackpad", "Drive a computer over USB or Bluetooth", LauncherIcons.Mouse, onClick = actions.trackpad)
            Item(
                "Installs",
                if (status.activeInstalls > 0) "${status.activeInstalls} going now" else "What apt installed and removed",
                LauncherIcons.History,
                badge = status.activeInstalls.takeIf { it > 0 },
                onClick = actions.installs,
            )
            Item("System files", "bashrc, apt sources, DNS, hosts", LauncherIcons.Code, onClick = actions.systemFiles)
            Item("Display", "Resolution and scaling of Linux windows", LauncherIcons.Monitor, onClick = actions.displaySettings)
            Item("Copy diagnostics", "Everything a helper needs, to paste", LauncherIcons.TextFile, onClick = actions.copyDiagnostics)

            Divider()
            Heading("Engine")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp)) {
                BackendMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = status.mode == m,
                        onClick = { actions.setMode(m) },
                        enabled = m != BackendMode.CHROOT || status.rootAvailable,
                        shape = SegmentedButtonDefaults.itemShape(i, BackendMode.entries.size),
                    ) { Text(if (m == BackendMode.PROOT) "PRoot" else "Chroot") }
                }
            }
            Text(
                if (status.rootAvailable) "Each engine has its own Linux system."
                else "Chroot needs root (Magisk, KernelSU or APatch).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
            )

            Divider()
            Heading("Touch in Linux windows")
            Column(Modifier.padding(horizontal = 16.dp)) {
                InputChoices(
                    mode = X11Input.mode(context),
                    onMode = { X11Input.setMode(context, it) },
                    longPress = X11Input.longPressRightClick(context),
                    onLongPress = { X11Input.setLongPressRightClick(context, it) },
                )
            }

            Divider()
            Item("Settings", "Tabs, store, graphics and more", Icons.Rounded.Settings, onClick = actions.settings)
            Item("Introduction", "The tour from the first run", Icons.Rounded.PlayArrow, onClick = actions.intro)
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
