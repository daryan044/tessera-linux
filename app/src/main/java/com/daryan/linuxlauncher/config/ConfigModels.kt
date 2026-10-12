// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.config

/*
 * Structured views of config files for the side menu's forms. Each model keeps every line it
 * doesn't understand verbatim and in place, and keeps the original text of lines it does
 * understand: a line is only rewritten when the form actually changed it.
 */

// ---------------------------------------------------------------- shell rc files

/** One line of a shell rc file, as far as the form cares. [raw] is the line as read. */
sealed interface RcLine {
    data class Export(val key: String, val value: String, val raw: String? = null) : RcLine
    data class Alias(val name: String, val command: String, val raw: String? = null) : RcLine
    data class Other(val text: String) : RcLine
}

/** `.bashrc`, `/etc/profile` and friends: variables and aliases editable, the rest kept. */
data class ShellRc(val lines: List<RcLine>) {
    /** Non-blank lines the form leaves alone (functions, conditions, comments). */
    val otherCount get() = lines.count { it is RcLine.Other && it.text.isNotBlank() }

    fun render(): String = lines.joinToString("\n", postfix = "\n") { line ->
        when (line) {
            is RcLine.Export -> line.raw?.takeIf { parseLine(it) == line } ?: "export ${line.key}=${shellValue(line.value)}"
            is RcLine.Alias -> line.raw?.takeIf { parseLine(it) == line } ?: "alias ${line.name}=${singleQuote(line.command)}"
            is RcLine.Other -> line.text
        }
    }

    companion object {
        // Top-level lines only: an indented export inside an if/function belongs to that block.
        // Empty names are allowed so a row the user is still filling in survives a round trip.
        private val EXPORT = Regex("^export ([A-Za-z_][A-Za-z0-9_]*|)=(.*)$")
        private val ALIAS = Regex("^alias ([^=\\s]*)=(.*)$")

        fun parse(text: String) = ShellRc(lines(text).map(::parseLine))

        private fun parseLine(line: String): RcLine =
            EXPORT.matchEntire(line)?.let { m -> unquoteShell(m.groupValues[2])?.let { RcLine.Export(m.groupValues[1], it, line) } }
                ?: ALIAS.matchEntire(line)?.let { m -> unquoteShell(m.groupValues[2])?.let { RcLine.Alias(m.groupValues[1], it, line) } }
                ?: RcLine.Other(line)
    }
}

/** `/etc/environment`: KEY=value per line, read by pam_env (no `export`, no expansion). */
data class EnvironmentFile(val lines: List<RcLine>) {
    fun render(): String = lines.joinToString("\n", postfix = "\n") { line ->
        when (line) {
            is RcLine.Export -> line.raw?.takeIf { parseLine(it) == line }
                ?: ("${line.key}=" + if (line.value.any { it.isWhitespace() }) "\"${line.value}\"" else line.value)
            is RcLine.Other -> line.text
            is RcLine.Alias -> ""
        }
    }

    companion object {
        private val VAR = Regex("^([A-Za-z_][A-Za-z0-9_]*|)=(.*)$")

        fun parse(text: String) = EnvironmentFile(lines(text).map(::parseLine))

        private fun parseLine(line: String): RcLine =
            VAR.matchEntire(line)?.let { m -> RcLine.Export(m.groupValues[1], m.groupValues[2].removeSurrounding("\""), line) }
                ?: RcLine.Other(line)
    }
}

/** A file's lines without the trailing newline's empty entry. */
private fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")

/**
 * A shell word as the form shows it, quotes removed. Null when the value is more than one plain
 * word (a command substitution, `;`, trailing code), so the line stays untouched text.
 */
internal fun unquoteShell(v: String): String? {
    if (v.length >= 2 && v.startsWith('\'') && v.endsWith('\'')) {
        val inner = v.substring(1, v.length - 1)
        // 'it'\''s' is the usual way to put a ' inside single quotes.
        return inner.takeIf { "'" !in it.replace("'\\''", "") }?.replace("'\\''", "'")
    }
    if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
        val inner = v.substring(1, v.length - 1)
        if (Regex("(?<!\\\\)\"").containsMatchIn(inner) || "`" in inner || "\$(" in inner) return null
        return inner.replace("\\\"", "\"").replace("\\\\", "\\")
    }
    return v.takeIf { w -> w.none { it.isWhitespace() || it in "'\"`;&|<>()" } }
}

/** Double quotes keep `$VAR` working (PATH="$HOME/bin:$PATH"); bare when nothing needs quoting. */
internal fun shellValue(v: String): String =
    if (v.isNotEmpty() && v.none { it.isWhitespace() || it in "'\"`;&|<>()#\\" }) v
    else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

internal fun singleQuote(v: String) = "'" + v.replace("'", "'\\''") + "'"

// ---------------------------------------------------------------- apt sources (deb822)

