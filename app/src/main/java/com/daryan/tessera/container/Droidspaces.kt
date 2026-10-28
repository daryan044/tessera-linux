// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.util.concurrent.TimeUnit

/** A Droidspaces container as found on the phone. */
data class DroidspacesContainer(
    /** The folder under Containers/, which is what `--name` takes. */
    val name: String,
    val config: DroidspacesConfig,
    /** The container's init, as the phone numbers processes; null while it is stopped. */
    val pid: Int?,
    /** Tessera's folders are shared into it (as it booted, when it runs). */
    val bridged: Boolean,
) {
    val running: Boolean get() = pid != null

    /**
     * Where its files are, as root sees them: /proc/<pid>/root while it runs (which works for
     * an image rootfs too), the rootfs directory while it is stopped. Null for a stopped image.
     */
    val hostRoot: String? get() = when {
        pid != null -> "/proc/$pid/root"
        config.rootfs != null && !config.isImage -> config.rootfs
        else -> null
    }
}

/** Where Droidspaces keeps things, and where Tessera's folders show up inside a container. */
object Droidspaces {
    const val WORKSPACE = "/data/local/Droidspaces"
    const val CONTAINERS = "$WORKSPACE/Containers"
    val BINARY_PATHS = listOf("$WORKSPACE/bin/droidspaces")

    /**
     * The folders Tessera shares into a container, at container start (Droidspaces has no way to
     * add a mount to a running container). Not under /run: some init systems mount a fresh one.
     */
    const val GUEST_SHARE = "/mnt/tessera"
    const val GUEST_SESSIONS = "$GUEST_SHARE/sessions"
    const val GUEST_BIN = "$GUEST_SHARE/bin"
    const val GUEST_SCRIPT = "$GUEST_BIN/enter.sh"

    /** The file in a session's folder that the in-container script reads its settings from. */
    const val LAUNCH_FILE = "launch.env"

    /** What a container needs bound to run Tessera's apps: every session's files, and the script. */
    fun bridgeBinds(paths: ContainerPaths) = listOf(
        DroidspacesBind(paths.sessionsDir.invariantSeparatorsPath, GUEST_SESSIONS),
        DroidspacesBind(paths.droidspacesDir.invariantSeparatorsPath, GUEST_BIN, readOnly = true),
    )

    /** Names Droidspaces makes are plain; anything else is refused before it reaches a root shell. */
    fun validName(name: String) = name.isNotEmpty() && name != "." && name != ".." && name.all { it.isLetterOrDigit() || it in "._-" }
}

/**
 * Droidspaces seen from the app, through root: which containers exist, starting and stopping
 * them, and preparing one for Tessera. Every call blocks; call them off the main thread.
 */
class DroidspacesHost(private val su: String) {
    class Result(val ok: Boolean, val output: String)

    private class SuOut(val code: Int, val text: String)

