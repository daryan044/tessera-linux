// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

/** The parts of a freedesktop `.desktop` file needed to list and launch the app. */
data class DesktopEntry(
    val name: String?,
    val exec: List<String>,
    val terminal: Boolean,
    val icon: String?,
    val comment: String? = null,
    val categories: List<String> = emptyList(),
    /** Not for menus: NoDisplay, Hidden, not an application, or only for another desktop. */
    val hidden: Boolean = false,
) {
    companion object {
        /** Reads the [Desktop Entry] group; null if it has no usable Exec line. */
        fun parse(text: String): DesktopEntry? {
            val keys = mutableMapOf<String, String>()
            var inMain = false
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.startsWith("[")) {
                    inMain = line == "[Desktop Entry]"
                    continue
                }
                if (!inMain || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                keys.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
            }
            val exec = keys["Exec"]?.let(::splitExec)?.takeIf { it.isNotEmpty() } ?: return null
            fun flag(key: String) = keys[key].equals("true", ignoreCase = true)
            val type = keys["Type"]
            return DesktopEntry(
                name = keys["Name"],
                exec = exec,
                terminal = flag("Terminal"),
                icon = keys["Icon"],
                comment = keys["Comment"] ?: keys["GenericName"],
                categories = keys["Categories"].orEmpty().split(';').filter { it.isNotBlank() },
                hidden = flag("NoDisplay") || flag("Hidden") || (type != null && type != "Application") ||
                    !keys["OnlyShowIn"].isNullOrBlank(),
            )
        }

        /**
         * Splits an Exec value into argv the way GLib does (a shell's quoting: double quotes,
         * single quotes, backslashes), dropping field codes (%f, %U, ...) since the launcher
         * starts apps without files. The spec only names double quotes, but desktops launch
         * through GLib, so files rely on single ones too: Debian's 2048 runs
         * `sh -c '/usr/games/2048;echo;...'`, which split at every space before.
         */
        fun splitExec(exec: String): List<String> {
            val args = mutableListOf<String>()
            val cur = StringBuilder()
            var quoted = false
            var hasArg = false
            var i = 0
            while (i < exec.length) {
                val c = exec[i]
                when {
                    c == '\\' && i + 1 < exec.length -> { cur.append(exec[i + 1]); i++; hasArg = true }
                    c == '"' -> { quoted = !quoted; hasArg = true }
                    // Literal up to the next single quote, as in a shell.
                    c == '\'' && !quoted -> {
                        val end = exec.indexOf('\'', i + 1).let { if (it < 0) exec.length else it }
                        cur.append(exec, i + 1, end)
                        hasArg = true
                        i = end
                    }
                    c.isWhitespace() && !quoted -> {
                        if (hasArg) args += cur.toString()
                        cur.clear(); hasArg = false
                    }
                    else -> { cur.append(c); hasArg = true }
                }
                i++
            }
            if (hasArg) args += cur.toString()
            return args
                .filterNot { FIELD_CODE.matches(it) }
                .map { it.replace("%%", "%") }
        }

        private val FIELD_CODE = Regex("%[fFuUdDnNickvm]")
    }
}
