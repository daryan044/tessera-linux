// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalLayoutApi::class)

package com.daryan.linuxlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.daryan.linuxlauncher.Shortcut

private val ICON_CHOICES = listOf("terminal", "code", "globe", "monitor", "folder", "spark")

/** Makes a new shortcut ([shortcut] with an empty id) or edits one. */
@Composable
fun ShortcutDialog(shortcut: Shortcut, onDismiss: () -> Unit, onSave: (Shortcut) -> Unit) {
    var name by remember { mutableStateOf(shortcut.name) }
    var command by remember { mutableStateOf(shortcut.command) }
    var window by remember { mutableStateOf(shortcut.window) }
    var root by remember { mutableStateOf(shortcut.root) }
    var icon by remember { mutableStateOf(shortcut.icon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (shortcut.id.isEmpty()) "New shortcut" else "Edit shortcut") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    command, { command = it },
                    label = { Text("Command") },
                    placeholder = { Text("htop") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                Text(
                    "Runs in bash as a login shell. Several lines are fine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ICON_CHOICES.forEach { key ->
                        FilterChip(
                            selected = icon == key,
                            onClick = { icon = key },
                            label = { Icon(appIcon(key), key, Modifier.size(20.dp)) },
                        )
                    }
                }
                SwitchLine("Open in a window", "For graphical programs; otherwise a terminal", window) { window = it }
                SwitchLine("Run as root", "Root inside the container", root) { root = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(shortcut.copy(name = name.trim(), command = command.trim(), window = window, root = root, icon = icon)) },
                enabled = name.isNotBlank() && command.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchLine(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
