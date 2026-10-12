@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.daryan.linuxlauncher.container.RunningSession

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[()][0-9A-B]|\r")

@Composable
fun SessionsScreen(
    sessions: List<RunningSession>,
    padding: PaddingValues,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (sessions.isEmpty()) {
        EmptyState(
            title = "No sessions running",
            body = "Open an app from the Apps tab. Running apps stay here, even when you leave.",
            padding = padding,
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(sessions.asReversed(), key = { it.session.id }) { s ->
            SessionCard(s, onOpen, onStop, onDismiss, Modifier.animateItem())
        }
    }
}

@Composable
private fun SessionCard(
    s: RunningSession,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier,
) {
    Surface(
        onClick = { onOpen(s.session.id) },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (container, onContainer) = appColors(s.app)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(44.dp).clip(appShape(s.app)).background(container),
                ) {
                    Icon(appIcon(s.app.icon), null, Modifier.size(22.dp), tint = onContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.app.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(if (s.running) "Running" else "Exited (${s.exitCode})")
                            append(" · ${s.backend}")
                            s.session.display?.let { append(" · display :$it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.running) {
                    FilledTonalButton(onClick = { onStop(s.session.id) }) { Text("Stop") }
                } else {
                    IconButton(onClick = { onDismiss(s.session.id) }) {
                        Icon(Icons.Rounded.Close, "Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val text = s.preview().replace(ANSI, "").trimEnd()
            Text(
                text.ifEmpty { "Waiting for output" },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(12.dp),
            )
        }
    }
}
