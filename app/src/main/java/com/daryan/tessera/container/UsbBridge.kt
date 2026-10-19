// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * USB devices for PRoot apps. Android doesn't let apps open /dev/bus/usb nodes, so the launcher
 * opens each device an app was granted through Android's USB permission ([open]) and the app's
 * process inherits the files. Inside, scripts/usb/build-usb.sh's shim (preloaded) answers opens
 * of those paths with them, and its libusb finds them in /dev/bus/usb, where the session gets a
 * stand-in file per device ([env], [makeTree]). Chroot apps open the real nodes instead.
 */
object UsbBridge {
    const val GUEST_DIR = "/opt/tessera-usb"
    const val USB_PATH = "/dev/bus/usb/"
    private const val TAG = "UsbBridge"

    private val bundle = OptBundle(GUEST_DIR, "usb/usb-bridge.tgz", "usb/VERSION", TAG)

    /** Whether this build carries the guest half. */
    fun available(context: Context): Boolean = bundle.available(context)

    /** Unpacks the guest half into [rootfs] unless that version is already there. */
    fun install(context: Context, rootfs: File): Boolean = bundle.install(context, rootfs)

    /** Devices opened for one session. */
    class Opened(
        /** Guest path -> fd number the app's process inherits. */
        val fds: Map<String, Int>,
        /** Granted devices that couldn't be opened: unplugged, or permission refused. */
        val missing: List<String>,
        private val inherited: List<ParcelFileDescriptor>,
        private val connections: List<UsbDeviceConnection>,
    ) {
        /** Once the app's process has started with its copies, the launcher's go. */
        fun closeInherited() = inherited.forEach { runCatching { it.close() } }

        /** When the session ends. */
        fun close() {
            closeInherited()
            connections.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Opens the USB devices among [devices] (granted paths), asking Android's permission for
     * each one that doesn't have it yet. Must run on the main thread's coroutine context or one
     * that may wait for the user's answer.
     */
    suspend fun open(context: Context, devices: List<String>): Opened {
        val manager = context.getSystemService(UsbManager::class.java)
        val plugged = manager?.deviceList?.values.orEmpty().associateBy { it.deviceName }
        val fds = linkedMapOf<String, Int>()
        val missing = mutableListOf<String>()
        val inherited = mutableListOf<ParcelFileDescriptor>()
        val connections = mutableListOf<UsbDeviceConnection>()
        for (path in devices.filter { it.startsWith(USB_PATH) }.distinct()) {
            val device = plugged[path]
            if (manager == null || device == null || !(manager.hasPermission(device) || requestPermission(context, manager, device))) {
                missing += path
                continue
            }
            val connection = manager.openDevice(device)
            if (connection == null) {
                missing += path
                continue
            }
            // A copy without close-on-exec, so the app's process inherits it.
            val copy = ParcelFileDescriptor.fromFd(connection.fileDescriptor)
            Pty.fcntlInt(copy.fd, OsConstants.F_SETFD, 0)
            connections += connection
            inherited += copy
            fds[path] = copy.fd
        }
        return Opened(fds, missing, inherited, connections)
    }

    /** What a bridged session's environment needs, given the one it would have had ([env]). */
    fun env(fds: Map<String, Int>, env: Map<String, String>): Map<String, String> = mapOf(
        "TESSERA_USB_FDS" to fds.entries.joinToString(";") { (path, fd) -> "$path=$fd" },
        "LD_PRELOAD" to listOfNotNull("$GUEST_DIR/lib/tessera-usb.so", env["LD_PRELOAD"]).joinToString(":"),
        // Its libusb ahead of Debian's.
        "LD_LIBRARY_PATH" to listOfNotNull("$GUEST_DIR/lib", env["LD_LIBRARY_PATH"]).joinToString(":"),
    )

    /** A stand-in file per bridged device under [dir] (bound at /dev/bus/usb), for directory scans. */
    fun makeTree(dir: File, fds: Map<String, Int>) {
        dir.deleteRecursively()
        fds.keys.forEach { path ->
            File(dir, path.removePrefix(USB_PATH)).apply { parentFile?.mkdirs(); createNewFile() }
        }
    }

    private suspend fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val action = "${context.packageName}.USB_PERMISSION.${device.deviceId}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // Mutable: the USB service adds the device and the answer to it.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(context, device.deviceId, Intent(action).setPackage(context.packageName), flags)
            manager.requestPermission(device, intent)
        }
}
