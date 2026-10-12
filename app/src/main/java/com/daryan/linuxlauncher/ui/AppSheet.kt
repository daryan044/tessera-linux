@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
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
import androidx.compose.material.icons.rounded.Add
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
import com.daryan.linuxlauncher.container.Permissions

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppSheet(
    app: PermissionManifest,
    backend: ContainerBackend,
    blocker: String?,
    /** False when the app's packages need installing first; null when unknown. */
    installed: Boolean?,
    customTabs: List<CustomTab>,
    onToggleTab: (String) -> Unit,
    onLaunch: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    /** Whether the user changed this app's permissions. */
    permissionsChanged: Boolean,
    onPermissions: (Permissions) -> List<String>,
    onResetPermissions: () -> Unit,
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
                    AppGlyph(app, 32.dp, onContainer)
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
            PermissionEditor(app, backend, permissionsChanged, onPermissions, onResetPermissions)

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
            val needsInstall = installed == false
            Button(
                onClick = if (needsInstall) onInstall else onLaunch,
                enabled = blocker == null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (blocker == null) {
                    Icon(if (needsInstall) Icons.Rounded.Add else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    blocker ?: if (needsInstall) "Install ${app.name}" else "Open ${app.name}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (needsInstall && blocker == null) {
                Text(
                    "Installs ${app.packages.joinToString()} with apt, in a terminal you can watch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun EnforcementPill(e: Enforcement) {
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
