// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.daryan.tessera.MouseButton
import java.util.concurrent.Executors

/**
 * The phone as a Bluetooth mouse and keyboard. No root and no cable: Android has an official
 * profile for being a HID peripheral, so the PC sees an ordinary Bluetooth mouse and keyboard
 * once they are paired.
 *
 * Both devices share one descriptor here, told apart by report id, because the profile offers a
 * single pipe where USB gives each its own interface.
 */
@RequiresApi(Build.VERSION_CODES.P)
class BtHidSink private constructor(
    private val profile: BluetoothHidDevice,
    private val adapter: BluetoothAdapter,
) : InputSink, AutoCloseable {

    private val held = mutableSetOf<MouseButton>()

    /** The PC we are sending to; set once it connects to us, or we to it. */
    @Volatile private var host: BluetoothDevice? = null

    override val connected: Boolean get() = host != null

    /**
     * Reports go out on one thread of their own, in order, so a press can be held for a moment
     * before its release without blocking a gesture. Down and up in the same instant is a click
     * of no duration, which a host may drop or merge into whatever follows it.
     */
    private val sender = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission")
    private fun send(id: Int, report: ByteArray, pauseAfterMs: Long = 0) {
        val device = host ?: return
        sender.execute {
            runCatching {
                profile.sendReport(device, id, report)
                if (pauseAfterMs > 0) Thread.sleep(pauseAfterMs)
            }.onFailure { Log.i(TAG, "report not sent: ${it.message}") }
        }
    }

    override fun move(dx: Float, dy: Float) =
        send(HidReports.REPORT_ID_MOUSE, HidReports.mouse(HidReports.buttonBits(held), dx.toInt(), dy.toInt()))

    override fun scroll(dx: Float, dy: Float) {
        val notches = -dy.toInt()
        if (notches != 0) {
            send(HidReports.REPORT_ID_MOUSE, HidReports.mouse(HidReports.buttonBits(held), 0, 0, notches))
        }
    }

    override fun press(button: MouseButton, down: Boolean) {
        if (down) held += button else held -= button
        send(HidReports.REPORT_ID_MOUSE, HidReports.mouse(HidReports.buttonBits(held), 0, 0), CLICK_MS)
    }

    override fun click(button: MouseButton) {
        if (button in held) return
        send(HidReports.REPORT_ID_MOUSE, HidReports.mouse(HidReports.buttonBits(held + button), 0, 0), CLICK_MS)
        send(HidReports.REPORT_ID_MOUSE, HidReports.mouse(HidReports.buttonBits(held), 0, 0), CLICK_MS)
    }

    override fun keys(vararg keyCodes: Int) {
        HidKeymap.stroke(keyCodes)?.let(::tap)
    }

    override fun type(text: String) = HidKeymap.strokes(text).forEach(::tap)

    private fun tap(stroke: HidKeymap.Stroke) {
        send(HidReports.REPORT_ID_KEYBOARD, HidReports.keyboard(stroke.modifiers, listOf(stroke.usage)), CLICK_MS)
        send(HidReports.REPORT_ID_KEYBOARD, HidReports.KEYS_UP, CLICK_MS)
    }

    /** Offers ourselves to [device]; the host may also connect to us on its own. */
    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice) {
        runCatching { profile.connect(device) }
    }

    /** Devices already paired with this phone, as candidates to drive. */
    @SuppressLint("MissingPermission")
    fun paired(): List<BluetoothDevice> = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    override fun close() {
        runCatching { send(HidReports.REPORT_ID_KEYBOARD, HidReports.KEYS_UP) }
        runCatching { host?.let { profile.disconnect(it) } }
        runCatching { profile.unregisterApp() }
        runCatching { sender.shutdownNow() }
        host = null
    }

    companion object {
        private const val TAG = "BtHid"

        /** How long a button or key stays down, and the gap after any such change. */
        private const val CLICK_MS = 24L

        /** Whether this phone can be a Bluetooth keyboard at all. */
        @SuppressLint("MissingPermission")
        fun supported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                runCatching {
                    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
                }.getOrDefault(false)

        /**
         * Registers as a HID device and calls [onReady] once Android hands us the profile, then
         * [onHostChanged] as a PC connects or goes away. Registration is asynchronous, which is
         * why this is a callback rather than a value.
         */
        @RequiresApi(Build.VERSION_CODES.P)
        @SuppressLint("MissingPermission")
        fun open(
            context: Context,
            onReady: (BtHidSink) -> Unit,
            onHostChanged: (BluetoothDevice?) -> Unit,
            onFailed: (String) -> Unit,
        ) {
            val adapter = runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            }.getOrNull()
            if (adapter == null || runCatching { !adapter.isEnabled }.getOrDefault(true)) {
                return onFailed("Bluetooth is off")
            }

            val listener = object : BluetoothProfile.ServiceListener {
                // Android calls this back later and on its own, so anything thrown here reaches
                // no caller: it would take the app down. A withheld permission is a sentence to
                // show, not a crash, so every step of the registration is caught.
                override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile?) {
                    runCatching { register(adapter, proxy, onReady, onHostChanged) }
                        .onFailure { onFailed(reason(it)) }
                }

                override fun onServiceDisconnected(profileId: Int) = onHostChanged(null)
            }

            val asked = runCatching {
                adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)
            }.getOrElse { return onFailed(reason(it)) }
            if (!asked) onFailed("this phone has no HID device profile")
        }

        /** Hands [proxy] our descriptors, and ourselves to [onReady] once Android accepts them. */
        @RequiresApi(Build.VERSION_CODES.P)
        @SuppressLint("MissingPermission")
        private fun register(
            adapter: BluetoothAdapter,
            proxy: BluetoothProfile?,
            onReady: (BtHidSink) -> Unit,
            onHostChanged: (BluetoothDevice?) -> Unit,
        ) {
            val profile = proxy as? BluetoothHidDevice ?: error("no HID device profile")
            val sink = BtHidSink(profile, adapter)
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Tessera",
                "Phone as mouse and keyboard",
                "Tessera",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidReports.COMBINED_DESCRIPTOR,
            )
            val callback = object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    if (registered) onReady(sink)
                }

                override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                    val now = device?.takeIf { state == BluetoothProfile.STATE_CONNECTED }
                    sink.host = now
                    onHostChanged(now)
                }
            }
            if (!profile.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)) {
                error("Android would not register us as a HID device")
            }
        }

        /** What went wrong, in the user's terms: these are nearly always a missing permission. */
        private fun reason(e: Throwable): String = when {
            e is SecurityException -> "Allow the Nearby devices permission to be a Bluetooth keyboard."
            else -> e.message ?: "Bluetooth refused us: ${e::class.java.simpleName}"
        }
    }
}
