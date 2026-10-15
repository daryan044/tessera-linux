// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.files

import com.daryan.tessera.container.ContainerBackend
import com.daryan.tessera.container.ContainerPaths
import com.daryan.tessera.container.FsGrant
import com.daryan.tessera.container.GuestShell
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.Permissions
import com.daryan.tessera.container.shellQuote
import com.daryan.tessera.store.DesktopEntry
import java.io.Closeable
import java.io.File
import java.io.IOException

/** One file or folder as the Files tab shows it. Paths are guest paths. */
data class FileEntry(
    val path: String,
    val name: String,
    /** Is, or links to, a folder. */
    val isDir: Boolean,
    val isLink: Boolean,
    /** A link whose target is missing. */
    val broken: Boolean,
    val size: Long,
    /** Milliseconds since the epoch. */
    val modified: Long,
    /** Permission bits, e.g. 0755. */
    val mode: Int,
    val owner: String,
    val group: String,
    val linkTarget: String?,
) {
    val hidden get() = name.startsWith('.')
    val extension get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
}

/** What a .deb file installs. */
data class DebInfo(val pkg: String, val version: String, val summary: String)

/** What a folder holds, for Properties. */
data class Usage(val bytes: Long, val files: Int, val folders: Int)

enum class ArchiveFormat(val label: String, val extension: String) {
    TAR_GZ("tar.gz", ".tar.gz"),
    TAR_XZ("tar.xz", ".tar.xz"),
    ZIP("zip", ".zip"),
}

/**
 * File operations inside the container, through a [GuestShell]. Android's storage is bound in
 * at its usual paths, so one shell sees both systems. Files cross to Android (to open them in
 * an Android app, or to bring one in) through [transferDir], bound at [TRANSFER].
 */
