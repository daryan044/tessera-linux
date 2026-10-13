@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daryan.tessera.CastState
import com.daryan.tessera.MouseButton
import com.daryan.tessera.X11Actions
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A tap's ripple on the pad: where, when, and which button it clicked. */
private class Ripple(val at: Offset, val start: Long, val button: MouseButton)

/** Where a finger has just been; these fade out behind it. */
private class Trail(val at: Offset, val time: Long)

private const val TAP_MS = 220L
private const val DOUBLE_TAP_MS = 300L
private const val RIPPLE_MS = 520L
private const val TRAIL_MS = 380L

/** The clock frames are drawn by (withFrameMillis), for when trails and ripples began. */
private fun frameClock() = System.nanoTime() / 1_000_000

/**
 * The phone while a window is cast: a trackpad for the picture on the other display. One finger
 * moves the pointer and taps to click, a tap and then a hold drags, two fingers scroll or tap a
 * right click, three tap a middle click. A grid of dots under the fingers swells and takes the
 * theme's color around them, with fading trails and a ripple for each click.
 */
@Composable
fun TrackpadScreen(cast: CastState, actions: X11Actions) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    BackHandler { actions.stopCast() }

    // Visual state, shared between the gesture handler and the drawing.
    val fingers = remember { mutableStateMapOf<PointerId, Offset>() }
    val trails = remember { mutableStateListOf<Trail>() }
    val ripples = remember { mutableStateListOf<Ripple>() }
    var now by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    // While anything is moving or fading, redraw every frame.
    val animating = fingers.isNotEmpty() || trails.isNotEmpty() || ripples.isNotEmpty()
    LaunchedEffect(animating) {
        while (animating) {
            withFrameMillis { t ->
                now = t
                trails.removeAll { t - it.time > TRAIL_MS }
                ripples.removeAll { t - it.start > RIPPLE_MS }
            }
        }
    }

    // The app's windows, to pick which one the cast shows; looked at again now and then.
    var windows by remember { mutableStateOf(actions.windows()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            windows = actions.windows()
        }
    }

    Surface(color = colors.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            // What's cast where, and the way out.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LauncherIcons.Cast, null, tint = colors.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Casting", style = MaterialTheme.typography.titleMedium)
                    Text(cast.display, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                }
                FilledTonalButton(
                    onClick = actions::stopCast,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
                ) { Text("Stop") }
            }
            if (windows.size > 1) {
                Spacer(Modifier.height(10.dp))
                var shown by remember { mutableStateOf<Long?>(null) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(windows, key = { it.id }) { w ->
                        FilterChip(
                            selected = shown == w.id,
                            onClick = { shown = w.id; actions.showWindow(w.id) },
                            label = { Text(w.title.ifBlank { "Window" }, maxLines = 1) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // The pad.
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(colors.surfaceContainer)
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        var lastTapUp = 0L
                        var lastTapAt = Offset.Zero
                        // Movement smaller than a pixel adds up instead of being lost.
                        var restX = 0f
                        var restY = 0f
                        var scrollX = 0f
                        var scrollY = 0f
                        fun moveBy(d: Offset) {
                            val dp = d / density.density
                            val speed = dp.getDistance()
                            // Slow and precise when moving slowly, quicker the faster the finger.
                            val gain = 1.4f * (1f + ((speed - 1.5f) / 6f).coerceIn(0f, 2.5f))
                            restX += dp.x * gain
                            restY += dp.y * gain
                            val sx = restX.toInt()
                            val sy = restY.toInt()
                            if (sx != 0 || sy != 0) {
                                actions.move(sx.toFloat(), sy.toFloat())
                                restX -= sx
                                restY -= sy
                            }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.uptimeMillis
                            var most = 1
                            var travel = 0f
                            // A tap, then straight away a finger down again: hold the button and drag.
                            val drag = start - lastTapUp < DOUBLE_TAP_MS && (down.position - lastTapAt).getDistance() < 48.dp.toPx()
                            if (drag) {
                                actions.press(MouseButton.LEFT, true)
                                dragging = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            fingers[down.id] = down.position
                            var last = down.position
                            var up = start
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                most = max(most, pressed.size)
                                event.changes.forEach { c ->
                                    if (c.pressed) fingers[c.id] = c.position else fingers.remove(c.id)
                                    if (c.pressed && (c.position - (trails.lastOrNull()?.at ?: Offset.Infinite)).getDistance() > 10.dp.toPx()) {
                                        trails += Trail(c.position, frameClock())
                                    }
                                }
                                if (pressed.size == 1 && most == 1) {
                                    val c = pressed[0]
                                    val d = c.position - c.previousPosition
                                    travel += d.getDistance()
                                    if (travel > slop || drag) moveBy(d)
                                    last = c.position
                                } else if (pressed.size >= 2) {
                                    // Two fingers: the average of their movement scrolls, the way
                                    // content follows the fingers on a phone.
                                    val d = pressed.map { it.position - it.previousPosition }
                                        .fold(Offset.Zero) { a, b -> a + b } / pressed.size.toFloat()
                                    travel += d.getDistance()
                                    if (travel > slop) {
                                        scrollX -= d.x / density.density * 2f
                                        scrollY -= d.y / density.density * 2f
                                        if (abs(scrollX) >= 1f || abs(scrollY) >= 1f) {
                                            actions.scroll(scrollX, scrollY)
                                            scrollX = 0f
                                            scrollY = 0f
                                        }
                                    }
                                    last = pressed.map { it.position }.fold(Offset.Zero) { a, b -> a + b } / pressed.size.toFloat()
                                }
                                event.changes.forEach { it.consume() }
                                if (pressed.isEmpty()) {
                                    up = event.changes.first().uptimeMillis
                                    break
                                }
                            }
                            fingers.clear()
                            if (drag) {
                                actions.press(MouseButton.LEFT, false)
                                dragging = false
                            } else if (travel < slop * 1.5f && up - start < TAP_MS || travel < slop && most > 1) {
                                val button = when (most) {
                                    1 -> MouseButton.LEFT
                                    2 -> MouseButton.RIGHT
                                    else -> MouseButton.MIDDLE
                                }
                                actions.click(button)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                ripples += Ripple(last, frameClock(), button)
                                if (button == MouseButton.LEFT) {
                                    lastTapUp = up
                                    lastTapAt = last
                                }
                            }
                        }
                    },
            ) {
                DotField(fingers.values.toList(), trails, ripples, now, dragging)
                if (!animating) {
                    Text(
                        "Move with one finger · tap to click\nTap, then hold to drag · two fingers scroll or right-click",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            // Keys a desktop app wants that a phone keyboard doesn't have.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                items(X11Actions.SHORTCUTS, key = { it.first }) { (label, codes) ->
                    AssistChip(onClick = { actions.keys(*codes) }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            // The buttons under a laptop's trackpad, with the keyboard between them.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("Left", MouseButton.LEFT, actions, RoundedCornerShape(topStart = 12.dp, bottomStart = 28.dp, topEnd = 12.dp, bottomEnd = 12.dp), Modifier.weight(1f))
                FilledTonalIconButton(onClick = actions::toggleKeyboard, modifier = Modifier.size(64.dp)) {
                    Icon(LauncherIcons.Keyboard, "Keyboard")
                }
                PadButton("Right", MouseButton.RIGHT, actions, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 12.dp, bottomEnd = 28.dp), Modifier.weight(1f))
            }
        }
    }
}

/** A mouse button you can hold: down while the finger is, so holding it and moving drags. */
@Composable
private fun PadButton(label: String, button: MouseButton, actions: X11Actions, shape: RoundedCornerShape, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            when (it) {
                is PressInteraction.Press -> {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    actions.press(button, true)
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> actions.press(button, false)
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = {},
        interactionSource = interaction,
        shape = shape,
        color = if (pressed) colors.primary else colors.secondaryContainer,
        contentColor = if (pressed) colors.onPrimary else colors.onSecondaryContainer,
        modifier = modifier.height(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * The pad's dot grid. Dots near a finger grow, lean toward it and take the primary color;
 * trails leave a fading wake, and a click sends a ring outward that lights the dots it passes.
 */
@Composable
private fun DotField(fingers: List<Offset>, trails: List<Trail>, ripples: List<Ripple>, now: Long, dragging: Boolean) {
    val colors = MaterialTheme.colorScheme
    val base = colors.outlineVariant
    val accent = if (dragging) colors.tertiary else colors.primary
    Canvas(Modifier.fillMaxSize()) {
        val spacing = 22.dp.toPx()
        val dot = 1.6.dp.toPx()
        val reach = 120.dp.toPx()
        val ringWidth = 18.dp.toPx()
        val cols = (size.width / spacing).toInt() + 1
        val rows = (size.height / spacing).toInt() + 1
        val x0 = (size.width - (cols - 1) * spacing) / 2
        val y0 = (size.height - (rows - 1) * spacing) / 2
        for (r in 0 until rows) for (c in 0 until cols) {
            val p = Offset(x0 + c * spacing, y0 + r * spacing)
            var glow = 0f
            var pull = Offset.Zero
            for (f in fingers) {
                val d = (f - p).getDistance()
                if (d < reach) {
                    val t = 1f - d / reach
                    glow = max(glow, t * t)
                    if (d > 1f) pull += (f - p) / d * (6.dp.toPx() * t * t)
                }
            }
            for (tr in trails) {
                val d = (tr.at - p).getDistance()
                val age = 1f - (now - tr.time).toFloat() / TRAIL_MS
                if (d < reach / 2 && age > 0f) glow = max(glow, (1f - d / (reach / 2)) * age * 0.6f)
            }
            for (rp in ripples) {
                val progress = ((now - rp.start).toFloat() / RIPPLE_MS).coerceIn(0f, 1f)
                val radius = progress * reach * 1.4f
                val d = abs((rp.at - p).getDistance() - radius)
                if (d < ringWidth) glow = max(glow, (1f - d / ringWidth) * (1f - progress))
            }
            drawCircle(
                color = lerp(base, accent, min(1f, glow)),
                radius = dot * (1f + 2.4f * glow),
                center = p + pull,
            )
        }
        // The ripples themselves, and a soft halo under each finger.
        for (rp in ripples) {
            val progress = ((now - rp.start).toFloat() / RIPPLE_MS).coerceIn(0f, 1f)
            val color = when (rp.button) {
                MouseButton.LEFT -> colors.primary
                MouseButton.RIGHT -> colors.tertiary
                MouseButton.MIDDLE -> colors.secondary
            }
            drawCircle(color.copy(alpha = 0.5f * (1f - progress)), radius = progress * reach * 1.4f, center = rp.at, style = Stroke(width = 2.dp.toPx()))
        }
        for (f in fingers) {
            drawCircle(accent.copy(alpha = 0.16f), radius = 36.dp.toPx(), center = f)
            drawCircle(accent.copy(alpha = 0.9f), radius = 7.dp.toPx(), center = f)
        }
    }
}
