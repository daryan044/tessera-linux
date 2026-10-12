@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.daryan.linuxlauncher.CustomTab
import com.daryan.linuxlauncher.container.Capability
import com.daryan.linuxlauncher.container.ContainerBackend
import com.daryan.linuxlauncher.container.Enforcement
import com.daryan.linuxlauncher.container.PermissionManifest

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppSheet(
    app: PermissionManifest,
    backend: ContainerBackend,
    blocker: String?,
    customTabs: List<CustomTab>,
    onToggleTab: (String) -> Unit,
    onLaunch: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (container, onContainer) = appColors(app)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(72.dp).clip(appShape(app)).background(container),
                ) {
                    Icon(appIcon(app.icon), null, Modifier.size(32.dp), tint = onContainer)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(app.name, style = MaterialTheme.typography.headlineSmall)
                    if (app.description.isNotEmpty()) {
                        Text(
                            app.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Text(
                "What ${backend.name} mode can hold this app to",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column {
                    Capability.entries.forEachIndexed { i, cap ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
                        PermissionRow(
                            title = cap.label,
                            detail = describe(cap, app),
                            enforcement = if (restricts(cap, app)) backend.enforcement(cap, app) else null,
                        )
                    }
                }
            }

            if (customTabs.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Show in tabs", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    customTabs.forEach { tab ->
                        val on = app.id in tab.appIds
                        FilterChip(
                            selected = on,
                            onClick = { onToggleTab(tab.name) },
                            label = { Text(tab.name) },
                            leadingIcon = if (on) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }
                            } else null,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onLaunch,
                enabled = blocker == null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (blocker == null) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(blocker ?: "Open ${app.name}", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, detail: String, enforcement: Enforcement?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (enforcement != null) {
            Spacer(Modifier.width(12.dp))
            EnforcementPill(enforcement)
        }
    }
}

@Composable
private fun EnforcementPill(e: Enforcement) {
    val c = MaterialTheme.colorScheme
    val (label, bg: Color, fg: Color) = when (e) {
        Enforcement.ENFORCED -> Triple("Enforced", c.tertiaryContainer, c.onTertiaryContainer)
        Enforcement.ADVISORY -> Triple("Best-effort", c.secondaryContainer, c.onSecondaryContainer)
        Enforcement.UNAVAILABLE -> Triple("Not enforced", c.errorContainer, c.onErrorContainer)
    }
    Surface(shape = CircleShape, color = bg, contentColor = fg) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

private fun describe(cap: Capability, app: PermissionManifest): String {
    val p = app.permissions
    return when (cap) {
        Capability.FILESYSTEM ->
            if (p.filesystem.isEmpty()) "Only its own files"
            else p.filesystem.joinToString("\n") {
                val name = it.path.substringAfterLast('/')
                "$name → ${it.mountAt}" + if (it.readOnly) " (read-only)" else ""
            }
        Capability.NETWORK -> if (p.network) "Internet access" else "No network"
        Capability.DISPLAY -> if (p.display) "Its own window and display" else "Terminal only"
        Capability.AUDIO -> if (p.audio) "Can play sound" else "Muted"
        Capability.DEVICES -> p.devices.joinToString("\n").ifEmpty { "No hardware access" }
    }
}

/** Whether the manifest withholds something here; if it grants everything there is nothing to enforce. */
private fun restricts(cap: Capability, app: PermissionManifest): Boolean = when (cap) {
    Capability.NETWORK -> !app.permissions.network
    Capability.AUDIO -> !app.permissions.audio
    else -> true
}
