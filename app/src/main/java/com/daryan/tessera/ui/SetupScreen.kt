@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import com.daryan.tessera.container.PhantomLimit
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.daryan.tessera.BackendMode
import com.daryan.tessera.container.Distro
import com.daryan.tessera.container.HostStatus
import com.daryan.tessera.container.InstallState

private enum class ItemState { Done, Todo, Warn }

@Composable
fun SetupScreen(
    status: HostStatus?,
    mode: BackendMode,
    install: InstallState,
    padding: PaddingValues,
    onMode: (BackendMode) -> Unit,
    onRefresh: () -> Unit,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onLiftPhantomLimit: () -> Unit,
    diagnosticsEnabled: Boolean,
    onDiagnostics: (Boolean) -> Unit,
    /** Puts the notes and recent session logs on the clipboard, for any assistant. */
    onCopyDiagnostics: () -> Unit,
    onShowIntro: () -> Unit,
    /** What Droidspaces mode can do with its containers; null where there's no such mode. */
    droidspaces: DroidspacesActions? = null,
    /** What the picked Droidspaces container is being made to do ("Starting web"), while it does it. */
    containerBusy: String? = null,
) {
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
        item {
            Text("Container engine", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                BackendMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { onMode(m) },
                        enabled = m == BackendMode.PROOT || status?.su != null,
                        shape = SegmentedButtonDefaults.itemShape(i, BackendMode.entries.size),
                    ) { Text(m.short, maxLines = 1) }
                }
            }
        }

        if (status == null) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                ) {
                    ContainedLoadingIndicator(Modifier.size(72.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Checking your device", style = MaterialTheme.typography.bodyLarge)
                }
            }
            return@LazyColumn
        }

        item {
            SetupItem(
                state = if (status.prootBinary) ItemState.Done else ItemState.Todo,
                title = "PRoot engine",
                body = if (status.prootBinary) "Bundled and ready."
                else "This build doesn't include libproot.so yet. See docs/native-binaries.md.",
            )
        }
        if (mode == BackendMode.DROIDSPACES) {
            item { DroidspacesEngineItem(status) }
            item { DroidspacesContainerItem(status, droidspaces, containerBusy) }
        } else item {
            val distro = Distro.DEBIAN
            // Each mode has its own system: in app storage for PRoot, under /data/local for chroot.
            val chroot = mode == BackendMode.CHROOT
            val installed = if (chroot) status.chrootRootfs == true else status.prootRootfs
            SetupItem(
                state = if (installed) ItemState.Done else ItemState.Todo,
                title = if (chroot) "Linux system (chroot)" else "Linux system",
                body = when {
                    installed && chroot -> "${distro.label} is in place at /data/local/linux/debian."
                    installed -> "${distro.label} is installed."
                    chroot && status.chrootRootfs == null -> "Couldn't check /data/local/linux/debian; was root access refused?"
                    install is InstallState.Failed -> "Install failed: ${install.message}"
                    install.running -> "Installing ${distro.label}. You can leave the app; it keeps going."
                    chroot -> "Chroot mode has no ${distro.label} yet. It's installed as root at /data/local/linux/debian, about ${distro.downloadMb} MB to download."
                    else -> "${distro.label} isn't installed yet. About ${distro.downloadMb} MB to download."
                },
            ) {
                if (!installed && !(chroot && status.chrootRootfs == null)) {
                    InstallControls(install, if (chroot) status.su != null else status.prootBinary, onInstall, onCancelInstall)
                }
            }
        }
        item {
            SetupItem(
                state = if (status.su != null) ItemState.Done else ItemState.Warn,
                title = "Root access (optional)",
                body = if (status.su != null) "Found ${status.su}. Chroot and Droidspaces modes can enforce every permission with the kernel."
                else "Not rooted. PRoot mode still works, but its permissions are best-effort.",
            )
        }
        if (status.phantomProcessLimit) {
            item {
                PhantomProcessItem(rooted = status.su != null, onLift = onLiftPhantomLimit)
            }
        }
        item {
            SetupItem(
                state = if (diagnosticsEnabled) ItemState.Done else ItemState.Warn,
                title = "Help and diagnostics",
                body = if (diagnosticsEnabled) {
                    "Tessera keeps notes on how it works, and what the last few apps printed as " +
                        "they closed. The Claude Code shortcut reads both, so it can tell you why " +
                        "an app won't start."
                } else {
                    "Off. Nothing is kept, so anyone helping you has only what you can describe."
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Keep what apps print",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = diagnosticsEnabled, onCheckedChange = onDiagnostics)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onCopyDiagnostics) { Text("Copy diagnostics") }
                        TextButton(onClick = onShowIntro) { Text("Show intro") }
                    }
                    Text(
                        "Copy puts those notes and the recent logs on your clipboard, to paste " +
                            "into any AI you like. It names your apps and folders, so read it " +
                            "before sending it anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = onRefresh, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Check again")
                }
            }
        }
    }
}

/** What Setup can do with Droidspaces containers; the view model's. */
class DroidspacesActions(
    val select: (String) -> Unit,
    val start: () -> Unit,
    val stop: () -> Unit,
    val prepare: () -> Unit,
)

@Composable
private fun DroidspacesEngineItem(status: HostStatus) {
    val found = status.droidspacesBinary
    SetupItem(
        state = when {
            found != null -> ItemState.Done
            status.droidspacesContainers == null -> ItemState.Todo
            else -> ItemState.Warn
        },
        title = "Droidspaces",
        body = when {
            status.su == null -> "Droidspaces needs root, and none was found."
            status.droidspacesContainers == null -> "Looking for Droidspaces."
            found == null -> "Couldn't find Droidspaces. Install it (github.com/ravindu644/Droidspaces-OSS), " +
                "make a Debian or Ubuntu container in it, then check again."
            else -> "Found at $found."
        },
    )
}

