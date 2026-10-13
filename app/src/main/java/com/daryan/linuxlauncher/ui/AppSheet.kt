@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.ui

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
    /** This app's GPU acceleration; null when it can't have any (no window, or no Adreno GPU). */
    gpu: Boolean?,
    onGpu: (Boolean) -> Unit,
) {
    // Opens compact, permissions folded; dragging up (or tapping Permissions) unfolds them and
    // the sheet grows; dragging down closes it. Those drags are ours, watched without taking the
    // touches: the sheet's own dragging is off, because in Material 1.5.0-beta01 a sheet that
    // changes size during or after a drag keeps treating the next finger as that drag, and
    // taps stop reaching its buttons (a second finger still gets through).
    var unfolded by rememberSaveable(app.id) { mutableStateOf(false) }
    val step = with(LocalDensity.current) { 48.dp.toPx() }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dy = 0f
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            dy += change.position.y - change.previousPosition.y
                            if (!unfolded && dy < -step) {
                                unfolded = true
                                break
                            }
                            // Down from the top of the content: close, as the sheet itself would.
                            if (dy > 2 * step && scroll.value == 0) {
                                close()
                                break
                            }
                            if (!change.pressed) break
                        }
                    }
                }
                .verticalScroll(scroll)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
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

            Spacer(Modifier.height(24.dp))
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
                    "Installs ${app.packages.joinToString()} with apt in the background; follow it under Installs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (gpu != null) {
                Spacer(Modifier.height(16.dp))
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("GPU acceleration", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (gpu) "Draws with the phone's GPU. Experimental: OpenGL apps may show a black window"
                                else "Draws in software",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = gpu, onCheckedChange = onGpu)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Surface(
                onClick = { unfolded = !unfolded },
                shape = MaterialTheme.shapes.large,
                color = Color.Transparent,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Permissions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (unfolded) "What ${backend.name} mode can hold this app to"
                            else "Drag up or tap to see and change them",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Rounded.KeyboardArrowDown, if (unfolded) "Fold" else "Unfold",
                        Modifier.graphicsLayer { rotationZ = if (unfolded) 180f else 0f },
                    )
                }
            }
            if (unfolded) {
                Spacer(Modifier.height(8.dp))
                PermissionEditor(app, backend, permissionsChanged, onPermissions, onResetPermissions)
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
