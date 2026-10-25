// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException

sealed interface InstallState {
    val running: Boolean get() = false

    data object Idle : InstallState
    data object Resolving : InstallState { override val running = true }
    data class Downloading(val done: Long, val total: Long) : InstallState { override val running = true }
    data object Extracting : InstallState { override val running = true }
    data object Configuring : InstallState { override val running = true }
    data object Done : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * Installs a distro's rootfs into app storage for PRoot mode:
 * download (digest-checked) -> extract through PRoot -> [RootfsSetup] -> marker file.
 */
class RootfsInstaller(
    private val paths: ContainerPaths,
    private val registry: OciRegistry,
    private val scope: CoroutineScope,
    private val uid: Int,
    private val gid: Int,
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state
    private var job: Job? = null

    /** Installs [distro] for PRoot mode, or with [su] for chroot mode (root's own location). */
    fun start(distro: Distro, su: String? = null) {
        if (_state.value.running) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = try {
                if (su == null) install(distro) else installChroot(distro, su)
                InstallState.Done
            } catch (e: CancellationException) {
                InstallState.Idle
            } catch (e: Exception) {
                InstallState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** The distro's image, downloaded and digest-checked; kept across failed attempts. */
    private suspend fun fetch(distro: Distro): Pair<Layer, File> {
        _state.value = InstallState.Resolving
        val layer = registry.resolve(distro, Platform.forAbi(Build.SUPPORTED_ABIS.first()))
        val tarball = File(paths.cacheDir, "rootfs-${layer.digest.substringAfter(':').take(16)}.tar.gz")
        if (tarball.length() != layer.size) {
            _state.value = InstallState.Downloading(0, layer.size)
            registry.download(distro, layer, tarball) { _state.value = InstallState.Downloading(it, layer.size) }
        }
        return layer to tarball
    }

    /**
     * Installs [distro] where chroot mode expects it, as root: unpacked with real ownership
     * into a scratch directory, configured, then moved into place. An existing system there is
     * never replaced.
     */
    private suspend fun installChroot(distro: Distro, su: String) {
        val (layer, tarball) = fetch(distro)
        _state.value = InstallState.Extracting
        val script = File(paths.cacheDir, "chroot-install.sh")
        script.writeText(ChrootSetup.script(paths.chrootRootfs.path, tarball.path, "${distro.name} ${layer.digest}"))
        runInterruptible {
            val proc = ProcessBuilder(su, "-c", "/system/bin/sh ${script.path}").redirectErrorStream(true).start()
            try {
                val output = proc.inputStream.bufferedReader().readLines()
                if (proc.waitFor() != 0) throw IOException(output.lastOrNull { it.isNotBlank() } ?: "chroot install failed")
            } finally {
                proc.destroy()
            }
        }
        script.delete()
        tarball.delete()
    }

    private suspend fun install(distro: Distro) {
        val (layer, tarball) = fetch(distro)

        // Extract straight into place: link2symlink records absolute paths, so the tree can't be
        // moved afterwards. The marker file written last is what makes it count as installed.
        _state.value = InstallState.Extracting
        val root = paths.prootRootfs
        runProot("--link2symlink", "-0", "/system/bin/tar", "-xzf", tarball.path, "-C", root.path) {
            root.deleteRecursively()
            root.mkdirs()
        }

        _state.value = InstallState.Configuring
        RootfsSetup.configure(root, uid, gid)
        FakeProc.write(paths.fakeProcDir)
        File(root, ContainerPaths.INSTALLED_MARKER).writeText("${distro.name} ${layer.digest}\n")
        tarball.delete()
    }

    /** Runs PRoot, after [prepare]; once more without seccomp if the kernel has PRoot's bug. */
    private suspend fun runProot(vararg args: String, prepare: () -> Unit) {
        prepare()
        var (code, output) = exec(args)
        var first: List<String>? = null
        // The marker makes every later run, the apps' included, go without seccomp too.
        if (code != 0 && seccompBug(output) && paths.prootNoSeccompMarker.createNewFile()) {
            first = output
            prepare()
            exec(args).let { code = it.first; output = it.second }
        }
        if (code != 0) {
            val withSeccomp = first?.let { " (with seccomp: ${prootErrors(it)})" }.orEmpty()
            throw IOException("extract failed ($code): ${prootErrors(output)}$withSeccomp; ${fileChecks()}")
        }
    }

    /**
     * How the files PRoot touches on its first exec look from this app's own process, which runs
     * under the same rules as PRoot's. PRoot reports EPERM both when the kernel refuses the exec
     * and when its own lstat() of the program or its ELF interpreter fails, whatever the reason.
     */
    private fun fileChecks() = listOf(
        "/system/bin/tar", "/system/bin/linker64", paths.prootLoader.path,
    ).joinToString(", ", "checks: ") { path ->
        val real = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val lstat = errno { Os.lstat(real) }
        val exec = errno { Os.access(real, OsConstants.X_OK) }
        "$real lstat $lstat exec $exec"
    }

    private fun errno(call: () -> Unit) = try {
        call()
        "ok"
    } catch (e: ErrnoException) {
        OsConstants.errnoName(e.errno) ?: "errno ${e.errno}"
    }

    private suspend fun exec(args: Array<out String>) = runInterruptible {
        val proc = ProcessBuilder(listOf(paths.proot.path) + args)
            .redirectErrorStream(true)
            .apply { environment().putAll(paths.prootEnv()) }
            .start()
        try {
            val output = proc.inputStream.bufferedReader().readLines()
            proc.waitFor() to output
        } finally {
            proc.destroy()
        }
    }
}

/**
 * PRoot's answer to the first execve failing with EPERM, before seccomp was turned off. It blames
 * an old kernel bug, but that is a guess: it says this for any EPERM.
 */
internal fun seccompBug(output: List<String>) = output.any { "PROOT_NO_SECCOMP" in it }

/**
 * What PRoot said went wrong, for an error message: its "error:" and "warning:" lines name the
 * cause (the failing call and the system's reason); the advice after them is the same every time.
 */
internal fun prootErrors(output: List<String>): String {
    val errors = output.filter { (" error: " in it || " warning: " in it) && !it.startsWith("fatal error") }.distinct()
    return (errors.ifEmpty { output.takeLast(3) }).joinToString(" / ")
}

/**
 * The root-side install for chroot mode, as one shell script: what [RootfsSetup] does for
 * PRoot, but with real root (so no APT sandbox tweak is needed beyond Android's network rules)
 * and in a directory the app can't write itself.
 */
object ChrootSetup {
    fun script(root: String, tarball: String, marker: String): String = """
        set -e
        R='$root'
        T="${'$'}R.partial"
        if [ -e "${'$'}R/usr/bin/env" ]; then echo "A Linux system is already at ${'$'}R"; exit 0; fi
        # Something unusable in the way: keep it aside rather than delete it.
        [ -e "${'$'}R" ] && mv "${'$'}R" "${'$'}R.old-${'$'}(date +%s)"
        rm -rf "${'$'}T"; mkdir -p "${'$'}T"
        /system/bin/tar -xzf '$tarball' -C "${'$'}T"
        rm -f "${'$'}T/etc/resolv.conf" "${'$'}T/etc/hosts"
        printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "${'$'}T/etc/resolv.conf"
        printf '127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n' > "${'$'}T/etc/hosts"
        # apt downloads as _apt, which isn't in Android's network groups.
        [ -d "${'$'}T/etc/apt/apt.conf.d" ] && echo 'APT::Sandbox::User "root";' > "${'$'}T/etc/apt/apt.conf.d/99launcher"
        for f in "${'$'}T"/etc/apt/sources.list.d/*.sources; do
          [ -f "${'$'}f" ] && sed -i 's/^Components:.*/Components: main contrib non-free non-free-firmware/' "${'$'}f"
        done
        # Groups Android gives apps, named so id and bash stay quiet.
        for g in aid_inet:3003 aid_net_raw:3004 aid_everybody:9997; do
          grep -q "^${'$'}{g%%:*}:" "${'$'}T/etc/group" || echo "${'$'}{g%%:*}:x:${'$'}{g#*:}:" >> "${'$'}T/etc/group"
        done
        mkdir -p "${'$'}T/home/user" "${'$'}T/root"
        echo '$marker' > "${'$'}T/${ContainerPaths.INSTALLED_MARKER}"
        mv "${'$'}T" "${'$'}R"
        echo "Installed at ${'$'}R"
    """.trimIndent()
}

/** First-boot configuration of a freshly extracted rootfs. Plain file edits, no guest code runs. */
object RootfsSetup {
    fun configure(root: File, uid: Int, gid: Int) {
        val etc = File(root, "etc").apply { mkdirs() }

        // Android has no /etc/resolv.conf, and image ones may be symlinks into /run.
        replace(File(etc, "resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        replace(File(etc, "hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")

        // apt drops privileges to _apt for downloads, which PRoot's fake root can't do.
        File(etc, "apt/apt.conf.d").takeIf { it.isDirectory }?.let {
            File(it, "99launcher").writeText("APT::Sandbox::User \"root\";\n")
        }
        enableAllComponents(etc)

        // Non-root sessions run as the app's real uid; give it a name and a home.
        val shell = if (File(root, "bin/bash").exists()) "/bin/bash" else "/bin/sh"
        appendLineIfMissing(File(etc, "passwd"), "user:", "user:x:$uid:$gid:User:/home/user:$shell")
        appendLineIfMissing(File(etc, "group"), "user:", "user:x:$gid:")
        val appId = uid % 100_000
        // Supplementary groups Android gives every app; named so `id` and bash stay quiet.
        for ((name, id) in listOf(
            "aid_inet" to 3003, "aid_net_raw" to 3004, "aid_everybody" to 9997,
            "aid_${appId}_cache" to 20_000 + appId, "aid_${appId}_all" to 50_000 + appId,
        )) {
            appendLineIfMissing(File(etc, "group"), "$name:", "$name:x:$id:user")
        }
        File(root, "home/user").mkdirs()
        File(root, "root").mkdirs()
    }

    /**
     * Container images ship Debian with only `main` enabled; desktop users expect contrib and
     * non-free too (fonts, codecs, firmware, unrar, ...). Rewrites deb822 Components lines.
     */
    fun enableAllComponents(etc: File) {
        val sources = File(etc, "apt/sources.list.d").listFiles { f -> f.name.endsWith(".sources") }.orEmpty()
        for (f in sources) {
            val lines = f.readLines()
            val fixed = lines.map { line ->
                if (!line.startsWith("Components:")) return@map line
                val have = line.removePrefix("Components:").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                "Components: " + (have + DEBIAN_COMPONENTS.filterNot { it in have }).joinToString(" ")
            }
            if (fixed != lines) f.writeText(fixed.joinToString("\n", postfix = "\n"))
        }
    }

    private val DEBIAN_COMPONENTS = listOf("main", "contrib", "non-free", "non-free-firmware")

    private fun replace(f: File, content: String) {
        f.delete() // drops a dangling symlink rather than writing through it
        f.writeText(content)
    }

    private fun appendLineIfMissing(f: File, prefix: String, line: String) {
        val existing = if (f.exists()) f.readLines() else emptyList()
        if (existing.none { it.startsWith(prefix) }) {
            f.writeText((existing + line).joinToString("\n", postfix = "\n"))
        }
    }
}

/**
 * Stand-ins for /proc files Android's SELinux policy hides from apps. Without them htop, top,
 * uptime and friends fail. Bound over the real paths by [ProotBackend].
 */
object FakeProc {
    val NAMES = listOf("stat", "loadavg", "uptime", "version", "vmstat", "filesystems", "cpuinfo", "sys/kernel/cap_last_cap")

    /** A core without SVE (Cortex-A78), for [withoutSve]. */
    const val NO_SVE_PART = "0xd41"

    /**
     * [cpuinfo] with every core named a Cortex-A78, unless the kernel lists SVE. LLVM, and so
     * llvmpipe's shader JIT, picks instructions by core model rather than by what the kernel
     * enables: Cortex-X3 and A715 have SVE, Android kernels leave it off, and OpenGL apps
     * drawing in software died of SIGILL. enter.sh and flatpak.sh do the same in chroot mode.
     */
    fun withoutSve(cpuinfo: String): String {
        val sve = cpuinfo.lineSequence().any { it.startsWith("Features") && "sve" in it.substringAfter(':').split(' ', '\t') }
        if (sve) return cpuinfo
        return cpuinfo.replace(Regex("^(CPU part\\s*:\\s*).*$", RegexOption.MULTILINE)) { it.groupValues[1] + NO_SVE_PART }
    }

    /**
     * Once: drops the shaders llvmpipe compiled in [rootfs] before [withoutSve], which Mesa's
     * disk cache doesn't key by core. enter.sh does the same in chroot mode.
     */
    fun clearSveShaders(rootfs: File) {
        for (cache in listOf(File(rootfs, "root/.cache"), File(rootfs, "home/user/.cache"))) {
            val marker = File(cache, ".tessera-no-sve")
            if (!cache.isDirectory || marker.exists()) continue
            File(cache, "mesa_shader_cache").deleteRecursively()
            File(cache, "mesa_shader_cache_db").deleteRecursively()
            marker.createNewFile()
        }
    }

    fun write(
        dir: File,
        cpus: Int = Runtime.getRuntime().availableProcessors(),
        kernel: String = System.getProperty("os.version") ?: "6.1.0",
        cpuinfo: String = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault(""),
    ) {
        fun put(name: String, text: String) = File(dir, name).apply { parentFile?.mkdirs() }.writeText(text)
        put("cpuinfo", withoutSve(cpuinfo))
        put("stat", buildString {
            append("cpu  ${cpus * 1000} 0 ${cpus * 500} ${cpus * 90000} 0 0 0 0 0 0\n")
            repeat(cpus) { append("cpu$it 1000 0 500 90000 0 0 0 0 0 0\n") }
            append("intr 0\nctxt 0\nbtime ${System.currentTimeMillis() / 1000}\n")
            append("processes 0\nprocs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
        })
        put("loadavg", "0.12 0.07 0.02 2/165 765\n")
        put("uptime", "1000.00 900.00\n")
        put("version", "Linux version $kernel (launcher@android) #1 SMP PREEMPT\n")
        put("vmstat", listOf(
            "nr_free_pages 100000", "nr_inactive_anon 0", "nr_active_anon 0", "nr_inactive_file 0",
            "nr_active_file 0", "nr_dirty 0", "nr_writeback 0", "pgpgin 0", "pgpgout 0",
            "pswpin 0", "pswpout 0", "pgfault 0", "pgmajfault 0",
        ).joinToString("\n", postfix = "\n"))
        put("filesystems", listOf(
            "nodev\tsysfs", "nodev\ttmpfs", "nodev\tproc", "nodev\tdevpts", "\text4", "\tf2fs", "\tvfat",
        ).joinToString("\n", postfix = "\n"))
        put("sys/kernel/cap_last_cap", "40\n")
    }
}
