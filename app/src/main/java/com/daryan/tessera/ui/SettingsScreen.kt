// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.tessera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.daryan.tessera.BackendMode
import com.daryan.tessera.CustomTab
import com.daryan.tessera.container.Appearance
import com.daryan.tessera.container.ColorMode
import com.daryan.tessera.container.ConfigCatalog
import com.daryan.tessera.container.LinuxTheme
import com.daryan.tessera.container.ConfigFile
import com.daryan.tessera.container.HostStatus
import com.daryan.tessera.container.InstallState
import com.daryan.tessera.store.CatalogState

/** Everything the launcher lets you configure, in one place. Each row hands off to its owner. */
@Composable
fun SettingsScreen(
    /** Why the system files can't be edited, or null when they can. */
    filesUnavailable: String?,
    onOpenFile: (ConfigFile) -> Unit,
    customTabs: List<CustomTab>,
    onAddTab: () -> Unit,
    onRemoveTab: (String) -> Unit,
    catalog: CatalogState,
    onRefreshCatalog: () -> Unit,
    activeInstalls: Int,
    onOpenQueue: () -> Unit,
    onDisplaySettings: () -> Unit,
    /** Null when this phone or build can't use hardware graphics. */
    gpuEnabled: Boolean?,
    onGpuEnabled: (Boolean) -> Unit,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    /** Imported Linux themes, and importing or removing one. */
    linuxThemes: List<LinuxTheme>,
    onImportTheme: () -> Unit,
    onDeleteTheme: (String) -> Unit,
    runningSessions: Int,
    onStopAll: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            Group("System files") {
                if (filesUnavailable != null) Note(filesUnavailable)
                ConfigCatalog.files.forEach { f ->
                    Row(LauncherIcons.Folder, f.title, f.path, onClick = if (filesUnavailable == null) ({ onOpenFile(f) }) else null)
                }
            }

            Group("Launcher") {
                if (customTabs.isEmpty()) Note("No custom tabs yet. Tabs group apps you pick, next to the category tabs.")
                customTabs.forEach { tab ->
                    Row(LauncherIcons.Apps, tab.name, "${tab.appIds.size} apps", action = {
                        IconButton(onClick = { onRemoveTab(tab.name) }) { Icon(Icons.Rounded.Delete, "Remove tab") }
                    })
                }
                Row(Icons.Rounded.Add, "New tab", "Group apps your way", onClick = onAddTab)
            }

            Group("App store") {
                Row(
                    LauncherIcons.Store, "App catalog",
                    when (catalog) {
                        is CatalogState.Ready -> "${catalog.apps.size} apps · updated ${DateUtils.getRelativeTimeSpanString(catalog.updated)}"
                        is CatalogState.Loading -> catalog.step
                        is CatalogState.Failed -> "Couldn't load: ${catalog.message}"
                        CatalogState.Empty -> "Not downloaded yet (about 20 MB)"
                    },
                    action = { IconButton(onClick = onRefreshCatalog) { Icon(Icons.Rounded.Refresh, "Refresh catalog") } },
                )
                Row(LauncherIcons.Store, "Install queue", if (activeInstalls == 0) "Nothing installing" else "$activeInstalls installing or waiting", onClick = onOpenQueue)
            }

            Group("Display") {
                Row(Icons.Rounded.Settings, "X11 display settings", "Resolution, touch mode, keyboard", onClick = onDisplaySettings)
                Row(
                    LauncherIcons.Monitor, "GPU acceleration",
                    when (gpuEnabled) {
                        null -> "Needs an Adreno GPU (Snapdragon); apps draw in software here"
                        true -> "Windowed apps draw with the phone's GPU (OpenGL and Vulkan, through Turnip and Zink). Applies to apps opened from now on"
                        false -> "Off: apps draw in software"
                    },
                    action = gpuEnabled?.let { on -> { androidx.compose.material3.Switch(checked = on, onCheckedChange = onGpuEnabled) } },
                )
            }

            Group("Appearance") {
                Row(
                    LauncherIcons.Palette, "Theme Linux apps",
                    if (appearance.enabled) "Apps take the phone's colors and follow its dark mode, live. Each app can opt out from its long-press menu"
                    else "Off: apps look the way their toolkit draws them by default",
                    action = { androidx.compose.material3.Switch(checked = appearance.enabled, onCheckedChange = { onAppearance(appearance.copy(enabled = it)) }) },
                )
                if (appearance.enabled) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ColorMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = appearance.colorMode == mode,
                                onClick = { onAppearance(appearance.copy(colorMode = mode)) },
                                shape = SegmentedButtonDefaults.itemShape(i, ColorMode.entries.size),
                                // One line whatever the width: with its check mark, a long label
                                // wrapped and pushed the row out of shape.
                            ) { Text(mode.label, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                        }
                    }
                    ThemePicker(appearance, linuxThemes, onAppearance, onImportTheme, onDeleteTheme)
                    if (Build.VERSION.SDK_INT >= 31 && appearance.linuxTheme == null) {
                        Row(
                            LauncherIcons.Palette, "Wallpaper colors",
                            if (appearance.dynamicColor) "Accents and surfaces from your wallpaper, like Android's own apps" else "A fixed blue palette",
                            action = { androidx.compose.material3.Switch(checked = appearance.dynamicColor, onCheckedChange = { onAppearance(appearance.copy(dynamicColor = it)) }) },
                        )
                    }
                }
            }

            Group("Sessions") {
                Row(
                    LauncherIcons.Terminal, "Running apps", if (runningSessions == 0) "Nothing running" else "$runningSessions running",
                    action = if (runningSessions > 0) ({ TextButton(onClick = onStopAll) { Text("Stop all") } }) else null,
                )
            }


            Group("Tessera") {
                Row(Icons.Rounded.Info, "About", "Version, creator and licenses", onClick = onAbout)
            }
        }
    }
}

