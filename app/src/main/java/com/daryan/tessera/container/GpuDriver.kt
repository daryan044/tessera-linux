// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
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
    const val MALI = "/dev/mali0"
    private const val TAG = "GpuDriver"

    fun isMali(): Boolean = File(MALI).exists() || File(MALI).canRead()
    fun isAdreno(): Boolean = File(KGSL).exists() || File(KGSL).canRead()

    fun deviceNode(): String = when {
        isMali() -> MALI
        isAdreno() -> KGSL
        else -> KGSL
    }

    private val BASE_ENV = mapOf(
        "LD_LIBRARY_PATH" to "$GUEST_DIR/lib",
        "__GLX_VENDOR_LIBRARY_NAME" to "mesa",
        "__EGL_VENDOR_LIBRARY_FILENAMES" to "$GUEST_DIR/share/glvnd/egl_vendor.d/50_mesa.json",
        "LIBGL_DRIVERS_PATH" to "$GUEST_DIR/lib/dri",
        "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
        "GALLIUM_DRIVER" to "zink",
        "MESA_VK_WSI_DEBUG" to "sw,noshm",
        "LIBGL_KOPPER_DRI2" to "1",
    )

    /** What makes an app use the driver, with [fixes] for its drawing bugs. */
    fun env(fixes: Set<GpuFix>): Map<String, String> {
        val isMaliGpu = isMali()
        val icd = if (isMaliGpu) {
            "$GUEST_DIR/share/vulkan/icd.d/panfrost_icd.aarch64.json"
        } else {
            "$GUEST_DIR/share/vulkan/icd.d/freedreno_icd.aarch64.json"
        }
        val extra = if (isMaliGpu) {
            mapOf(
                "PAN_I_WANT_A_BROKEN_VULKAN_DRIVER" to "1",
                "PAN_MESA_DEBUG" to "sync",
            )
        } else {
            mapOf("TU_DEBUG" to (listOf("noconform") + fixes.map { it.tuDebug }).joinToString(","))
        }
        return BASE_ENV + ("VK_ICD_FILENAMES" to icd) + extra
    }

    private val turnipBundle = OptBundle(GUEST_DIR, "gpu/mesa-turnip.tgz", "gpu/VERSION", TAG)
    private val panvkBundle = OptBundle(GUEST_DIR, "gpu/mesa-panvk.tgz", "gpu/VERSION-panvk", TAG)
    private val bundle get() = if (isMali()) panvkBundle else turnipBundle

    /**
     * Whether this build carries the driver and the phone has an Adreno or Mali GPU the app can open.
     */
    fun available(context: Context): Boolean =
        (isAdreno() || isMali()) && bundle.available(context) && supports(renderer(context))

    /**
     * The Adreno models with 700 and 800 numbers that the bundled Mesa's Turnip has (Mesa 26.2:
     * src/freedreno/common/freedreno_devices.py); others in those series are newer than it.
     * Older series are left alone: Turnip has nearly all of them, under names that don't always
     * match Android's (Android's 662 is Mesa's 644).
     */
    private val TURNIP_7XX_8XX = setOf(702, 704, 725, 730, 735, 740, 750, 810, 829, 830, 840)

    /** Whether the driver drives the GPU Android names [renderer]; true when unknown or Mali. */
    internal fun supports(renderer: String?): Boolean {
        if (isMali()) return true
        val model = renderer?.let { Regex("""Adreno\D{0,8}?(\d{3})(?!\d)""").find(it) }?.groupValues?.get(1)?.toInt() ?: return true
        return model !in 700..899 || model in TURNIP_7XX_8XX
    }

    /** What the diagnostics say about the GPU. */
    fun describe(context: Context): String {
        if (isMali()) {
            val name = renderer(context) ?: "Mali GPU"
            return "$name (PanVK kbase)"
        }
        if (!isAdreno()) return "not a supported GPU (no kgsl-3d0 or mali0)"
        val name = renderer(context) ?: return "Adreno, model unknown"
        return if (supports(name)) name else "$name, which this build's GPU driver doesn't support yet"
    }

    @Volatile private var cachedRenderer: String? = null

    /**
     * The GPU's name, from Android's OpenGL ES ("Adreno (TM) 710"), or null if it can't be read.
     * Asked once per system build: it takes an EGL context.
     */
    fun renderer(context: Context): String? {
        cachedRenderer?.let { return it.ifEmpty { null } }
        val prefs = context.getSharedPreferences(TAG, Context.MODE_PRIVATE)
        val key = "renderer:${android.os.Build.FINGERPRINT}"
        val name = prefs.getString(key, null) ?: (probeRenderer() ?: "").also { prefs.edit().putString(key, it).apply() }
        cachedRenderer = name
        return name.ifEmpty { null }
    }

    /** On a thread of its own, so no context current on the caller's (Termux:X11's) is touched. */
    private fun probeRenderer(): String? {
        var name: String? = null
        val t = Thread({ name = runCatching(::eglRenderer).onFailure { android.util.Log.w(TAG, "reading the GPU's name", it) }.getOrNull() }, "gpu-probe")
        t.start()
        t.join(5000)
        return name
    }

    private fun eglRenderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        val config = configs[0] ?: return null
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return null
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            return GLES20.glGetString(GLES20.GL_RENDERER)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            // Not eglTerminate: the display is the whole process's, and Termux:X11 draws with it.
        }
    }

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
