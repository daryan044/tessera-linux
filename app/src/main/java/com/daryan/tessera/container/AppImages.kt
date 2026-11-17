// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

/**
 * AppImages as launcher apps (#9). An AppImage is a program with its files packed behind it,
 * which it mounts with FUSE; there's none here, so Tessera has it unpack itself once, into
 * [ROOT], and starts the app from there (assets/appimage/install.sh, in the install queue).
 */
object AppImages {
    /** Where AppImages are unpacked, each in a folder named by its [slug]. */
    const val ROOT = "/opt/appimages"

    /** What their launcher ids begin with. */
    const val ID_PREFIX = "appimage-"

    /** The desktop file and the icon (base64 of a PNG) install.sh leaves in an app's folder. */
    const val DESKTOP = ".tessera.desktop"
    const val ICON = ".tessera-icon"

    fun isAppImage(app: PermissionManifest) = app.id.startsWith(ID_PREFIX)

    /** The folder of the AppImage behind [app], or null when it isn't one. */
    fun dirOf(app: PermissionManifest): String? =
        app.id.takeIf { isAppImage(app) }?.removePrefix(ID_PREFIX)?.takeIf(SLUG::matches)?.let(::dir)

    fun dir(slug: String) = "$ROOT/$slug"

    /**
     * A folder name for the AppImage in the file named [fileName]: "Obsidian-1.5.3-arm64.AppImage"
     * gives "obsidian". Its version and processor are left out, so a newer download of the same
     * app replaces the old one rather than sitting beside it.
     */
    fun slug(fileName: String): String {
        val base = fileName.substringAfterLast('/').replace(Regex("(?i)\\.appimage$"), "")
        val parts = base.split(Regex("[-_ ]+")).filter { it.isNotEmpty() }
        // Up to the first part that reads as a version or a processor.
        val name = parts.takeWhile { !VERSION.matches(it) && it.lowercase() !in MACHINES }.ifEmpty { parts.take(1) }
        return name.joinToString("-").lowercase().replace(Regex("[^a-z0-9.-]"), "").trim('-', '.').take(40).ifEmpty { "app" }
    }

    /** A name to show for the AppImage in [fileName], until its own desktop file is read. */
    fun title(fileName: String): String =
        slug(fileName).split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /** install.sh ([script]) for the AppImage at [staged], unpacked under [slug]. */
    fun installScript(script: String, staged: String, slug: String): String {
        require(SLUG.matches(slug)) { "not a folder name for an AppImage: $slug" }
        return "STAGED=${shellQuote(staged)}; SLUG=$slug\n$script"
    }

    /** Deletes the unpacked AppImage [slug]. */
    fun removeScript(slug: String): String {
        require(SLUG.matches(slug)) { "not a folder name for an AppImage: $slug" }
        return "rm -rf ${dir(slug)} && echo Removed; test ! -e ${dir(slug)}"
    }

    /** What starts the app unpacked under [slug]: its AppRun, told where it is as a mounted AppImage would be. */
    fun exec(slug: String): List<String> = listOf("/usr/bin/env", "APPDIR=${dir(slug)}", "${dir(slug)}/AppRun")

    /**
     * The processor an AppImage is for, from the [header] of its file (an ELF program's first 20
     * bytes): "arm64-v8a", "armeabi-v7a", "x86_64", "x86", or null when it isn't a program at all.
     */
    fun abi(header: ByteArray): String? {
        if (header.size < 20 || header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return null
        return when ((header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)) {
            0xB7 -> "arm64-v8a"
            0x28 -> "armeabi-v7a"
            0x3E -> "x86_64"
            0x03 -> "x86"
            else -> "another"
        }
    }

    /** How AppImage makers name a processor, for telling the user which download to get. */
    fun machineName(abi: String): String = when (abi) {
        "arm64-v8a" -> "aarch64 (arm64)"
        "armeabi-v7a" -> "armhf"
        "x86_64" -> "x86_64"
        "x86" -> "i386"
        else -> abi
    }

    private val SLUG = Regex("[a-z0-9][a-z0-9.-]{0,39}")
    private val VERSION = Regex("v?\\d+([._]\\d+)*([._-]?(alpha|beta|rc|nightly|stable|git)\\d*)?", RegexOption.IGNORE_CASE)
    private val MACHINES = setOf("aarch64", "arm64", "armhf", "armv7", "arm", "x86_64", "x86-64", "x86", "amd64", "x64", "i386", "i686", "linux", "appimage")
}
