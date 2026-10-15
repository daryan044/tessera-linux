// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.tessera.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.files.DebInfo
import com.daryan.tessera.files.FileBrowser
import com.daryan.tessera.files.FileEntry
import com.daryan.tessera.files.OpenOptions
import com.daryan.tessera.store.DesktopEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A Linux app that opens a file: its launcher entry when it has one, for the icon. */
data class LinuxOpener(val desktopFile: String, val entry: DesktopEntry, val app: PermissionManifest?)

/**
 * Tapping a file: what it is, then the ways to open it. Quick actions first (edit as text,
 * extract, install a .deb), then the Linux apps for its type, then Android's.
 */
@Composable
fun OpenWithSheet(
    b: FileBrowser,
    entry: FileEntry,
    /** Finds the launcher entry for a Linux handler, for its icon and permissions. */
    launcherApp: (String, DesktopEntry) -> PermissionManifest?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onExtract: () -> Unit,
    onInstallDeb: (DebInfo) -> Unit,
    onOpenLinux: (LinuxOpener) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var options by remember(entry.path) { mutableStateOf<OpenOptions?>(null) }
    LaunchedEffect(entry.path) { options = b.options(entry) }
    val mime = options?.mimes?.firstOrNull() ?: b.androidMime(entry)
    var android by remember(entry.path) { mutableStateOf<List<AndroidOpener>?>(null) }
    LaunchedEffect(entry.path, mime) { android = androidOpeners(context, mime) }
    var busy by remember { mutableStateOf(false) }

    // Copies the file out, then hands it to [component] (or Android's chooser when null).
    val openInAndroid: (ComponentName?) -> Unit = { component ->
        if (!busy) {
            busy = true
            scope.launch {
                runCatching { b.exportFile(entry) }
                    .onSuccess { file ->
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            if (component != null) context.startActivity(intent.setComponent(component))
                            else context.startActivity(Intent.createChooser(intent, "Open ${entry.name} with"))
                            onDismiss()
                        } catch (_: ActivityNotFoundException) {
                            onMessage("That app can't open it")
                        } catch (e: SecurityException) {
                            onMessage("That app can't open it")
                        }
                    }
                    .onFailure { onMessage(it.message ?: "Couldn't open ${entry.name}") }
                busy = false
            }
        }
    }

    val motion = MaterialTheme.motionScheme
    DraggableSheet(onDismissRequest = onDismiss) { peek ->
        // Grows smoothly as the Linux and Android lists arrive, rather than jumping.
        Column(Modifier.animateContentSize(motion.defaultSpatialSpec()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            // The file itself.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(Formatter.formatShortFileSize(context, entry.size), options?.mimes?.firstOrNull()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) LoadingIndicator(Modifier.size(36.dp))
            }

            val o = options
            if (o == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                Spacer(peek)
                return@Column
            }

            o.deb?.let { deb ->
                Spacer(Modifier.height(16.dp))
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("${deb.pkg} ${deb.version}", style = MaterialTheme.typography.titleSmall)
                        if (deb.summary.isNotEmpty()) Text(deb.summary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { onInstallDeb(deb) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Install package")
                        }
                        Text(
                            "apt installs it with whatever it needs, in the background; follow it under Installs.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (entry.extension == "deb" && o.deb == null) {
                Text(
                    "This .deb couldn't be read, so it can't be installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (o.text || o.archive) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (o.text) FilledTonalButton(onClick = onEdit) {
                        Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Edit as text")
                    }
                    if (o.archive) FilledTonalButton(onClick = onExtract) {
                        Icon(LauncherIcons.Archive, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Extract here")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Heading("Open in Linux", LauncherIcons.Terminal)
            val linux = remember(o) { o.linux.map { (file, e) -> LinuxOpener(file, e, launcherApp(file, e)) } }
            if (linux.isEmpty()) {
                Note(
                    if (o.mimes.isEmpty()) "Linux can't tell what kind of file this is."
                    else "No Linux app here opens ${o.mimes.first()}. Apps for it are in the Store.",
                )
            } else {
                AppTiles(linux.map { l ->
                    Tile(l.entry.name.orEmpty(), { l.app?.let { AppGlyph(it, 28.dp, MaterialTheme.colorScheme.onSecondaryContainer) } ?: Icon(LauncherIcons.Terminal, null) }) {
                        onOpenLinux(l)
                    }
                })
            }

            Spacer(Modifier.height(24.dp))
            Heading("Open in Android", LauncherIcons.Phone)
            val list = android
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
                list.isEmpty() -> Note("No Android app says it opens ${mime ?: "this kind of file"}. Try another one:")
                else -> AppTiles(list.map { a ->
                    Tile(a.label, { Image(a.icon, null, Modifier.size(36.dp)) }) { openInAndroid(a.component) }
                })
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { openInAndroid(null) }.padding(vertical = 12.dp, horizontal = 4.dp),
            ) {
                Icon(Icons.Rounded.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Another Android app…", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(peek)
        }
    }
}

@Composable
private fun Heading(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private class Tile(val label: String, val icon: @Composable () -> Unit, val onClick: () -> Unit)

/** Apps as icon tiles, four or so to a row, like Android's own share sheet. */
@Composable
private fun AppTiles(tiles: List<Tile>) {
    FlowRow(maxItemsInEachRow = 4, modifier = Modifier.fillMaxWidth()) {
        tiles.forEach { t ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = t.onClick)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                ) { t.icon() }
                Spacer(Modifier.height(6.dp))
                Text(t.label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        // Keeps a short last row's tiles the same width as the rest.
        repeat((4 - tiles.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
    }
}

private class AndroidOpener(val label: String, val icon: ImageBitmap, val component: ComponentName)

/** Android's apps for [mime], without Tessera itself. */
private suspend fun androidOpeners(context: Context, mime: String?): List<AndroidOpener> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://${context.packageName}.files/transfer/x"), mime ?: "*/*")
    @Suppress("DEPRECATION")
    val found: List<ResolveInfo> = pm.queryIntentActivities(probe, 0)
    val size = (48 * context.resources.displayMetrics.density).toInt()
    found.filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
        .mapNotNull { r ->
            runCatching {
                AndroidOpener(
                    r.loadLabel(pm).toString(),
                    r.loadIcon(pm).toBitmap(size, size).asImageBitmap(),
                    ComponentName(r.activityInfo.packageName, r.activityInfo.name),
                )
            }.getOrNull()
        }
        .sortedBy { it.label.lowercase() }
}
