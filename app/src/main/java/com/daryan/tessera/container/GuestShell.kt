// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * A bash running inside the container over pipes, for work the launcher does itself (the Files
 * tab). It is started like any app, through the backend, so paths are guest paths and chroot
 * mode needs su once per shell rather than once per command.
 *
 * Commands run one at a time. Each one's stdout comes back as bytes, its stderr as text.
 */
class GuestShell private constructor(private val process: Process) : Closeable {
    private val mutex = Mutex()
    private val input = BufferedInputStream(process.inputStream)
    private val stdin = process.outputStream
    /** What the shell itself (su, PRoot, bash) printed: the reason when it dies. */
    private val log = StringBuffer()

    init {
        Thread({
            runCatching {
                process.errorStream.bufferedReader().forEachLine {
                    log.append(it).append('\n')
                    if (log.length > 4000) log.delete(0, log.length - 4000)
                }
            }
        }, "guest-shell-stderr").apply { isDaemon = true }.start()
    }

    class Result(val status: Int, val out: ByteArray, val err: String) {
        val ok get() = status == 0
        val text get() = String(out)

        /** Throws with the command's own error message when it failed. */
        fun check(what: String): Result {
            if (!ok) throw IOException(err.trim().lineSequence().lastOrNull { it.isNotBlank() }?.let(::tidy) ?: "$what failed")
            return this
        }

        /** "cp: cannot stat '/x': No such file or directory" -> "Cannot stat '/x': No such file or directory". */
        private fun tidy(line: String) = line.substringAfter(": ").replaceFirstChar { it.uppercase() }
    }

    val alive get() = process.isAlive

    /**
     * Runs [script] (bash, may span lines). Its stdin is /dev/null and it runs in a subshell,
     * so it can't change this shell's state or read the commands meant for it.
     */
    suspend fun run(script: String): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!process.isAlive) throw IOException(died())
            val wrapped = "{ e=\$( { $script\n} </dev/null 2>&1 >&3 3>&- ); s=\$?; } 3>&1; " +
                "printf '\\0\\036%s\\036%s\\0\\036\\036' \"\$s\" \"\$e\"\n"
            try {
                stdin.write(wrapped.toByteArray())
                stdin.flush()
                read()
            } catch (e: IOException) {
                throw IOException(died(), e)
            }
        }
    }

    /** Reads up to the end marker the wrapper prints: \0 \036 status \036 stderr \0 \036 \036. */
    private fun read(): Result {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("end of stream")
            if (prev == 0 && b == 0x1e) break
            if (prev >= 0) out.write(prev)
            prev = b
        }
        val status = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("end of stream")
            if (b == 0x1e) break
            status.append(b.toChar())
        }
        val err = ByteArrayOutputStream()
        val tail = IntArray(3) { -1 }
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("end of stream")
            err.write(b)
            tail[0] = tail[1]; tail[1] = tail[2]; tail[2] = b
            if (tail[0] == 0 && tail[1] == 0x1e && tail[2] == 0x1e) break
        }
        val errBytes = err.toByteArray()
        return Result(status.toString().toIntOrNull() ?: -1, out.toByteArray(), String(errBytes, 0, errBytes.size - 3))
    }

    private fun died(): String {
        val why = log.toString().trim().lineSequence().lastOrNull { it.isNotBlank() }
        return if (why != null) "The Linux shell stopped: $why" else "The Linux shell stopped"
    }

    override fun close() {
        // EOF ends bash; in chroot mode killing su alone could leave it running.
        runCatching { stdin.close() }
        process.destroy()
    }

    companion object {
        /**
         * Starts a shell for [app] (its permissions decide root, folders and network) in a
         * session of its own: [sessionId] names its private /tmp and /dev/shm.
         */
        suspend fun start(backend: ContainerBackend, paths: ContainerPaths, app: PermissionManifest, sessionId: String): GuestShell =
            withContext(Dispatchers.IO) {
                val session = Session(id = sessionId, term = "dumb")
                for (dir in listOf(paths.sessionTmp(session), paths.sessionShm(session))) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                }
                val spec = backend.buildLaunch(app.copy(exec = listOf("/bin/bash", "--noprofile", "--norc"), gui = false), session)
                val process = ProcessBuilder(spec.argv)
                    .directory(File(spec.cwd))
                    .apply { environment().putAll(spec.env) }
                    .start()
                val shell = GuestShell(process)
                // A shell that never answers (su waiting on a prompt nobody sees) shouldn't hang
                // the caller forever; a minute is plenty for anyone to grant root.
                val watchdog = Thread {
                    try {
                        Thread.sleep(60_000)
                        shell.log.append("no answer after a minute\n")
                        process.destroy()
                    } catch (_: InterruptedException) {
                    }
                }.apply { isDaemon = true; start() }
                // Proves the whole chain (su, PRoot, chroot, bash) is up before anyone relies on it.
                val probe = runCatching { shell.run("echo ready") }
                watchdog.interrupt()
                if (probe.getOrNull()?.text?.trim() != "ready") {
                    shell.close()
                    throw IOException(probe.exceptionOrNull()?.message ?: shell.died())
                }
                shell
            }
    }
}
