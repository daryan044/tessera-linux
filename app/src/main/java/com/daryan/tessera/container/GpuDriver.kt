// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.Context
import java.io.File

/**
 * Hardware graphics for GUI apps on Adreno phones: our own Mesa build (scripts/gpu/build-mesa.sh)
 * with Turnip, Mesa's Vulkan driver, talking to Android's GPU device (/dev/kgsl-3d0), and Zink
 * giving OpenGL on top of it. It lives apart from the distro's Mesa, in [GUEST_DIR], and apps
 * are pointed at it through their environment ([ENV]), so turning it off is just not doing that.
 */
object GpuDriver {
    const val GUEST_DIR = "/opt/tessera-gpu"
    /** Adreno's GPU device; world-readable and writable, so apps need no group for it. */
    const val KGSL = "/dev/kgsl-3d0"
    private const val TAG = "GpuDriver"

    /**
     * What makes apps use it. Frames reach the X server as plain images: Termux:X11's DRI3
     * hangs Mesa (see LIBGL_DRI3_DISABLE), so GLX goes through Zink's Kopper "DRI2" path and
     * Vulkan presents in software ("sw"), without SysV shared memory, which Android doesn't
     * have ("noshm": with it, presenting dies of SIGBUS). Checked on an Adreno 740: glxgears
     * at ~240 fps, Firefox and HandBrake (GTK 4, EGL) drawing. Turnip's own options come from
     * the app's [GpuFix]es; see [env].
     */
    private val ENV = mapOf(
        "LD_LIBRARY_PATH" to "$GUEST_DIR/lib",
        "__GLX_VENDOR_LIBRARY_NAME" to "mesa",
        "__EGL_VENDOR_LIBRARY_FILENAMES" to "$GUEST_DIR/share/glvnd/egl_vendor.d/50_mesa.json",
        "VK_ICD_FILENAMES" to "$GUEST_DIR/share/vulkan/icd.d/freedreno_icd.aarch64.json",
        "LIBGL_DRIVERS_PATH" to "$GUEST_DIR/lib/dri",
        "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
        "GALLIUM_DRIVER" to "zink",
        "MESA_VK_WSI_DEBUG" to "sw,noshm",
        "LIBGL_KOPPER_DRI2" to "1",
    )

    /** What makes an app use the driver, with [fixes] for its drawing bugs. */
    fun env(fixes: Set<GpuFix>): Map<String, String> =
        ENV + ("TU_DEBUG" to (listOf("noconform") + fixes.map { it.tuDebug }).joinToString(","))

    private val bundle = OptBundle(GUEST_DIR, "gpu/mesa-turnip.tgz", "gpu/VERSION", TAG)

    /** Whether this build carries the driver and the phone has an Adreno GPU the app can open. */
    fun available(context: Context): Boolean = File(KGSL).canRead() && bundle.available(context)

    /** Whether this build's driver is unpacked in [rootfs], ready to use. */
    fun installed(context: Context, rootfs: File): Boolean = available(context) && bundle.installed(context, rootfs)

    /** Unpacks the driver into [rootfs]; see [OptBundle.install]. */
    fun install(context: Context, rootfs: File): Boolean = bundle.install(context, rootfs)

    /** Whether this build's driver is in the chroot system; known once [installAsRoot] ran. */
    fun installedAsRoot(context: Context): Boolean = available(context) && bundle.installedAsRoot(context)

    /** Unpacks the driver into the chroot system; see [OptBundle.installAsRoot]. */
    fun installAsRoot(context: Context, rootfs: File, su: String): Boolean = bundle.installAsRoot(context, rootfs, su)
}

/**
 * Workarounds for drawing bugs in the driver, chosen per app from its Graphics fixes menu. Each
 * is a Turnip option (TU_DEBUG); [default] ones are on until the user turns them off.
 */
enum class GpuFix(val tuDebug: String, val default: Boolean) {
    /**
     * UBWC, Adreno's framebuffer compression, off. With it, some frames of Chromium-based apps
     * (VS Code) reach the window with only every sixth 16x16 block drawn, the rest black, until
     * the next frame. Costs memory bandwidth.
     */
    NO_COMPRESSION("noubwc", true),

    /** Draws straight to memory instead of in tiles (GMEM), for parts drawn wrong or missing. */
    DIRECT_RENDERING("sysmem", false),

    /** Flushes caches and waits after every draw, for flicker and half-drawn frames. Slow. */
    SYNC_DRAWS("flushall,syncdraw", false),
}
