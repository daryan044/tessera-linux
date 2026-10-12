// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.store

import android.os.ParcelFileDescriptor
import com.daryan.linuxlauncher.container.ContainerBackend
import com.daryan.linuxlauncher.container.ContainerPaths
import com.daryan.linuxlauncher.container.PermissionManifest
import com.daryan.linuxlauncher.container.Permissions
import com.daryan.linuxlauncher.container.Pty
import com.daryan.linuxlauncher.container.Session
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
        onDone: suspend () -> Unit,
    ) {
        if (_jobs.value.any { it.active && it.packages == packages }) return
        val job = QueueJob(nextId.getAndIncrement(), title, packages, action, iconFile)
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
            is JobState.Running -> runningPid.takeIf { it > 0 }?.let { android.os.Process.killProcess(it) }
            else -> {}
        }
    }

    fun clearFinished() = _jobs.update { list -> list.filter { it.active } }

    private fun run(job: QueueJob, backend: ContainerBackend): Boolean {
        set(job.id, JobState.Running(null, "Starting"))
        val session = Session(id = "q${job.id}")
        paths.sessionTmp(session).apply { deleteRecursively(); mkdirs() }
        paths.sessionShm(session).apply { deleteRecursively(); mkdirs() }
        val manifest = PermissionManifest(
            id = "queue-${job.id}",
            name = job.title,
            exec = listOf("/bin/sh", "-c", script(job)),
            permissions = Permissions(network = job.action == QueueAction.INSTALL, root = true),
        )
        return try {
            val spec = backend.buildLaunch(manifest, session)
            val pid = IntArray(1)
            // A pty, not a pipe: the chroot entry script needs a terminal for setsid -c.
            val fd = Pty.spawn(spec, rows = 24, cols = 200, pidOut = pid)
            runningPid = pid[0]
            val parser = AptProgress()
            val tail = ArrayDeque<String>()
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd)).bufferedReader().use { r ->
                try {
                    r.lineSequence().forEach { raw ->
                        val line = raw.trimEnd('\r')
                        parser.feed(line)?.let { (p, msg) -> set(job.id, JobState.Running(p, msg)) }
                        if (line.isNotBlank() && !line.startsWith("dlstatus:") && !line.startsWith("pmstatus:")) {
                            tail.addLast(line); if (tail.size > 3) tail.removeFirst()
                        }
                    }
                } catch (_: IOException) {
                    // EIO when the pty closes: end of output.
                }
            }
            val code = Pty.waitFor(pid[0])
            runningPid = 0
            if (code == 0) {
                set(job.id, JobState.Done); true
            } else {
                set(job.id, JobState.Failed(if (code < 0) "Stopped" else tail.lastOrNull { it.startsWith("E:") } ?: tail.lastOrNull() ?: "apt failed ($code)"))
                false
            }
        } catch (e: Exception) {
            set(job.id, JobState.Failed(e.message ?: "couldn't start"))
            false
        } finally {
            paths.sessionTmp(session).parentFile?.deleteRecursively()
        }
    }

    private fun set(id: Int, state: JobState) = _jobs.update { list -> list.map { if (it.id == id) it.copy(state = state) else it } }

    companion object {
        /** apt in two phases with machine-readable progress; markers tell [AptProgress] which. */
        fun script(job: QueueJob): String {
            val opts = "-o APT::Status-Fd=1 -o Dpkg::Use-Pty=0 -o Dpkg::Progress-Fancy=0"
            val pkgs = job.packages.joinToString(" ")
            val main = when (job.action) {
                QueueAction.INSTALL -> "apt-get install -y --no-install-recommends $opts $pkgs"
                QueueAction.REMOVE -> "apt-get remove -y $opts $pkgs"
            }
            return "export DEBIAN_FRONTEND=noninteractive; " +
                (if (job.action == QueueAction.INSTALL) "echo '##phase update'; apt-get update $opts || exit \$?; " else "") +
                "echo '##phase main'; $main"
        }
    }
}

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
