// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** How long a first tap stays armed before the button goes back to its label. */
private const val ARMED_MS = 3000L

/**
 * Whether a two-tap action is armed: the first tap arms it, the second within [ARMED_MS] goes
 * ahead. Shared by [ConfirmButton] and anything else that confirms the same way (the window
 * panel's Close tile).
 */
@Composable
fun rememberArmed(): MutableState<Boolean> {
    val armed = remember { mutableStateOf(false) }
    LaunchedEffect(armed.value) {
        if (armed.value) {
            kotlinx.coroutines.delay(ARMED_MS)
            armed.value = false
        }
    }
    return armed
}

/**
 * A button for what can't be undone, such as ending a task: the first tap turns its [label]
 * into "Sure?" (sliding in, the button easing into the error color), the second does it. Left
 * alone, it goes back after a few seconds. Lighter than a dialog for something done often.
 */
@Composable
fun ConfirmButton(label: String, onConfirm: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var armed by rememberArmed()
    val haptics = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val c = MaterialTheme.colorScheme
    val container by animateColorAsState(if (armed) c.errorContainer else c.secondaryContainer, motion.defaultEffectsSpec(), label = "confirmContainer")
    val content by animateColorAsState(if (armed) c.onErrorContainer else c.onSecondaryContainer, motion.defaultEffectsSpec(), label = "confirmContent")
    FilledTonalButton(
        onClick = {
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        },
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = content),
        modifier = modifier,
    ) {
        ConfirmLabel(armed, label)
    }
}

/** [label], or "Sure?" while [armed]: the new text slides up into place as the old one leaves. */
@Composable
fun ConfirmLabel(armed: Boolean, label: String, style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = armed,
        transitionSpec = {
            val up = targetState
            (slideInVertically(motion.defaultSpatialSpec()) { if (up) it else -it } + fadeIn(motion.defaultEffectsSpec()))
                .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { if (up) -it else it } + fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform(clip = false))
        },
        label = "confirmLabel",
    ) { a -> Text(if (a) "Sure?" else label, style = style) }
}
