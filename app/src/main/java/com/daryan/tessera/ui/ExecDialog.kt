// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.daryan.tessera.container.ExecLine

/**
 * The command an app runs, from its long-press menu: the manifest's argv as a line to edit. It
 * is run directly, not through a shell, so quotes only hold an argument with spaces together.
 * Changes apply from the app's next launch.
 */
@Composable
fun ExecDialog(
    appName: String,
    exec: List<String>,
    default: List<String>,
    /** Saves the command; returns what's wrong with it, and then nothing is saved. */
    onSave: (List<String>) -> List<String>,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(exec) { mutableStateOf(ExecLine.format(exec)) }
    var problems by remember(exec) { mutableStateOf(emptyList<String>()) }
    val defaultLine = remember(default) { ExecLine.format(default) }
    val changed = text.trim() != defaultLine

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Command for $appName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; problems = emptyList() },
                    label = { Text("Command") },
                    isError = problems.isNotEmpty(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (problems.isNotEmpty()) {
                    Text(
                        problems.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Run as it is written, without a shell: no pipes, no variables. Quote an " +
                        "argument that contains spaces.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (changed) {
                    Text(
                        "The app asks for: $defaultLine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onReset(); onDismiss() }) { Text("Reset to default") }
                }
                Text(
                    "Changes apply the next time the app opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                problems = onSave(ExecLine.parse(text))
                if (problems.isEmpty()) onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
