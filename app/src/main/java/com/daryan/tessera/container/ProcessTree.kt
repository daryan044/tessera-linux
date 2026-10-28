// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File

/**
 * Killing a session means killing everything under it. PRoot is a ptrace tracer: SIGKILL on it
 * doesn't take its tracees along, they're orphaned and keep running (holding locks, like a
 * Firefox profile). So collect the whole tree from /proc first, then kill every process in it.
 */
object ProcessTree {
    private const val SIGKILL = 9

    /** [root] and every process descended from it, deepest first. */
    fun descendants(root: Int, proc: File = File("/proc")): List<Int> {
        val parents = parentMap(proc)
        val children = parents.entries.groupBy({ it.value }, { it.key })
        val out = mutableListOf<Int>()
        fun walk(pid: Int) {
            children[pid].orEmpty().forEach(::walk)
            out += pid
        }
        walk(root)
        return out
    }

    fun killTree(root: Int) {
        if (root <= 0) return
        descendants(root).forEach { android.os.Process.sendSignal(it, SIGKILL) }
    }

    /**
     * Processes of this app left over from an earlier run: anything with our uid that isn't us
     * or one of our children. Sessions don't survive the process that started them, so these
     * are orphans by definition.
     */
    fun leftovers(selfPid: Int, uid: Int, proc: File = File("/proc")): List<Int> {
        val parents = parentMap(proc)
        val ours = descendantsOf(selfPid, parents)
        return parents.keys.filter { pid ->
            pid !in ours && pid != selfPid && ownerUid(File(proc, "$pid")) == uid
        }
    }

    fun killLeftovers(selfPid: Int, uid: Int) = leftovers(selfPid, uid).forEach { android.os.Process.sendSignal(it, SIGKILL) }

    /**
     * Processes started for session [sessionId], found by the [SESSION_ENV] they inherit, so
     * daemons that detached from the session's tree are found too.
     */
    fun inSession(sessionId: String, proc: File = File("/proc")): List<Int> {
        val marker = "$SESSION_ENV=$sessionId".toByteArray()
        return proc.listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
            val env = runCatching { File(dir, "environ").readBytes() }.getOrNull() ?: return@mapNotNull null
            dir.name.toInt().takeIf { env.split(0).any { it.contentEquals(marker) } }
        }
    }

    fun killSession(sessionId: String) = inSession(sessionId).forEach { android.os.Process.sendSignal(it, SIGKILL) }

    /**
     * As [killSession], through root, for chroot mode: its sessions run under su's SELinux
     * domain, which hides them from the app's own view of /proc, so they were never found and
     * their daemons (D-Bus, PulseAudio, gvfs) piled up. [sessionId] null: every session's, for
     * what an earlier run of the app left behind. Blocks; call off the main thread.
     */
    fun killSessionAsRoot(su: String, sessionId: String?) {
        runCatching {
            val p = ProcessBuilder(su, "-c", RootShell.line(rootKillScript(sessionId))).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor()
        }
    }

    /** The shell for [killSessionAsRoot]: every process whose environment has the session's marker. */
    internal fun rootKillScript(sessionId: String?): String {
        val marker = if (sessionId == null) "^$SESSION_ENV=" else "^$SESSION_ENV=$sessionId${'$'}"
        // Through cat: toybox tr reading the environ of a process that has just exited (likely,
        // while killing) never returns, and kept a core busy long after the app was gone.
        // grep -z reads its NUL-separated entries itself: some phones have no tr at all (#5).
        return "for e in /proc/[0-9]*/environ; do p=${'$'}{e%/environ}; p=${'$'}{p#/proc/}; " +
            "cat ${'$'}e 2>/dev/null | grep -qz '$marker' && kill -9 ${'$'}p 2>/dev/null; done; true"
    }

    private fun ByteArray.split(sep: Byte): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var start = 0
        for (i in indices) if (this[i] == sep) { out += copyOfRange(start, i); start = i + 1 }
        if (start < size) out += copyOfRange(start, size)
        return out
    }

    private fun descendantsOf(root: Int, parents: Map<Int, Int>): Set<Int> {
        val children = parents.entries.groupBy({ it.value }, { it.key })
        val out = mutableSetOf<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (out.add(pid)) queue += children[pid].orEmpty()
        }
        return out
    }

    /** pid -> ppid for every process we can see. */
    internal fun parentMap(proc: File): Map<Int, Int> =
        proc.listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
            val pid = dir.name.toInt()
            parentOf(dir)?.let { pid to it }
        }.toMap()

    /** ppid from /proc/<pid>/stat; the command name may contain spaces, so parse after its ")". */
    internal fun parentOf(dir: File): Int? = runCatching {
        val stat = File(dir, "stat").readText()
        stat.substring(stat.lastIndexOf(')') + 2).split(' ')[1].toInt()
    }.getOrNull()

    private fun ownerUid(dir: File): Int? = runCatching {
        File(dir, "status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
    }.getOrNull()
}
