@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daryan.tessera.input.PcInput
import com.daryan.tessera.input.PcLink

/**
 * The phone as a mouse and keyboard for a PC: the same trackpad the cast screen uses, pointed at
 * a USB or Bluetooth HID sink instead of an app's X server.
 *
 * Typing goes through a text field rather than any cleverness: Android gives an app the characters
 * its keyboard produced, not key presses, so the field collects them and they are sent as
 * keystrokes. Keys a phone keyboard hasn't got are the chips above it.
 */
@Composable
fun PcTrackpadScreen(su: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val pc = remember { PcInput(context, su) }
    val sink = pc.sink
    BackHandler { onClose() }
    // Leaving the screen gives Android its USB gadget back; nothing is left half-configured.
    DisposableEffect(Unit) { onDispose { pc.stop() } }

    Surface(color = colors.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LauncherIcons.Mouse, null, tint = colors.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mouse and keyboard", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            pc.host != null -> pc.host!!
                            pc.link == PcLink.USB -> "Over USB"
                            pc.link == PcLink.BLUETOOTH -> "Over Bluetooth"
                            else -> "Not connected to a PC yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                FilledTonalButton(
                    onClick = onClose,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.errorContainer,
                        contentColor = colors.onErrorContainer,
                    ),
                ) { Text("Done") }
            }

            Spacer(Modifier.height(12.dp))
            // Pick a way across. Exclusive: two pointers would fight over the same gestures.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { if (pc.link == PcLink.USB) pc.stop() else pc.startUsb(scope) },
                    enabled = pc.usbSupported || pc.link == PcLink.USB,
                    modifier = Modifier.weight(1f),
                ) { Text(if (pc.link == PcLink.USB) "Stop USB" else "Use USB cable") }
                FilledTonalButton(
                    onClick = { if (pc.link == PcLink.BLUETOOTH) pc.stop() else pc.startBluetooth() },
                    enabled = pc.bluetoothSupported || pc.link == PcLink.BLUETOOTH,
                    modifier = Modifier.weight(1f),
                ) { Text(if (pc.link == PcLink.BLUETOOTH) "Stop Bluetooth" else "Use Bluetooth") }
            }
            if (!pc.usbSupported) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "USB needs root, and a kernel with the HID gadget. Bluetooth needs neither.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (pc.status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(pc.status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }

            Spacer(Modifier.height(12.dp))
            if (sink == null) {
                // Nothing to drive yet: say so rather than offer a pad that goes nowhere.
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Choose USB or Bluetooth above.\nThe PC will see an ordinary mouse and keyboard,\nwith nothing to install at its end.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                TrackpadSurface(sink, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                TrackpadKeys(sink)
                Spacer(Modifier.height(8.dp))
                // Characters the Android keyboard produced, sent on as keystrokes.
                val focus = remember { FocusRequester() }
                var typed by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { text ->
                        // Only ever the new characters: the field is a conduit, not a document.
                        if (text.length > typed.length) sink.type(text.substring(typed.length))
                        else if (text.length < typed.length) sink.keys(KeyEvent.KEYCODE_DEL)
                        typed = ""
                    },
                    label = { Text("Type to the PC") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { sink.keys(KeyEvent.KEYCODE_ENTER) }, label = { Text("Enter") })
                    AssistChip(onClick = { sink.keys(KeyEvent.KEYCODE_DEL) }, label = { Text("Backspace") })
                    AssistChip(onClick = { sink.keys(KeyEvent.KEYCODE_SPACE) }, label = { Text("Space") })
                }
                Spacer(Modifier.height(8.dp))
                TrackpadButtons(sink, onKeyboard = { runCatching { focus.requestFocus() } })
            }
        }
    }
}
