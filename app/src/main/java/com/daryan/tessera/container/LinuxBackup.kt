// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A whole Linux system as one .tar.gz, to keep and put back (#19): after a reinstall of Tessera,
 * or on a phone reset, without downloading Debian and every app again.
 *
 * Each engine has its own system and its own kind of backup. PRoot's is the folder in app
 * storage as it is, PRoot's stand-ins for hard links included; those name absolute paths, so it
 * only goes back to the same place ([header] says which, and restoring checks it before anything
 * is deleted). Chroot's is root's tree under /data/local, read and written as root with owners
 * kept by number. Restoring never runs anything from the backup.
 */
object LinuxBackup {
    /** The first file of a PRoot backup, in the system's top folder: what the backup is of. */
    const val MARKER = ".tessera-backup"
    private const val TAR = "/system/bin/tar"
    private const val BLOCK = 512

    /** What a PRoot backup says about itself. */
    internal fun header(rootfs: String, abi: String): String =
        "tessera-backup 1\nengine=proot\npath=$rootfs\nabi=$abi\n"

    /**
     * Why the backup that starts with [first] (its first two tar blocks) can't be put back at
     * [rootfs] on a phone of [abi], or null when it can.
     */
    internal fun refusal(first: ByteArray, rootfs: String, abi: String): String? {
        val name = String(first, 0, minOf(100, first.size), Charsets.ISO_8859_1).substringBefore('\u0000')
        if (first.size < 2 * BLOCK || name != "${rootfs.substringAfterLast('/')}/$MARKER") {
            return "That file isn't a backup of PRoot mode's Linux system. Each engine has its own system and its own backups."
        }
        val size = String(first, 124, 12, Charsets.ISO_8859_1).trim('\u0000', ' ').toIntOrNull(8) ?: 0
        val fields = String(first, BLOCK, size.coerceIn(0, BLOCK), Charsets.UTF_8).lines()
            .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        return when {
            fields["engine"] != "proot" -> "That backup isn't of PRoot mode's Linux system."
            fields["abi"] != abi -> "That backup is from a phone with another kind of processor (${fields["abi"]}); its programs can't run on this one."
            // Another user profile, or another build of the app: the links inside would point nowhere.
            fields["path"] != rootfs -> "That backup was made where Tessera's files were at ${fields["path"]}; here they're at $rootfs, " +
                "and the links inside it name the old place. It can only be restored in the same user profile."
            else -> null
        }
    }

    /**
     * Writes PRoot mode's system at [rootfs] to [out] as a .tar.gz, calling [progress] with the
     * bytes written so far. Nothing may be running in it meanwhile.
     */
    fun backupProot(rootfs: File, abi: String, out: OutputStream, progress: (Long) -> Unit) {
        val parent = requireNotNull(rootfs.parentFile)
        val marker = File(rootfs, MARKER)
        marker.writeText(header(rootfs.path, abi))
        try {
            // The marker first, so restoring can read it before it touches anything; tar then
            // meets it again inside the folder, which does no harm.
            val tar = ProcessBuilder(TAR, "-cf", "-", "-C", parent.path, "${rootfs.name}/$MARKER", rootfs.name).start()
            pump(tar, out, progress, "Backing up")
        } finally {
            marker.delete()
        }
    }

    /**
     * Replaces PRoot mode's system at [rootfs] with the backup in [input]. The backup is checked
     * first ([refusal]); only then is the system there deleted. On a failure midway what's left
     * is deleted too, as half a system starts nothing: Setup then offers a fresh install.
     */
    fun restoreProot(rootfs: File, abi: String, input: InputStream, progress: (Long) -> Unit) {
        val parent = requireNotNull(rootfs.parentFile)
        val counted = Counted(input, progress)
        GZIPInputStream(counted.buffered(1 shl 16)).use { tarStream ->
            val first = ByteArray(2 * BLOCK)
            var n = 0
            while (n < first.size) {
                val r = tarStream.read(first, n, first.size - n)
                if (r < 0) break
                n += r
            }
            refusal(first.copyOf(n), rootfs.path, abi)?.let { throw IOException(it) }
            remove(rootfs)
            val tar = ProcessBuilder(TAR, "-xf", "-", "-C", parent.path).redirectErrorStream(true).start()
            val errors = StringBuilder()
            val drain = Thread { runCatching { tar.inputStream.bufferedReader().forEachLine { if (errors.length < 4000) errors.appendLine(it) } } }.apply { start() }
            try {
                tar.outputStream.use { to ->
                    to.write(first, 0, n)
                    tarStream.copyTo(to, 1 shl 16)
                }
                val code = tar.waitFor()
                drain.join()
                if (code != 0 || !File(rootfs, ContainerPaths.INSTALLED_MARKER).isFile) {
                    throw IOException("Unpacking the backup failed: ${errors.lines().lastOrNull { it.isNotBlank() } ?: "tar ended with $code"}")
                }
                File(rootfs, MARKER).delete()
            } catch (e: Exception) {
                tar.destroy()
                remove(rootfs)
                throw if (e is IOException) e else IOException(e.message ?: "Restoring failed", e)
            }
        }
    }

