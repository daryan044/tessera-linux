// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException

/** One process of Tessera's, as the task manager shows it. */
data class GuestProcess(
    val pid: Int,
    val ppid: Int,
    /**
     * The session it belongs to: the [SESSION_ENV] it inherited, or for Tessera's own processes
     * the session they serve. [HELPERS] for what serves none.
     */
    val session: String,
    /** The kernel's short name for it (comm). */
    val name: String,
    /** Its full command line, when readable. */
    val command: String,
    /** R running, S sleeping, D waiting on disk, Z zombie, T stopped… */
    val state: Char,
    /** Share of one CPU core since the last sample, like top: 100 is one core flat out. */
    val cpu: Float,
    /** Resident memory, in bytes. */
    val memory: Long,
    val threads: Int,
    val uid: Int,
) {
    val root get() = uid == 0
}

/** [GuestProcess.session] of Tessera's own processes that serve no session. */
const val HELPERS = "tessera"

/**
 * Samples Tessera's processes every [intervalMs]: every process running as the app's uid
 * (except the app itself), and, with [su] (chroot mode), those running as root that carry
 * [SESSION_ENV] or [HELPER_ENV], read by a root reader started once rather than asking su
 * again every sample. Nothing Tessera started is left out: orphaned helpers whose parent is
 * long gone show up too, under [HELPERS].
 */
