// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalLayoutApi::class)

package com.daryan.tessera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daryan.tessera.HomeIconSize
import com.daryan.tessera.HomeLayout
import com.daryan.tessera.HomeLayoutStore
import com.daryan.tessera.HomeSort
import com.daryan.tessera.container.PermissionManifest

/** The home screen's settings: its grid, how it's sorted, and the apps left off it. */
@Composable
fun HomeSettingsSheet(
    layout: HomeLayout,
    store: HomeLayoutStore,
    apps: List<PermissionManifest>,
    onEditLayout: () -> Unit,
    onWidgets: () -> Unit,
    onDismiss: () -> Unit,
) {
    DraggableSheet(onDismissRequest = onDismiss) { peek ->
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Home settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = { onDismiss(); onEditLayout() }, modifier = Modifier.fillMaxWidth()) {
                Text("Drag icons into place")
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { onDismiss(); onWidgets() }, modifier = Modifier.fillMaxWidth()) {
                Text("Add a widget")
            }

            Section("Grid")
            Choices(HomeLayout.COLUMNS, layout.columns, { if (it == 0) "Fit screen" else "$it across" }, store::setColumns)

            Section("Icon size")
            Choices(HomeIconSize.entries, layout.iconSize, { it.label }, store::setIconSize)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show names", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = layout.showLabels, onCheckedChange = store::setShowLabels)
            }
            Spacer(peek)

            Section("Sort by")
            Choices(HomeSort.entries, layout.sort, { it.label }, store::setSort)
            Text(
                "Pinned apps stay first. “Your order” is what you drag, kept for each tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (layout.order.isNotEmpty()) {
                TextButton(onClick = store::resetOrder) { Text("Forget my order") }
            }

            Section("Hidden apps")
            val hidden = apps.filter { it.id in layout.hidden }.sortedBy { it.name.lowercase() }
            if (hidden.isEmpty()) {
                Text(
                    "None. Hold an app and choose Hide; search still finds it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            hidden.forEach { app ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(app.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { store.setHidden(app.id, false) }) { Text("Show") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onPick(option) }, label = { Text(label(option)) })
        }
    }
}

/** Colors an icon can have, besides the theme's. */
private val ICON_COLORS = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5,
    0xFF00ACC1, 0xFF00897B, 0xFF43A047, 0xFFC0CA33, 0xFFFDD835, 0xFFFB8C00,
    0xFF6D4C41, 0xFF546E7A, 0xFF212121, 0xFFF5F5F5,
).map { Color(it) }

/** Picks one app's icon color. An app with its own icon keeps it: the color goes behind. */
@Composable
fun IconColorSheet(
    app: PermissionManifest,
    current: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    DraggableSheet(onDismissRequest = onDismiss) { peek ->
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (container, onContainer) = appColors(app)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(56.dp).clip(appShape(app)).background(container),
                ) { AppGlyph(app, 24.dp, onContainer) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Icon color", style = MaterialTheme.typography.titleLarge)
                    Text(app.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(20.dp))
            FlowRow(
                maxItemsInEachRow = 6,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ICON_COLORS.forEachIndexed { i, color ->
                    val argb = color.toArgb()
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onPick(argb) }
                            .semantics { contentDescription = "Color ${i + 1}" },
                    ) {
                        if (current == argb) Icon(Icons.Rounded.Check, "Chosen", tint = contentColorOn(color))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { onPick(null) }, enabled = current != null) { Text("Use the theme's color") }
            Spacer(peek)
        }
    }
}
