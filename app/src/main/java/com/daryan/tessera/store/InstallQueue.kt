// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import android.os.ParcelFileDescriptor
import com.daryan.tessera.container.ContainerBackend
import com.daryan.tessera.container.ContainerPaths
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.Permissions
import com.daryan.tessera.container.Pty
import com.daryan.tessera.container.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

enum class QueueAction { INSTALL, REMOVE }

sealed interface JobState {
    data object Queued : JobState
    /** [progress] is 0..1 over the whole job; null while it's not known yet. */
    data class Running(val progress: Float?, val message: String) : JobState
    data object Done : JobState
    data class Failed(val message: String) : JobState
}

/** One package install or removal waiting in, or done by, the queue. */
data class QueueJob(
    val id: Int,
    val title: String,
    val packages: List<String>,
    val action: QueueAction,
    val iconFile: String?,
    val state: JobState = JobState.Queued,
    /** apt's output, minus the progress lines. */
    val log: String = "",
    val queuedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /** What runs instead of apt (Flathub's setup, flatpak install), as root; its last line is the progress text. */
    val script: String? = null,
    /** The Flathub id a flatpak install [script] installs, whose progress [FlatpakProgress] reads. */
    val flatpakApp: String? = null,
) {
    val active get() = state is JobState.Queued || state is JobState.Running
}

/**
 * Installs and removals, one at a time and out of sight: apt runs as root in the active
 * container and reports progress through APT::Status-Fd, which drives the bar in the launcher.
 */
class InstallQueue(private val scope: CoroutineScope, private val paths: ContainerPaths) {
    private val nextId = AtomicInteger(1)
    private val _jobs = MutableStateFlow<List<QueueJob>>(emptyList())
    val jobs: StateFlow<List<QueueJob>> = _jobs
    private val runner = Mutex()
    @Volatile private var runningPid = 0
    /** Set when the user stops the running job, so its end reads "Stopped" rather than a signal. */
    @Volatile private var stopRequested = false
    /** How to run each job again: what [retry] needs that the job itself doesn't hold. */
    private val reruns = java.util.concurrent.ConcurrentHashMap<Int, Pair<ContainerBackend, suspend () -> Unit>>()

    /**
     * Queues [packages] for [action] with [backend]; [onDone] runs after apt succeeds (adding or
     * removing the launcher entry). Ignored if the same packages are already waiting.
     */
    fun enqueue(
        title: String,
        packages: List<String>,
        action: QueueAction,
        iconFile: String?,
        backend: ContainerBackend,
        script: String? = null,
        flatpakApp: String? = null,
        onDone: suspend () -> Unit,
    ) {
        if (_jobs.value.any { it.active && it.packages == packages }) return
        val job = QueueJob(nextId.getAndIncrement(), title, packages, action, iconFile, script = script, flatpakApp = flatpakApp)
        reruns[job.id] = backend to onDone
        _jobs.update { it + job }
        scope.launch(Dispatchers.IO) {
            // One apt at a time: dpkg holds a lock, and the bar shows one job.
            runner.withLock {
                if (_jobs.value.none { it.id == job.id && it.state == JobState.Queued }) return@withLock // cancelled
                val ok = run(job, backend)
                if (ok) runCatching { onDone() }
            }
        }
    }