/** One stanza of a `.sources` file. Fields the form doesn't show are kept in [extra]. */
data class AptSource(
    val enabled: Boolean = true,
    val types: List<String> = listOf("deb"),
    val uris: String = "http://deb.debian.org/debian",
    val suites: String = "trixie",
    val components: List<String> = listOf("main"),
    val signedBy: String = "/usr/share/keyrings/debian-archive-keyring.pgp",
    /** Comments and other fields, in their original order. */
    val extra: List<String> = emptyList(),
    /** The stanza as read; reused when nothing in it changed. */
    val raw: List<String>? = null,
)

data class AptSources(val sources: List<AptSource>, val header: List<String> = emptyList()) {
    fun render(): String = buildString {
        header.forEach { append(it).append('\n') }
        if (header.isNotEmpty() && sources.isNotEmpty()) append('\n')
        sources.forEachIndexed { i, s ->
            if (i > 0) append('\n')
            val unchanged = s.raw?.takeIf { parseStanza(it).copy(raw = null) == s.copy(raw = null) }
            (unchanged ?: generate(s)).forEach { append(it).append('\n') }
        }
    }

    private fun generate(s: AptSource): List<String> = buildList {
        addAll(s.extra.filter { it.startsWith("#") })
        if (!s.enabled) add("Enabled: no")
        add("Types: " + s.types.joinToString(" "))
        add("URIs: " + s.uris)
        add("Suites: " + s.suites)
        add("Components: " + s.components.joinToString(" "))
        if (s.signedBy.isNotBlank()) add("Signed-By: " + s.signedBy)
        addAll(s.extra.filterNot { it.startsWith("#") })
    }

    companion object {
        val KNOWN_COMPONENTS = listOf("main", "contrib", "non-free", "non-free-firmware")

        fun parse(text: String): AptSources {
            val blocks = text.split(Regex("\n\\s*\n")).map { b -> b.lines().filter { it.isNotBlank() } }.filter { it.isNotEmpty() }
            // A leading block of comments only is the file's header.
            val header = blocks.firstOrNull()?.takeIf { b -> b.all { it.startsWith("#") } }.orEmpty()
            val stanzas = if (header.isEmpty()) blocks else blocks.drop(1)
            return AptSources(stanzas.map(::parseStanza), header)
        }

        internal fun parseStanza(lines: List<String>): AptSource {
            var s = AptSource(types = emptyList(), uris = "", suites = "", components = emptyList(), signedBy = "")
            val extra = mutableListOf<String>()
            for (line in lines) {
                if (line.startsWith("#")) { extra += line; continue }
                // Continuation lines (a multi-line field such as an inline key) stay with it.
                if (line.first().isWhitespace()) { extra += line; continue }
                val key = line.substringBefore(':').trim()
                val value = line.substringAfter(':', "").trim()
                s = when (key.lowercase()) {
                    "enabled" -> s.copy(enabled = value.lowercase() != "no")
                    "types" -> s.copy(types = value.split(Regex("\\s+")).filter { it.isNotEmpty() })
                    "uris" -> s.copy(uris = value)
                    "suites" -> s.copy(suites = value)
                    "components" -> s.copy(components = value.split(Regex("\\s+")).filter { it.isNotEmpty() })
                    // An empty Signed-By starts an inline key block; keep the whole block as is.
                    "signed-by" -> if (value.isEmpty()) { extra += line; s } else s.copy(signedBy = value)
                    else -> { extra += line; s }
                }
            }
            return s.copy(extra = extra, raw = lines)
        }
    }
}

// ---------------------------------------------------------------- resolv.conf, hosts

/** A resolv.conf line: a name server, or anything else (search, options, comments) kept. */
sealed interface ResolvLine {
    data class Server(val address: String) : ResolvLine
    data class Other(val text: String) : ResolvLine
}

data class ResolvConf(val lines: List<ResolvLine>) {
    fun render() = lines.joinToString("\n", postfix = "\n") {
        when (it) {
            is ResolvLine.Server -> "nameserver ${it.address}"
            is ResolvLine.Other -> it.text
        }
    }

    companion object {
        fun parse(text: String) = ResolvConf(
            lines(text).map { line ->
                val t = line.trim()
                if (t.startsWith("nameserver ") || t == "nameserver") ResolvLine.Server(t.removePrefix("nameserver").trim())
                else ResolvLine.Other(line)
            },
        )
    }
}

/** One `/etc/hosts` line: an address and its names, or a line kept as is. */
sealed interface HostsLine {
    data class Entry(val address: String, val names: String, val raw: String? = null) : HostsLine
    data class Other(val text: String) : HostsLine
}

data class HostsFile(val lines: List<HostsLine>) {
    fun render() = lines.joinToString("\n", postfix = "\n") {
        when (it) {
            is HostsLine.Entry -> it.raw?.takeIf { r -> parseLine(r) == it } ?: "${it.address} ${it.names}".trimEnd()
            is HostsLine.Other -> it.text
        }
    }

    companion object {
        fun parse(text: String) = HostsFile(lines(text).map(::parseLine))

        private fun parseLine(line: String): HostsLine {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return HostsLine.Other(line)
            val words = t.split(Regex("\\s+"))
            return HostsLine.Entry(words.first(), words.drop(1).joinToString(" "), line)
        }
    }
}
