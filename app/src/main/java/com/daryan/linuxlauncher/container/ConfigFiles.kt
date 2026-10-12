// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Which form edits a file; [TEXT] files only have the text view. */
enum class ConfigKind { SHELL, ENVIRONMENT, SOURCES, RESOLV, HOSTS, TEXT }

/** A config file inside the container, as listed in the side menu. Paths are guest paths. */
data class ConfigFile(val group: String, val title: String, val path: String, val hint: String, val kind: ConfigKind)

object ConfigCatalog {
    val files = listOf(
        ConfigFile("Shell", "Root's .bashrc", "/root/.bashrc", "Runs for every root shell (Root shell, installs)", ConfigKind.SHELL),
        ConfigFile("Shell", "User's .bashrc", "/home/user/.bashrc", "Runs for every shell of the normal user", ConfigKind.SHELL),
        ConfigFile("Shell", "System bashrc", "/etc/bash.bashrc", "Runs for every interactive bash, before ~/.bashrc", ConfigKind.SHELL),
        ConfigFile("Shell", "Login profile", "/etc/profile", "Runs for login shells; sets PATH and the prompt", ConfigKind.SHELL),
        ConfigFile("Shell", "Environment", "/etc/environment", "KEY=value variables for every session", ConfigKind.ENVIRONMENT),
        ConfigFile("Packages", "Package sources", "/etc/apt/sources.list.d/debian.sources", "Repositories apt installs from", ConfigKind.SOURCES),
        ConfigFile("Packages", "apt settings", "/etc/apt/apt.conf.d/99launcher", "apt options set by the launcher", ConfigKind.TEXT),
        ConfigFile("Network", "DNS servers", "/etc/resolv.conf", "Name servers used inside the container", ConfigKind.RESOLV),
        ConfigFile("Network", "Hosts", "/etc/hosts", "Static host names", ConfigKind.HOSTS),
    )
}

/** Reads and writes container files. Writes keep a one-time `.bak-launcher` of the original. */
interface ConfigStore {
    /** The file's text, or null when it doesn't exist. */
    suspend fun read(path: String): String?
    suspend fun write(path: String, text: String)
}

/** PRoot mode: the rootfs lives in app storage. */
class DirectConfigStore(private val root: File) : ConfigStore {
    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        guest(path).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(path: String, text: String) = withContext(Dispatchers.IO) {
        val f = guest(path)
        val backup = File(f.path + BACKUP)
        if (f.isFile && !backup.exists()) f.copyTo(backup)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun guest(path: String): File {
        require(path.startsWith("/") && ".." !in path.split('/')) { "bad path $path" }
        return File(root, path.removePrefix("/"))
    }
}

/** Chroot mode: the rootfs is root-owned, so every access goes through su. */
class SuConfigStore(private val su: String, private val root: String) : ConfigStore {
    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        val p = shellQuote(guest(path))
        val proc = ProcessBuilder(su, "-c", "if [ -f $p ]; then cat $p; else exit $MISSING; fi").start()
        val text = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        when (proc.waitFor()) {
            0 -> text
            MISSING -> null
            else -> throw IOException("su: ${err.ifBlank { "permission denied" }.trim()}")
        }
    }

    override suspend fun write(path: String, text: String) = withContext(Dispatchers.IO) {
        val full = guest(path)
        val p = shellQuote(full)
        val dir = shellQuote(full.substringBeforeLast('/'))
        val script = "if [ -f $p ] && [ ! -f $p$BACKUP ]; then cp -p $p $p$BACKUP; fi; " +
            "mkdir -p $dir && cat > $p && " +
            // Files in the normal user's home must stay theirs (uid 1000 in chroot sessions).
            "case $p in */home/user/*) chown 1000:1000 $p;; esac"
        val proc = ProcessBuilder(su, "-c", script).start()
        proc.outputStream.use { it.write(text.toByteArray()) }
        val err = proc.errorStream.bufferedReader().readText()
        if (proc.waitFor() != 0) throw IOException("su: ${err.ifBlank { "write failed" }.trim()}")
    }

    private fun guest(path: String): String {
        require(path.startsWith("/") && ".." !in path.split('/')) { "bad path $path" }
        return root.trimEnd('/') + path
    }

    private companion object {
        const val MISSING = 3
    }
}

private const val BACKUP = ".bak-launcher"
