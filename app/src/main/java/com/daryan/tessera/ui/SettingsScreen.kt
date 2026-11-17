// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.tessera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.format.DateUtils
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
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.daryan.tessera.BackendMode
import com.daryan.tessera.CustomTab
import com.daryan.tessera.UpdateState
import com.daryan.tessera.X11Input
import com.daryan.tessera.container.Appearance
import com.daryan.tessera.container.ColorMode
import com.daryan.tessera.container.ConfigCatalog
import com.daryan.tessera.container.LinuxTheme
import com.daryan.tessera.container.ConfigFile
import com.daryan.tessera.container.HostStatus
import com.daryan.tessera.container.InstallState
import com.daryan.tessera.store.CatalogState

/**
 * Everything that is set once, in one place, from what people change most to what they rarely
 * touch: how Linux apps look, how they're shown and touched, Tessera's own screens, the store,
 * the Linux system's files, and Tessera itself. Each row hands off to its owner.
 */
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
    /** Whether Flathub installs flush each file to storage as they write it. */
    flathubFsync: Boolean,
    onFlathubFsync: (Boolean) -> Unit,
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
    /** Whether web links Linux apps open go to Android's apps (#17). */
    androidLinks: Boolean,
    onAndroidLinks: (Boolean) -> Unit,
    /** Low power mode: Tessera's own screens without blur (#21). */
    lowPower: Boolean,
    onLowPower: (Boolean) -> Unit,
    /** What the update check found, this build's version, and the check's switch and button. */
    updates: UpdateState,
    currentVersion: String,
    onUpdateChecks: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenUpdate: () -> Unit,
    onIntro: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit,
) {
    val back = rememberBackSwipe(onBack = onClose)
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.backSwipePage(back),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            Group("How Linux apps look") {
                Row(
                    LauncherIcons.Palette, "Match the phone's theme",
                    if (appearance.enabled) "Apps take the phone's colors and dark mode. Hold an app's icon to leave it out"
                    else "Off: apps look the way they do on a Linux desktop",
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
                            if (appearance.dynamicColor) "Colors come from your wallpaper, like Android's own apps" else "A fixed blue palette",
                            action = { androidx.compose.material3.Switch(checked = appearance.dynamicColor, onCheckedChange = { onAppearance(appearance.copy(dynamicColor = it)) }) },
                        )
                    }
                }
            }

            Group("Display") {
                Row(
                    LauncherIcons.Monitor, "GPU acceleration",
                    when (gpuEnabled) {
                        null -> "Not available on this phone: apps draw without the GPU"
                        true -> "Apps draw with the phone's GPU. Hold an app's icon to turn it off for that app"
                        false -> "Off: apps draw without the GPU, slower but always right"
                    },
                    action = gpuEnabled?.let { on -> { androidx.compose.material3.Switch(checked = on, onCheckedChange = onGpuEnabled) } },
                )
                Row(Icons.Rounded.Settings, "Size and resolution", "How big Linux windows draw, and more display options", onClick = onDisplaySettings)
            }

            Group("Touch in Linux windows") {
                Note("What a finger does in apps that haven't been given their own mode. An app's own is set from its window's panel.")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    InputChoices(
                        mode = X11Input.defaultMode(context),
                        onMode = { X11Input.setDefaultMode(context, it) },
                        longPress = X11Input.longPressRightClick(context),
                        onLongPress = { X11Input.setLongPressRightClick(context, it) },
                    )
                }
            }

            Group("Linux and Android") {
                Row(
                    LauncherIcons.Apps, "Open links on the phone",
                    if (androidLinks) "A web link tapped in a Linux app opens Android's Open with: the phone's browsers, or Open in Linux for a Linux one. Applies to apps opened from now on"
                    else "Off: links open in a Linux browser, if one is installed",
                    action = { androidx.compose.material3.Switch(checked = androidLinks, onCheckedChange = onAndroidLinks) },
                )
                Note("Either way, Linux apps list Open in Android among the apps a file or link can be opened with, and phone numbers, mail addresses and map links go to the phone.")
            }

            Group("Tessera's screens") {
                Row(
                    LauncherIcons.Monitor, "Low power mode",
                    if (lowPower) "On: no blur behind Tessera's bars, simpler transitions"
                    else "For slow phones and tablets: turns off blur and other costly effects in Tessera. Linux apps aren't changed",
                    action = { androidx.compose.material3.Switch(checked = lowPower, onCheckedChange = onLowPower) },
                )
                customTabs.forEach { tab ->
                    Row(LauncherIcons.Apps, "Tab: ${tab.name}", "${tab.appIds.size} apps", action = {
                        IconButton(onClick = { onRemoveTab(tab.name) }) { Icon(Icons.Rounded.Delete, "Remove tab") }
                    })
                }
                Row(Icons.Rounded.Add, "New tab", "Your own group of apps, next to the category tabs", onClick = onAddTab)
            }

            Group("Store and installs") {
                Row(LauncherIcons.Store, "Installs", if (activeInstalls == 0) "Nothing installing. Tap for what was installed and removed" else "$activeInstalls installing or waiting", onClick = onOpenQueue)
                Row(
                    LauncherIcons.Store, "Debian's app list",
                    when (catalog) {
                        is CatalogState.Ready -> "${catalog.apps.size} apps · updated ${DateUtils.getRelativeTimeSpanString(catalog.updated)}"
                        is CatalogState.Loading -> catalog.step
                        is CatalogState.Failed -> "Couldn't load: ${catalog.message}"
                        CatalogState.Empty -> "Not downloaded yet (about 20 MB)"
                    },
                    action = { IconButton(onClick = onRefreshCatalog) { Icon(Icons.Rounded.Refresh, "Refresh the list") } },
                )
                Row(
                    LauncherIcons.Store, "Faster Flathub installs",
                    if (flathubFsync) "Off: safe and slow, most of all without root"
                    else "On. If the phone loses power during an install, install that app again",
                    action = { androidx.compose.material3.Switch(checked = !flathubFsync, onCheckedChange = { onFlathubFsync(!it) }) },
                )
            }

            Group("Linux system") {
                Row(
                    LauncherIcons.Terminal, "Running apps", if (runningSessions == 0) "Nothing running" else "$runningSessions running",
                    action = if (runningSessions > 0) ({ TextButton(onClick = onStopAll) { Text("Stop all") } }) else null,
                )
                // Eight rows few people need: folded away until asked for.
                var showFiles by rememberSaveable { mutableStateOf(false) }
                Row(
                    LauncherIcons.Code, "System files",
                    filesUnavailable ?: if (showFiles) "Tap one to edit it. The first save keeps a backup" else ".bashrc, apt sources, DNS, hosts and more, for editing",
                    onClick = if (filesUnavailable == null) ({ showFiles = !showFiles }) else null,
                )
                if (showFiles && filesUnavailable == null) {
                    ConfigCatalog.files.forEach { f -> Row(LauncherIcons.Folder, f.title, f.path, onClick = { onOpenFile(f) }) }
                }
            }

            Group("Tessera") {
                val release = updates.available
                Row(
                    Icons.Rounded.Refresh,
                    if (release != null) "Tessera ${release.version} is out" else "Updates",
                    when {
                        updates.checking -> "Checking…"
                        release != null -> "You have $currentVersion. Tap to see what's new and get it"
                        updates.error != null -> "Couldn't check: ${updates.error}. Tap to try again"
                        updates.checkedAt > 0 -> "$currentVersion is the latest · checked ${DateUtils.getRelativeTimeSpanString(updates.checkedAt)}. Tap to check now"
                        else -> "You have $currentVersion. Tap to check now"
                    },
                    onClick = if (release != null) onOpenUpdate else onCheckUpdates,
                )
                Row(
                    Icons.Rounded.Refresh, "Tell me about new versions",
                    if (updates.enabled) "Tessera looks once a day and tells you. Nothing is downloaded or installed by itself"
                    else "Off: Tessera only looks when you tap Updates",
                    action = { androidx.compose.material3.Switch(checked = updates.enabled, onCheckedChange = onUpdateChecks) },
                )
                Row(Icons.Rounded.PlayArrow, "Introduction", "The tour from the first run", onClick = onIntro)
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
    // The phone's own palette, whatever Tessera wears at the moment (it may be a Linux theme's).
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val phone = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
    } else MaterialTheme.colorScheme
    ThemeChoice(
        title = "Phone colors",
        detail = "Material You, from your wallpaper",
        selected = appearance.linuxTheme == null,
        swatches = listOf(phone.surface, phone.primary, phone.onSurface),
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
