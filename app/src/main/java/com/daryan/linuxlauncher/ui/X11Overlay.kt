// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.daryan.linuxlauncher.ui

import android.content.Context
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
private val HANDLE_WIDTH = 28.dp
private val HANDLE_HEIGHT = 120.dp
private const val HANDLE_PREFS = "x11_overlay"
private const val KEY_LEFT = "handle_left"
private const val KEY_ALONG = "handle_along"

/**
 * Game-mode style controls over a GUI app's window: swipe in from the handle on the screen's
 * edge to pull out a panel of what a phone lacks (right click, keys, a keyboard key) and the
 * app's settings. Hold the handle to move it up or down either edge; it stays where it's put.
 * Closed, only the handle strip takes touches; the rest go to the app.
 */
@Composable
fun X11Overlay(session: RunningSession?, actions: X11Actions) {
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val prefs = LocalContext.current.getSharedPreferences(HANDLE_PREFS, Context.MODE_PRIVATE)
    val widthPx = with(density) { PANEL_WIDTH.toPx() }
    // 0 = closed (off screen past the edge), 1 = fully open.
    val open = remember { Animatable(0f) }
    var page by remember { mutableStateOf(Page.MAIN) }
    // Where the handle sits: which edge, and how far down it (0 = top, 1 = bottom).
    var onLeft by remember { mutableStateOf(prefs.getBoolean(KEY_LEFT, false)) }
    var along by remember { mutableFloatStateOf(prefs.getFloat(KEY_ALONG, 0.4f)) }
    // While it's being moved: where the finger has taken it, in px from the top left.
    var moving by remember { mutableStateOf<Offset?>(null) }

    fun settle(toOpen: Boolean) = scope.launch {
        open.animateTo(if (toOpen) 1f else 0f, motion.defaultSpatialSpec())
        if (!toOpen) page = Page.MAIN
    }
    fun run(action: () -> Unit) {
        settle(false)
        action()
    }
    // One drag handler for the handle and the panel: follow the finger, then spring. Pulling
    // away from the handle's edge opens.
    val dragToOpen = Modifier.pointerInput(onLeft) {
        detectHorizontalDragGestures(
            onDragEnd = { settle(open.value > 0.4f) },
            onDragCancel = { settle(open.value > 0.4f) },
        ) { change, dx ->
            change.consume()
            val away = if (onLeft) dx else -dx
            scope.launch { open.snapTo((open.value + away / widthPx).coerceIn(0f, 1f)) }
        }
    }
    BackHandler(enabled = open.value > 0f) { settle(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val handleW = with(density) { HANDLE_WIDTH.toPx() }
        val handleH = with(density) { HANDLE_HEIGHT.toPx() }

        // Tap outside to close; only present while the panel is out.
        if (open.value > 0f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = open.value }
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settle(false) },
            )
        }

        // The handle: a slim pill on the edge, with a wider invisible strip to grab. Hold it to
        // move it; let go and it snaps to the nearer edge.
        val drag = moving
        val x = drag?.x ?: if (onLeft) 0f else w - handleW
        val y = drag?.y ?: (along * (h - handleH))
        Box(
            contentAlignment = if (onLeft) Alignment.CenterStart else Alignment.CenterEnd,
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(HANDLE_WIDTH)
                .height(HANDLE_HEIGHT)
                .graphicsLayer {
                    alpha = 1f - open.value
                    val grow = if (drag != null) 1.25f else 1f
                    scaleX = grow
                    scaleY = grow
                }
                .then(dragToOpen)
                .pointerInput(onLeft, w, h) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            moving = Offset(if (onLeft) 0f else w - handleW, along * (h - handleH))
                        },
                        onDragEnd = {
                            moving?.let { m ->
                                onLeft = m.x + handleW / 2 < w / 2
                                along = (m.y / (h - handleH)).coerceIn(0f, 1f)
                                prefs.edit().putBoolean(KEY_LEFT, onLeft).putFloat(KEY_ALONG, along).apply()
                            }
                            moving = null
                        },
                        onDragCancel = { moving = null },
                    ) { change, amount ->
                        change.consume()
                        moving = moving?.let {
                            Offset((it.x + amount.x).coerceIn(0f, w - handleW), (it.y + amount.y).coerceIn(0f, h - handleH))
                        }
                    }
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settle(true) },
        ) {
            Box(
                Modifier.padding(horizontal = 4.dp).width(if (drag != null) 7.dp else 5.dp).height(56.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (drag != null) 0.9f else 0.55f)),
            )
        }

        // The panel, sliding in from the handle's edge with the drag.
        if (open.value > 0f) {
            val corner = 32.dp
            Surface(
                shape = if (onLeft) RoundedCornerShape(topEnd = corner, bottomEnd = corner)
                else RoundedCornerShape(topStart = corner, bottomStart = corner),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .width(PANEL_WIDTH)
                    .fillMaxHeight(0.82f)
                    .offset {
                        val hidden = ((1f - open.value) * widthPx).roundToInt()
                        IntOffset(if (onLeft) -hidden else hidden, 0)
                    }
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
