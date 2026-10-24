// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.tessera.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daryan.tessera.store.JobState
import com.daryan.tessera.store.QueueAction
import com.daryan.tessera.store.QueueJob

/**
 * Every install and removal: the one running, those waiting their turn, and what's finished,
 * each with apt's full output a tap away. Installs live here rather than in Sessions.
 */
@Composable
fun InstallsScreen(
    jobs: List<QueueJob>,
    onCancel: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    onClearFinished: () -> Unit,
    onClose: () -> Unit,
) {
    val back = rememberBackSwipe(onBack = onClose)
    var logFor by remember { mutableStateOf<Int?>(null) }
    val running = jobs.filter { it.state is JobState.Running }
    val waiting = jobs.filter { it.state == JobState.Queued }
    val finished = jobs.filter { !it.active }.sortedByDescending { it.finishedAt ?: it.queuedAt }

    Scaffold(
        modifier = Modifier.backSwipePage(back),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Installs") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { if (finished.isNotEmpty()) TextButton(onClick = onClearFinished) { Text("Clear finished") } },
            )
        },
    ) { padding ->
        if (jobs.isEmpty()) {
            EmptyState(
                title = "No installs",
                body = "Apps you install or remove wait here, one after another, and stay listed until you clear them.",
                padding = padding,
            )
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            section("Now", running, onCancel, onRetry) { logFor = it }
            section("Waiting", waiting, onCancel, onRetry) { logFor = it }
            section("Finished", finished, onCancel, onRetry) { logFor = it }
        }
    }

    jobs.firstOrNull { it.id == logFor }?.let { job -> JobLogSheet(job, onDismiss = { logFor = null }) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    jobs: List<QueueJob>,
    onCancel: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    if (jobs.isEmpty()) return
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp).animateItem(),
        )
    }
    items(jobs, key = { it.id }) { job -> JobCard(job, onCancel, onRetry, onOpen, Modifier.animateItem()) }
}

@Composable
private fun JobCard(job: QueueJob, onCancel: (Int) -> Unit, onRetry: (Int) -> Unit, onOpen: (Int) -> Unit, modifier: Modifier) {
    val failed = job.state is JobState.Failed
    Surface(
        onClick = { onOpen(job.id) },
        shape = MaterialTheme.shapes.large,
        color = if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JobIcon(job, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (job.action == QueueAction.INSTALL) job.title else "Remove ${job.title}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        job.statusText(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    job.active -> IconButton(onClick = { onCancel(job.id) }) { Icon(Icons.Rounded.Close, "Cancel") }
                    failed -> IconButton(onClick = { onRetry(job.id) }) { Icon(Icons.Rounded.Refresh, "Retry") }
                    else -> Icon(Icons.Rounded.Check, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (job.active) {
                Spacer(Modifier.height(8.dp))
                JobProgress(job)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(job.packages.joinToString(", "))
                    job.finishedAt?.let { append(" · ").append(DateUtils.getRelativeTimeSpanString(it)) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** apt's output for one job: selectable, with Copy. */
@Composable
private fun JobLogSheet(job: QueueJob, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = job.log.trim().ifEmpty { if (job.state == JobState.Queued) "Not started yet." else "No output yet." }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.headlineSmall)
                    Text(job.statusText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("${job.title} log", "${job.title}: ${job.statusText()}\n$text"))
                }) { Text("Copy") }
            }
            if (job.state is JobState.Failed) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("The last lines usually say why.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            val scroll = rememberScrollState(Int.MAX_VALUE)
            // While it runs, follow the output like a terminal does.
            LaunchedEffect(text) { if (job.active) scroll.animateScrollTo(scroll.maxValue) }
            SelectionContainer {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .verticalScroll(scroll)
                        .padding(12.dp),
                )
            }
        }
    }
}
