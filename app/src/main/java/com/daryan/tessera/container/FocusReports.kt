// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the focus watcher's reports for one session (see [guestCommand]) as they're appended:
 * true when a text field got focus, false when focus moved to anything else. [SessionFiles]
 * calls [poll] when the file changes.
 */
class FocusReports(dir: File) {
    private val file = File(dir, FOCUS_FILE_NAME)
    // Only what's written after we start; earlier reports are about focus already handled.
    private var offset = file.length()

    /** The latest report written since the last call, if any. */
    fun poll(): Boolean? = runCatching { parse(read()) }.getOrNull()

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
