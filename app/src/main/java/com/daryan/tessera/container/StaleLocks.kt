// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File
import java.nio.file.Files

/**
 * Mozilla apps (Firefox, Thunderbird) mark a profile in use with a `lock` symlink to
 * "<ip>:+<pid>" and treat it as live while that pid exists. Sessions we stop never get to
 * remove it, and Android reuses pids constantly, so a stale link soon names some unrelated
 * process and every launch ends in "Firefox is already running, but is not responding".
 * Before launching, drop links whose pid isn't one of our own Mozilla processes.
 */
object StaleLocks {
    private val PROFILE_DIRS = listOf(
        "home/user/.config/mozilla/firefox", "home/user/.mozilla/firefox", "home/user/.thunderbird",
        "root/.config/mozilla/firefox", "root/.mozilla/firefox", "root/.thunderbird",
    )
    private val PID = Regex("\\+(\\d+)$")

    /** Removes stale profile locks under [rootfs]; returns the links removed. */
    fun clean(rootfs: File, uid: Int, proc: File = File("/proc")): List<File> =
        PROFILE_DIRS.flatMap { dir -> File(rootfs, dir).listFiles().orEmpty().toList() }
            .map { File(it, "lock") }
            .filter { link ->
                val path = link.toPath()
                if (!Files.isSymbolicLink(path)) return@filter false
                val pid = PID.find(Files.readSymbolicLink(path).toString())?.groupValues?.get(1)?.toIntOrNull()
                pid == null || !isOurMozillaProcess(pid, uid, proc)
            }
            .onEach { Files.deleteIfExists(it.toPath()) }

    private fun isOurMozillaProcess(pid: Int, uid: Int, proc: File): Boolean {
        val dir = File(proc, "$pid")
        val owner = runCatching {
            File(dir, "status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
        }.getOrNull() ?: return false
        if (owner != uid) return false
        val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrDefault("")
        return "firefox" in cmdline || "thunderbird" in cmdline
    }
}