class GuestFiles private constructor(
    private val shell: GuestShell,
    /** Host side of [TRANSFER]. */
    val transferDir: File,
    val root: Boolean,
) : Closeable {
    val alive get() = shell.alive

    suspend fun list(dir: String): List<FileEntry> {
        val r = shell.run("find -H ${q(dir)} -mindepth 1 -maxdepth 1 -printf $FORMAT")
        val entries = parse(r.out)
        // find still lists what it could when part of a folder is unreadable.
        if (!r.ok && entries.isEmpty()) r.check("Listing $dir")
        return entries
    }

    /** Names in [dir] matching [query] (any case), searched through every folder below it. */
    suspend fun search(dir: String, query: String, limit: Int = 500): List<FileEntry> {
        val pattern = "*" + query.replace(Regex("""[\\*?\[\]]""")) { "\\" + it.value } + "*"
        val r = shell.run(
            "find -H ${q(dir)} -mindepth 1 \\( -path /proc -o -path /sys -o -path /dev \\) -prune " +
                "-o -iname ${q(pattern)} -printf $FORMAT 2>/dev/null | head -z -n ${limit * FIELDS}",
        )
        return parse(r.out)
    }

    suspend fun stat(path: String): FileEntry? =
        parse(shell.run("find -H ${q(path)} -maxdepth 0 -printf $FORMAT").out).firstOrNull()

    suspend fun exists(path: String) = shell.run("[ -e ${q(path)} ] || [ -L ${q(path)} ]").ok

    suspend fun mkdir(path: String) {
        refuseExisting(path)
        shell.run("mkdir -- ${q(path)}").check("Making the folder")
    }

    suspend fun newFile(path: String) {
        refuseExisting(path)
        shell.run(": > ${q(path)}").check("Making the file")
    }

    suspend fun rename(path: String, newName: String) {
        require('/' !in newName && newName.isNotBlank() && newName != "." && newName != "..") { "That name can't be used" }
        val target = path.substringBeforeLast('/').ifEmpty { "" } + "/" + newName
        refuseExisting(target)
        shell.run("mv -T -- ${q(path)} ${q(target)}").check("Renaming")
    }

    suspend fun delete(paths: List<String>) {
        shell.run("rm -rf -- ${paths.joinToString(" ") { q(it) }}").check("Deleting")
    }

    suspend fun chmod(path: String, mode: Int, recursive: Boolean) {
        val r = if (recursive) "-R " else ""
        shell.run("chmod $r${Integer.toOctalString(mode)} -- ${q(path)}").check("Changing permissions")
    }

    /** Names already in [dir], to pick free names for pasted and extracted things. */
    suspend fun names(dir: String): Set<String> =
        String(shell.run("find -H ${q(dir)} -mindepth 1 -maxdepth 1 -printf '%P\\0'").out)
            .split('\u0000').filter { it.isNotEmpty() }.toSet()

    /** Total size of [paths], following nothing; for progress. */
    suspend fun bytes(paths: List<String>): Long =
        shell.run("du -sbc -- ${paths.joinToString(" ") { q(it) }} 2>/dev/null | tail -n1")
            .text.substringBefore('\t').trim().toLongOrNull() ?: 0L

    suspend fun usage(path: String): Usage {
        val p = q(path)
        val out = shell.run(
            "du -sb -- $p 2>/dev/null | cut -f1; find -H $p -type f 2>/dev/null | wc -l; find -H $p -mindepth 1 -type d 2>/dev/null | wc -l",
        ).text.lines()
        return Usage(out.getOrNull(0)?.trim()?.toLongOrNull() ?: 0, out.getOrNull(1)?.trim()?.toIntOrNull() ?: 0, out.getOrNull(2)?.trim()?.toIntOrNull() ?: 0)
    }

    /** Copies each source to its target path. */
    suspend fun copy(pairs: List<Pair<String, String>>) {
        val script = pairs.joinToString("\n") { (src, dst) ->
            // Android's storage keeps no Linux owners or modes; asking cp to keep them there fails.
            val keep = if (onAndroid(dst) || onAndroid(src)) "-R --preserve=timestamps" else "-a"
            "cp $keep -- ${q(src)} ${q(dst)} || exit"
        }
        shell.run(script).check("Copying")
    }

    suspend fun move(pairs: List<Pair<String, String>>) {
        val script = pairs.joinToString("\n") { (src, dst) ->
            if (onAndroid(src) != onAndroid(dst)) {
                // Between the two systems mv would copy and then trip over owners, as above.
                "cp -R --preserve=timestamps -- ${q(src)} ${q(dst)} && rm -rf -- ${q(src)} || exit"
            } else {
                "mv -T -- ${q(src)} ${q(dst)} || exit"
            }
        }
        shell.run(script).check("Moving")
    }

    suspend fun has(command: String) = shell.run("command -v ${q(command)} >/dev/null").ok

    /** Packs [paths], which share a folder, into [archive]. */
    suspend fun compress(paths: List<String>, archive: String, format: ArchiveFormat) {
        val dir = paths.first().substringBeforeLast('/').ifEmpty { "/" }
        val names = paths.joinToString(" ") { q(it.substringAfterLast('/')) }
        val cmd = when (format) {
            ArchiveFormat.TAR_GZ -> "tar -czf ${q(archive)} -- $names"
            ArchiveFormat.TAR_XZ -> "tar -cJf ${q(archive)} -- $names"
            ArchiveFormat.ZIP -> "command -v zip >/dev/null || { echo 'x: Install the zip package to make zip files' >&2; exit 1; }; zip -qry ${q(archive)} $names"
        }
        shell.run("cd ${q(dir)} && $cmd").check("Compressing")
    }

    /** Unpacks [archive] into [dir], which it makes. */
    suspend fun extract(archive: String, dir: String) {
        val a = q(archive)
        val d = q(dir)
        val cmd = if (archive.lowercase().endsWith(".zip")) {
            "if command -v unzip >/dev/null; then unzip -q $a -d $d; " +
                "elif command -v python3 >/dev/null; then python3 -m zipfile -e $a $d; " +
                "else echo 'x: Install the unzip package to open zip files' >&2; exit 1; fi"
        } else {
            "tar -xf $a -C $d"
        }
        shell.run("mkdir -p -- $d && { $cmd; } || { s=\$?; rmdir -- $d 2>/dev/null; exit \$s; }").check("Extracting")
    }

    /**
     * Copies [path] out to Android, for an Android app to open or share. It keeps its name, in a
     * folder of its own; [discard] it when done.
     */
    suspend fun export(path: String): File {
        val sub = System.nanoTime().toString()
        val name = path.substringAfterLast('/')
        val d = q("$TRANSFER/$sub")
        val t = q("$TRANSFER/$sub/$name")
        // Readable by the launcher even when a root shell made the copy.
        shell.run(
            "mkdir -p $d && cp -L -- ${q(path)} $t && chmod 755 $d && chmod 644 $t && " +
                "{ [ \"\$(id -u)\" != 0 ] || chown -R --reference=$TRANSFER $d 2>/dev/null || true; }",
        ).check("Copying out")
        return File(File(transferDir, sub), name)
    }

    /** Removes an [export]ed copy. */
    fun discard(f: File) {
        f.delete()
        f.parentFile?.takeIf { it != transferDir && it.parentFile == transferDir }?.delete()
    }

    /** Moves [file], which must be in [transferDir], to [path] in the container. */
    suspend fun import(file: File, path: String, overwrite: Boolean = false) {
        require(file.parentFile == transferDir)
        val t = q("$TRANSFER/${file.name}")
        // Overwriting writes into the existing file, so it keeps its owner and mode.
        val put = if (overwrite) "cat $t > ${q(path)}" else "cp -- $t ${q(path)}"
        try {
            shell.run(put).check("Saving")
        } finally {
            file.delete()
        }
    }

    suspend fun readText(path: String, limit: Long = MAX_TEXT): String? {
        if (!exists(path)) return null
        val size = stat(path)?.size ?: 0
        if (size > limit) throw IOException("Too big to edit here (${size / 1024} KB)")
        val f = export(path)
        try {
            return f.readText()
        } finally {
            discard(f)
        }
    }

    suspend fun writeText(path: String, text: String) {
        val f = File(transferDir, "${System.nanoTime()}-edit")
        f.writeText(text)
        import(f, path, overwrite = exists(path))
    }

    /**
     * The file's types as Linux names them (from `file` when installed, else from its name in
     * shared-mime-info, plus each type's parents), and the Linux apps that open any of them.
     * [hint] is Android's guess from the extension, to fall back on.
     */
    suspend fun handlers(path: String, hint: String?): Pair<List<String>, List<Pair<String, DesktopEntry>>> {
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && "'" !in it }
        val types = shell.run(
            """
            p=${q(path)}; ext=${q(ext ?: "")}
            all=${q(hint ?: "")}
            command -v file >/dev/null 2>&1 && all="${'$'}all ${'$'}(file --mime-type -b -L -- "${'$'}p" 2>/dev/null)"
            if [ -n "${'$'}ext" ] && [ -f /usr/share/mime/globs2 ]; then
              all="${'$'}all ${'$'}(awk -F: -v e="*.${'$'}ext" 'tolower(${'$'}3)==e {print ${'$'}2}' /usr/share/mime/globs2)"
            fi
            if [ -f /usr/share/mime/subclasses ]; then
              for x in ${'$'}all; do all="${'$'}all ${'$'}(awk -v x="${'$'}x" '${'$'}1==x {print ${'$'}2}' /usr/share/mime/subclasses)"; done
            fi
            printf '%s\n' ${'$'}all
            """.trimIndent(),
        ).text.lines().map { it.trim() }.filter { MIME.matches(it) }.toMutableList()
        // Text editors register for text/plain; every text/* is plain text to them.
        if (types.any { it.startsWith("text/") }) types += "text/plain"
        val mimes = types.distinct().filterNot { it == "application/octet-stream" || it == "inode/x-empty" }
        if (mimes.isEmpty()) return mimes to emptyList()
        // Extended-regex specials that turn up in MIME names.
        val pattern = mimes.joinToString("|") { it.replace(Regex("""[.+]""")) { m -> "\\" + m.value } }
        val listing = shell.run(
            "grep -l -E ${q("^MimeType=(.*;)?($pattern)(;|$)")} /usr/share/applications/*.desktop 2>/dev/null | " +
                "while IFS= read -r f; do printf '\\036%s\\n' \"\${f##*/}\"; cat -- \"\$f\"; done",
        ).text
        val apps = listing.split('\u001e').filter { it.isNotBlank() }.mapNotNull { section ->
            val name = section.substringBefore('\n').trim()
            val entry = runCatching { DesktopEntry.parse(section.substringAfter('\n')) }.getOrNull() ?: return@mapNotNull null
            if (entry.name.isNullOrBlank()) null else name to entry
        }.distinctBy { it.second.name }
        return mimes to apps
    }

    /** Name, version and summary of the package in a .deb file. */
    suspend fun debInfo(path: String): DebInfo? {
        val r = shell.run("dpkg-deb -W --showformat='\${Package}\\n\${Version}\\n\${Description}' -- ${q(path)}")
        if (!r.ok) return null
        val lines = r.text.lines()
        val pkg = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DebInfo(pkg, lines.getOrNull(1).orEmpty().trim(), lines.getOrNull(2).orEmpty().trim())
    }

    /**
     * Copies a .deb to where apt can see it in the install queue's session (which shares none
     * of Android's folders), under a name safe to put on its command line.
     */
    suspend fun stageDeb(path: String, info: DebInfo): String {
        val name = "${info.pkg}_${info.version}".replace(Regex("[^A-Za-z0-9._+-]"), "_") + ".deb"
        val target = "$DEB_STAGING/$name"
        shell.run("mkdir -p $DEB_STAGING && chmod 1777 $DEB_STAGING 2>/dev/null; cp -- ${q(path)} $target && chmod 644 $target")
            .check("Getting the package ready")
        return target
    }

    suspend fun removeStaged(path: String) {
        if (path.startsWith("$DEB_STAGING/")) shell.run("rm -f -- ${q(path)}")
    }

    /**
     * This shell's pid when it leads its own session (chroot mode starts it under setsid), so
     * [killSession] can end everything it started; null under PRoot, where closing it does.
     */
    suspend fun sessionLeader(): Int? {
        val stat = shell.run("cat /proc/\$\$/stat").text
        val pid = stat.substringBefore(' ').trim().toIntOrNull() ?: return null
        // After the name: state, ppid, pgrp, session.
        val sid = stat.substringAfterLast(") ").split(' ').getOrNull(3)?.toIntOrNull()
        return pid.takeIf { sid == pid }
    }

    suspend fun killSession(sid: Int) {
        shell.run(
            "for f in /proc/[0-9]*/stat; do read -r l < \"\$f\" 2>/dev/null || continue; set -- \${l##*\") \"}; " +
                "[ \"\$4\" = $sid ] && { p=\${f#/proc/}; kill -9 \"\${p%/stat}\" 2>/dev/null; }; done; true",
        )
    }

    override fun close() = shell.close()

    private suspend fun refuseExisting(path: String) {
        if (exists(path)) throw IOException("${path.substringAfterLast('/')} already exists")
    }

    companion object {
        /** Where the transfer folder appears inside. */
        const val TRANSFER = "/run/tessera-transfer"
        const val MAX_TEXT = 2L * 1024 * 1024
        /** Where .deb files wait for apt; inside the rootfs, so every session sees it. */
        const val DEB_STAGING = "/var/tmp/tessera-debs"
        private val MIME = Regex("[a-z0-9.+-]+/[A-Za-z0-9.+_-]+")

        /** find's -printf: type, type behind a link, size, mtime, mode, owner, group, path, link. */
        private const val FORMAT = "'%y\\0%Y\\0%s\\0%T@\\0%m\\0%u\\0%g\\0%p\\0%l\\0'"
        private const val FIELDS = 9

        fun onAndroid(path: String) = path == "/storage" || path.startsWith("/storage/") || path.startsWith("/sdcard")

        private fun q(s: String) = shellQuote(s)

        internal fun parse(out: ByteArray): List<FileEntry> {
            val fields = String(out).split('\u0000')
            return (0 until fields.size / FIELDS).mapNotNull { i ->
                val f = fields.subList(i * FIELDS, i * FIELDS + FIELDS)
                val path = f[7].takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val link = f[0] == "l"
                FileEntry(
                    path = path,
                    name = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" },
                    isDir = f[1] == "d",
                    isLink = link,
                    broken = link && (f[1] == "N" || f[1] == "L"),
                    size = f[2].toLongOrNull() ?: 0,
                    modified = ((f[3].toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                    mode = f[4].toIntOrNull(8) ?: 0,
                    owner = f[5],
                    group = f[6],
                    linkTarget = f[8].takeIf { link },
                )
            }
        }

        /**
         * A shell for the Files tab: Android's [volumes] bound at their own paths, network off,
         * as root inside when [root].
         */
        suspend fun start(
            backend: ContainerBackend,
            paths: ContainerPaths,
            volumes: List<String>,
            root: Boolean,
            sessionId: String,
        ): GuestFiles {
            val transfer = File(paths.cacheDir, "files-transfer").apply { mkdirs() }
            val app = PermissionManifest(
                id = "files",
                name = "Files",
                exec = listOf("/bin/bash"),
                permissions = Permissions(
                    filesystem = volumes.map { FsGrant(it) } + FsGrant(transfer.invariantSeparatorsPath, TRANSFER),
                    root = root,
                ),
            )
            return GuestFiles(GuestShell.start(backend, paths, app, sessionId), transfer, root)
        }
    }
}
