// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import android.content.Context
import android.system.Os
import android.util.Log
import com.daryan.linuxlauncher.store.Tar
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Hardware graphics for GUI apps on Adreno phones: our own Mesa build (scripts/gpu/build-mesa.sh)
 * with Turnip, Mesa's Vulkan driver, talking to Android's GPU device (/dev/kgsl-3d0), and Zink
 * giving OpenGL on top of it. It lives apart from the distro's Mesa, in [GUEST_DIR], and apps
 * are pointed at it through their environment ([ENV]), so turning it off is just not doing that.
 */
object GpuDriver {
    const val GUEST_DIR = "/opt/linuxlauncher-gpu"
    private const val ASSET = "gpu/mesa-turnip.tgz"
    private const val VERSION_ASSET = "gpu/VERSION"
    private const val KGSL = "/dev/kgsl-3d0"
    private const val TAG = "GpuDriver"

    /**
     * What makes apps use it. Frames reach the X server as plain images: Termux:X11's DRI3
     * hangs Mesa (see LIBGL_DRI3_DISABLE), so GLX goes through Zink's Kopper "DRI2" path and
     * Vulkan presents in software ("sw"), without SysV shared memory, which Android doesn't
     * have ("noshm": with it, presenting dies of SIGBUS). Checked on an Adreno 740: glxgears
     * at ~240 fps, Firefox and HandBrake (GTK 4, EGL) drawing.
     */
    val ENV = mapOf(
        "LD_LIBRARY_PATH" to "$GUEST_DIR/lib",
        "__GLX_VENDOR_LIBRARY_NAME" to "mesa",
        "__EGL_VENDOR_LIBRARY_FILENAMES" to "$GUEST_DIR/share/glvnd/egl_vendor.d/50_mesa.json",
        "VK_ICD_FILENAMES" to "$GUEST_DIR/share/vulkan/icd.d/freedreno_icd.aarch64.json",
        "LIBGL_DRIVERS_PATH" to "$GUEST_DIR/lib/dri",
        "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
        "GALLIUM_DRIVER" to "zink",
        "MESA_VK_WSI_DEBUG" to "sw,noshm",
        "LIBGL_KOPPER_DRI2" to "1",
        "TU_DEBUG" to "noconform",
    )

    /** Whether this build carries the driver and the phone has an Adreno GPU the app can open. */
    fun available(context: Context): Boolean =
        File(KGSL).canRead() && runCatching { context.assets.open(VERSION_ASSET).close() }.isSuccess

    /** Whether this build's driver is unpacked in [rootfs], ready to use. */
    fun installed(context: Context, rootfs: File): Boolean = available(context) && runCatching {
        val version = context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        File(rootfs, "${GUEST_DIR.removePrefix("/")}/.version").readText().trim() == version
    }.getOrDefault(false)

    /**
     * Unpacks the driver into [rootfs] unless that version is already there. Returns false when
     * it couldn't; apps then just run without it.
     */
    fun install(context: Context, rootfs: File): Boolean = runCatching {
        val version = context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        val dir = File(rootfs, GUEST_DIR.removePrefix("/"))
        val marker = File(dir, ".version")
        if (marker.takeIf { it.exists() }?.readText()?.trim() == version) return true
        dir.deleteRecursively()
        context.assets.open(ASSET).use { raw ->
            Tar.extract(GZIPInputStream(raw.buffered()), rootfs) { target, link -> Os.symlink(target, link.path) }
        }
        marker.writeText(version)
        true
    }.onFailure { Log.w(TAG, "couldn't unpack the GPU driver", it) }.getOrDefault(false)
}
