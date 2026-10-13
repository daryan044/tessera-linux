@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import com.daryan.tessera.container.PhantomLimit
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
                    ) { Text(m.label) }
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
        item {
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
                body = if (status.su != null) "Found ${status.su}. Chroot mode can enforce every permission with the kernel."
                else "Not rooted. PRoot mode still works, but its permissions are best-effort.",
            )
        }
        if (status.phantomProcessLimit) {
            item {
                PhantomProcessItem(rooted = status.su != null, onLift = onLiftPhantomLimit)
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
