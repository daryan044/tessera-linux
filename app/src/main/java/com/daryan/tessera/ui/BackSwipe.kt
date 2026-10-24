// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/** How far a back swipe has gone, and where; see [rememberBackSwipe]. */
@Stable
class BackSwipe(
    /** The screen's own corner radius, which a shrinking page takes on as the system's window does. */
    val cornerPx: Float,
) {
    /** 0 to 1, as Android reports it; eased where it's drawn. */
    val progress = Animatable(0f)
    /** After letting go: 0 to 1 as the page slides off. */
    val exit = Animatable(0f)
    var fromLeft by mutableStateOf(true)
    /** How far the finger moved up or down since the swipe began. */
    var dy by mutableFloatStateOf(0f)

    internal val eased get() = GESTURE.transform(progress.value)
}

/** Material's curve for back progress (MaterialBackAnimationHelper): quick at first, then settling. */
private val GESTURE = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
/** Accelerating out, as a page leaving does. */
private val LEAVE = CubicBezierEasing(0.3f, 0f, 1f, 1f)

/**
 * Back for a page or sheet that closes, followed as the swipe goes (Android's predictive back)
 * rather than only at its end, as BackHandler does: letting go slides the page off ([slideOff])
 * and runs [onBack]; giving up eases it back. [closes]: false when back asks first (unsaved
 * changes), so the page returns to full size instead.
 */
@Composable
fun rememberBackSwipe(enabled: Boolean = true, closes: Boolean = true, slideOff: Boolean = true, onBack: () -> Unit): BackSwipe {
    val view = LocalView.current
    val fallback = with(LocalDensity.current) { 28.dp.toPx() }
    val swipe = remember {
        val corner = if (Build.VERSION.SDK_INT >= 31) view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius else null
        BackSwipe(corner?.toFloat()?.takeIf { it > 0f } ?: fallback)
    }
    // A page brought back while it's still leaving keeps its composition, and with it the
    // slid-off, invisible state of the swipe that closed it: an invisible editor then covered
    // Settings and took every tap. Shown again, it starts over.
    val shown = LocalPageShown.current
    LaunchedEffect(shown) {
        if (shown) {
            swipe.exit.snapTo(0f)
            swipe.progress.snapTo(0f)
        }
    }
    val scope = rememberCoroutineScope()
    val back by rememberUpdatedState(onBack)
    val stays by rememberUpdatedState(!closes)
    // Leaving, it no longer takes back: the page under it does.
    PredictiveBackHandler(enabled && shown) { events ->
        var startY = Float.NaN
        try {
            events.collect { e ->
                if (startY.isNaN()) startY = e.touchY
                swipe.fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                swipe.dy = e.touchY - startY
                swipe.progress.snapTo(e.progress)
            }
            if (stays) {
                back()
                scope.launch { swipe.progress.animateTo(0f) }
            } else {
                // Off the side it was heading, then gone; the page's own exit then has nothing to show.
                if (slideOff) swipe.exit.animateTo(1f, tween(200, easing = LEAVE))
                back()
            }
        } catch (e: CancellationException) {
            scope.launch { swipe.progress.animateTo(0f) }
            throw e
        }
    }
    return swipe
}

/** Whether the page around is shown or on its way in; false while it leaves. See [PageShown]. */
val LocalPageShown = compositionLocalOf { true }

/** Tells the page in an AnimatedVisibility whether it's shown, for [rememberBackSwipe]. */
@Composable
fun AnimatedVisibilityScope.PageShown(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPageShown provides (transition.targetState == EnterExitState.Visible), content = content)

/**
 * A full-screen page following [swipe] as Google's pages do (Material's full-screen back, the
 * system's back between activities): down to 90%, held 8dp in from the edges, drifting with the
 * finger, corners rounding to the screen's, the launcher dimming behind it. Off the side once let go.
 */
fun Modifier.backSwipePage(swipe: BackSwipe) = drawBehind {
    val dim = 0.25f * swipe.eased * (1f - swipe.exit.value)
    if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
}.graphicsLayer {
    val p = swipe.eased
    val e = swipe.exit.value
    if (p == 0f && e == 0f) return@graphicsLayer
    val gap = 8.dp.toPx()
    val scale = 1f - 0.1f * p
    scaleX = scale
    scaleY = scale
    val dir = if (swipe.fromLeft) 1f else -1f
    val maxX = max(0f, (size.width - size.width * scale) / 2f - gap)
    translationX = dir * (maxX + e * size.width)
    val maxY = max(0f, (size.height - size.height * scale) / 2f - gap)
    val yProgress = GESTURE.transform((abs(swipe.dy) / size.height).coerceIn(0f, 1f))
    translationY = sign(swipe.dy) * yProgress * maxY
    alpha = 1f - e
    shape = RoundedCornerShape(swipe.cornerPx * p)
    clip = true
}

/**
 * A bottom sheet following [swipe] as Material's do (MaterialBottomContainerBackHelper): 48dp
 * narrower and 24dp lower at most, from its bottom edge.
 */
fun Modifier.backSwipeSheet(swipe: BackSwipe) = graphicsLayer {
    val p = swipe.eased
    if (p == 0f || size.width == 0f || size.height == 0f) return@graphicsLayer
    scaleX = 1f - p * 48.dp.toPx() / size.width
    scaleY = 1f - p * 24.dp.toPx() / size.height
    transformOrigin = TransformOrigin(0.5f, 1f)
}
