// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.daryan.tessera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daryan.tessera.HomeWidget
import com.daryan.tessera.WidgetKind
import com.daryan.tessera.WidgetTarget
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.RunningSession
import com.daryan.tessera.widgets.PhoneWidgets
import com.daryan.tessera.widgets.WidgetStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How often a stats widget looks again, while it's on screen. */
private const val STATS_EVERY_MS = 2_000L

/** A widget on one of Tessera's tabs. It has its own settings, whatever's on the phone's home screen. */
@Composable
fun HomeWidgetCard(
    widget: HomeWidget,
    running: List<RunningSession>,
    /** What its target is now; null once that's been uninstalled. */
    target: PermissionManifest?,
    editing: Boolean,
    onStart: (PermissionManifest) -> Unit,
    onShowSession: (RunningSession) -> Unit,
    onResize: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(modifier.padding(4.dp)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).combinedClickable(
                enabled = !editing,
                onClick = { if (widget.kind.needsTarget) target?.let(onStart) },
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    menu = true
                },
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                when (widget.kind) {
                    WidgetKind.APP, WidgetKind.COMMAND -> TargetContent(widget, target)
                    WidgetKind.RUNNING -> RunningContent(running, editing, onShowSession)
                    WidgetKind.STATS -> StatsContent(running.count { it.running })
                }
            }
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DropdownMenuItem(
                text = { Text(if (widget.wide) "Make it smaller" else "Make it wider") },
                onClick = { menu = false; onResize() },
            )
            DropdownMenuItem(
                text = { Text("Remove widget") },
                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}

@Composable
private fun TargetContent(widget: HomeWidget, target: PermissionManifest?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (target != null) {
            val (container, onContainer) = appColors(target)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(appShape(target)).background(container),
            ) { AppGlyph(target, 22.dp, onContainer) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                widget.target?.name ?: widget.kind.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    target == null -> "Isn't installed"
                    widget.kind == WidgetKind.COMMAND -> "Tap to run"
                    else -> "Tap to open"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RunningContent(running: List<RunningSession>, editing: Boolean, onShow: (RunningSession) -> Unit) {
    val open = running.filter { it.running }
    Text(
        if (open.isEmpty()) "Nothing's running" else "${open.size} running",
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
    if (open.isEmpty()) {
        Text(
            "Open a Linux app and it shows up here",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // As many as fit; the rest wrap to the next row, so none is out of reach.
    FlowRow(maxItemsInEachRow = 4, modifier = Modifier.fillMaxWidth()) {
        open.forEach { session ->
            val (container, onContainer) = appColors(session.app)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = !editing) { onShow(session) }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clip(appShape(session.app)).background(container),
                ) { AppGlyph(session.app, 22.dp, onContainer) }
                Spacer(Modifier.height(4.dp))
                Text(
                    session.app.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        repeat((4 - open.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun StatsContent(runningCount: Int) {
    val context = LocalContext.current
    val stats by produceState<WidgetStats?>(null, runningCount) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { WidgetStats.read(context, runningCount) }.getOrNull() }
            delay(STATS_EVERY_MS)
        }
    }
    val s = stats ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(WidgetKind.STATS.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(s.runningShort, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        WidgetRing("Memory", s.memoryShort, s.memoryPercent, s.memoryFraction, Modifier.weight(1f))
        WidgetRing("Storage", s.storageShort, s.storagePercent, s.storageFraction, Modifier.weight(1f))
    }
}

private val RING_SIZE = 68.dp

/** How full something is, as a ring with the percentage inside. */
@Composable
private fun WidgetRing(label: String, detail: String, percent: Int, fraction: Float, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(RING_SIZE)) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(RING_SIZE),
                strokeWidth = 7.dp,
                trackColor = MaterialTheme.colorScheme.secondaryContainer,
            )
            Text("$percent%", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Picks what an App or Quick command widget starts; [onPick] also gets its icon, drawn for a widget. */
@Composable
fun WidgetTargetPicker(kind: WidgetKind, targets: List<PermissionManifest>, onPick: (PermissionManifest, Bitmap) -> Unit) {
    if (targets.isEmpty()) {
        Text(
            if (kind == WidgetKind.COMMAND) "No shortcuts yet. Make one in the Shortcuts tab first."
            else "No apps are installed yet. You'll find them in the Store.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(maxItemsInEachRow = 4, modifier = Modifier.fillMaxWidth()) {
        targets.forEach { app ->
            val icon = rememberHomeIcon(app)
            CompactAppTile(app, Modifier.weight(1f)) { onPick(app, icon()) }
        }
        repeat((4 - targets.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * Adds a widget: which kind, what it starts if it starts something, and where it goes. Each one
 * added is separate from the rest, so the same app can have one here and another on the phone.
 */
@Composable
fun WidgetsSheet(
    tab: String,
    targets: (WidgetKind) -> List<PermissionManifest>,
    onAddHere: (WidgetKind, WidgetTarget?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf<WidgetKind?>(null) }
    var target by remember { mutableStateOf<WidgetTarget?>(null) }
    val canPin = remember { PhoneWidgets.canPin(context) }
    DraggableSheet(onDismissRequest = onDismiss) { peek ->
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Widgets", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            val chosen = kind
            when {
                chosen == null -> {
                    WidgetKind.entries.forEach { k ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .clickable { kind = k }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        ) {
                            Text(k.label, style = MaterialTheme.typography.titleSmall)
                            Text(k.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                chosen.needsTarget && target == null -> {
                    Text(
                        if (chosen == WidgetKind.COMMAND) "Which shortcut?" else "Which app?",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    WidgetTargetPicker(chosen, targets(chosen)) { app, icon ->
                        PhoneWidgets.saveIcon(context, app.id, icon)
                        target = WidgetTarget(app.id, app.name)
                    }
                    TextButton(onClick = { kind = null }) { Text("Back") }
                }
                else -> {
                    Text(target?.name ?: chosen.label, style = MaterialTheme.typography.titleSmall)
                    Text(chosen.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = { onAddHere(chosen, target); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add to “$tab”", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { if (PhoneWidgets.pin(context, chosen, target)) onDismiss() },
                        enabled = canPin,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add to the phone's home screen") }
                    if (!canPin) {
                        Text(
                            "This home screen doesn't take widgets from apps. Hold it and look for Widgets; Tessera's are listed there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    TextButton(onClick = { kind = null; target = null }) { Text("Back") }
                }
            }
            Spacer(peek)
        }
    }
}
