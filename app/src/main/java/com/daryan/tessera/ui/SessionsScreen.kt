@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import com.daryan.tessera.container.GuestProcess
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.daryan.tessera.container.RunningSession

/** Lines a log shows before it is opened. */
private const val PREVIEW_LINES = 8

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
            // What it uses; what it printed is under Logs in the title bar.
            if (s.running && !processes.isNullOrEmpty()) {
                Spacer(Modifier.height(16.dp))
                SessionMeters(processes, gpu = s.session.gpu)
                Spacer(Modifier.height(8.dp))
                SessionLoad(processes, onEndProcess, totals = false)
            }
        }
    }
}

/**
 * What sessions printed, from the title bar's Logs or an app that stopped: one at a time,
 * picked from chips when there are several, selectable, with Copy. Opens on [initial], else
 * the newest.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SessionLogSheet(sessions: List<RunningSession>, initial: String?, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var picked by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initial) }
    val s = sessions.firstOrNull { it.session.id == picked } ?: sessions.lastOrNull()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberBottomSheetState(
            initialValue = androidx.compose.material3.SheetValue.Hidden,
            enabledValues = setOf(androidx.compose.material3.SheetValue.Hidden, androidx.compose.material3.SheetValue.Expanded),
        ),
    ) {
        if (s == null) {
            Text(
                "Nothing has run yet. Apps' output shows here while they run and after they stop.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            )
            return@ModalBottomSheet
        }
        val text = s.log.replace(ANSI, "").trim().ifEmpty { "${s.app.name} printed nothing." }
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            if (sessions.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 12.dp),
                ) {
                    sessions.asReversed().forEach { o ->
                        androidx.compose.material3.FilterChip(
                            selected = o.session.id == s.session.id,
                            onClick = { picked = o.session.id },
                            label = { Text(o.app.name) },
                        )
                    }
                }
            }
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
            // The last lines first; a tap opens all of it, selectable (Copy takes all either way).
            val lines = text.lines()
            var expanded by androidx.compose.runtime.saveable.rememberSaveable(s.session.id) { androidx.compose.runtime.mutableStateOf(false) }
            val box = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            if (expanded || lines.size <= PREVIEW_LINES) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = box
                            .heightIn(max = 480.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(12.dp),
                    )
                }
            } else {
                Text(
                    lines.takeLast(PREVIEW_LINES).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = box.clickable { expanded = true }.padding(12.dp),
                )
            }
            if (lines.size > PREVIEW_LINES) {
                androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Show all ${lines.size} lines")
                }
            }
        }
    }
}
