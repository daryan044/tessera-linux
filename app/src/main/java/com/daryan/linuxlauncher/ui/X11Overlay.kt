// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.daryan.linuxlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.daryan.linuxlauncher.MouseButton
import com.daryan.linuxlauncher.TouchMode
import com.daryan.linuxlauncher.X11Actions
import com.daryan.linuxlauncher.container.RunningSession
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Page { MAIN, KEYS, INPUT }

private val PANEL_WIDTH = 248.dp

/**
 * Game-mode style controls over a GUI app's window: swipe in from the handle on the right edge
 * to pull out a panel of what a phone lacks (right click, keys, a keyboard key) and the app's
 * settings. Closed, only the handle strip takes touches; the rest go to the app.
 */
@Composable
fun X11Overlay(session: RunningSession?, actions: X11Actions) {
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val widthPx = with(LocalDensity.current) { PANEL_WIDTH.toPx() }
    // 0 = closed (off screen to the right), 1 = fully open.
    val open = remember { Animatable(0f) }
    var page by remember { mutableStateOf(Page.MAIN) }

    fun settle(toOpen: Boolean) = scope.launch {
        open.animateTo(if (toOpen) 1f else 0f, motion.defaultSpatialSpec())
        if (!toOpen) page = Page.MAIN
    }
    fun run(action: () -> Unit) {
        settle(false)
        action()
    }
    // One drag handler for the handle and the panel: follow the finger, then spring.
    val dragToOpen = Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragEnd = { settle(open.value > 0.4f) },
            onDragCancel = { settle(open.value > 0.4f) },
        ) { change, dx ->
            change.consume()
            scope.launch { open.snapTo((open.value - dx / widthPx).coerceIn(0f, 1f)) }
        }
    }
    BackHandler(enabled = open.value > 0f) { settle(false) }

    Box(Modifier.fillMaxSize()) {
        // Tap outside to close; only present while the panel is out.
        if (open.value > 0f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = open.value }
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settle(false) },
            )
        }

        // The handle: a slim pill on the edge, with a wider invisible strip to grab.
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(y = (-80).dp)
                .width(28.dp)
                .height(120.dp)
                .graphicsLayer { alpha = 1f - open.value }
                .then(dragToOpen)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settle(true) },
        ) {
            Box(
                Modifier.padding(end = 4.dp).width(5.dp).height(56.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.55f)),
            )
        }

        // The panel, sliding in from the right with the drag.
        if (open.value > 0f) {
            Surface(
                shape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .width(PANEL_WIDTH)
                    .fillMaxHeight(0.82f)
                    .offset { IntOffset(((1f - open.value) * widthPx).roundToInt(), 0) }
                    .then(dragToOpen),
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState != Page.MAIN
                        (slideInHorizontally(motion.defaultSpatialSpec()) { if (forward) it else -it } + fadeIn(motion.defaultEffectsSpec()))
                            .togetherWith(slideOutHorizontally(motion.fastSpatialSpec()) { if (forward) -it else it } + fadeOut(motion.fastEffectsSpec()))
                    },
                    label = "page",
                ) { p ->
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        when (p) {
                            Page.MAIN -> MainPage(session, actions, onPage = { page = it }, run = ::run)
                            Page.KEYS -> SubPage("Keys", onBack = { page = Page.MAIN }) { KeysPage(actions) }
                            Page.INPUT -> SubPage("Input mode", onBack = { page = Page.MAIN }) { InputPage(actions) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPage(session: RunningSession?, actions: X11Actions, onPage: (Page) -> Unit, run: (() -> Unit) -> Unit) {
    Text(session?.app?.name ?: "Linux app", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text("Linux · ${session?.backend ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
    FlowRow(
        maxItemsInEachRow = 3,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Tile(LauncherIcons.Keyboard, "Keyboard") { run(actions::toggleKeyboard) }
        Tile(LauncherIcons.Mouse, "Right click") { run { actions.click(MouseButton.RIGHT) } }
        Tile(LauncherIcons.Mouse, "Middle click") { run { actions.click(MouseButton.MIDDLE) } }
        Tile(LauncherIcons.Keyboard, "Keys") { onPage(Page.KEYS) }
        Tile(LauncherIcons.Mouse, "Input mode") { onPage(Page.INPUT) }
        if (session != null) Tile(Icons.Rounded.Lock, "Permissions") { run { actions.openPermissions(session.app.id) } }
        Tile(Icons.Rounded.Settings, "Display") { run(actions::displaySettings) }
        Tile(Icons.Rounded.Close, "Close", danger = true) { run { actions.close(session?.session?.id) } }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(68.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(52.dp).clip(CircleShape).background(if (danger) c.errorContainer else c.secondaryContainer),
        ) {
            Icon(icon, null, tint = if (danger) c.onErrorContainer else c.onSecondaryContainer)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
    Spacer(Modifier.height(8.dp))
    content()
}

@Composable
private fun KeysPage(actions: X11Actions) {
    Text(
        "Sent to the app as if typed. The panel stays open so you can press several.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        X11Actions.SHORTCUTS.forEach { (label, codes) ->
            FilledTonalButton(onClick = { actions.keys(*codes) }) { Text(label) }
        }
    }
}

@Composable
private fun InputPage(actions: X11Actions) {
    var current by remember { mutableStateOf(actions.touchMode()) }
    TouchMode.entries.forEach { mode ->
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .clickable { current = mode; actions.setTouchMode(mode) }.padding(vertical = 8.dp),
        ) {
            RadioButton(selected = current == mode, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
            Column {
                Text(mode.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    when (mode) {
                        TouchMode.DIRECT -> "Like a phone app: tap, swipe to scroll, pinch to zoom."
                        TouchMode.TOUCHPAD -> "The screen is a touchpad: move a pointer, tap to click."
                        TouchMode.SIMULATED -> "Taps click where you touch, drag to drag. For apps without touch support."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
