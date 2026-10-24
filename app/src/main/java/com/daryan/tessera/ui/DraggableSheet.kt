// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A bottom sheet that follows the finger: it opens at its peek height (down to wherever
 * [content] puts the peek modifier it's handed), drags up to full height and down to close,
 * and settles at the nearest of the three, or the next one in a flick's direction. Past full
 * height the content scrolls; dragging down from its top moves the sheet again.
 *
 * Ours rather than Material's ModalBottomSheet: in Material 1.5.0-beta01 a sheet that changes
 * height during or after a drag keeps treating the next finger as that drag, and taps stop
 * reaching its buttons. Here the sheet only moves through nested scrolling, which a tap never
 * starts, so taps always land.
 */
@Composable
fun DraggableSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(peek: Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val motion = MaterialTheme.motionScheme
    val scroll = rememberScrollState()
    // How far the sheet is pushed down from fully open, in px; anchors below.
    val offset = remember { Animatable(Float.MAX_VALUE) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    var peekBottom by remember { mutableFloatStateOf(0f) }
    // The sheet's content, to measure the peek marker against (both move with the sheet).
    var sheetContent by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val expanded = 0f
    fun peek() = (sheetHeight - peekBottom).coerceAtLeast(0f)
    fun hidden() = sheetHeight
    // A release past this many px from where the sheet rested, or this fast, goes on to the
    // next stop that way; anything less springs back.
    val step = with(density) { 40.dp.toPx() }
    val flick = with(density) { 300.dp.toPx() }
    var rest by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }

    fun close() = scope.launch {
        if (closing) return@launch
        // Touches pass through from here on, while it slides away.
        closing = true
        offset.animateTo(hidden(), motion.fastSpatialSpec())
        onDismissRequest()
    }

    fun settle(velocity: Float) = scope.launch {
        val anchors = listOf(expanded, peek(), hidden()).distinct().sorted()
        val moved = offset.value - rest
        val target = when {
            velocity < -flick || moved < -step -> anchors.lastOrNull { it < rest - 1f } ?: expanded
            velocity > flick || moved > step -> anchors.firstOrNull { it > rest + 1f } ?: hidden()
            else -> anchors.minBy { abs(it - rest) }
        }
        if (target >= hidden() && hidden() > 0f) return@launch close().join()
        rest = target
        offset.animateTo(target, motion.defaultSpatialSpec(), initialVelocity = velocity)
    }

    val connection = remember {
        object : NestedScrollConnection {
            // Finger up: raise the sheet before the content scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || offset.value <= expanded) return Offset.Zero
                val next = (offset.value + available.y).coerceAtLeast(expanded)
                val used = next - offset.value
                scope.launch { offset.snapTo(next) }
                return Offset(0f, used)
            }

            // Finger down with the content at its top: lower the sheet.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val next = (offset.value + available.y).coerceAtMost(hidden())
                val used = next - offset.value
                scope.launch { offset.snapTo(next) }
                return Offset(0f, used)
            }

            // Once per release: the sheet settles here, or (fully open) the content flings.
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset.value <= expanded && rest <= expanded) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    // Open to the peek height once, as soon as the content has been measured.
    LaunchedEffect(Unit) {
        snapshotFlow { sheetHeight > 0f && peekBottom > 0f }.first { it }
        offset.snapTo(hidden())
        rest = peek()
        offset.animateTo(peek(), motion.defaultSpatialSpec())
    }
    // A sheet closes downwards, as it does when dragged.
    val back = rememberBackSwipe(slideOff = false, onBack = { close() })

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = WindowInsets.statusBars.getTop(density)
        val maxHeight = with(density) { (constraints.maxHeight - top).toDp() }
        val shown = if (sheetHeight > 0f && offset.value != Float.MAX_VALUE) 1f - (offset.value / sheetHeight).coerceIn(0f, 1f) else 0f

        // Scrim: dims with how far the sheet is out, and closes it on a tap.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = shown }
                .background(Color.Black.copy(alpha = 0.32f))
                .then(
                    if (closing) Modifier
                    else Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
                ),
        )

        // A plain Box, not a Surface: Surface swallows touches even when it isn't clickable,
        // which would keep blocking the grid while the sheet slides away.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .onSizeChanged { sheetHeight = it.height.toFloat() }
                .backSwipeSheet(back)
                .graphicsLayer { translationY = if (offset.value == Float.MAX_VALUE) sheetHeight.coerceAtLeast(10_000f) else offset.value }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .nestedScroll(connection),
        ) {
            // What a Surface would have set: text and icons in the sheet's on-colour.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.onGloballyPositioned { sheetContent = it }.verticalScroll(scroll).navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                }
                content(
                    Modifier.onGloballyPositioned { c ->
                        // Its bottom within the sheet, plus a little room under it.
                        val sheet = sheetContent
                        if (sheet != null && scroll.value == 0) {
                            peekBottom = sheet.localPositionOf(c, Offset.Zero).y + c.size.height + with(density) { 40.dp.toPx() }
                        }
                    },
                )
            }
            }
        }
    }
}

