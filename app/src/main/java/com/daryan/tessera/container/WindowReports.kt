// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.os.FileObserver
import android.system.Os
import android.system.OsConstants
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One of a GUI app's X windows, as [WINDOW_WATCHER] reports it. */
@Serializable
data class XWindow(
    val id: Long,
    val title: String = "",
    /** A window of its own (it gets its own Android task), not a dialog of another. */
    val main: Boolean = true,
    /** The second part of its WM_CLASS ("Firefox-esr"), to tell which app it belongs to. */
    @kotlinx.serialization.SerialName("class") val windowClass: String = "",
    /** Minimized. */
    val hidden: Boolean = false,
    val maximized: Boolean = false,
)

@Serializable
data class XWindows(
    val active: Long = 0,
    val windows: List<XWindow> = emptyList(),
    /** The X screen's size in its pixels, [width, height]; empty until reported. */
    val screen: List<Int> = emptyList(),
) {
    val main get() = windows.filter { it.main }
}

/**
 * Follows what a GUI session's helpers report in its X11 socket directory [dir]: the window
 * watcher's window list ([onWindows]) and the focus watcher's text-field focus ([onFocus]),
 * both called on FileObserver's thread. Also sends the window watcher its commands.
 *
 * One observer for both on purpose: Android's FileObservers on the same directory share one
 * inotify watch, so a second one silently takes the first one's events away.
 */
@Suppress("DEPRECATION") // FileObserver(File, Int) needs API 29.
class SessionFiles(
    private val dir: File,
    private val onFocus: (Boolean) -> Unit,
    /** Taps and menus on a desktop's icons ([DesktopFiles.Events]); desktops only. */
    private val onDesktop: ((DesktopEvent) -> Unit)? = null,
    private val onWindows: (XWindows) -> Unit,
) : FileObserver(dir.path, CREATE or MODIFY or MOVED_TO or CLOSE_WRITE) {
    private val focus = FocusReports(dir)
    private val desktop = DesktopFiles.Events(dir)

    override fun onEvent(event: Int, path: String?) {
        when (path) {
            WINDOWS_FILE_NAME -> if (event and (MOVED_TO or CLOSE_WRITE) != 0) read()?.let(onWindows)
            FOCUS_FILE_NAME -> if (event and (CREATE or MODIFY) != 0) focus.poll()?.let(onFocus)
            DESKTOP_EVENTS_NAME -> if (event and (CREATE or MODIFY) != 0) onDesktop?.let { desktop.poll().forEach(it) }
        }
    }

    /** The latest report, if there is one yet. */
    fun read(): XWindows? = runCatching { parseWindows(File(dir, WINDOWS_FILE_NAME).readText()) }.getOrNull()

    fun activate(id: Long) = send("activate $id")

    fun close(id: Long) = send("close $id")

    fun minimize(id: Long) = send("minimize $id")

    /** Maximizes [id], or restores it when it is. */
    fun toggleMaximize(id: Long) = send("maximize $id")

    /** Puts [id] on the [left] or right half of the screen. */
    fun snap(id: Long, left: Boolean) = send("snap $id ${if (left) "left" else "right"}")

    private fun send(line: String) { sendLine(File(dir, WINDOW_COMMANDS_NAME), line) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseWindows(text: String): XWindows = json.decodeFromString(XWindows.serializer(), text)
    }
}

/**
 * Writes [line] to a helper's command FIFO. Non-blocking: with no helper reading, nothing to
 * tell; false then (and on any other failure).
 */
internal fun sendLine(fifo: File, line: String): Boolean =
    runCatching {
        val fd = Os.open(fifo.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
        try {
            val bytes = "$line\n".toByteArray()
            Os.write(fd, bytes, 0, bytes.size)
        } finally {
            Os.close(fd)
        }
    }.isSuccess
