// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalLayoutApi::class)

package com.daryan.tessera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daryan.tessera.container.Capability
import com.daryan.tessera.container.ContainerBackend
import com.daryan.tessera.container.Enforcement
import com.daryan.tessera.container.FsGrant
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.Permissions

/** Android folders offered for sharing, and where each appears in the container. */
private val SHARED_FOLDERS = listOf(
    "Downloads" to "Download",
    "Documents" to "Documents",
    "Pictures" to "Pictures",
    "Camera" to "DCIM",
    "Music" to "Music",
    "Movies" to "Movies",
)
private const val STORAGE = "/storage/emulated/0"

/**
 * An app's permissions, each with how well the current engine can hold the app to it, and
 * changeable: network, sound and root as switches, shared folders and devices as lists.
 * Changes apply from the app's next launch.
 */
@Composable
fun PermissionEditor(
    app: PermissionManifest,
    backend: ContainerBackend,
    /** Whether the user changed this app's permissions from what its manifest asks for. */
    changed: Boolean,
    /** Applies new permissions; returns what's wrong with them, if anything. */
    onChange: (Permissions) -> List<String>,
    onReset: () -> Unit,
) {
    val p = app.permissions
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var addingFolder by remember { mutableStateOf(false) }
    var addingDevice by remember { mutableStateOf(false) }
    fun change(next: Permissions) {
        problems = onChange(next)
    }
    fun pill(cap: Capability, restricts: Boolean) = if (restricts) backend.enforcement(cap, app) else null

    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            SwitchRow("Network", if (p.network) "Internet access" else "No network", p.network, pill(Capability.NETWORK, !p.network)) {
                change(p.copy(network = it))
            }
            Divider()
            SwitchRow("Sound", if (p.audio) "Can play sound" else "Muted", p.audio, pill(Capability.AUDIO, !p.audio)) {
                change(p.copy(audio = it))
            }
            Divider()
            SwitchRow(
                "USB drives and SD cards",
                if (p.drives) "Each drive plugged in when it opens shows up in /media/user" else "Can't see plugged-in drives",
                p.drives, null,
            ) { change(p.copy(drives = it)) }
            Divider()
            SwitchRow(
                "All USB devices",
                when {
                    !p.usb -> "Only the devices listed under Devices"
                    backend.name == "PRoot" -> "Those plugged in when it opens, each asked of Android. One that reconnects (a phone rebooting into fastboot) is lost; chroot mode keeps it"
                    p.root -> "Every USB device, as a PC sees them, including ones that reconnect. For fastboot, adb, heimdall, edl"
                    else -> "Every USB device, including ones that reconnect. Devices Android has opened itself also need Run as root"
                },
                p.usb, pill(Capability.DEVICES, true).takeIf { p.usb },
            ) { change(p.copy(usb = it)) }
            Divider()
            SwitchRow(
                "Control this phone (adb)",
                when {
                    !p.adb -> "adb inside reaches only devices plugged into this phone"
                    backend.name == "Chroot" && !p.network -> "Needs Network too: without it the app is on a network of its own"
                    else -> "adb inside reaches this phone too, as adb from a PC would: apps, settings, files. Needs root; the phone asks to allow it once"
                },
                p.adb, null,
            ) { change(if (it) p.copy(adb = true, network = true) else p.copy(adb = false)) }
            Divider()
            SwitchRow(
                "Run as root",
                if (p.root) "Root inside the container. In chroot mode this weakens every other limit." else "Runs as a normal user",
                p.root, null,
            ) { change(p.copy(root = it)) }
            Divider()
            InfoRow(
                "Display",
                if (p.display) "Its own window and display" else "Terminal only",
                pill(Capability.DISPLAY, true),
            )
            Divider()

            ListSection(
                title = "Files",
                empty = "Only its own files",
                items = p.filesystem.map { g ->
                    val name = g.path.removePrefix("$STORAGE/").ifEmpty { "All storage" }
                    "$name → ${g.mountAt}" + if (g.readOnly) " (read-only)" else ""
                },
                enforcement = pill(Capability.FILESYSTEM, true),
                addLabel = "Share a folder",
                onAdd = { addingFolder = true },
                onRemove = { i -> change(p.copy(filesystem = p.filesystem.filterIndexed { j, _ -> j != i })) },
            )
            Divider()
            ListSection(
                title = "Devices",
                empty = if (backend.name == "PRoot") "No hardware access. USB devices added here are opened with Android's permission when the app starts"
                else "No hardware access",
                items = p.devices,
                enforcement = pill(Capability.DEVICES, true),
                addLabel = "Add a device",
                onAdd = { addingDevice = true },
                onRemove = { i -> change(p.copy(devices = p.devices.filterIndexed { j, _ -> j != i })) },
            )
        }
    }
    problems.forEach {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(
            "Changes apply the next time ${app.name} opens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (changed) TextButton(onClick = { problems = emptyList(); onReset() }) { Text("Reset") }
    }

    if (addingFolder) {
        FolderDialog(
            taken = p.filesystem.map { it.mountAt },
            onDismiss = { addingFolder = false },
            onAdd = { grant -> addingFolder = false; change(p.copy(filesystem = p.filesystem + grant)) },
        )
    }
    if (addingDevice) {
        DeviceDialog(
            taken = p.devices,
            onDismiss = { addingDevice = false },
            onAdd = { path -> addingDevice = false; change(p.copy(devices = p.devices + path)) },
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enforcement: Enforcement?, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                enforcement?.let { Spacer(Modifier.width(8.dp)); EnforcementPill(it) }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun InfoRow(title: String, detail: String, enforcement: Enforcement?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        enforcement?.let { Spacer(Modifier.width(12.dp)); EnforcementPill(it) }
    }
}

@Composable
private fun ListSection(
    title: String,
    empty: String,
    items: List<String>,
    enforcement: Enforcement?,
    addLabel: String,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            enforcement?.let { EnforcementPill(it); Spacer(Modifier.width(8.dp)) }
        }
        if (items.isEmpty()) {
            Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(i) }) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp)) }
            }
        }
        AssistChip(
            onClick = onAdd,
            label = { Text(addLabel) },
            leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Pick an Android folder (or type a path), where it shows up in the container, and read-only. */
@Composable
private fun FolderDialog(taken: List<String>, onDismiss: () -> Unit, onAdd: (FsGrant) -> Unit) {
    var host by remember { mutableStateOf("$STORAGE/Download") }
    var mountAt by remember { mutableStateOf("/home/user/Downloads") }
    var readOnly by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share a folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SHARED_FOLDERS.forEach { (name, dir) ->
                        FilterChip(
                            selected = host == "$STORAGE/$dir",
                            onClick = { host = "$STORAGE/$dir"; mountAt = "/home/user/$name" },
                            label = { Text(name) },
                        )
                    }
                    FilterChip(
                        selected = host == STORAGE,
                        onClick = { host = STORAGE; mountAt = "/mnt/android" },
                        label = { Text("All storage") },
                    )
                }
                OutlinedTextField(host, { host = it }, label = { Text("Android folder") }, singleLine = true)
                OutlinedTextField(mountAt, { mountAt = it }, label = { Text("Appears in Linux at") }, singleLine = true)
                if (mountAt.trimEnd('/') in taken) {
                    Text("Something is already shared there.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Read-only", Modifier.weight(1f))
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(FsGrant(host.trimEnd('/').ifEmpty { "/" }, mountAt.trimEnd('/'), readOnly)) },
                enabled = host.isNotBlank() && mountAt.isNotBlank() && mountAt.trimEnd('/') !in taken,
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a plugged-in USB device or the GPU, or type a device path. */
@Composable
private fun DeviceDialog(taken: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val devices = remember { com.daryan.tessera.container.HostDevices.list(context).filterNot { it.path in taken } }
    var path by remember { mutableStateOf("/dev/") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (devices.isEmpty()) {
                    Text("No USB devices plugged in.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                devices.forEach { d ->
                    Surface(onClick = { onAdd(d.path) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.label, style = MaterialTheme.typography.titleSmall)
                            Text(d.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(path, { path = it }, label = { Text("Or a device path") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(path.trim()) }, enabled = path.trim().length > 5 && path.trim() !in taken) { Text("Add path") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun TextDialog(title: String, label: String, initial: String, hint: String?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true)
                hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
