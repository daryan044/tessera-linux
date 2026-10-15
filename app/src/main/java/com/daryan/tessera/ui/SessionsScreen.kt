@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import com.daryan.tessera.container.GuestProcess
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
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
import com.daryan.tessera.container.RunningSession

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[()][0-9A-B]|\r")

@Composable
fun SessionsScreen(
    sessions: List<RunningSession>,
    padding: PaddingValues,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
    onDismiss: (String) -> Unit,
    /** Every container process, sampled live; null until the first sample. */
    processes: List<GuestProcess>? = null,
    onCloseAll: () -> Unit = {},
    onEndProcess: (GuestProcess) -> Unit = {},
) {
    val ids = sessions.map { it.session.id }.toSet()
    // Linux work that isn't an app the user opened: the Files tab's shells, apt.
    val background = processes.orEmpty().filter { it.session !in ids }.groupBy { it.session }
    if (sessions.isEmpty() && background.isEmpty()) {
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
        item(key = "summary") {
            TaskSummary(processes, running = sessions.count { it.running }, onCloseAll = onCloseAll, modifier = Modifier.animateItem())
        }
        items(sessions.asReversed(), key = { it.session.id }) { s ->
            val mine = processes?.filter { it.session == s.session.id }
            SessionCard(s, mine, onOpen, onStop, onDismiss, onEndProcess, Modifier.animateItem())
        }
        if (background.isNotEmpty()) {
            item(key = "background") {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().animateItem(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("In the background", style = MaterialTheme.typography.titleMedium)
                        background.toSortedMap().forEach { (session, procs) ->
                            Spacer(Modifier.height(12.dp))
                            Text(backgroundLabel(session), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            SessionLoad(procs, onEndProcess)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    s: RunningSession,
    /** Its processes right now; null before the first sample. */
    processes: List<GuestProcess>?,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onEndProcess: (GuestProcess) -> Unit,
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
                    AppGlyph(s.app, 22.dp, onContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.app.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(s.statusLabel)
                            append(" · ${s.backend}")
                            s.session.display?.let { append(" · display :$it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.running) {
                    ConfirmButton("End task", onConfirm = { onStop(s.session.id) })
                } else {
                    IconButton(onClick = { onDismiss(s.session.id) }) {
                        Icon(Icons.Rounded.Close, "Dismiss")
                    }
                }
            }
            if (s.running && !processes.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                SessionLoad(processes, onEndProcess)
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

/** Everything a session printed, for apps that stopped: selectable, with Copy. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SessionLogSheet(s: RunningSession, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val text = s.output.replace(ANSI, "").trim().ifEmpty { "${s.app.name} printed nothing." }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberBottomSheetState(
            initialValue = androidx.compose.material3.SheetValue.Hidden,
            enabledValues = setOf(androidx.compose.material3.SheetValue.Hidden, androidx.compose.material3.SheetValue.Expanded),
        ),
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.app.name, style = MaterialTheme.typography.headlineSmall)
                    Text("${s.statusLabel} · ${s.backend}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("${s.app.name} log", "${s.app.name}: ${s.statusLabel}\n$text"))
                }) { Text("Copy") }
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}
