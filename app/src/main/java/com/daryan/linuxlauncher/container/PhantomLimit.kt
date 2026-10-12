// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.concurrent.TimeUnit

/**
 * Android 12+ kills apps whose child processes number more than 32 ("phantom processes"),
 * which a Linux system with a desktop app easily reaches. Android 14 added a global setting to
 * turn the monitor off; 12 and 13 only have the activity_manager device_config limit.
 */
object PhantomLimit {
    private const val SETTING = "settings_enable_monitor_phantom_procs"
    private const val UNLIMITED = 2147483647

    /** What lifts it, run once from adb (no root) or through su. */
    val COMMANDS: List<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= 34) add("settings put global $SETTING false")
            add("/system/bin/device_config set_sync_disabled_for_tests persistent")
            add("/system/bin/device_config put activity_manager max_phantom_processes $UNLIMITED")
        }

    /** Whether apps may run as many processes as they like; null when that can't be told without root. */
    fun lifted(context: Context, su: String?): Boolean? {
        if (Build.VERSION.SDK_INT < 31) return true
        if (Build.VERSION.SDK_INT >= 34 &&
            Settings.Global.getString(context.contentResolver, SETTING) == "false"
        ) return true
        su ?: return if (Build.VERSION.SDK_INT >= 34) false else null
        val limit = runSu(su, "/system/bin/device_config get activity_manager max_phantom_processes")
            ?.trim()?.toLongOrNull() ?: return null
        return limit >= 1000
    }

    /** Lifts the limit through [su]; true when it worked. */
    fun lift(su: String): Boolean = runSu(su, COMMANDS.joinToString("; ")) != null

    /** [command]'s output, or null when it failed or su was refused. */
    private fun runSu(su: String, command: String): String? = runCatching {
        val p = ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroy()
            null
        } else out.takeIf { p.exitValue() == 0 }
    }.getOrNull()
}
