// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

/** One `src:dest[:ro]` entry of a container's `bind_mounts`. */
data class DroidspacesBind(val src: String, val dest: String, val readOnly: Boolean = false) {
    fun render() = "$src:$dest" + if (readOnly) ":ro" else ""
}

/**
 * A Droidspaces container's saved settings (its `container.config`, a plain key=value file), as
 * far as Tessera needs them: where the files are, what is already shared into it, and what of
 * the phone it can see. Everything else in the file is left alone, see [withBinds].
 */
data class DroidspacesConfig(
    val name: String,
    /** `rootfs_path`: a directory, or an image file or block device ([isImage]). */
    val rootfs: String? = null,
    val binds: List<DroidspacesBind> = emptyList(),
    /** `enable_android_storage`: the phone's /storage/emulated/0 is inside the container. */
    val androidStorage: Boolean = false,
    /** `enable_hw_access`: the host's /dev nodes are inside the container. */
    val hwAccess: Boolean = false,
    /** `enable_gpu_mode`: the GPU's device nodes are inside the container. */
    val gpu: Boolean = false,
    val netMode: String = "nat",
) {
    /** Image rootfs aren't readable from outside while the container is stopped. */
    val isImage: Boolean get() = rootfs != null && (rootfs.endsWith(".img", ignoreCase = true) || rootfs.startsWith("/dev/"))

    /** Whether every one of [needed] is bound (same place both sides) in this config. */
    fun hasBinds(needed: List<DroidspacesBind>): Boolean =
        needed.all { n -> binds.any { it.src == n.src && it.dest == n.dest && it.readOnly == n.readOnly } }

    /** The GPU's device node is among what the container has. */
    val hasGpuDevice: Boolean get() = gpu || hwAccess

    companion object {
        /** [text] is the file's content; [name] is the folder it came from (what `--name` takes). */
        fun parse(name: String, text: String): DroidspacesConfig {
            val values = HashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
            fun flag(key: String) = values[key] == "1"
            return DroidspacesConfig(
                name = name,
                rootfs = values["rootfs_path"]?.takeIf { it.isNotEmpty() },
                binds = parseBinds(values["bind_mounts"].orEmpty()),
                androidStorage = flag("enable_android_storage"),
                hwAccess = flag("enable_hw_access"),
                gpu = flag("enable_gpu_mode"),
                netMode = values["net_mode"]?.takeIf { it.isNotEmpty() } ?: "nat",
            )
        }

        /** `a:b,c:d:ro` the way Droidspaces writes it; entries it would reject are skipped. */
        fun parseBinds(value: String): List<DroidspacesBind> = value.split(',').mapNotNull { token ->
            val parts = token.trim().split(':')
            if (parts.size !in 2..3) return@mapNotNull null
            val src = parts[0].trim()
            val dest = parts[1].trim()
            if (src.isEmpty() || !dest.startsWith("/")) return@mapNotNull null
            DroidspacesBind(src, dest, readOnly = parts.getOrNull(2)?.trim() == "ro")
        }

        /**
         * [text] with [add] among its `bind_mounts`, and every other line untouched, in place. An
         * entry already there for the same destination is replaced (the source may have moved);
         * the rest of the existing entries keep their order. The file gets a trailing newline.
         */
        fun withBinds(text: String, add: List<DroidspacesBind>): String {
            val lines = if (text.isEmpty()) mutableListOf<String>() else text.removeSuffix("\n").split("\n").toMutableList()
            val at = lines.indexOfFirst { it.trim().startsWith("bind_mounts=") }
            val existing = if (at >= 0) parseBinds(lines[at].substringAfter('=')) else emptyList()
            val kept = existing.filterNot { e -> add.any { it.dest == e.dest } }
            val line = "bind_mounts=" + (kept + add).joinToString(",") { it.render() }
            if (at >= 0) lines[at] = line else lines += line
            return lines.joinToString("\n", postfix = "\n")
        }
    }
}

/** What a container can see of the phone, and so what Tessera can share with an app in it. */
object DroidspacesView {
    private const val STORAGE = "/storage/emulated/0"

    /**
     * Where the container sees [hostPath] (a path as Android has it), or null when it doesn't:
     * the container is its own mount namespace, so only what Droidspaces put in it is there.
     * That is the phone's storage (when the container has it on, at the same path) and whatever
     * the container's own `bind_mounts` bring in.
     */
    fun visibleAt(config: DroidspacesConfig, hostPath: String): String? {
        val path = hostPath.trimEnd('/').ifEmpty { "/" }
        // /sdcard and /storage/self/primary are links to the same place.
        val canonical = when {
            path == "/sdcard" || path.startsWith("/sdcard/") -> STORAGE + path.removePrefix("/sdcard")
            path == "/storage/self/primary" || path.startsWith("/storage/self/primary/") ->
                STORAGE + path.removePrefix("/storage/self/primary")
            else -> path
        }
        // A bind of the container's own wins: it names exactly where the folder shows up.
        config.binds.filter { under(canonical, it.src) }.maxByOrNull { it.src.length }?.let { b ->
            return b.dest.trimEnd('/') + canonical.removePrefix(b.src.trimEnd('/'))
        }
        if (config.androidStorage && under(canonical, STORAGE)) return canonical
        return null
    }

    private fun under(path: String, dir: String): Boolean {
        val d = dir.trimEnd('/')
        return path == d || path.startsWith("$d/")
    }
}
