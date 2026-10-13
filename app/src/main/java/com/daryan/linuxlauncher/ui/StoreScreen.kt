// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.daryan.linuxlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daryan.linuxlauncher.store.CatalogState
import com.daryan.linuxlauncher.store.QueueJob
import com.daryan.linuxlauncher.store.StoreApp
import com.daryan.linuxlauncher.store.StoreRegistry
import java.io.File

private val CATEGORIES = listOf("All") + StoreRegistry.CATEGORY_LABELS.values.distinct()

@Composable
fun StoreScreen(
    catalog: CatalogState,
    installed: Set<String>?,
    padding: PaddingValues,
    iconFor: (StoreApp) -> File?,
    onRetry: () -> Unit,
    onOpenApp: (StoreApp) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    val contentPadding = PaddingValues(
        start = 16.dp, end = 16.dp,
        top = padding.calculateTopPadding() + 8.dp,
        bottom = padding.calculateBottomPadding() + 24.dp,
    )

    when (catalog) {
        is CatalogState.Ready -> {
            val shown = remember(catalog.apps, query, category) {
                val q = query.trim().lowercase()
                catalog.apps.filter { app ->
                    (category == "All" || StoreRegistry.launcherCategory(app.categories) == category) &&
                        (q.isEmpty() || q in app.name.lowercase() || q in app.summary.lowercase() || q in app.pkg)
                }.let { list ->
                    // Name matches first when searching.
                    if (q.isEmpty()) list else list.sortedByDescending { it.name.lowercase().startsWith(q) }
                }
            }
            LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                item(key = "search") {
                    SearchField(query, onChange = { query = it })
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    ) {
                        CATEGORIES.forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                        }
                    }
                    Text(
                        "${shown.size} apps from Debian",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                items(shown, key = { it.id }) { app ->
                    StoreRow(app, iconFor(app), installed?.contains(app.pkg) == true, Modifier.animateItem()) { onOpenApp(app) }
                }
            }
        }
        else -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
        ) {
            when (catalog) {
                is CatalogState.Loading -> {
                    ContainedLoadingIndicator(Modifier.size(72.dp))
                    Spacer(Modifier.height(24.dp))
                    Text(catalog.step, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    catalog.progress?.let { p -> LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "First time only: about 20 MB of app listings and icons from Debian.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                is CatalogState.Failed -> {
                    Text("Couldn't load the store", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(catalog.message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Try again")
                    }
                }
                else -> ContainedLoadingIndicator(Modifier.size(72.dp))
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Clear, "Clear") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StoreRow(app: StoreApp, icon: File?, installed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
            StoreIcon(icon, 52)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (installed) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Check, "Installed", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun StoreIcon(file: File?, sizeDp: Int) {
    val bmp = rememberFileBitmap(file?.path)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp / 4).dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (bmp != null) Image(bmp, null, Modifier.size((sizeDp * 0.85).dp))
        else Icon(LauncherIcons.Apps, null, Modifier.size((sizeDp / 2).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An app's store page: what it is, and Install / Open / Uninstall. */
@Composable
fun StoreAppSheet(
    app: StoreApp,
    icon: File?,
    /** Downloads the app's screenshots; empty when it has none. */
    screenshots: suspend () -> List<File>,
    /** null when unknown (container's package list not readable yet). */
    installed: Boolean?,
    /** Why installing isn't possible right now, or null. */
    blocker: String?,
    /** This app's job in the install queue, while it's waiting or running. */
    job: QueueJob?,
    onInstall: () -> Unit,
    onOpen: (() -> Unit)?,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoreIcon(icon, 88)
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(app.name, style = MaterialTheme.typography.headlineSmall)
                    Text(app.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))

            if (job != null) {
                FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(job.statusText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (installed == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onOpen != null) {
                        Button(onClick = onOpen, modifier = Modifier.weight(1f).height(52.dp)) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open")
                        }
                    }
                    OutlinedButton(onClick = onUninstall, enabled = blocker == null, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("Uninstall")
                    }
                }
            } else {
                Button(onClick = onInstall, enabled = blocker == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (blocker == null) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(blocker ?: "Install")
                }
                if (blocker == null) {
                    Text(
                        "Installs ${app.pkg} from Debian ${app.component} with apt, in a terminal you can watch, " +
                            "then adds it to your apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            val shots by produceState(emptyList<File>(), app.id) { value = screenshots() }
            if (shots.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shots, key = { it.path }) { file ->
                        val bitmap = rememberFileBitmap(file.path)
                        val shape = MaterialTheme.shapes.large
                        if (bitmap != null) {
                            Image(
                                bitmap,
                                contentDescription = "Screenshot",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.height(200.dp)
                                    .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                                    .clip(shape),
                            )
                        } else {
                            Box(Modifier.height(200.dp).width(320.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                        }
                    }
                }
            }

            if (app.categories.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.categories.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }
            if (app.description.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("About", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(app.description, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Package ${app.pkg} · Debian ${app.component}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
