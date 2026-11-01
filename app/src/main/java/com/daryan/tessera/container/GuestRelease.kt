// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

/**
 * What a Linux system says it is, from its /etc/os-release. Tessera's own systems are Debian; a
 * Droidspaces container can be anything, and only the Debian family (apt, dpkg, Debian's package
 * names) works with the Store, the install queue and the app manifests.
 */
data class GuestRelease(
    /** `ID`: debian, ubuntu, kali, arch… */
    val id: String,
    /** `ID_LIKE`: what it derives from, nearest first. */
    val idLike: List<String> = emptyList(),
    /** `VERSION_CODENAME`: trixie, noble, kali-rolling… */
    val codename: String? = null,
    /** `UBUNTU_CODENAME`, which Ubuntu's derivatives (Mint, Pop!_OS) carry beside their own. */
    val ubuntuCodename: String? = null,
    /** `PRETTY_NAME`, for people. */
    val prettyName: String? = null,
) {
    val isUbuntu: Boolean get() = id == "ubuntu" || "ubuntu" in idLike
    val isDebianFamily: Boolean get() = id == "debian" || isUbuntu || "debian" in idLike

    /**
     * Whether Debian 13's packages fit it, for what its own sources lack: a Debian derivative or
     * Debian past 13 (testing, sid), whose libraries are as new or newer. Not Ubuntu, whose
     * libraries differ, nor older Debian, which Debian 13's would need a newer libc than.
     */
    val takesDebianFallback: Boolean
        get() = isDebianFamily && !isUbuntu && codename !in setOf("buster", "bullseye", "bookworm", "trixie", "oldoldstable", "oldstable", "stable")

    /** How it's called to the user. */
    val label: String get() = prettyName ?: id.replaceFirstChar { it.uppercase() }

    companion object {
        /** [text] is an os-release file; null when it names no system. */
        fun parse(text: String?): GuestRelease? {
            if (text.isNullOrBlank()) return null
            val values = HashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            }
            val id = values["ID"]?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return GuestRelease(
                id = id,
                idLike = values["ID_LIKE"].orEmpty().lowercase().split(' ').filter { it.isNotEmpty() },
                codename = values["VERSION_CODENAME"]?.takeIf { it.isNotEmpty() },
                ubuntuCodename = values["UBUNTU_CODENAME"]?.takeIf { it.isNotEmpty() },
                prettyName = values["PRETTY_NAME"]?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
