// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * The container's name servers: the ones Android's network uses (Wi-Fi's, the carrier's, a
 * VPN's), so networks that only allow their own resolver (eduroam, many offices) work. Public
 * servers only when Android gives none.
 *
 * /etc/resolv.conf stays automatic while its first line is [AUTO_MARKER]: it's rewritten as each
 * session starts. Saving the DNS servers in the config editor drops the line, and the file is
 * then the user's.
 */
object HostDns {
    const val AUTO_MARKER = "# Kept to Android's DNS servers by Tessera. Delete this line to keep your own."
    /** What earlier versions always wrote, taken as automatic too. */
    internal const val LEGACY = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
    private val FALLBACK = listOf("1.1.1.1", "8.8.8.8")

    /** The active network's DNS servers, as addresses; empty when Android doesn't say. */
    fun servers(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    /** An automatic resolv.conf for [servers]; glibc reads three at most. */
    fun resolvConf(servers: List<String>): String =
        AUTO_MARKER + "\n" + servers.ifEmpty { FALLBACK }.distinct().take(3).joinToString("") { "nameserver $it\n" }

    /** Whether a resolv.conf reading [text] (null: none) is Tessera's to rewrite. */
    fun isAutomatic(text: String?): Boolean = text == null || text.startsWith(AUTO_MARKER) || text == LEGACY

    /** [text] as the user's own file: an edit means they chose their servers. */
    fun userOwned(text: String): String = text.lines().filterNot { it == AUTO_MARKER }.joinToString("\n")

    /** Rewrites [file] with [servers] while it's automatic (PRoot: the app owns the rootfs). */
    fun refresh(file: File, servers: List<String>) {
        runCatching {
            val current = if (java.nio.file.Files.isSymbolicLink(file.toPath())) null else file.takeIf { it.isFile }?.readText()
            if (!isAutomatic(current)) return
            val text = resolvConf(servers)
            if (text == current) return
            file.delete()
            file.writeText(text)
        }
    }
}
