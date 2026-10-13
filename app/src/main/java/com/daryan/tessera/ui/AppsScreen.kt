@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daryan.tessera.LauncherTab
import com.daryan.tessera.SHORTCUTS_TAB
import com.daryan.tessera.Shortcut
import com.daryan.tessera.container.PermissionManifest
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabPills(
    tabs: List<LauncherTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, tab ->
            val isSelected = i == selected
            val container by animateColorAsState(
                if (isSelected) colors.secondaryContainer else colors.surfaceContainerHighest,
                motion.defaultEffectsSpec(), label = "pill",
            )
            val content by animateColorAsState(
                if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                motion.defaultEffectsSpec(), label = "pillText",
            )
            Surface(
                shape = CircleShape,
                color = container,
                contentColor = content,
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { onSelect(i) },
                        onLongClick = if (tab.custom) ({ onRemove(tab.title) }) else null,
                    ),
            ) {
                Text(
                    tab.title,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .animateContentSize(motion.fastSpatialSpec())
                        // The selected pill grows a little, like the expressive button group.
                        .padding(horizontal = if (isSelected) 20.dp else 16.dp, vertical = 10.dp),
                )
            }
        }
        Surface(
            onClick = onAdd,
            shape = CircleShape,
            color = colors.surfaceContainerHighest,
            contentColor = colors.onSurfaceVariant,
        ) {
            Icon(Icons.Rounded.Add, "New tab", Modifier.padding(8.dp).size(20.dp))
        }
    }
}

@Composable
fun AppsPager(
    tabs: List<LauncherTab>,
    pagerState: PagerState,
    padding: PaddingValues,
    onOpen: (PermissionManifest) -> Unit,
    pinned: List<String>,
    favorites: List<String>,
    onTogglePin: (PermissionManifest) -> Unit,
    onToggleFavorite: (PermissionManifest) -> Unit,
    onUninstall: (PermissionManifest) -> Unit,
    onEditShortcut: (PermissionManifest) -> Unit,
    onNewShortcut: () -> Unit,
    /** Whether [onGraphics] applies to windowed apps (the phone has the GPU driver). */
    graphics: Boolean,
    onGraphics: (PermissionManifest) -> Unit,
) {
    val context = LocalContext.current
    val homeScreen = remember { HomeShortcuts.supported(context) }
    // Tabs whose tiles already rose in: coming back to one (or swiping past it) shouldn't replay it.
    val shown = remember { mutableSetOf<String>() }
    // The tabs either side are composed ahead, so a swipe doesn't build a whole grid mid-gesture.
    HorizontalPager(pagerState, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { tabs[it].title }) { page ->
        val tab = tabs[page]
        val animate = remember(tab.title) { shown.add(tab.title) && page == pagerState.currentPage }
        // Only the first screenful rises in; tiles scrolled to later just appear.
        val enteredAt = remember(tab.title) { SystemClock.uptimeMillis() }
        val shortcuts = tab.title == SHORTCUTS_TAB && !tab.custom
        if (tab.apps.isEmpty() && !shortcuts) {
            EmptyState(
                title = "Nothing here yet",
                body = if (tab.custom) "Open an app and add it to “${tab.title}”." else "No apps in this category.",
                padding = padding,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(tab.apps, key = { _, app -> app.id }) { index, app ->
                    AppTile(
                        app, index.takeIf { animate && SystemClock.uptimeMillis() - enteredAt < 1000 },
                        pinned = app.id in pinned,
                        favorite = app.id in favorites,
                        onClick = { onOpen(app) },
                        onTogglePin = { onTogglePin(app) },
                        onToggleFavorite = { onToggleFavorite(app) },
                        onUninstall = { onUninstall(app) },
                        onEdit = if (Shortcut.isShortcut(app)) ({ onEditShortcut(app) }) else null,
                        onGraphics = if (graphics && app.gui) ({ onGraphics(app) }) else null,
                        homeScreen = homeScreen,
                        modifier = Modifier.animateItem(),
                    )
                }
                if (shortcuts) {
                    item(key = "new-shortcut") { NewShortcutTile(onNewShortcut, Modifier.animateItem()) }
                }
            }
        }
    }
}