@Composable
private fun DroidspacesContainerItem(status: HostStatus, actions: DroidspacesActions?, busy: String?) {
    var confirm by remember { mutableStateOf(false) }
    if (status.droidspacesBinary == null || actions == null) return
    val containers = status.droidspacesContainers.orEmpty()
    val picked = status.droidspacesSelected
    SetupItem(
        state = when {
            picked != null && picked.bridged && picked.running -> ItemState.Done
            picked != null -> ItemState.Warn
            else -> ItemState.Todo
        },
        title = "Container",
        body = when {
            containers.isEmpty() -> "Droidspaces has no containers yet. Make a Debian or Ubuntu one in the Droidspaces app, then check again."
            picked == null -> "Pick the container Tessera runs apps in."
            !picked.bridged -> "${picked.name} needs a one-time setup before Tessera can use it."
            !picked.running -> "${picked.name} is stopped."
            else -> "${picked.name} is running. Apps open inside it."
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(8.dp))
            containers.forEach { c ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(enabled = busy == null) { actions.select(c.name) }
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = c.name == status.droidspacesName, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOf(
                                if (c.running) "running" else "stopped",
                                if (c.bridged) "set up" else "needs setup",
                                c.config.netMode,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (busy != null) {
                Spacer(Modifier.height(8.dp))
                Text("$busy…", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (picked != null) {
                Spacer(Modifier.height(8.dp))
                when {
                    !picked.bridged -> Button(onClick = { confirm = true }) { Text("Set up for Tessera") }
                    !picked.running -> Button(onClick = actions.start) { Text("Start") }
                    else -> FilledTonalButton(onClick = actions.stop) { Text("Stop") }
                }
                val hints = listOfNotNull(
                    "Phone storage isn't in this container, so apps won't see Android folders. Turn on Android storage for it in Droidspaces."
                        .takeIf { !picked.config.androidStorage },
                    "This container has no network, so apps in it won't either.".takeIf { picked.config.netMode == "none" },
                    "Tessera installs apps with apt: use a Debian or Ubuntu container.",
                )
                hints.forEach {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirm && picked != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Set up ${picked.name}?") },
            text = {
                Text(
                    "Tessera adds two folders to this container's settings in Droidspaces, so it can start apps inside it; " +
                        "a copy of the original settings is kept. " +
                        (if (picked.running) "The container restarts now, which ends everything running in it. " else "") +
                        "Tessera then installs graphics and app libraries in it with apt, and its GPU driver and theme files " +
                        "in /opt/tessera-gpu and /opt/tessera-theme.\n\n" +
                        "Apps run as Tessera's own user, so the container's \"user\" account takes Tessera's uid and " +
                        "/home/user is handed over to it. Best on a container made for Tessera, not one you work in.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; actions.prepare() }) { Text(if (picked.running) "Set up and restart" else "Set up") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SetupItem(
    state: ItemState,
    title: String,
    body: String,
    extra: @Composable (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.extraLarge, color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            val (shape, bg, fg) = when (state) {
                ItemState.Done -> Triple(MaterialShapes.Cookie9Sided, c.tertiaryContainer, c.onTertiaryContainer)
                ItemState.Warn -> Triple(MaterialShapes.Sunny, c.secondaryContainer, c.onSecondaryContainer)
                ItemState.Todo -> Triple(MaterialShapes.Circle, c.surfaceContainerHighest, c.onSurfaceVariant)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).clip(shape.toShape()).background(bg),
            ) {
                when (state) {
                    ItemState.Done -> Icon(Icons.Rounded.Check, null, tint = fg)
                    ItemState.Warn -> Icon(Icons.Rounded.Warning, null, Modifier.size(20.dp), tint = fg)
                    ItemState.Todo -> Box(Modifier.size(10.dp).clip(MaterialShapes.Circle.toShape()).background(fg))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                extra?.invoke()
            }
        }
    }
}

@Composable
private fun InstallControls(
    install: InstallState,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    if (install.running) {
        val label = when (install) {
            InstallState.Resolving -> "Finding the image"
            is InstallState.Downloading ->
                "Downloading ${install.done / 1_000_000} of ${install.total / 1_000_000} MB"
            InstallState.Extracting -> "Unpacking files"
            InstallState.Configuring -> "Setting up"
            else -> ""
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        if (install is InstallState.Downloading && install.total > 0) {
            val progress by animateFloatAsState(
                install.done.toFloat() / install.total, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "dl",
            )
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    } else {
        Button(onClick = onInstall, enabled = canInstall) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (install is InstallState.Failed) "Try again" else "Install")
        }
    }
}

@Composable
private fun PhantomProcessItem(rooted: Boolean, onLift: () -> Unit) {
    val context = LocalContext.current
    val command = "adb shell \"" + PhantomLimit.COMMANDS.joinToString("; ") + "\""
    SetupItem(
        state = ItemState.Warn,
        title = "Background process limit",
        body = if (rooted) "Android 12+ kills apps that run more than 32 processes, which a busy Linux system does. With root, this can be lifted here."
        else "Android 12+ kills apps that run more than 32 processes, which a busy Linux system does. Run this once from a computer:",
    ) {
        if (rooted) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onLift) { Text("Lift it") }
            return@SetupItem
        }
        Spacer(Modifier.height(10.dp))
        Text(
            command,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp),
        )
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("adb command", command))
        }) { Text("Copy command") }
    }
}
