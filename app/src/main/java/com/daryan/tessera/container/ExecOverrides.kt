// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Commands the user changed, by app id. Like [PermissionOverrides] they replace the manifest's
 * when the app is listed or launched, and the manifest is left alone: a store reinstall or a
 * fresh scan of the system's desktop files keeps the user's command, and resetting is just
 * dropping the override. It is how an app gets a flag it needs (VS Code and --no-sandbox) or a
 * different binary, without editing anything inside the container.
 */
class ExecOverrides(private val file: File) {
    private val _overrides = MutableStateFlow(load())
    val overrides: StateFlow<Map<String, List<String>>> = _overrides

    /** [app] with the user's command, if they changed it. */
    fun apply(app: PermissionManifest): PermissionManifest =
        _overrides.value[app.id]?.let { app.copy(exec = it) } ?: app

    /**
     * Sets [app]'s command. Returns what's wrong with it instead, leaving things as they were,
     * when it doesn't pass the manifest's own checks. Setting it back to [default] drops the
     * override rather than storing a copy of it.
     */
    fun set(app: PermissionManifest, exec: List<String>, default: List<String>): List<String> {
        val problems = app.copy(exec = exec).validate()
        if (problems.isNotEmpty()) return problems
        edit { if (exec == default) it - app.id else it + (app.id to exec) }
        return emptyList()
    }

    fun reset(appId: String) = edit { it - appId }

    private fun edit(f: (Map<String, List<String>>) -> Map<String, List<String>>) {
        _overrides.update(f)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(SERIALIZER, _overrides.value))
            // Some filesystems won't rename over an existing file.
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): Map<String, List<String>> =
        runCatching { json.decodeFromString(SERIALIZER, file.readText()) }.getOrDefault(emptyMap())

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val SERIALIZER = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    }
}

/**
 * An argv as one line for the user to edit, and back. The launcher runs a command as a list of
 * arguments, never through a shell, so quotes here only hold an argument with spaces together;
 * nothing in the line is expanded.
 */
object ExecLine {
    fun format(exec: List<String>): String = exec.joinToString(" ") { shellQuote(it) }

    /** Splits [line] into arguments the way a shell would, honouring ' " and \. */
    fun parse(line: String): List<String> {
        val args = mutableListOf<String>()
        val arg = StringBuilder()
        // Distinct from "arg is empty": an argument can be written as '' and still be one.
        var started = false
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && quote != '\'' && i + 1 < line.length -> {
                    arg.append(line[++i])
                    started = true
                }
                quote == null && (c == '\'' || c == '"') -> {
                    quote = c
                    started = true
                }
                quote == c -> quote = null
                quote == null && c.isWhitespace() -> {
                    if (started) {
                        args += arg.toString()
                        arg.clear()
                        started = false
                    }
                }
                else -> {
                    arg.append(c)
                    started = true
                }
            }
            i++
        }
        if (started) args += arg.toString()
        return args
    }
}