/**
 * Which theme Linux apps (and Tessera) wear: the phone's own colors, or an imported Linux theme,
 * each shown with its colors. Import adds themes from an archive; a theme's light and dark
 * variants count as one, and System, Light or Dark above picks between them.
 */
@Composable
private fun ThemePicker(
    appearance: Appearance,
    themes: List<LinuxTheme>,
    onAppearance: (Appearance) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
) {
    val families = themes.groupBy { it.family }
    Text(
        "Theme",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
    ThemeChoice(
        title = "Phone colors",
        detail = "Material You, from your wallpaper",
        selected = appearance.linuxTheme == null,
        swatches = listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface),
        onClick = { onAppearance(appearance.copy(linuxTheme = null)) },
    )
    families.forEach { (family, variants) ->
        val shown = variants.first()
        ThemeChoice(
            title = family,
            detail = when {
                variants.any { it.dark } && variants.any { !it.dark } -> "Light and dark"
                shown.dark -> "Dark"
                else -> "Light"
            } + (shown.radius?.let { " · ${if (it == 0) "square" else "${it}px"} corners" } ?: ""),
            selected = appearance.linuxTheme == family,
            swatches = listOf(shown.colors.background, shown.colors.accent, shown.colors.foreground).map { Color(0xFF000000.toInt() or it) },
            onClick = { onAppearance(appearance.copy(enabled = true, linuxTheme = family)) },
            onDelete = { onDelete(family) },
        )
    }
    Row(
        Icons.Rounded.Add, "Import a Linux theme",
        "A GTK theme as shared online (.tar.xz, .tar.gz or .zip). Linux apps use it as it is; Tessera takes its colors and corners",
        onClick = onImport,
    )
}

@Composable
private fun ThemeChoice(
    title: String,
    detail: String,
    selected: Boolean,
    swatches: List<Color>,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    ListItem(
        onClick = onClick,
        leadingContent = { androidx.compose.material3.RadioButton(selected = selected, onClick = null) },
        supportingContent = { Text(detail) },
        trailingContent = {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                swatches.forEach { c ->
                    androidx.compose.foundation.layout.Box(
                        Modifier.padding(start = 4.dp).size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape),
                    )
                }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Remove theme") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(title) }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun Row(icon: ImageVector, title: String, detail: String, onClick: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    if (onClick != null) {
        ListItem(
            onClick = onClick,
            leadingContent = { Icon(icon, null) },
            trailingContent = action,
            supportingContent = { Text(detail) },
            colors = colors,
        ) { Text(title) }
    } else {
        ListItem(
            leadingContent = { Icon(icon, null) },
            trailingContent = action,
            supportingContent = { Text(detail) },
            colors = colors,
        ) { Text(title) }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}
