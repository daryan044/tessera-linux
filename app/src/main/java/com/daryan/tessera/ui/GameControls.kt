// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daryan.tessera.MouseButton
import com.daryan.tessera.input.InputSink
import kotlin.math.roundToInt

/** Which apps show [GameControls], by launcher id; off unless turned on in the window's panel. */
object GameControlsPrefs {
    private const val PREFS = "game_controls"

    fun on(c: Context, appId: String): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(appId, false)

    fun set(c: Context, appId: String, on: Boolean) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(appId, on).apply()
}

private val STICK = 150.dp
private val KNOB = 64.dp
/** How far the pointer moves per pixel of finger on the look area. */
private const val LOOK_SPEED = 1.2f
/** Past this share of the stick's reach, a direction's key is held. */
private const val STICK_PRESS = 0.35f

/**
 * Touch controls for games that want a keyboard and mouse held down: a stick holding W, A, S
 * and D, buttons holding a key or the left mouse button while touched, and a look area that
 * moves the pointer by how far the finger goes (mouse-look). Games that poll the keyboard every
 * frame, as SFML and SDL ones often do, never see a tap from Android's keyboard: it presses and
 * lets go at once. Elsewhere touches go through to the app as usual.
 */
@Composable
fun GameControls(sink: InputSink) {
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        // The look area: the right half, behind the buttons.
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f).pointerInput(sink) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val d = change.positionChange()
                        if (d != Offset.Zero) sink.move(d.x * LOOK_SPEED, d.y * LOOK_SPEED)
                        change.consume()
                    }
                }
            },
        )
        Stick(sink, Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = 36.dp))
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 28.dp),
        ) {
            Row {
                HoldButton("R", 52.dp) { sink.key(KeyEvent.KEYCODE_R, it) }
                Spacer(Modifier.width(14.dp))
                HoldButton("Jump", 52.dp) { sink.key(KeyEvent.KEYCODE_SPACE, it) }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                HoldButton("Run", 52.dp) { sink.key(KeyEvent.KEYCODE_SHIFT_LEFT, it) }
                Spacer(Modifier.width(14.dp))
                HoldButton("Fire", 84.dp) { sink.press(MouseButton.LEFT, it) }
            }
        }
    }
}

/** A thumb stick: holds the WASD keys for the way it's pushed, eight ways; lets go when released. */
@Composable
private fun Stick(sink: InputSink, modifier: Modifier) {
    val reach = with(LocalDensity.current) { (STICK - KNOB).toPx() / 2 }
    var knob by remember { mutableStateOf(Offset.Zero) }
    var held by remember { mutableStateOf(emptySet<Int>()) }
    fun hold(keys: Set<Int>) {
        (held - keys).forEach { sink.key(it, false) }
        (keys - held).forEach { sink.key(it, true) }
        held = keys
    }
    // Leaving the screen mid-push mustn't leave the player walking.
    DisposableEffect(sink) { onDispose { held.forEach { sink.key(it, false) } } }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(STICK).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
            .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .pointerInput(sink, reach) {
                val center = Offset(size.width / 2f, size.height / 2f)
                fun follow(change: PointerInputChange) {
                    var v = change.position - center
                    val len = v.getDistance()
                    if (len > reach) v *= reach / len
                    knob = v
                    val x = v.x / reach
                    val y = v.y / reach
                    hold(buildSet {
                        if (y < -STICK_PRESS) add(KeyEvent.KEYCODE_W)
                        if (y > STICK_PRESS) add(KeyEvent.KEYCODE_S)
                        if (x < -STICK_PRESS) add(KeyEvent.KEYCODE_A)
                        if (x > STICK_PRESS) add(KeyEvent.KEYCODE_D)
                    })
                    change.consume()
                }
                awaitEachGesture {
                    follow(awaitFirstDown(requireUnconsumed = false))
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        follow(change)
                    }
                    knob = Offset.Zero
                    hold(emptySet())
                }
            },
    ) {
        Box(
            Modifier.offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }.size(KNOB).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.35f)),
        )
    }
}

/** A round button that holds whatever it stands for while a finger is on it. */
@Composable
private fun HoldButton(label: String, size: Dp, onHold: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (down) onHold(false) } }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape)
            .background(Color.White.copy(alpha = if (down) 0.4f else 0.15f))
            .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    down = true
                    onHold(true)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        change.consume()
                        if (!change.pressed) break
                    }
                    down = false
                    onHold(false)
                }
            },
    ) {
        Text(label, color = Color.White, fontSize = if (size > 60.dp) 16.sp else 13.sp, fontWeight = FontWeight.Medium)
    }
}
