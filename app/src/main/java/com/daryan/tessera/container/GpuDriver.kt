// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.Context
import android.system.Os
import android.util.Log
import com.daryan.tessera.store.Tar
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Hardware graphics for GUI apps on Adreno phones: our own Mesa build (scripts/gpu/build-mesa.sh)
 * with Turnip, Mesa's Vulkan driver, talking to Android's GPU device (/dev/kgsl-3d0), and Zink
 * giving OpenGL on top of it. It lives apart from the distro's Mesa, in [GUEST_DIR], and apps
 * are pointed at it through their environment ([ENV]), so turning it off is just not doing that.
 */
object GpuDriver {
    const val GUEST_DIR = "/opt/tessera-gpu"
    private const val ASSET = "gpu/mesa-turnip.tgz"
    private const val VERSION_ASSET = "gpu/VERSION"
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

    /** The driver version in the chroot system, as [installAsRoot] last found or left it. */
    @Volatile private var chrootVersion: String? = null

    /** Whether this build's driver is in the chroot system; known once [installAsRoot] ran. */
    fun installedAsRoot(context: Context): Boolean = available(context) && runCatching {
        context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() } == chrootVersion
    }.getOrDefault(false)

    /**
     * Unpacks the driver into the chroot system at [rootfs] through [su] (the app can't write
     * there, or even look), unless that version is already there. Blocks; call off the main thread.
     */
    fun installAsRoot(context: Context, rootfs: File, su: String): Boolean = runCatching {
        val version = context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        val dir = "${rootfs.path}$GUEST_DIR"
        if (runSu(su, "cat '$dir/.version' 2>/dev/null")?.trim() == version) {
            chrootVersion = version
            return true
        }
        val archive = File(context.cacheDir, "mesa-turnip.tgz")
        context.assets.open(ASSET).use { input -> archive.outputStream().use { input.copyTo(it) } }
        try {
            // Magisk's busybox tar when there is one (toybox's lacks -z on older Android).
            val script = """
                tar=tar
                for bb in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
                  [ -x "${'$'}bb" ] && tar="${'$'}bb tar" && break
                done
                rm -rf '$dir' && ${'$'}tar -xzf '${archive.path}' -C '${rootfs.path}' && echo '$version' > '$dir/.version' && echo ok
            """.trimIndent()
            check(runSu(su, script)?.lines()?.lastOrNull { it.isNotBlank() } == "ok") { "unpacking failed" }
        } finally {
            archive.delete()
        }
        chrootVersion = version
        true
    }.onFailure { Log.w(TAG, "couldn't unpack the GPU driver as root", it) }.getOrDefault(false)

    private fun runSu(su: String, script: String): String? {
        val p = ProcessBuilder(su, "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return null }
        return out.takeIf { p.exitValue() == 0 }
    }
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
