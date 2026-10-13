@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.daryan.tessera.X11Actions
import com.daryan.tessera.input.InputSink
import com.daryan.tessera.input.PcInput
import com.daryan.tessera.input.PcLink
import com.daryan.tessera.input.PcSettings

/**
 * The phone as a mouse and keyboard for a PC: the same trackpad the cast screen uses, pointed at
 * a USB or Bluetooth HID sink instead of an app's X server.
 *
 * Almost all of the screen is the pad. The transport is two chips in the top bar, the keys a
 * phone keyboard lacks are one short scrolling row, and typing is a single compact field —
 * Android hands an app the characters its keyboard produced rather than key presses, so there has
 * to be somewhere for them to land.
 */
@Composable
fun PcTrackpadScreen(su: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val pc = remember { PcInput(context, su) }
    val settings = remember { PcSettings.of(context) }
    var showSettings by remember { mutableStateOf(false) }
    val sink = pc.sink
    // Being a Bluetooth keyboard needs Nearby devices, a runtime permission: declaring it in the
    // manifest is not enough, and without it the HID profile throws rather than refusing politely.
    val needsNearby = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, NEARBY) != PackageManager.PERMISSION_GRANTED
    val askNearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[NEARBY] == true) pc.startBluetooth()
        else pc.note("Bluetooth needs the Nearby devices permission, which was declined.")
    }
    val typing = remember { FocusRequester() }
    // The keyboard was never asked for, so it only appeared if the field was tapped.
    LaunchedEffect(sink, settings.autoKeyboard) {
        if (sink != null && settings.autoKeyboard) runCatching { typing.requestFocus() }
    }
    BackHandler { onClose() }
    // Leaving the screen gives Android its USB gadget back; nothing is left half-configured.
    DisposableEffect(Unit) { onDispose { pc.stop() } }

    Surface(color = colors.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // One compact bar: what it is, how it's connected, and the way out.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LauncherIcons.Mouse, null, Modifier.size(20.dp), tint = colors.primary)
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = pc.link == PcLink.USB,
                    onClick = { if (pc.link == PcLink.USB) pc.stop() else pc.startUsb(scope) },
                    enabled = pc.usbSupported || pc.link == PcLink.USB,
                    label = { Text("USB", style = MaterialTheme.typography.labelMedium) },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = pc.link == PcLink.BLUETOOTH,
                    onClick = {
                        when {
                            pc.link == PcLink.BLUETOOTH -> pc.stop()
                            needsNearby -> askNearby.launch(arrayOf(NEARBY))
                            else -> pc.startBluetooth()
                        }
                    },
                    enabled = pc.bluetoothSupported || pc.link == PcLink.BLUETOOTH,
                    label = { Text("Bluetooth", style = MaterialTheme.typography.labelMedium) },
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Rounded.Settings, "Settings", Modifier.size(20.dp))
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Done", Modifier.size(20.dp)) }
            }

            // Whatever there is to say, in one line: progress, a refusal, or the PC's name.
            val note = pc.host ?: pc.status.ifBlank {
                when (pc.link) {
                    PcLink.USB -> "The PC sees a USB mouse and keyboard."
                    PcLink.BLUETOOTH -> "Waiting for the PC to connect."
                    PcLink.OFF -> "Pick USB or Bluetooth. Nothing to install on the PC."
                }
            }
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
            )

            if (sink == null) {
                // No pad at all rather than one that goes nowhere.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Not connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            } else {
                // The pad takes everything that is left.
                TrackpadSurface(
                    sink,
                    Modifier.weight(1f).fillMaxWidth(),
                    scrollScale = PC_SCROLL * settings.scrollSpeed,
                    pointerScale = settings.pointerSpeed,
                    naturalScroll = settings.naturalScroll,
                    pinchZoom = settings.pinchZoom,
                )
                Spacer(Modifier.height(8.dp))
                KeyRow(sink)
                Spacer(Modifier.height(8.dp))
                TypeField(sink, typing)
            }
        }
    }

    if (showSettings) PcSettingsDialog(settings) { showSettings = false }
}

