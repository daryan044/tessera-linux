// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

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
}
