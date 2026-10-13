// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.input

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the phone is reaching the PC, or why it isn't. */
enum class PcLink { OFF, USB, BLUETOOTH }

/**
 * The phone as a mouse and keyboard for a PC: owns whichever transport is in use and gives the
 * trackpad an [InputSink] to drive.
 *
 * The two are deliberately exclusive. Being a USB mouse and a Bluetooth mouse at once would have
 * the host see two pointers fighting over the same gestures.
 */
class PcInput(private val context: Context, private val su: String?) {

    var link by mutableStateOf(PcLink.OFF)
        private set

    /** What to tell the user: progress, or why a transport would not start. */
    var status by mutableStateOf("")
        private set

    var sink by mutableStateOf<InputSink?>(null)
        private set

    /** The PC's name over Bluetooth, once it connects. */
    var host by mutableStateOf<String?>(null)
        private set

    val usbSupported get() = UsbHidSink.supported(su)
    val bluetoothSupported get() = BtHidSink.supported(context)

    /** True once reports are actually going somewhere. */
    val ready get() = sink?.connected == true

    /**
     * Becomes a USB mouse and keyboard. Takes a moment and touches the USB gadget, so it runs off
     * the main thread and reports back through [status].
     */
    fun startUsb(scope: CoroutineScope) {
        val root = su ?: run {
            status = "USB needs root: the HID gadget is Android's to configure, not an app's."
            return
        }
        stop()
        status = "Setting up the USB mouse and keyboard…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { UsbHidSink.open(context, root) }
            result.fold(
                onSuccess = {
                    sink = it
                    link = PcLink.USB
                    status = "The PC sees a USB mouse and keyboard."
                },
                onFailure = {
                    link = PcLink.OFF
                    status = it.message ?: "The USB gadget would not take a HID function."
                },
            )
        }
    }

    /** Offers the phone as a Bluetooth mouse and keyboard; the PC still has to pair with it. */
    fun startBluetooth() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            status = "Bluetooth keyboards need Android 9 or newer."
            return
        }
        stop()
        status = "Registering as a Bluetooth keyboard…"
        BtHidSink.open(
            context,
            onReady = {
                sink = it
                link = PcLink.BLUETOOTH
                status = "Ready. Pair with “Tessera” on the PC, or pick it there from this phone."
            },
            onHostChanged = { device ->
                host = device?.name
                status = if (device == null) {
                    "Waiting for a PC to connect."
                } else {
                    "Connected to ${device.name ?: "the PC"}."
                }
            },
            onFailed = {
                link = PcLink.OFF
                status = it
            },
        )
    }

    /** Says something in the status line without changing what is connected. */
    fun note(text: String) {
        status = text
    }

    /** Puts everything back: the gadget as Android had it, the profile unregistered. */
    fun stop() {
        (sink as? AutoCloseable)?.let { closeable -> runCatching { closeable.close() } }
        sink = null
        host = null
        link = PcLink.OFF
        status = ""
    }
}
