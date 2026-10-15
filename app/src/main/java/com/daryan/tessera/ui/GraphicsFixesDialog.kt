// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daryan.tessera.container.GpuFix

private fun GpuFix.title() = when (this) {
    GpuFix.NO_COMPRESSION -> "Fix black blocks"
    GpuFix.DIRECT_RENDERING -> "Fix missing or wrong parts"
    GpuFix.SYNC_DRAWS -> "Fix flicker"
}

private fun GpuFix.detail() = when (this) {
    GpuFix.NO_COMPRESSION -> "Turns off the GPU's image compression. For windows that show a grid of black squares (VS Code and other Chromium apps)"
    GpuFix.DIRECT_RENDERING -> "Draws the whole window at once instead of in tiles, for parts that are drawn wrong or not at all"
    GpuFix.SYNC_DRAWS -> "Waits for every draw to finish, for flicker and half-drawn frames. Much slower"
}

/**
 * A windowed app's look and graphics settings, from its long-press menu: whether it takes the
 * phone's theme, GPU acceleration, and workarounds for the driver's drawing bugs. They apply
 * from the app's next launch.
 */
@Composable
fun GraphicsFixesDialog(
    appName: String,
    /** Whether the app is themed; null when theming is off for every app. */
    themed: Boolean?,
    onThemed: (Boolean) -> Unit,
    /** Null when the phone or build has no GPU driver. */
    gpu: Boolean?,
    onGpu: (Boolean) -> Unit,
    fixes: Set<GpuFix>,
    onFix: (GpuFix, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Look and graphics for $appName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (themed != null) {
                    FixLine("Phone's theme", "Colors and dark mode from Android. Off for apps that look wrong themed", themed, enabled = true, onThemed)
                }
                if (themed != null && gpu != null) HorizontalDivider()
                if (gpu != null) {
                    FixLine("GPU acceleration", "Off draws in software: slower, but always right", gpu, enabled = true, onGpu)
                    HorizontalDivider()
                    GpuFix.entries.forEach { fix ->
                        FixLine(fix.title(), fix.detail(), fix in fixes, enabled = gpu) { onFix(fix, it) }
                    }
                }
                Text(
                    "Changes apply the next time the app opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun FixLine(title: String, detail: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}
