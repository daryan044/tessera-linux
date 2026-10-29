// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

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
    /** Droidspaces' binary, once found through su (Droidspaces mode only); null when not there. */
    val droidspacesBinary: String? = null,
    /** Its containers; null = not checked yet (needs a root shell). */
    val droidspacesContainers: List<DroidspacesContainer>? = null,
    /** The container the user picked, whether or not it's still there. */
    val droidspacesName: String? = null,
) {
    val prootReady get() = prootBinary && prootRootfs
    val chrootReady get() = su != null && chrootRootfs != false

    /** The picked Droidspaces container, as found by the last check. */
    val droidspacesSelected: DroidspacesContainer? get() = droidspacesContainers?.firstOrNull { it.name == droidspacesName }
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

    /**
     * Whether a Linux system is in place for chroot mode. Asks through [su], since the app can't
     * look under /data/local itself; null when su refused or failed.
     */
    fun chrootInstalled(su: String): Boolean? = runCatching {
        val p = ProcessBuilder(su, "-c", "test -e '${paths.chrootRootfs.path}/usr/bin/env' && echo yes || echo no")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return@runCatching null }
        when (out.lines().lastOrNull()) { "yes" -> true; "no" -> false; else -> null }
    }.getOrNull()

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

        /**
         * Copies the script a Droidspaces container runs for each app out of the APK, where the
         * container gets it bound in ([Droidspaces.bridgeBinds]); and makes the folder it needs.
         */
        fun installDroidspacesScript(context: Context, paths: ContainerPaths) {
            paths.sessionsDir.mkdirs()
            paths.droidspacesScript.parentFile?.mkdirs()
            context.assets.open("droidspaces/enter.sh").use { input ->
                paths.droidspacesScript.outputStream().use { input.copyTo(it) }
            }
            context.assets.open("droidspaces/flatpak.sh").use { input ->
                File(paths.droidspacesDir, "flatpak.sh").outputStream().use { input.copyTo(it) }
            }
        }

        /** Copies the root-side launch script out of the APK so `su` can run it. */
        fun installChrootScript(context: Context, paths: ContainerPaths) {
            val target = paths.chrootScript
            target.parentFile?.mkdirs()
            context.assets.open("chroot/enter.sh").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            context.assets.open("chroot/flatpak.sh").use { input ->
                paths.chrootFlatpakScript.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