    /**
     * Writes chroot mode's system at [root] to [out] as a .tar.gz, read as root through [su],
     * with owners by number: Android and Debian give the same names different numbers.
     */
    fun backupChroot(su: String, root: String, out: OutputStream, progress: (Long) -> Unit) {
        val script = "cd ${shellQuote(root)} && [ -e usr/bin/env ] && exec $TAR --numeric-owner -cf - ."
        pump(ProcessBuilder(su, "-c", script).start(), out, progress, "Backing up")
    }

    /**
     * Puts the chroot backup [archive] (a .tar.gz the app copied into its cache, where root can
     * read it) at [root], as root. Like installing, it never replaces a system that's there:
     * it's unpacked beside, checked, and only then moved into place.
     */
    fun restoreChrootScript(root: String, archive: String): String {
        val r = shellQuote(root)
        val env = "\$T/usr/bin/env"
        val marker = "\$T/${ContainerPaths.INSTALLED_MARKER}"
        return "set -e; R=$r; T=\"\$R.partial\"; " +
            "if [ -e \"\$R/usr/bin/env\" ]; then echo 'A Linux system is already there. Remove it first (Setup), then restore.'; exit 4; fi; " +
            "rm -rf \"\$T\"; mkdir -p \"\$T\"; " +
            "$TAR --numeric-owner -xpzf ${shellQuote(archive)} -C \"\$T\" || { rm -rf \"\$T\"; echo \"The backup couldn't be unpacked.\"; exit 5; }; " +
            "if [ ! -e \"$env\" ] || [ ! -e \"$marker\" ]; then rm -rf \"\$T\"; " +
            "echo \"That file isn't a backup of chroot mode's Linux system. Each engine has its own.\"; exit 6; fi; " +
            "if [ -e \"\$R\" ]; then mv \"\$R\" \"\$R.old-\$(date +%s)\"; fi; mv \"\$T\" \"\$R\"; echo Restored"
    }

    /** Runs [restoreChrootScript] through [su]; null when the system is in place, else why not. */
    fun restoreChroot(su: String, root: String, archive: File): String? = runCatching {
        val p = ProcessBuilder(su, "-c", restoreChrootScript(root, archive.path)).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readLines()
        if (p.waitFor() == 0) null else output.lastOrNull { it.isNotBlank() } ?: "Root access was refused."
    }.getOrElse { it.message ?: "Couldn't start the restore." }

    /** [tar]'s output gzipped into [out]; throws with tar's own last words when it fails. */
    private fun pump(tar: Process, out: OutputStream, progress: (Long) -> Unit, what: String) {
        val errors = StringBuilder()
        val drain = Thread { runCatching { tar.errorStream.bufferedReader().forEachLine { if (errors.length < 4000) errors.appendLine(it) } } }.apply { start() }
        try {
            var read = 0L
            // Quick rather than small: a system is gigabytes, and most of it is already compressed.
            val gzip = object : GZIPOutputStream(out, 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }
            gzip.use { to ->
                val buf = ByteArray(1 shl 16)
                tar.inputStream.use { from ->
                    while (true) {
                        val r = from.read(buf)
                        if (r < 0) break
                        to.write(buf, 0, r)
                        read += r
                        progress(read)
                    }
                }
            }
            val code = tar.waitFor()
            drain.join()
            // Two empty blocks end an archive: fewer bytes than that is no archive at all.
            if (code != 0 || read < 2 * BLOCK) {
                throw IOException("$what failed: ${errors.lines().lastOrNull { it.isNotBlank() } ?: "tar ended with $code"}")
            }
        } catch (e: Exception) {
            tar.destroy()
            throw e
        }
    }

    /** Deletes [dir] without following the links in it: a link to phone storage must not take the storage with it. */
    private fun remove(dir: File) {
        if (!dir.exists()) return
        val code = ProcessBuilder("/system/bin/rm", "-rf", "--", dir.path).redirectErrorStream(true).start().let { p ->
            p.inputStream.readBytes()
            p.waitFor()
        }
        if (code != 0 || dir.exists()) throw IOException("Couldn't clear ${dir.path}")
    }

    /** Reports how much of [input] has been read. */
    private class Counted(private val input: InputStream, private val progress: (Long) -> Unit) : InputStream() {
        private var count = 0L
        override fun read(): Int = input.read().also { if (it >= 0) progress(++count) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            input.read(b, off, len).also { if (it > 0) { count += it; progress(count) } }
        override fun close() = input.close()
    }
}
