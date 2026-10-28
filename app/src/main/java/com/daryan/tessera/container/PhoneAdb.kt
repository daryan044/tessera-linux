// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * adb on this phone for apps with [Permissions.adb]: root turns on adbd's TCP port, reachable
 * from the phone itself only, and [WRAPPER] in the container connects the guest's adb to it.
 * The phone still asks, once, whether to allow the container's key, as it would for a PC.
 */
object PhoneAdb {
    /** Where adbd listens when Tessera turns it on; one the user set is kept instead. */
    const val PORT = 5555
    /** Debian's adb and fastboot, also wanted by apps with [Permissions.usb]. */
    val PACKAGES = listOf("adb", "fastboot")
    /** Ahead of /usr/bin/adb in PATH. */
    const val WRAPPER = "/usr/local/bin/adb"
    /** host:port the guest's adb reaches the phone at; see [WRAPPER_SCRIPT]. */
    const val ENV = "TESSERA_ADB"

    sealed interface Result {
        /** adbd listens at [address] (127.0.0.1:port). */
        data class Ready(val address: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Turns on adbd's TCP port through [su], unless it's on already, and writes [WRAPPER] into
     * [rootfs] ([asRoot]: the chroot system, root's files). Restarting adbd drops a PC's USB
     * connection for a moment; once on, it stays on until the phone restarts.
     */
    fun enable(su: String, rootfs: File, asRoot: Boolean): Result {
        if (!asRoot) runCatching {
            File(rootfs, WRAPPER.removePrefix("/")).apply {
                parentFile?.mkdirs()
                writeText(WRAPPER_SCRIPT)
                setExecutable(true, false)
            }
        }.onFailure { return Result.Failed("couldn't write $WRAPPER: ${it.message}") }
        val script = buildString {
            if (asRoot) {
                val target = File(rootfs, WRAPPER.removePrefix("/")).invariantSeparatorsPath
                append("mkdir -p ${shellQuote(File(target).parent!!)}\n")
                append("cat > ${shellQuote(target)} <<'TESSERA_EOF'\n$WRAPPER_SCRIPT\nTESSERA_EOF\n")
                append("chmod 755 ${shellQuote(target)}\n")
            }
            append(ENABLE_SCRIPT)
        }
        val (status, out) = runSu(su, script) ?: return Result.Failed("root was refused")
        return when (status) {
            0 -> out.lines().firstOrNull { it.startsWith("port=") }?.removePrefix("port=")?.trim()?.toIntOrNull()
                ?.let { Result.Ready("127.0.0.1:$it") } ?: Result.Failed("adbd didn't say where it listens")
            3 -> Result.Failed("USB debugging is off; turn it on in Developer options")
            4 -> Result.Failed("couldn't keep adb's port to this phone (iptables), so it stayed off")
            5 -> Result.Failed("adbd didn't start listening")
            else -> Result.Failed(out.trim().lines().lastOrNull().orEmpty().ifEmpty { "setup failed ($status)" })
        }
    }

    /**
     * The firewall goes first: other devices on the Wi-Fi must never reach the port. A port the
     * user turned on themselves is theirs, firewall and all, and used as it is.
     */
    private val ENABLE_SCRIPT = """
        [ "${'$'}(settings get global adb_enabled)" = 1 ] || exit 3
        port=${'$'}(getprop service.adb.tcp.port)
        case "${'$'}port" in ''|0|*[!0-9]*)
          port=$PORT
          for t in iptables ip6tables; do
            ${'$'}t -C INPUT -p tcp --dport ${'$'}port ! -i lo -j DROP 2>/dev/null ||
              ${'$'}t -I INPUT -p tcp --dport ${'$'}port ! -i lo -j DROP || exit 4
          done
          setprop service.adb.tcp.port ${'$'}port
          setprop ctl.restart adbd
        esac
        # Listening (0A) on the port, in /proc/net's hex.
        hex=${'$'}(printf '%04X' ${'$'}port)
        i=0
        until grep -q ":${'$'}hex [0-9A-F]*:0000 0A" /proc/net/tcp6 /proc/net/tcp 2>/dev/null; do
          [ ${'$'}i -lt 50 ] || exit 5
          sleep 0.1; i=${'$'}((i + 1))
        done
        echo "port=${'$'}port"
    """.trimIndent()

    /**
     * Connects before every command, so the phone is listed even after the adb server restarted
     * (it ends with the session that started it). Without [ENV], plain adb.
     */
    private val WRAPPER_SCRIPT = """
        #!/bin/sh
        # Written by Tessera: in apps allowed to control this phone, ${'$'}$ENV is where it listens.
        if [ -n "${'$'}$ENV" ]; then
          # Local ports from 5555 up are otherwise scanned for emulators, listing the phone twice.
          export ADB_LOCAL_TRANSPORT_MAX_PORT=5553
          /usr/bin/adb connect "${'$'}$ENV" >/dev/null 2>&1
        fi
        exec /usr/bin/adb "${'$'}@"
    """.trimIndent()

    /** [script]'s exit status and output, run through [su] from stdin; null when su failed. */
    private fun runSu(su: String, script: String): Pair<Int, String>? = runCatching {
        val p = ProcessBuilder(su).redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(RootShell.PRELUDE); it.write("\n"); it.write(script); it.write("\n") }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroy()
            null
        } else p.exitValue() to out
    }.getOrNull()
}
