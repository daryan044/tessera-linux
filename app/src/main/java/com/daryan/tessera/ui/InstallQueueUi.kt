// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.tessera.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daryan.tessera.store.JobState
import com.daryan.tessera.store.QueueAction
import com.daryan.tessera.store.QueueJob

/** What the queue says about [job], in a few words. */
fun QueueJob.statusText(): String = when (val s = state) {
    JobState.Queued -> "Waiting"
    is JobState.Running -> buildString {
        append(s.message)
        s.progress?.let { append(" · ${(it * 100).toInt()}%") }
    }
    JobState.Done -> if (action == QueueAction.INSTALL) "Installed" else "Removed"
    is JobState.Failed -> "Failed: ${s.message}"
}

internal fun QueueJob.verb() = if (action == QueueAction.INSTALL) "Installing" else "Removing"

/**
 * The bar under the app bar while installs are queued: the current one with its progress, and
 * on the right a button (with how many are waiting) that opens the whole queue.
 */
@Composable
fun QueueStrip(jobs: List<QueueJob>, onOpenQueue: () -> Unit) {
    val current = jobs.firstOrNull { it.state is JobState.Running } ?: jobs.firstOrNull { it.active } ?: return
    val active = jobs.count { it.active }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
            JobIcon(current, 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${current.verb()} ${current.title}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.statusText(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                JobProgress(current)
            }
            Spacer(Modifier.width(8.dp))
            BadgedBox(badge = { if (active > 1) Badge { Text("+${active - 1}") } }) {
                FilledTonalIconButton(onClick = onOpenQueue) { Icon(LauncherIcons.Store, "Install queue") }
            }
        }
    }
}

@Composable
internal fun JobProgress(job: QueueJob) {
    val p = (job.state as? JobState.Running)?.progress
    if (p != null) {
        val animated by animateFloatAsState(p, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "job")
        LinearWavyProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
    } else if (job.active) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun JobIcon(job: QueueJob, sizeDp: Int) {
    val bmp = rememberFileBitmap(job.iconFile)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp / 4).dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (bmp != null) Image(bmp, null, Modifier.size((sizeDp * 0.85).dp))
        else Icon(LauncherIcons.Apps, null, Modifier.size((sizeDp / 2).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
