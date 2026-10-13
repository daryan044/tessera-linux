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
)

@Serializable
data class XWindows(val active: Long = 0, val windows: List<XWindow> = emptyList()) {
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
    private val onWindows: (XWindows) -> Unit,
) : FileObserver(dir.path, CREATE or MODIFY or MOVED_TO or CLOSE_WRITE) {
    private val focus = FocusReports(dir)

    override fun onEvent(event: Int, path: String?) {
        when (path) {
            WINDOWS_FILE_NAME -> if (event and (MOVED_TO or CLOSE_WRITE) != 0) read()?.let(onWindows)
            FOCUS_FILE_NAME -> if (event and (CREATE or MODIFY) != 0) focus.poll()?.let(onFocus)
        }
    }

    /** The latest report, if there is one yet. */
    fun read(): XWindows? = runCatching { parseWindows(File(dir, WINDOWS_FILE_NAME).readText()) }.getOrNull()

    fun activate(id: Long) = send("activate $id")

    fun close(id: Long) = send("close $id")

    /** Non-blocking: when the watcher isn't running there's no reader, and nothing to tell. */
    private fun send(line: String) {
        runCatching {
            val fd = Os.open(File(dir, WINDOW_COMMANDS_NAME).path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
            try {
                val bytes = "$line\n".toByteArray()
                Os.write(fd, bytes, 0, bytes.size)
            } finally {
                Os.close(fd)
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseWindows(text: String): XWindows = json.decodeFromString(XWindows.serializer(), text)
    }
}
