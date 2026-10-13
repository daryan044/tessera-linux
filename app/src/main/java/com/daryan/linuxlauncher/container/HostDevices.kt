// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import android.content.Context
import android.hardware.usb.UsbManager
import java.io.File

/** A device node an app can be given, as the device picker offers it. */
data class HostDevice(val path: String, val label: String, val detail: String)

/** What can be handed to an app as a device: plugged-in USB devices and the GPU. */
object HostDevices {
    fun list(context: Context): List<HostDevice> = buildList {
        val usb = context.getSystemService(UsbManager::class.java)
        usb?.deviceList?.values.orEmpty().sortedBy { it.deviceName }.forEach { d ->
            val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB device" }
            add(HostDevice(d.deviceName, name, "USB %04x:%04x · %s".format(d.vendorId, d.productId, d.deviceName)))
        }
        // The GPU's render node, for apps that drive it themselves (chroot mode).
        if (File("/dev/dri/renderD128").exists()) add(HostDevice("/dev/dri/renderD128", "GPU", "/dev/dri/renderD128"))
    }
}
