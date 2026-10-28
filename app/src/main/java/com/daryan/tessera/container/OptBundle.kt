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
 * A tree of files the APK carries for the container, unpacked into its /opt: the GPU driver,
 * the USB bridge, the theme pack. Built by a script under scripts/ into build/<x>/assets
 * (bundled by app/build.gradle.kts), as [asset] (a .tgz holding [guestDir] relative to /) and a
 * VERSION file beside it; builds without them just don't offer what they bring. A `.version`
 * file in [guestDir] says which version is unpacked there, so each is unpacked once per update.
 */
class OptBundle(
    /** Where it lives inside the container, such as /opt/tessera-gpu. */
    val guestDir: String,
    /** The archive, under the APK's assets. */
    private val asset: String,
    /** Its version file, under the APK's assets. */
    private val versionAsset: String,
    private val tag: String,
) {
    /** Whether this build carries it. */
    fun available(context: Context): Boolean = version(context) != null

    private fun version(context: Context): String? =
        runCatching { context.assets.open(versionAsset).bufferedReader().use { it.readText().trim() } }.getOrNull()

    /** Whether this build's version is unpacked in [rootfs], ready to use. */
    fun installed(context: Context, rootfs: File): Boolean {
        val version = version(context) ?: return false
        return runCatching { File(rootfs, "${guestDir.removePrefix("/")}/.version").readText().trim() == version }.getOrDefault(false)
    }

    /**
     * Unpacks it into [rootfs] unless that version is already there. Returns false when it
     * couldn't; what it brings is then just missing.
     */
    fun install(context: Context, rootfs: File): Boolean = runCatching {
        val version = checkNotNull(version(context)) { "not in this build" }
        val dir = File(rootfs, guestDir.removePrefix("/"))
        val marker = File(dir, ".version")
        if (marker.takeIf { it.exists() }?.readText()?.trim() == version) return true
        dir.deleteRecursively()
        context.assets.open(asset).use { raw ->
            Tar.extract(GZIPInputStream(raw.buffered()), rootfs) { target, link -> Os.symlink(target, link.path) }
        }
        marker.writeText(version)
        true
    }.onFailure { Log.w(tag, "couldn't unpack $asset", it) }.getOrDefault(false)

    /** The version in the chroot system, as [installAsRoot] last found or left it. */
    @Volatile private var chrootVersion: String? = null

    /** Whether this build's version is in the chroot system; known once [installAsRoot] ran. */
    fun installedAsRoot(context: Context): Boolean = version(context).let { it != null && it == chrootVersion }

    /**
     * Unpacks it into the chroot system at [rootfs] through [su] (the app can't write there, or
     * even look), unless that version is already there. Blocks; call off the main thread.
     */
    fun installAsRoot(context: Context, rootfs: File, su: String): Boolean = runCatching {
        val version = checkNotNull(version(context)) { "not in this build" }
        val dir = "${rootfs.path}$guestDir"
        if (runSu(su, "cat '$dir/.version' 2>/dev/null")?.trim() == version) {
            chrootVersion = version
            return true
        }
        val archive = File(context.cacheDir, asset.substringAfterLast('/'))
        context.assets.open(asset).use { input -> archive.outputStream().use { input.copyTo(it) } }
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
    }.onFailure { Log.w(tag, "couldn't unpack $asset as root", it) }.getOrDefault(false)

    private fun runSu(su: String, script: String): String? {
        val p = ProcessBuilder(su, "-c", RootShell.line(script)).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return null }
        return out.takeIf { p.exitValue() == 0 }
    }
}
