// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What Tessera knows about itself, written where something inside the container can read it.
 *
 * A container cannot see Android's log, `/system`, or the app's own data directory, so an app that
 * dies at startup leaves its only trace in logcat, out of reach of the user and of anything they
 * ask for help. This directory is bound into the Claude Code shortcut at `~/.tessera`, and the
 * same material goes into [report] for pasting into any assistant outside the app.
 *
 * It holds the user's file paths and the names of their apps, so it is written only while
 * [enabled], and never leaves the phone on its own.
 */
class Diagnostics(
    /** Bound into the container read-only by whoever is granted it (the Claude Code shortcut). */
    val dir: File,
    /** Checked on every write, so turning diagnostics off in Setup takes effect at once. */
    private val enabled: () -> Boolean = { true },
) {
    val contextFile: File get() = File(dir, CONTEXT_NAME)
    private val sessionsDir get() = File(dir, "sessions")

    /** Copies the architecture notes out of the APK, so the container side has them too. */
    fun install(context: Context) = runCatching {
        dir.mkdirs()
        context.assets.open("claude/$CONTEXT_NAME").use { input ->
            contextFile.outputStream().use { input.copyTo(it) }
        }
    }

    /**
     * Records how a session ended. Keeps the last [KEEP_SESSIONS] and drops the rest: these are
     * for the failure in front of the user, not a history.
     */
    fun recordSession(app: PermissionManifest, session: Session, engine: String, exitCode: Int?, output: String) =
        runCatching {
            if (!enabled()) return@runCatching
            sessionsDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            // Session id too: the same app failing twice in a second must not overwrite itself.
            File(sessionsDir, "$stamp-${session.id}-${app.id}.log").writeText(
                buildString {
                    appendLine("app: ${app.name} (${app.id})")
                    appendLine("command: ${app.exec.joinToString(" ") { shellQuote(it) }}")
                    appendLine("engine: $engine")
                    appendLine("windowed: ${app.gui}, display: ${session.display}, gpu: ${session.gpu}")
                    if (session.gpu && session.gpuFixes.isNotEmpty()) {
                        appendLine("graphics fixes: ${session.gpuFixes.joinToString { it.name }}")
                    }
                    appendLine("permissions: ${describe(app.permissions)}")
                    appendLine("exit code: ${exitCode ?: "still running"}")
                    appendLine()
                    appendLine("--- output ---")
                    append(output.ifBlank { "(the app printed nothing)" })
                },
            )
            val old = sessionsDir.listFiles()?.sortedBy { it.name }.orEmpty().dropLast(KEEP_SESSIONS)
            old.forEach { it.delete() }
        }

    /** The phone's setup, for whoever reads it next. */
    fun writeState(state: String) = runCatching {
        dir.mkdirs()
        File(dir, "state.md").writeText(state)
    }

    fun clear() = runCatching { dir.deleteRecursively() }

    /**
     * Everything an assistant outside the app would need, as one block of Markdown to paste: what
     * Tessera is, how this phone is set up, and the sessions that ended recently.
     */
    fun report(state: String): String = buildString {
        appendLine("# Tessera diagnostics")
        appendLine()
        appendLine(
            "I am using Tessera, an Android app that runs Debian desktop apps on a phone, and " +
                "something is not working. Below is how Tessera works, how my phone is set up, " +
                "and the logs of the sessions that ended most recently. Please work out what " +
                "failed and tell me what to change. Say so plainly if the logs do not show the " +
                "cause.",
        )
        appendLine()
        appendLine("---")
        appendLine()
        appendLine(state)
        appendLine()
        val logs = sessionsDir.listFiles()?.sortedBy { it.name }?.takeLast(REPORT_SESSIONS).orEmpty()
        if (logs.isEmpty()) {
            appendLine("## Recent sessions")
            appendLine()
            appendLine("None recorded yet. Reproduce the problem with diagnostics on, then copy this again.")
        } else {
            appendLine("## Recent sessions, oldest first")
            logs.forEach { f ->
                appendLine()
                appendLine("### ${f.name}")
                appendLine()
                appendLine("```")
                appendLine(f.readText().trim().takeLast(MAX_LOG_IN_REPORT))
                appendLine("```")
            }
        }
        appendLine()
        appendLine("---")
        appendLine()
        // The architecture notes are the same ones the in-container assistant reads.
        appendLine(runCatching { contextFile.readText() }.getOrDefault("(architecture notes unavailable)"))
    }

    private companion object {
        const val CONTEXT_NAME = "TESSERA.md"
        const val KEEP_SESSIONS = 20
        const val REPORT_SESSIONS = 3
        const val MAX_LOG_IN_REPORT = 6 * 1024

        fun describe(p: Permissions) = buildList {
            if (p.network) add("network")
            if (p.display) add("display")
            if (p.audio) add("audio")
            if (p.root) add("root")
            if (p.adb) add("adb")
            if (p.devices.isNotEmpty()) add("devices=${p.devices.joinToString(",")}")
            p.filesystem.forEach { add("${it.path}->${it.mountAt}${if (it.readOnly) ":ro" else ""}") }
        }.joinToString(", ").ifEmpty { "none" }
    }
}
