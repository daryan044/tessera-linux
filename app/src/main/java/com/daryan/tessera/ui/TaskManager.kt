// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.tessera.ui

import android.app.ActivityManager
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daryan.tessera.container.GuestProcess

/** CPU, memory and process count across [processes], or for one session's. */
data class Load(val cpu: Float, val memory: Long, val count: Int) {
    companion object {
        fun of(processes: List<GuestProcess>) = Load(processes.sumOf { it.cpu.toDouble() }.toFloat(), processes.sumOf { it.memory }, processes.size)
    }
}

/**
 * The top of the Sessions page: what all of Linux is using right now, against the phone's
 * memory, and Close all.
 */
@Composable
fun TaskSummary(processes: List<GuestProcess>?, running: Int, onCloseAll: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val motion = MaterialTheme.motionScheme
    val total = remember {
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    }
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    val load = processes?.let(Load::of)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Task manager", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            load == null -> "Measuring…"
                            load.count == 0 -> "Nothing running in Linux"
                            else -> "${load.count} Linux ${if (load.count == 1) "process" else "processes"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ConfirmButton("Close all", onConfirm = onCloseAll, enabled = running > 0)
            }
            if (load != null) {
                Spacer(Modifier.height(16.dp))
                // CPU against every core, memory against the whole phone's.
                val cpuShare by animateFloatAsState((load.cpu / (cores * 100f)).coerceIn(0f, 1f), motion.defaultEffectsSpec(), label = "cpu")
                val memShare by animateFloatAsState(if (total > 0) (load.memory.toFloat() / total).coerceIn(0f, 1f) else 0f, motion.defaultEffectsSpec(), label = "mem")
                Meter("CPU", "${"%.0f".format(load.cpu)}% of one core · $cores cores", cpuShare)
                Spacer(Modifier.height(12.dp))
                Meter("Memory", "${Formatter.formatShortFileSize(context, load.memory)} of ${Formatter.formatShortFileSize(context, total)}", memShare)
            }
        }
    }
}

@Composable
private fun Meter(label: String, value: String, share: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(6.dp))
    LinearWavyProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth())
}

/**
 * One session's share, under its card: a line of totals that opens into its processes, each
 * with End task. Heaviest first.
 */
@Composable
fun SessionLoad(processes: List<GuestProcess>, onEndProcess: (GuestProcess) -> Unit) {
    val context = LocalContext.current
    val motion = MaterialTheme.motionScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val load = Load.of(processes)
    val turn by animateFloatAsState(if (open) 180f else 0f, motion.defaultSpatialSpec(), label = "chevron")
    var ending by remember { mutableStateOf<GuestProcess?>(null) }
    Column(Modifier.fillMaxWidth().animateContentSize(motion.defaultSpatialSpec())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(vertical = 6.dp),
        ) {
            Stat("CPU", "${"%.0f".format(load.cpu)}%")
            Stat("Memory", Formatter.formatShortFileSize(context, load.memory))
            Stat("Processes", "${load.count}")
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, if (open) "Hide processes" else "Show processes", Modifier.rotate(turn))
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                processes.sortedByDescending { it.cpu * 1e9 + it.memory }.forEach { p ->
                    ProcessRow(p, onEnd = { ending = p })
                }
            }
        }
    }
    ending?.let { p ->
        AlertDialog(
            onDismissRequest = { ending = null },
            title = { Text("End ${p.name}?") },
            text = {
                Text(
                    "Process ${p.pid}${if (p.root) ", running as root" else ""}. It's asked to quit, then stopped if it doesn't. " +
                        "Ending a helper (the window manager, D-Bus) can take the app down with it.",
                )
            },
            confirmButton = { TextButton(onClick = { ending = null; onEndProcess(p) }) { Text("End task", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { ending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.padding(end = 20.dp)) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProcessRow(p: GuestProcess, onEnd: () -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                when (p.state) {
                    'R' -> MaterialTheme.colorScheme.primary
                    'Z', 'T', 't' -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                },
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (p.root) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "root",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                "${p.pid} · ${"%.1f".format(p.cpu)}% · ${Formatter.formatShortFileSize(context, p.memory)}" +
                    (if (p.threads > 1) " · ${p.threads} threads" else "") + " · ${stateLabel(p.state)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEnd) { Icon(Icons.Rounded.Close, "End task") }
    }
}

private fun stateLabel(s: Char) = when (s) {
    'R' -> "running"
    'S' -> "sleeping"
    'D' -> "waiting on disk"
    'Z' -> "zombie"
    'T', 't' -> "stopped"
    'I' -> "idle"
    else -> "$s"
}

/** What a session id outside the launcher's sessions is: the Files tab's shells, apt's jobs. */
fun backgroundLabel(session: String): String = when {
    session == "files" -> "Files tab"
    session.startsWith("files-op") -> "Files: copying or extracting"
    session.startsWith("q") && session.drop(1).all(Char::isDigit) -> "Installs (apt)"
    else -> "Session $session"
}