/**
 * What the pad does, left to the person using it: speeds, which way scrolling goes, whether two
 * fingers zoom, and whether the keyboard shows itself.
 */
@Composable
private fun PcSettingsDialog(settings: PcSettings, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trackpad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SpeedSlider("Pointer speed", settings.pointerSpeed) { settings.pointerSpeed = it }
                SpeedSlider("Scroll speed", settings.scrollSpeed) { settings.scrollSpeed = it }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SettingSwitch(
                    "Natural scrolling",
                    "Content follows your fingers",
                    settings.naturalScroll,
                ) { settings.naturalScroll = it }
                SettingSwitch(
                    "Pinch to zoom",
                    "Off, two fingers always scroll",
                    settings.pinchZoom,
                ) { settings.pinchZoom = it }
                SettingSwitch(
                    "Open the keyboard",
                    "As soon as a PC is connected",
                    settings.autoKeyboard,
                ) { settings.autoKeyboard = it }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SpeedSlider(label: String, value: Float, onValue: (Float) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                // One decimal is as much as anyone needs to see.
                "${(value * 10).toInt() / 10f}x",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = PcSettings.MIN_SPEED..PcSettings.MAX_SPEED,
        )
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** The keys a phone keyboard hasn't got, small enough that a row of them is one line. */
@Composable
private fun KeyRow(sink: InputSink) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(PC_KEYS, key = { it.first }) { (label, codes) ->
            KeyChip(label) { sink.keys(*codes) }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.height(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 11.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/**
 * Where typing lands, and the only way characters can be had: Android gives an app the text its
 * keyboard produced, not key presses.
 *
 * The field is never really empty. It holds one zero-width space, so that backspace always has
 * something to delete and therefore reports a change — on a truly empty field an IME sends
 * nothing at all, which is why Backspace did nothing. Newlines arrive as text too, so Enter is
 * simply a character here; both are turned back into keystrokes and the sentinel is restored.
 */
@Composable
private fun TypeField(sink: InputSink, focus: FocusRequester) {
    val colors = MaterialTheme.colorScheme
    var field by remember { mutableStateOf(blank()) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(LauncherIcons.Keyboard, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (field.text.length <= SENTINEL.length) {
                    Text(
                        "Type to the PC",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = { next ->
                        val text = next.text
                        when {
                            text.length > SENTINEL.length -> sink.type(text.substring(SENTINEL.length))
                            // Shorter than the sentinel: the IME deleted it, meaning backspace.
                            text.length < SENTINEL.length -> sink.keys(KeyEvent.KEYCODE_DEL)
                        }
                        field = blank()
                    },
                    // Not singleLine: Enter has to produce a newline for us to see it at all.
                    maxLines = 1,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
    }
}

/** The field's resting state: the sentinel, with the cursor after it. */
private fun blank() = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

/** Zero-width: invisible, but something for backspace to bite on. */
private const val SENTINEL = "\u200B"

/**
 * Wheel notches per dp of finger. A PC scrolls three lines a notch, so this is far gentler than
 * an X11 session wants, and the leftovers are carried between frames to keep it smooth.
 */
private const val PC_SCROLL = 0.09f

/** Android 12's name for "be a Bluetooth device"; a runtime permission, so it must be asked for. */
private const val NEARBY = "android.permission.BLUETOOTH_CONNECT"

/** Enter, Backspace and Space first: the ones a trackpad user reaches for most. */
private val PC_KEYS: List<Pair<String, IntArray>> = listOf(
    "Enter" to intArrayOf(KeyEvent.KEYCODE_ENTER),
    "\u232B" to intArrayOf(KeyEvent.KEYCODE_DEL),
    "Space" to intArrayOf(KeyEvent.KEYCODE_SPACE),
) + X11Actions.SHORTCUTS
