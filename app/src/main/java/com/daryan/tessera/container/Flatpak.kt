// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.serialization.Serializable

/**
 * Where an installed Flathub app's files are, in the container, and how to start it. Flatpak
 * itself installs them (`flatpak install`, as root), but can't run them: `flatpak run` needs
 * bubblewrap, and bubblewrap needs user namespaces, which Android kernels don't have. So Tessera
 * runs them itself, in a root of their own: the runtime at /usr, the app at /app, the runtime's
 * GL extension where the runtime expects it (see the backends' buildFlatpakLaunch).
 */
@Serializable
data class FlatpakRef(
    /** Flathub's app id, e.g. io.mpv.Mpv. */
    val app: String,
    /** The app's files (its /app), as a path in the container. */
    val appDir: String,
    /** The runtime's files (its /usr). */
    val runtimeDir: String,
    /** The runtime's default GL extension (Mesa), when installed, and where it goes under /usr. */
    val glDir: String? = null,
    val glMount: String? = null,
    /** What the app's metadata says to run, from /app/bin. */
    val command: String,
    /** The runtime's and then the app's [Environment] sections. */
    val env: Map<String, String> = emptyMap(),
)

object Flatpak {
    const val ROOT = "/var/lib/flatpak"
    const val REMOTE = "flathub"
    private const val REMOTE_URL = "https://dl.flathub.org/repo/flathub.flatpakrepo"
    private val ID = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+){2,}")

    /** Whether [id] looks like a Flathub app id, safe on a command line. */
    fun validId(id: String) = ID.matches(id) && id.length <= 255

    /**
     * One-time setup, run as root in the container: flatpak itself, the Flathub remote, and the
     * GUI helpers every windowed app's session uses.
     */
    fun setupScript(helpers: List<String>): String =
        "export DEBIAN_FRONTEND=noninteractive; apt-get update && " +
            // ca-certificates: flatpak only recommends it, and without it a fresh system can't
            // reach Flathub over HTTPS ("Problem with the SSL CA cert").
            "apt-get install -y --no-install-recommends flatpak ca-certificates ${helpers.joinToString(" ")} && " +
            "flatpak remote-add --if-not-exists $REMOTE $REMOTE_URL && echo 'Flathub is ready'"

    /**
     * Installs [id] for aarch64. Flatpak's post-install steps (icon caches) need bubblewrap and
     * fail; the app is there all the same, so success is its deployed files, not the exit code.
     * Not --noninteractive: that prints no progress, and [FlatpakProgress] reads the bar it draws.
     * [bwrap] is Tessera's stand-in for bubblewrap (assets/flatpak/bwrap.sh), which apps whose
     * maker ships them separately (VS Code) need to unpack them, and the triggers to run.
     */
    fun installScript(id: String, fsync: Boolean = true, bwrap: String? = null): String {
        require(validId(id)) { "not a Flathub id: $id" }
        return fsyncScript(fsync) + bwrap?.let(::bwrapScript).orEmpty() +
            "flatpak install -y --arch=aarch64 $REMOTE $id; " +
            "test -e $ROOT/app/$id/current/active/metadata"
    }

    /** Where the install's own /tmp gets the bubblewrap stand-in. */
    const val BWRAP = "/tmp/.tessera-bwrap"

    /** Writes [script] to [BWRAP] and tells flatpak to run it in bubblewrap's place. */
    internal fun bwrapScript(script: String): String {
        require("\nTESSERA_BWRAP\n" !in "\n$script\n") { "the stand-in can't contain its heredoc's end" }
        return "cat > $BWRAP <<'TESSERA_BWRAP'\n${script.trimEnd('\n')}\nTESSERA_BWRAP\n" +
            "chmod 755 $BWRAP; export FLATPAK_BWRAP=$BWRAP; "
    }

    /** Removes [id] and any runtime no app uses any more. */
    fun uninstallScript(id: String, fsync: Boolean = true): String {
        require(validId(id)) { "not a Flathub id: $id" }
        return fsyncScript(fsync) + "flatpak uninstall -y --noninteractive $id; flatpak uninstall -y --noninteractive --unused; " +
            "test ! -e $ROOT/app/$id/current"
    }

    /**
     * Sets whether OSTree flushes each file it writes to storage ([core] fsync in the repo's
     * config). Off is much faster, in PRoot above all, where every call goes through ptrace, but a
     * power cut mid-install can leave that install broken, to be installed again.
     */
    internal fun fsyncScript(fsync: Boolean): String =
        "sed -i '/^fsync=/d' $REPO_CONFIG; " +
            (if (fsync) "" else "sed -i 's/^\\[core\\]\$/[core]\\nfsync=false/' $REPO_CONFIG; ")

    /** Whether Flathub is set up in a container, from its repo config. */
    fun isSetUp(repoConfig: String?): Boolean = repoConfig?.contains("[remote \"$REMOTE\"]") == true

    const val REPO_CONFIG = "$ROOT/repo/config"

    /** An app's metadata file, which names its runtime and command. */
    fun appMetadata(id: String) = "$ROOT/app/$id/current/active/metadata"

    /** A runtime's metadata, for "org.freedesktop.Platform/aarch64/25.08". */
    fun runtimeMetadata(runtime: String) = "$ROOT/runtime/$runtime/active/metadata"

    /**
     * [id]'s [FlatpakRef] from its metadata and its runtime's, read by [read] (a path in the
     * container to its text, or null). Null when the app or its runtime isn't installed.
     */
    suspend fun resolve(id: String, read: suspend (String) -> String?): FlatpakRef? {
        val app = Ini.parse(read(appMetadata(id)) ?: return null)
        val section = app["Application"] ?: return null
        val runtime = section["runtime"] ?: return null
        val command = section["command"] ?: return null
        val rt = Ini.parse(read(runtimeMetadata(runtime)) ?: return null)
        val (arch, _) = runtime.split('/').let { it.getOrNull(1) to it.getOrNull(2) }
        // The runtime's GL extension point names the folder; its "default" Mesa build is a
        // runtime of its own, in one of the versions the point accepts.
        val gl = rt.entries.firstOrNull { (name, _) -> name == "Extension org.freedesktop.Platform.GL" }?.value
        val glDir = gl?.let { ext ->
            val versions = (ext["versions"] ?: ext["version"]).orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
            versions.firstNotNullOfOrNull { v ->
                "$ROOT/runtime/org.freedesktop.Platform.GL.default/$arch/$v/active/files".takeIf { read("$it/.ref") != null }
            }
        }
        val glMount = gl?.get("directory")?.let { "/usr/${it.trim('/')}/default" }
        val env = rt["Environment"].orEmpty() + app["Environment"].orEmpty()
        return FlatpakRef(
            app = id,
            appDir = "$ROOT/app/$id/current/active/files",
            runtimeDir = "$ROOT/runtime/$runtime/active/files",
            glDir = glDir?.takeIf { glMount != null },
            glMount = glMount?.takeIf { glDir != null },
            command = command,
            env = env,
        )
    }

    /**
     * The environment a Flatpak app needs on top of the session's: its own folders first, and
     * GL from the runtime's Mesa unless the session uses our GPU driver (whose own settings come
     * from [GpuDriver.env]).
     */
    fun env(ref: FlatpakRef, gpu: Boolean, sessionEnv: Map<String, String>): Map<String, String> = buildMap {
        // What the runtime and app ask for, under what Tessera sets (the cursor, the theme).
        putAll(ref.env.filterKeys { it !in sessionEnv })
        put("PATH", "/app/bin:/usr/bin")
        // ld.so.cache in the runtime doesn't know /app/lib (flatpak run regenerates it).
        val gl = ref.glMount
        put(
            "LD_LIBRARY_PATH",
            listOfNotNull(
                GpuDriver.GUEST_DIR.takeIf { gpu }?.let { "$it/lib" },
                "/app/lib",
                gl?.takeIf { !gpu }?.let { "$it/lib" },
            ).joinToString(":"),
        )
        if (!gpu && gl != null) {
            put("__EGL_VENDOR_LIBRARY_DIRS", "$gl/share/glvnd/egl_vendor.d")
            put("LIBGL_DRIVERS_PATH", "$gl/lib/dri")
            put("VK_ICD_FILENAMES", "")
        }
        // The session's own data dirs (the theme's) ahead of the app's and the runtime's.
        val theme = sessionEnv["XDG_DATA_DIRS"]?.split(':')?.filter { it.startsWith("/tmp/") }.orEmpty()
        val own = (ref.env["XDG_DATA_DIRS"] ?: "/app/share:/usr/share").split(':').filterNot { it.startsWith("/run/host") }
        put("XDG_DATA_DIRS", (theme + own).distinct().joinToString(":"))
        put("FLATPAK_ID", ref.app)
        put("container", "flatpak")
    }
}

/** Flatpak's metadata format: INI sections of key=value lines. */
internal object Ini {
    fun parse(text: String): Map<String, Map<String, String>> {
        val out = linkedMapOf<String, MutableMap<String, String>>()
        var section: MutableMap<String, String>? = null
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { line ->
            if (line.startsWith('[') && line.endsWith(']')) {
                section = out.getOrPut(line.substring(1, line.length - 1)) { linkedMapOf() }
            } else {
                val eq = line.indexOf('=')
                if (eq > 0) section?.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
            }
        }
        return out
    }
}
