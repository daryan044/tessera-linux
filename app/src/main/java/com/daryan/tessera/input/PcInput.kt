// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.input

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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

    /** Computers this phone is paired with, to connect to from here; see [BtHidSink.paired]. */
    var pairedPcs by mutableStateOf<List<BluetoothDevice>>(emptyList())
        private set

    /**
     * Set when the phone should become discoverable, so a PC that never paired with it can find
     * it; the screen asks Android (that takes an activity) and clears it.
     */
    var wantsDiscoverable by mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())

    /** Listens for the cable while the phone is a USB mouse; see [watchCable]. */
    private var cableWatch: BroadcastReceiver? = null
    private val cableGone = Runnable {
        if (link != PcLink.USB) return@Runnable
        stop()
        status = "The cable came out, so the phone stopped being a USB mouse and keyboard."
    }

    /**
     * Ends USB mode when the cable comes out of the PC. Left on, the phone would be a mouse to
     * the next thing it's plugged into, a charger included, and Android rebuilding its USB setup
     * for charging around a mouse still being written to can hang the whole phone.
     */
    private fun watchCable() {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // Setting the gadget up drops the link for a moment itself; a real unplug lasts.
                if (android.os.SystemClock.elapsedRealtime() - startedAt < 3000) return
                if (intent.getBooleanExtra("connected", true)) main.removeCallbacks(cableGone)
                else main.postDelayed(cableGone, 800)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(USB_STATE), ContextCompat.RECEIVER_EXPORTED)
        cableWatch = receiver
    }

    /** Takes off HID functions an earlier run left on, if any; see [UsbHidSink.cleanUp]. */
    fun cleanUp(scope: CoroutineScope) {
        val root = su ?: return
        scope.launch(Dispatchers.IO) { UsbHidSink.cleanUp(context, root) }
    }

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
                    watchCable()
                },
                onFailure = {
                    Log.w("PcInput", "usb: ${it.message}", it)
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
        status = REGISTERING
        // Android's callbacks come on its own threads; the state they change is the screen's.
        BtHidSink.open(
            context,
            onReady = { bt ->
                main.post {
                    sink = bt
                    link = PcLink.BLUETOOTH
                    pairedPcs = bt.paired()
                    // A phone answers only devices it knows; to be found and paired it has to be
                    // made discoverable, which only the user can allow.
                    wantsDiscoverable = true
                    status = "On the PC, add a Bluetooth device and pick “${bt.phoneName}”, or connect to a PC below."
                }
            },
            onHostChanged = { device ->
                main.post {
                    host = device?.name
                    status = if (device == null) {
                        "Waiting for a PC to connect."
                    } else {
                        "Connected to ${device.name ?: "the PC"}."
                    }
                }
            },
            onFailed = {
                Log.w("PcInput", "bluetooth: $it")
                main.post {
                    link = PcLink.OFF
                    status = it
                }
            },
        )
        // Some phones accept the request and then never answer it.
        main.postDelayed({
            if (link == PcLink.OFF && status == REGISTERING) {
                status = "Android didn't answer: this phone may not offer being a Bluetooth keyboard."
            }
        }, 8000)
    }

    /** Asks a PC this phone is paired with to take it as its mouse and keyboard. */
    fun connect(device: BluetoothDevice) {
        val bt = sink as? BtHidSink ?: return
        status = "Connecting to ${runCatching { device.name }.getOrNull() ?: "the PC"}…"
        bt.connectTo(device)
    }

    /** Says the phone stayed hidden, after the user declined to make it discoverable. */
    fun stayedHidden() {
        status = "The phone stays hidden, so new PCs can't find it. Connect to a paired PC below, or make it visible."
    }

    /** Says something in the status line without changing what is connected. */
    fun note(text: String) {
        status = text
    }

    /** Puts everything back: the gadget as Android had it, the profile unregistered. */
    fun stop() {
        cableWatch?.let { runCatching { context.unregisterReceiver(it) } }
        cableWatch = null
        main.removeCallbacks(cableGone)
        when (val old = sink) {
            // Root work that can take seconds; never on the main thread.
            is UsbHidSink -> Thread({ runCatching { old.close() } }, "hid-teardown").start()
            is AutoCloseable -> runCatching { old.close() }
        }
        sink = null
        host = null
        pairedPcs = emptyList()
        wantsDiscoverable = false
        link = PcLink.OFF
        status = ""
    }
}

private const val REGISTERING = "Registering as a Bluetooth keyboard…"

/** Android's broadcast of the USB cable's state (hidden in the SDK, sticky, sent to everyone). */
private const val USB_STATE = "android.hardware.usb.action.USB_STATE"
