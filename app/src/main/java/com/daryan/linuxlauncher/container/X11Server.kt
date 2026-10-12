// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LorieApp
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/**
 * One Termux:X11 server per GUI session: its own display number, its own socket in the
 * session's private /tmp, and its own Android window (a tagged MainActivity document).
 */
class X11Server(private val context: Context, private val paths: ContainerPaths) {

    /** Starts the server for [session]. Returns once its socket accepts connections. */
    suspend fun start(session: Session): Process {
        val display = session.display ?: throw IllegalArgumentException("session has no display")
        val xkb = installXkb()
        // In chroot mode clients run as uid 1000, not as the app; let them reach the socket.
        paths.x11SocketDir(session).apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }

        val proc = ProcessBuilder(
            "/system/bin/app_process", "/", CmdEntryPoint::class.java.name,
            ":$display", "-tag", tag(session),
        ).redirectErrorStream(true).apply {
            environment()["CLASSPATH"] = context.applicationInfo.sourceDir
            // We extract native libs (PRoot needs that), so lorie can't dlopen from the APK.
            environment()["XLORIE_LIBRARY"] = File(paths.nativeLibDir, "libXlorie.so").path
            environment()["TMPDIR"] = paths.sessionTmp(session).path
            environment()["XKB_CONFIG_ROOT"] = xkb.path
        }.start()

        val socket = File(paths.x11SocketDir(session), "X$display")
        repeat(100) {
            if (socket.exists()) return proc
            if (!proc.isAlive) {
                val out = proc.inputStream.bufferedReader().readText().trim().lines().takeLast(3)
                throw IOException("X server exited (${proc.exitValue()}): ${out.joinToString(" / ")}")
            }
            delay(50)
        }
        proc.destroy()
        throw IOException("X server didn't create $socket within 5 s")
    }

    /** Opens (or brings back) the session's window. */
    fun showWindow(session: Session) {
        context.startActivity(
            Intent().setClassName(context, TAGGED_WINDOW)
                .setData(Uri.parse("termux-x11-window://${tag(session)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Closes the session's window; the server itself is stopped by destroying its process. */
    fun closeWindow(session: Session) {
        (context.applicationContext as? LorieApp)?.onBroadcastReceive(
            Intent(ACTION_STOP).putExtra(CmdEntryPoint.EXTRA_DOCUMENT_TAG, tag(session)),
        )
    }

    /** Copies the bundled xkb data out of the APK once per version; the server reads it as files. */
    private fun installXkb(): File {
        val dir = File(paths.filesDir, "xkb")
        val bundled = runCatching {
            context.assets.open("xkb/VERSION").bufferedReader().use { it.readText().trim() }
        }.getOrNull() ?: throw IOException("this build has no xkb data; run scripts/x11/build-native.sh")
        val marker = File(dir, "VERSION")
        if (marker.takeIf { it.exists() }?.readText()?.trim() == bundled) return dir

        dir.deleteRecursively()
        copyAssetDir("xkb", dir)
        return dir
    }

    private fun copyAssetDir(asset: String, dest: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        children.forEach { copyAssetDir("$asset/$it", File(dest, it)) }
    }

    private fun tag(session: Session) = "linux-${session.id}"

    private companion object {
        // By name: referencing MainActivity's class would drag AppCompat onto our classpath.
        const val TAGGED_WINDOW = "com.termux.x11.MainActivity\$MainActivityTagged"
        const val ACTION_STOP = "com.termux.x11.ACTION_STOP"
    }
}