    /** Drops a waiting job, or stops the one running. */
    fun cancel(id: Int) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        when (job.state) {
            JobState.Queued -> _jobs.update { list -> list.filterNot { it.id == id } }
            is JobState.Running -> runningPid.takeIf { it > 0 }?.let {
                stopRequested = true
                com.daryan.tessera.container.ProcessTree.killTree(it)
            }
            else -> {}
        }
    }

    fun clearFinished() = _jobs.update { list ->
        list.filter { it.active }.also { kept -> reruns.keys.retainAll(kept.map { it.id }.toSet()) }
    }

    /** Queues a failed job again, in place of the failed one. */
    fun retry(id: Int) {
        val job = _jobs.value.firstOrNull { it.id == id && it.state is JobState.Failed } ?: return
        val (backend, onDone) = reruns.remove(id) ?: return
        _jobs.update { list -> list.filterNot { it.id == id } }
        enqueue(job.title, job.packages, job.action, job.iconFile, backend, job.script, job.flatpakApp, onDone)
    }

    private fun run(job: QueueJob, backend: ContainerBackend): Boolean {
        set(job.id, JobState.Running(null, "Starting"))
        val session = Session(id = "q${job.id}")
        paths.sessionTmp(session).apply { deleteRecursively(); mkdirs() }
        paths.sessionShm(session).apply { deleteRecursively(); mkdirs() }
        val manifest = PermissionManifest(
            id = "queue-${job.id}",
            name = job.title,
            exec = listOf("/bin/sh", "-c", job.script ?: script(job)),
            permissions = Permissions(network = job.action == QueueAction.INSTALL, root = true),
        )
        return try {
            val spec = backend.buildLaunch(manifest, session)
            val pid = IntArray(1)
            // A pty, not a pipe: the chroot entry script needs a terminal for setsid -c.
            val fd = Pty.spawn(spec, rows = 24, cols = 200, pidOut = pid)
            stopRequested = false
            runningPid = pid[0]
            val parser = AptProgress()
            val flatpak = job.flatpakApp?.let(::FlatpakProgress)
            val tail = ArrayDeque<String>()
            val log = StringBuilder()
            var state: JobState = JobState.Running(null, "Starting")
            var published = 0L
            val pty = ParcelFileDescriptor.adoptFd(fd)
            val answer = java.io.FileOutputStream(pty.fileDescriptor)
            ParcelFileDescriptor.AutoCloseInputStream(pty).bufferedReader().use { r ->
                try {
                    terminalLines(r) { runCatching { answer.write(CURSOR_REPORT) } }.forEach { raw ->
                        // Without terminal colors: flatpak colors its errors even on a pipe.
                        val line = raw.trimEnd('\r').replace(ANSI, "")
                        if (job.script == null) parser.feed(line)?.let { (p, msg) -> state = JobState.Running(p, msg) }
                        else if (flatpak != null) flatpak.feed(line)?.let { (p, msg) -> state = JobState.Running(p, msg) }
                        // Other tools' progress isn't machine-readable: their latest line says it.
                        else if (line.isNotBlank()) state = JobState.Running(null, line.trim().take(120))
                        if (line.isNotBlank() && !line.startsWith("dlstatus:") && !line.startsWith("pmstatus:") && !line.startsWith("##phase") &&
                            !FlatpakProgress.isProgress(line)
                        ) {
                            tail.addLast(line); if (tail.size > 3) tail.removeFirst()
                            log.append(line).append('\n')
                            if (log.length > MAX_LOG) log.delete(0, log.length - MAX_LOG)
                        }
                        // Live output for the Installs page, a few times a second rather than per line.
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - published >= LOG_PUBLISH_MS) {
                            set(job.id, state, log)
                            published = now
                        }
                    }
                } catch (_: IOException) {
                    // EIO when the pty closes: end of output.
                }
            }
            val code = Pty.waitFor(pid[0])
            runningPid = 0
            if (code == 0) {
                set(job.id, JobState.Done, log); true
            } else {
                val why = when {
                    code < 0 -> ended(-code, stopRequested)
                    else -> tail.lastOrNull { it.startsWith("E:") }
                        // flatpak's own words, not the table row drawn before them.
                        ?: tail.lastOrNull { "error: " in it }?.let { it.substring(it.indexOf("error: ")) }
                        ?: tail.lastOrNull() ?: "apt failed ($code)"
                }
                set(job.id, JobState.Failed(why), log)
                false
            }
        } catch (e: Exception) {
            set(job.id, JobState.Failed(e.message ?: "couldn't start"), null)
            false
        } finally {
            paths.sessionTmp(session).parentFile?.deleteRecursively()
        }
    }

    private fun set(id: Int, state: JobState, log: CharSequence? = null) = _jobs.update { list ->
        list.map { job ->
            if (job.id != id) job
            else job.copy(
                state = state,
                log = log?.toString() ?: job.log,
                finishedAt = if (state is JobState.Done || state is JobState.Failed) System.currentTimeMillis() else null,
            )
        }
    }

    companion object {
        private const val MAX_LOG = 64 * 1024
        /** Terminal color and cursor codes (ESC [ ... letter). */
        private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
        private const val LOG_PUBLISH_MS = 250L

        /**
         * Why a job killed by [signal] ended. Android's background process limit (Android 12 and
         * later) kills with SIGKILL and leaves no output, so a bare "Stopped" told no one anything.
         */
        fun ended(signal: Int, byUser: Boolean): String = when {
            byUser -> "Stopped"
            signal == 9 -> "Killed (signal 9), most likely by Android's background process limit; see Setup"
            // SIGSYS: a system call Android's filter for apps doesn't allow.
            signal == 31 -> "Stopped by Android's system call filter (signal 31); please report it with Copy diagnostics"
            else -> "Ended by signal $signal"
        }

        /** apt in two phases with machine-readable progress; markers tell [AptProgress] which. */
        fun script(job: QueueJob): String {
            val opts = "-o APT::Status-Fd=1 -o Dpkg::Use-Pty=0 -o Dpkg::Progress-Fancy=0"
            val pkgs = job.packages.joinToString(" ")
            val main = when (job.action) {
                QueueAction.INSTALL -> "apt-get install -y --no-install-recommends $opts $pkgs"
                QueueAction.REMOVE -> "apt-get remove -y $opts $pkgs"
            }
            // A package setup step that was interrupted (or stumbled) leaves dpkg half done and
            // apt failing; finish what's pending and try once more before calling it failed.
            val retry = "{ echo 'Finishing interrupted package setup and trying again'; dpkg --configure -a; $main; }"
            return "export DEBIAN_FRONTEND=noninteractive; " +
                (if (job.action == QueueAction.INSTALL) "echo '##phase update'; apt-get update $opts || exit \$?; " else "") +
                "echo '##phase main'; $main || $retry"
        }
    }
}