class ProcessMonitor(private val su: String?, private val intervalMs: Long = 2000) {
    private val ticksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1)
    private val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)

    /** Raw fields for one process, before CPU is worked out. */
    internal data class Raw(val pid: Int, val session: String, val stat: String, val statm: String, val uid: Int, val cmdline: String)

    fun watch(): Flow<List<GuestProcess>> = flow {
        val reader = su?.let { runCatching { RootReader(it) }.getOrNull() }
        val last = HashMap<Int, Pair<Long, Long>>()
        try {
            while (true) {
                val now = System.nanoTime()
                val raws = merge(reader?.let { r -> runCatching { r.sample() }.getOrNull() }.orEmpty() + readOwn())
                val seen = HashSet<Int>()
                val list = raws.mapNotNull { raw ->
                    seen += raw.pid
                    parse(raw, now, last)
                }
                last.keys.retainAll(seen)
                emit(attribute(list))
                delay(intervalMs)
            }
        } finally {
            reader?.close()
        }
    }.flowOn(Dispatchers.IO)

    /** What the app can see without root: every process running as its own uid but itself. */
    private fun readOwn(proc: File = File("/proc")): List<Raw> {
        val marker = "$SESSION_ENV="
        val self = android.os.Process.myPid()
        return proc.listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
            if (dir.name.toInt() == self) return@mapNotNull null
            val env = runCatching { File(dir, "environ").readBytes() }.getOrNull() ?: return@mapNotNull null
            val session = String(env).split('\u0000').firstOrNull { it.startsWith(marker) }?.removePrefix(marker).orEmpty()
            runCatching {
                Raw(
                    pid = dir.name.toInt(),
                    session = session,
                    stat = File(dir, "stat").readText(),
                    statm = File(dir, "statm").readText(),
                    uid = Os.stat(dir.path).st_uid,
                    cmdline = File(dir, "cmdline").readBytes().let { String(it).replace('\u0000', ' ').trim() },
                )
            }.getOrNull()
        }
    }

    private fun parse(raw: Raw, now: Long, last: HashMap<Int, Pair<Long, Long>>): GuestProcess? {
        val stat = raw.stat
        val open = stat.indexOf('(')
        val close = stat.lastIndexOf(')')
        if (open < 0 || close < open) return null
        // After the name: state, ppid, … utime is the 12th, stime the 13th, threads the 18th.
        val f = stat.substring(close + 2).trim().split(' ')
        if (f.size < 18) return null
        val ticks = (f[11].toLongOrNull() ?: 0) + (f[12].toLongOrNull() ?: 0)
        val before = last[raw.pid]
        last[raw.pid] = ticks to now
        val cpu = if (before == null || now <= before.second) 0f
        else ((ticks - before.first).toFloat() / ticksPerSecond) / ((now - before.second) / 1e9f) * 100f
        return GuestProcess(
            pid = raw.pid,
            ppid = f[1].toIntOrNull() ?: 0,
            session = raw.session,
            name = stat.substring(open + 1, close),
            command = raw.cmdline,
            state = f[0].firstOrNull() ?: '?',
            cpu = cpu.coerceAtLeast(0f),
            memory = (raw.statm.trim().split(' ').getOrNull(1)?.toLongOrNull() ?: 0) * pageSize,
            threads = f[17].toIntOrNull() ?: 1,
            uid = raw.uid,
        )
    }

    /** A root shell, started once, that prints every container process each time it's asked. */
    private class RootReader(su: String) : Closeable {
        private val process = ProcessBuilder(su).redirectErrorStream(false).start()
        private val out: BufferedReader = process.inputStream.bufferedReader()

        init {
            process.outputStream.write((SCRIPT + "\n").toByteArray())
            process.outputStream.flush()
        }

        fun sample(): List<Raw> {
            if (!process.isAlive) throw IOException("root reader stopped")
            process.outputStream.write('\n'.code)
            process.outputStream.flush()
            val list = mutableListOf<Raw>()
            while (true) {
                val line = out.readLine() ?: throw IOException("root reader stopped")
                if (line == "END") break
                val p = line.split('\u001f')
                if (p.size < 6) continue
                list += Raw(p[0].toIntOrNull() ?: continue, p[1], p[2], p[3], p[4].toIntOrNull() ?: -1, p[5].trim())
            }
            return list
        }

        override fun close() {
            runCatching { process.outputStream.close() }
            process.destroy()
        }

        companion object {
            /**
             * One grep over every environ finds Tessera's processes (one may match both markers;
             * [merge] keeps one line); only those are read further. Fields are split by \037, records by newlines; END closes a sample.
             */
            private val SCRIPT = """
                while read -r _; do
                  grep -a -o -e '$SESSION_ENV=[^[:cntrl:]]*' -e '$HELPER_ENV=[^[:cntrl:]]*' /proc/[0-9]*/environ 2>/dev/null | while IFS= read -r m; do
                    d=${'$'}{m%%/environ:*}
                    case "${'$'}m" in *":$SESSION_ENV="*) s=${'$'}{m#*$SESSION_ENV=} ;; *) s= ;; esac
                    st=${'$'}(cat "${'$'}d/stat" 2>/dev/null) || continue
                    sm=${'$'}(cat "${'$'}d/statm" 2>/dev/null)
                    u=${'$'}(stat -c %u "${'$'}d" 2>/dev/null)
                    # Through cat: toybox tr reading the cmdline of a process that has just
                    # exited never returns, and kept a core busy long after the app was gone.
                    cl=${'$'}(cat "${'$'}d/cmdline" 2>/dev/null | tr '\000\n' '  ')
                    printf '%s\037%s\037%s\037%s\037%s\037%s\n' "${'$'}{d#/proc/}" "${'$'}s" "${'$'}st" "${'$'}sm" "${'$'}u" "${'$'}cl"
                  done
                  echo END
                done
            """.trimIndent()
        }
    }

    companion object {
        private val X11_TAG = Regex("-tag linux-(\\S+)")

        /** One [Raw] per pid: the one naming a session, where a process matched twice. */
        internal fun merge(raws: List<Raw>): List<Raw> =
            raws.groupBy { it.pid }.values.map { same -> same.firstOrNull { it.session.isNotEmpty() } ?: same.first() }

        /**
         * Gives Tessera's own processes, which carry no [SESSION_ENV], the session they serve:
         * a session's X server by its window tag, PRoot or su by the guest under them. The rest
         * go under [HELPERS].
         */
        internal fun attribute(list: List<GuestProcess>): List<GuestProcess> {
            val session = HashMap<Int, String>()
            list.forEach { p ->
                val s = p.session.ifEmpty { X11_TAG.find(p.command)?.groupValues?.get(1).orEmpty() }
                if (s.isNotEmpty()) session[p.pid] = s
            }
            // Up from the guests: a launcher (proot, su, unshare) takes its child's session.
            repeat(4) {
                list.forEach { p ->
                    if (p.pid !in session) list.firstOrNull { it.ppid == p.pid && it.pid in session }?.let { session[p.pid] = session.getValue(it.pid) }
                }
            }
            return list.map { it.copy(session = session[it.pid] ?: HELPERS) }
        }

        /**
         * Ends [pid]: asks it to quit (TERM), then insists (KILL) if it's still there a moment
         * later. Processes of another uid (root, in chroot mode) need [su].
         */
        suspend fun end(pid: Int, uid: Int, su: String?) {
            val own = uid == android.os.Process.myUid()
            fun signal(sig: Int) {
                if (own) android.os.Process.sendSignal(pid, sig)
                else if (su != null) runCatching { ProcessBuilder(su, "-c", "kill -$sig $pid").start().waitFor() }
            }
            signal(15)
            delay(1500)
            if (File("/proc/$pid").exists() || !own) signal(9)
        }
    }
}
