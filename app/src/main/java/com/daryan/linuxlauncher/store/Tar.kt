// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.store

import java.io.EOFException
import java.io.File
import java.io.InputStream

/** Just enough of ustar to unpack an icon pack: regular files, flattened into one directory. */
object Tar {
    /** Extracts regular files from [input] into [dest] by base name. Returns how many. */
    fun extractFlat(input: InputStream, dest: File, accept: (String) -> Boolean = { true }): Int {
        dest.mkdirs()
        val header = ByteArray(BLOCK)
        var count = 0
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break // end-of-archive marker
            val name = field(header, 345, 155).let { prefix ->
                val base = field(header, 0, 100)
                if (prefix.isEmpty()) base else "$prefix/$base"
            }
            val size = field(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val padded = (size + BLOCK - 1) / BLOCK * BLOCK

            val fileName = name.substringAfterLast('/')
            val wanted = (type == '0' || type == '\u0000') && fileName.isNotEmpty() && accept(fileName) &&
                // Names come from the archive; never let one point outside dest.
                !fileName.startsWith(".") && '\\' !in fileName
            if (wanted) {
                File(dest, fileName).outputStream().use { out -> copyExactly(input, out, size) }
                skipExactly(input, padded - size)
                count++
            } else {
                skipExactly(input, padded)
            }
        }
        return count
    }

    private fun field(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) return if (n == 0) false else throw EOFException("truncated tar header")
            n += r
        }
        return true
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException("truncated tar entry")
            out.write(buf, 0, r)
            left -= r
        }
    }

    private fun skipExactly(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException("truncated tar entry")
            left -= r
        }
    }

    private const val BLOCK = 512
}
