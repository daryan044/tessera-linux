@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.ui

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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daryan.linuxlauncher.LauncherTab
import com.daryan.linuxlauncher.container.PermissionManifest
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
    onLongPress: (PermissionManifest) -> Unit,
) {
    HorizontalPager(pagerState, Modifier.fillMaxSize(), key = { tabs[it].title }) { page ->
        val tab = tabs[page]
        if (tab.apps.isEmpty()) {
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
                    AppTile(app, index, onClick = { onOpen(app) }, onLongPress = { onLongPress(app) })
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: PermissionManifest, index: Int, onClick: () -> Unit, onLongPress: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, motion.fastSpatialSpec(), label = "press")
    val spin by animateFloatAsState(if (pressed) 30f else 0f, motion.defaultSpatialSpec(), label = "spin")

    // Staggered entrance: tiles rise and fade in one after another.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 40L)
        appear.animateTo(1f, motion.slowSpatialSpec())
    }

    val (container, onContainer) = appColors(app)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
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
                    onLongPress()
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
        Spacer(Modifier.height(10.dp))
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
