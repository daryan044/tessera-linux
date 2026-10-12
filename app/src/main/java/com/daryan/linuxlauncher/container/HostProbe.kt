// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import android.content.Context
import android.os.Build
import java.io.File

data class HostStatus(
    val prootBinary: Boolean,
    val prootRootfs: Boolean,
    /** null = not checked yet (needs a root shell). */
    val chrootRootfs: Boolean?,
    val su: String?,
    /** Android 12+ kills apps with more than 32 child processes unless the user disables it. */
    val phantomProcessLimit: Boolean,
) {
    val prootReady get() = prootBinary && prootRootfs
    val chrootReady get() = su != null && chrootRootfs != false
}

class HostProbe(private val paths: ContainerPaths) {

    fun probe(): HostStatus {
        val su = SU_PATHS.firstOrNull { File(it).exists() }
        return HostStatus(
            prootBinary = paths.proot.isFile,
            prootRootfs = File(paths.prootRootfs, ContainerPaths.INSTALLED_MARKER).exists(),
            // Lives under /data/local, which the app uid cannot stat; checked later through su.
            chrootRootfs = null,
            su = su,
            phantomProcessLimit = Build.VERSION.SDK_INT >= 31,
        )
    }

    companion object {
        val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
        )

        fun pathsFor(context: Context, distro: String = "debian") = ContainerPaths(
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
            filesDir = context.filesDir,
            cacheDir = context.cacheDir,
            prootRootfs = File(context.filesDir, "rootfs/$distro"),
            chrootRootfs = File("/data/local/linux/$distro"),
        )

        /** Copies the root-side launch script out of the APK so `su` can run it. */
        fun installChrootScript(context: Context, paths: ContainerPaths) {
            val target = paths.chrootScript
            target.parentFile?.mkdirs()
            context.assets.open("chroot/enter.sh").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
