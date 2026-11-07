// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.widgets

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the stats widgets show. No CPU: Android has kept apps from reading the whole phone's CPU
 * use since 8.0, so there's nothing honest to put there.
 */
data class WidgetStats(
    val memoryUsed: Long,
    val memoryTotal: Long,
    val storageFree: Long,
    val storageTotal: Long,
    val running: Int,
) {
    /** How full memory is, 0 to 1. */
    val memoryFraction get() = fraction(memoryUsed, memoryTotal)

    /** How full storage is, 0 to 1. */
    val storageFraction get() = fraction(storageTotal - storageFree, storageTotal)

    /** The same as whole percentages, for a ring and the number inside it. */
    val memoryPercent get() = (memoryFraction * 100).roundToInt()
    val storagePercent get() = (storageFraction * 100).roundToInt()

    val memoryText get() = "${size(memoryUsed)} of ${size(memoryTotal)} used"
    val storageText get() = "${size(storageFree)} free of ${size(storageTotal)}"

    /** What fits under a ring. */
    val memoryShort get() = "${size(memoryUsed)} used"
    val storageShort get() = "${size(storageFree)} free"

    /** What fits beside the widget's title. */
    val runningShort get() = "$running running"
    val runningText get() = when (running) {
        0 -> "No Linux apps running"
        1 -> "1 Linux app running"
        else -> "$running Linux apps running"
    }

    companion object {
        private fun fraction(part: Long, whole: Long) = if (whole <= 0) 0f else (part.toFloat() / whole).coerceIn(0f, 1f)

        /** [bytes] the way the phone's own settings count them, in GB or MB. */
        fun size(bytes: Long): String {
            val gb = bytes / 1_000_000_000.0
            return when {
                gb >= 100 -> String.format(Locale.ROOT, "%.0f GB", gb)
                gb >= 1 -> String.format(Locale.ROOT, "%.1f GB", gb)
                else -> String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000.0)
            }
        }

        fun read(context: Context, running: Int): WidgetStats {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            val storage = StatFs(Environment.getDataDirectory().path)
            return WidgetStats(
                memoryUsed = memory.totalMem - memory.availMem,
                memoryTotal = memory.totalMem,
                storageFree = storage.availableBytes,
                storageTotal = storage.totalBytes,
                running = running,
            )
        }
    }
}