/** One app in the grid. Holding it opens its menu: pin, favorite, home screen, graphics, uninstall. */
@Composable
private fun AppTile(
    app: PermissionManifest,
    /** Its place in the grid, for the staggered entrance; null shows it at once. */
    index: Int?,
    pinned: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUninstall: () -> Unit,
    /** For shortcuts: edit it (and Delete replaces Uninstall). */
    onEdit: (() -> Unit)?,
    /** For windowed apps with the GPU driver: their graphics settings. */
    onGraphics: (() -> Unit)?,
    /** Whether the phone's home screen takes icons. */
    homeScreen: Boolean,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, motion.fastSpatialSpec(), label = "press")
    val spin by animateFloatAsState(if (pressed) 30f else 0f, motion.defaultSpatialSpec(), label = "spin")

    // Staggered entrance: tiles rise and fade in one after another.
    val appear = remember { Animatable(if (index == null) 1f else 0f) }
    if (index != null) {
        LaunchedEffect(Unit) {
            delay(index * 40L)
            appear.animateTo(1f, motion.slowSpatialSpec())
        }
    }

    val (container, onContainer) = appColors(app)
    Box(modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * 48.dp.toPx()
            }
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    menu = true
                },
            )
            .padding(vertical = 14.dp, horizontal = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer { rotationZ = spin }
                .clip(appShape(app))
                .background(container),
        ) {
            AppGlyph(app, 30.dp, onContainer, Modifier.graphicsLayer { rotationZ = -spin })
        }
        if (pinned || favorite) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val tint = MaterialTheme.colorScheme.primary
                if (pinned) Icon(LauncherIcons.Pin, "Pinned", Modifier.size(14.dp), tint = tint)
                if (favorite) Icon(Icons.Rounded.Star, "Favorite", Modifier.size(14.dp), tint = tint)
            }
            Spacer(Modifier.height(2.dp))
        } else {
            Spacer(Modifier.height(10.dp))
        }
        Text(
            app.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            if (app.gui) "Window" else "Terminal",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    DropdownMenu(
        expanded = menu,
        onDismissRequest = { menu = false },
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        DropdownMenuItem(
            text = { Text(if (pinned) "Unpin" else "Pin to top") },
            leadingIcon = { Icon(LauncherIcons.Pin, null) },
            onClick = { menu = false; onTogglePin() },
        )
        DropdownMenuItem(
            text = { Text(if (favorite) "Remove from favorites" else "Add to favorites") },
            leadingIcon = { Icon(Icons.Rounded.Star, null) },
            onClick = { menu = false; onToggleFavorite() },
        )
        if (homeScreen) {
            val context = LocalContext.current
            val homeIcon = rememberHomeIcon(app)
            DropdownMenuItem(
                text = { Text("Add to home screen") },
                leadingIcon = { Icon(Icons.Rounded.Home, null) },
                onClick = { menu = false; HomeShortcuts.pin(context, app, homeIcon()) },
            )
        }
        if (onGraphics != null) {
            DropdownMenuItem(
                text = { Text("Graphics fixes") },
                leadingIcon = { Icon(LauncherIcons.Monitor, null) },
                onClick = { menu = false; onGraphics() },
            )
        }
        HorizontalDivider()
        if (onEdit != null) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                onClick = { menu = false; onEdit() },
            )
        }
        DropdownMenuItem(
            text = { Text(if (onEdit != null) "Delete" else "Uninstall") },
            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
            onClick = { menu = false; onUninstall() },
        )
    }
    }
}

/** The last tile in the Shortcuts tab: make a new shortcut. */
@Composable
private fun NewShortcutTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(10.dp))
        Text("New shortcut", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Text("A command", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(title: String, body: String, padding: PaddingValues) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .clip(MaterialShapes.SoftBurst.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(
                LauncherIcons.Apps, null, Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
