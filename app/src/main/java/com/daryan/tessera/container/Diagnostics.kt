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
    private val failuresDir get() = File(dir, "failures")
    /** Keeps two failures in the same second apart, and in order. */
    private val failureSeq = java.util.concurrent.atomic.AtomicInteger()

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

    /**
     * Records anything else that went wrong: an install, a catalog, a crash. Every failure the
     * user sees should land here or in [recordSession], or a report can't explain it.
     */
    fun recordFailure(what: String, message: String, output: String = "") = runCatching {
        if (!enabled()) return@runCatching
        failuresDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val slug = what.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        File(failuresDir, "$stamp-${"%04d".format(failureSeq.incrementAndGet())}-$slug.log").writeText(
            buildString {
                appendLine("what: $what")
                appendLine("error: $message")
                if (output.isNotBlank()) {
                    appendLine()
                    appendLine("--- output ---")
                    append(output.trim())
                }
            },
        )
        failuresDir.listFiles()?.sortedBy { it.name }.orEmpty().dropLast(KEEP_SESSIONS).forEach { it.delete() }
    }

    /** Records [error] before the app dies of it, then lets [previous] handle it as before. */
    fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordFailure("Tessera crashed (thread ${thread.name})", error.toString(), error.stackTraceToString())
            previous?.uncaughtException(thread, error)
        }
    }

    /** The phone's setup, for whoever reads it next. */
    fun writeState(state: String) = runCatching {
        dir.mkdirs()
        File(dir, "state.md").writeText(state)
    }

    fun clear() = runCatching { dir.deleteRecursively() }

    /**
     * Everything an assistant outside the app would need, as one block of Markdown to paste: what
     * Tessera is, how this phone is set up, and what failed and ended recently. Never longer
     * than [MAX_REPORT], so it fits a GitHub issue; the oldest logs give way first.
     */
    fun report(state: String): String {
        val head = buildString {
            appendLine("# Tessera diagnostics")
            appendLine()
            appendLine(
                "I am using Tessera, an Android app that runs Debian desktop apps on a phone, and " +
                    "something is not working. Below is how Tessera works, how my phone is set up, " +
                    "and what failed or ended most recently. Please work out what failed and tell " +
                    "me what to change. Say so plainly if the logs do not show the cause.",
            )
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(state.take(MAX_STATE))
            appendLine()
        }
        // The architecture notes are the same ones the in-container assistant reads.
        val notes = "\n---\n\n" + runCatching { contextFile.readText() }.getOrDefault("(architecture notes unavailable)") + "\n"

        // Failures and sessions on one timeline (file names start with the time); newest first
        // while there is room, then shown oldest first.
        val recent = (newest(failuresDir, REPORT_FAILURES) + newest(sessionsDir, REPORT_SESSIONS))
            .sortedByDescending { it.name }
        var room = MAX_REPORT - head.length - notes.length - 200
        val shown = mutableListOf<String>()
        for (f in recent) {
            val block = "\n### ${f.name}\n\n```\n${shorten(runCatching { f.readText() }.getOrDefault("").trim())}\n```\n"
            if (block.length > room) break
            shown += block
            room -= block.length
        }
        val logs = buildString {
            if (shown.isEmpty()) {
                appendLine("## Recent failures and sessions")
                appendLine()
                appendLine("None recorded yet. Reproduce the problem with diagnostics on, then copy this again.")
            } else {
                appendLine("## Recent failures and sessions, oldest first")
                shown.asReversed().forEach { append(it) }
                if (shown.size < recent.size) appendLine("\n(${recent.size - shown.size} older logs left out to keep this short.)")
            }
        }
        return head + logs + notes
    }

    /**
     * A log cut to [MAX_LOG_IN_REPORT]: its start (what ran, how it ended) and its end (the
     * error, usually), without the middle.
     */
    private fun shorten(log: String): String {
        if (log.length <= MAX_LOG_IN_REPORT) return log
        val head = log.take(MAX_LOG_IN_REPORT / 4)
        val tail = log.takeLast(MAX_LOG_IN_REPORT - head.length)
        return "$head\n[... ${log.length - head.length - tail.length} characters left out ...]\n$tail"
    }

    private fun newest(dir: File, n: Int) = dir.listFiles()?.sortedBy { it.name }?.takeLast(n).orEmpty()

    private companion object {
        const val CONTEXT_NAME = "TESSERA.md"
        const val KEEP_SESSIONS = 20
        const val REPORT_SESSIONS = 3
        const val REPORT_FAILURES = 5
        const val MAX_LOG_IN_REPORT = 6 * 1024
        /** A GitHub issue takes 65,536 characters; leave the reporter room to write. */
        const val MAX_REPORT = 60_000
        /** The phone's setup lists every launcher app; a few hundred of them mustn't crowd out the logs. */
        const val MAX_STATE = 16 * 1024

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