/**
 * Turns the progress line flatpak draws in a terminal ("Installing 2/3… ███ 45%  1.2 MB/s") into
 * one 0..1 figure over all its steps, naming each runtime it brings: the first app on Flathub
 * comes with hundreds of MB of them, and a bar stuck on the app's name read as a hang. [app] is
 * the Flathub id being installed.
 */
class FlatpakProgress(private val app: String) {
    /** What each numbered step installs, from the table flatpak prints first. */
    private val steps = mutableMapOf<Int, String>()
    private var started = false

    /**
     * Progress and a short message for [line], or null if it says nothing new. Where the terminal
     * answers its cursor queries, flatpak redraws the table and the bar together, with no line
     * breaks between its rows, so rows and bar are looked for anywhere in [line].
     */
    fun feed(line: String): Pair<Float?, String>? {
        val rows = ROW.findAll(line).onEach { steps[it.groupValues[1].toInt()] = it.groupValues[2] }.count()
        val m = STEP.findAll(line).lastOrNull()
        if (m == null) {
            // Before the bar: "Looking for matches…"; after it, bubblewrap's noise.
            return if (started || rows > 0 || line.isBlank() || HEADER.containsMatchIn(line)) null else null to line.trim().take(120)
        }
        started = true
        val (verb, n, total, pct) = m.destructured
        val step = n.toIntOrNull() ?: 1
        val of = total.toIntOrNull() ?: 1
        val p = pct.toFloat().coerceIn(0f, 100f) / 100f
        val id = steps[step]
        val what = when {
            id == null || id == app -> verb
            else -> "$verb runtime $id"
        }
        val speed = SPEED.find(line, m.range.first)?.value?.let { ", $it" }.orEmpty()
        val count = if (of > 1) " ($step of $of)" else ""
        return ((step - 1 + p) / of).coerceIn(0f, 1f) to "$what$count$speed"
    }

