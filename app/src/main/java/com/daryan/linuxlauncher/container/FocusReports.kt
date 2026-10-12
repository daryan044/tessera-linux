// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import android.os.FileObserver
import java.io.File
import java.io.RandomAccessFile

/**
 * Follows the focus watcher's reports for one session (see [guestCommand]): calls [onChange]
 * with true when a text field gets focus, false when focus moves to anything else. Calls come
 * on FileObserver's thread.
 */
@Suppress("DEPRECATION") // FileObserver(File, Int) needs API 29.
class FocusReports(private val dir: File, private val onChange: (Boolean) -> Unit) :
    FileObserver(dir.path, CREATE or MODIFY) {

    private val file = File(dir, FOCUS_FILE_NAME)
    // Only what's written after we start; earlier reports are about focus we've already handled.
    private var offset = file.length()

    override fun onEvent(event: Int, path: String?) {
        if (path != FOCUS_FILE_NAME) return
        val fresh = runCatching { read() }.getOrNull() ?: return
        parse(fresh)?.let(onChange)
    }

    private fun read(): String = RandomAccessFile(file, "r").use { f ->
        if (f.length() < offset) offset = 0 // a new watcher started over
        val bytes = ByteArray((f.length() - offset).toInt())
        f.seek(offset)
        f.readFully(bytes)
        offset += bytes.size
        String(bytes)
    }

    companion object {
        /** The latest complete report in [text]: true for "text", false for "none". */
        fun parse(text: String): Boolean? =
            text.lines().map(String::trim).lastOrNull { it == "text" || it == "none" }?.let { it == "text" }
    }
}
