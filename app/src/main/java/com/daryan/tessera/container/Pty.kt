// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.IOException

/** JNI bindings for app/src/main/cpp/pty.c. */
object Pty {
    init {
        System.loadLibrary("launcher")
    }

    /** Starts [spec] on a new pty. Returns the master fd; the pid is written to [pidOut][0]. */
    @Throws(IOException::class)
    fun spawn(spec: LaunchSpec, rows: Int, cols: Int, pidOut: IntArray): Int = spawn(
        spec.executable,
        spec.argv.toTypedArray(),
        spec.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
        spec.cwd,
        rows,
        cols,
        pidOut,
    )

    @JvmStatic
    private external fun spawn(
        path: String, argv: Array<String>, env: Array<String>, cwd: String?,
        rows: Int, cols: Int, pidOut: IntArray,
    ): Int

    @JvmStatic external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    /** Blocks until exit. Returns the exit code, or -signal when killed. */
    @JvmStatic external fun waitFor(pid: Int): Int

    @JvmStatic external fun close(fd: Int)

    /**
     * fcntl(2) with an int argument: its result, or -errno. In place of Os.fcntlInt, which is a
     * hidden API that Android 10 and older refuse to apps targeting API 27 or newer.
     */
    @JvmStatic external fun fcntlInt(fd: Int, cmd: Int, arg: Int): Int
}