    companion object {
        private val STEP = Regex("""(Installing|Updating|Uninstalling)(?: (\d+)/(\d+))?(?:…|\.\.\.)[^%]*?(\d{1,3})%""")
        /** " 1.     org.kde.Platform  aarch64  6.9  i  flathub  < 400 MB", once done "1. [✓] …". */
        private val ROW = Regex("""(?<![\d.])(\d+)\.\s+(?:\[.]\s+)?([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)+)\s+\S+\s+\S+\s+[iur]\s""")
        private val HEADER = Regex("""^\s*ID\s+Arch\s""")
        /** "1.2 MB/s"; flatpak puts a no-break space before the unit. */
        private val SPEED = Regex("""\d+(?:\.\d+)?[\s\u00A0]*[kMG]B/s""")

        /** A row as redrawn while it runs, with its state in brackets: "1. [/] org.kde.Platform …". */
        private val REDRAWN_ROW = Regex("""\d+\.\s+\[.]\s""")

        /** Whether [line] is one of the bar's or the table's redraws, which the log can do without. */
        fun isProgress(line: String) = STEP.containsMatchIn(line) || REDRAWN_ROW.containsMatchIn(line)
    }
}

/**
 * [r]'s lines, split at \n or \r (a redrawn progress line starts with \r) and where the cursor is
 * moved or the screen cleared (ESC [ … H or J: flatpak goes back to the top and draws its table
 * and bar again), answering a terminal's cursor position query with [onQuery] as soon as it
 * arrives: flatpak asks before each redraw and waits a tenth of a second for the answer, with its
 * downloads waiting too.
 */
internal fun terminalLines(r: java.io.Reader, onQuery: () -> Unit): Sequence<String> = sequence {
    val line = StringBuilder()
    while (true) {
        val c = r.read()
        if (c < 0) break
        if (c == '\n'.code || c == '\r'.code) {
            yield(line.toString())
            line.setLength(0)
        } else {
            line.append(c.toChar())
            if (line.endsWith(CURSOR_QUERY)) {
                line.setLength(line.length - CURSOR_QUERY.length)
                onQuery()
            } else if (c == 'H'.code || c == 'J'.code) {
                MOVE.find(line)?.let { move ->
                    line.setLength(move.range.first)
                    if (line.isNotEmpty()) yield(line.toString())
                    line.setLength(0)
                }
            }
        }
    }
    if (line.isNotEmpty()) yield(line.toString())
}

/** "Where is the cursor?", and an answer: top left, which is all flatpak needs to draw. */
private const val CURSOR_QUERY = "\u001B[6n"
/** Moving the cursor (ESC [ row ; col H) or clearing (ESC [ n J), at the end of the text so far. */
private val MOVE = Regex("""\u001B\[[0-9;]*[HJ]$""")
internal val CURSOR_REPORT = "\u001B[1;1R".toByteArray()

/**
 * Turns apt's Status-Fd lines into one 0..1 figure: package lists (first 10%), downloads (to
 * 55%), then dpkg's unpack/configure steps (to 100%).
 */
class AptProgress {
    private var phase = "update"

    /** Progress and a short message for [line], or null if it says nothing new. */
    fun feed(line: String): Pair<Float?, String>? {
        if (line.startsWith("##phase ")) {
            phase = line.removePrefix("##phase ").trim()
            return if (phase == "update") 0f to "Refreshing package lists" else 0.1f to "Preparing"
        }
        val parts = line.split(':', limit = 4)
        if (parts.size < 4) return null
        val pct = parts[2].toFloatOrNull()?.coerceIn(0f, 100f)?.div(100f) ?: return null
        val msg = parts[3].trim()
        return when {
            phase == "update" && parts[0] == "dlstatus" -> pct * 0.1f to "Refreshing package lists"
            parts[0] == "dlstatus" -> 0.1f + pct * 0.45f to msg.ifBlank { "Downloading" }
            parts[0] == "pmstatus" -> 0.55f + pct * 0.45f to msg.ifBlank { "Installing" }
            else -> null
        }
    }
}