    /** Droidspaces' binary, or null when it isn't installed (or root was refused). */
    fun binary(): String? {
        val candidates = Droidspaces.BINARY_PATHS.joinToString(" ") { shellQuote(it) }
        val script = "for b in $candidates; do if [ -x \"\$b\" ]; then echo \"\$b\"; exit 0; fi; done; command -v droidspaces"
        return suRun(script, 15)?.text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.startsWith("/") }
    }

    /**
     * Every container in Droidspaces' workspace, stopped ones too; null when root answered
     * nothing usable. The config of a running one is the one it booted with, so what Tessera
     * reads about it (what is bound) is what is actually in it.
     */
    fun containers(binary: String, needed: List<DroidspacesBind>): List<DroidspacesContainer>? {
        val b = shellQuote(binary)
        val script = buildString {
            appendLine("b=$b")
            appendLine("cd ${Droidspaces.CONTAINERS} 2>/dev/null || exit 0")
            appendLine("for d in */; do")
            appendLine("  n=\${d%/}")
            appendLine("  [ -f \"\$n/container.config\" ] || continue")
            appendLine("  pid=\$(\"\$b\" --name=\"\$n\" pid 2>/dev/null | head -n 1)")
            appendLine("  echo \"$MARK\$n\"")
            appendLine("  echo \"$PID_MARK\$pid\"")
            appendLine("  if [ \"\$pid\" -gt 1 ] 2>/dev/null && [ -f \"/proc/\$pid/root/run/droidspaces/container.config\" ]; then")
            appendLine("    cat \"/proc/\$pid/root/run/droidspaces/container.config\"")
            appendLine("  else")
            appendLine("    cat \"\$n/container.config\"")
            appendLine("  fi")
            appendLine("done")
            // A container whose config can't be read must not make the whole list look failed.
            appendLine("exit 0")
        }
        val out = suRun(script, 60)?.takeIf { it.code == 0 } ?: return null
        return parseListing(out.text, needed)
    }

    fun start(binary: String, name: String): Result = control(binary, name, "start", 120)

    fun stop(binary: String, name: String): Result = control(binary, name, "stop", 60)

    fun restart(binary: String, name: String): Result = control(binary, name, "restart", 180)

    /**
     * Shares Tessera's folders ([needed]) into [name]: they are added to the container's saved
     * config, which Droidspaces applies the next time the container boots. A running container
     * is restarted when [restart] says so (its apps end). Everything else in the config stays.
     */
    fun prepare(binary: String, name: String, needed: List<DroidspacesBind>, restart: Boolean): Result {
        if (!Droidspaces.validName(name)) return Result(false, "Unusable container name")
        val file = shellQuote("${Droidspaces.CONTAINERS}/$name/container.config")
        val current = suRun("if [ -f $file ]; then cat $file; else exit 3; fi", 15)
        if (current == null || current.code != 0) {
            return Result(false, "Couldn't read the container's config (it may live outside ${Droidspaces.CONTAINERS})")
        }
        val updated = DroidspacesConfig.withBinds(current.text, needed)
        // A copy of the original, once; the write keeps the file's owner and mode.
        val write = suRun("[ -f $file.bak-tessera ] || cp -p $file $file.bak-tessera; cat > $file", 15, stdin = updated)
        if (write == null || write.code != 0) return Result(false, "Couldn't save the container's config: ${write?.text?.trim().orEmpty()}")
        return if (restart) restart(binary, name) else Result(true, "")
    }

    /** Runs [command] on the container with its output in a file, not on our pipe: a container that boots from it must not hold the pipe open. */
    private fun control(binary: String, name: String, command: String, seconds: Long): Result {
        if (!Droidspaces.validName(name)) return Result(false, "Unusable container name")
        val script = "f=\$(mktemp 2>/dev/null || echo /data/local/tmp/tessera-ds.\$\$); " +
            "${shellQuote(binary)} --name=${shellQuote(name)} $command >\"\$f\" 2>&1 </dev/null; c=\$?; " +
            "cat \"\$f\"; rm -f \"\$f\"; exit \$c"
        val r = suRun(script, seconds) ?: return Result(false, "Droidspaces didn't answer in time")
        return Result(r.code == 0, r.text.trim())
    }

    private fun suRun(script: String, seconds: Long, stdin: String? = null): SuOut? = runCatching {
        val p = ProcessBuilder(su, "-c", script).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = StringBuffer()
        val reader = Thread({
            runCatching { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        }, "droidspaces-su").apply { isDaemon = true; start() }
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.destroy()
            return@runCatching null
        }
        reader.join(2000)
        SuOut(p.exitValue(), out.toString())
    }.getOrNull()

    companion object {
        private const val MARK = "@@TESSERA "
        private const val PID_MARK = "@@PID "

        /** The listing [containers]'s script prints: per container a marker line, its pid line, its config. */
        internal fun parseListing(text: String, needed: List<DroidspacesBind>): List<DroidspacesContainer> {
            val found = mutableListOf<DroidspacesContainer>()
            var name: String? = null
            var pid: Int? = null
            val body = StringBuilder()
            fun flush() {
                val n = name ?: return
                val config = DroidspacesConfig.parse(n, body.toString())
                found += DroidspacesContainer(n, config, pid, config.hasBinds(needed))
            }
            for (line in text.lineSequence()) {
                when {
                    line.startsWith(MARK) -> {
                        flush()
                        name = line.removePrefix(MARK).trim().takeIf { Droidspaces.validName(it) }
                        pid = null
                        body.setLength(0)
                    }
                    line.startsWith(PID_MARK) && name != null && body.isEmpty() ->
                        pid = line.removePrefix(PID_MARK).trim().toIntOrNull()?.takeIf { it > 1 }
                    name != null -> body.appendLine(line)
                }
            }
            flush()
            return found.sortedBy { it.name.lowercase() }
        }
    }
}
