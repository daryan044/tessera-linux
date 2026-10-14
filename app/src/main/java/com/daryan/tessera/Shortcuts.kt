// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import com.daryan.tessera.container.FsGrant
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

const val SHORTCUTS_TAB = "Shortcuts"
private const val ID_PREFIX = "shortcut-"

/** A command the user put on the launcher as an icon of its own. */
@Serializable
data class Shortcut(
    val id: String,
    val name: String,
    /** Run by bash as a login shell, so it sees the user's PATH and profile. */
    val command: String,
    /** In a window of its own (with a display) rather than a terminal. */
    val window: Boolean = false,
    val root: Boolean = false,
    /** Key into the launcher's icon set. */
    val icon: String = "terminal",
    /** Distro packages the command needs; offered for install like an app's. */
    val packages: List<String> = emptyList(),
    /** Shown under the name; the command's first line when empty. */
    val description: String = "",
) {
    /**
     * [tesseraDir]: where Tessera keeps its notes and the logs of sessions that just ended. Only
     * the Claude Code shortcut is shown them, read-only, so it can diagnose what the user sees.
     */
    fun toManifest(tesseraDir: File? = null) = PermissionManifest(
        id = ID_PREFIX + id,
        name = name,
        description = description.ifEmpty { command.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty() },
        category = SHORTCUTS_TAB,
        icon = icon,
        exec = listOf("/bin/bash", "-lc", command),
        gui = window,
        packages = packages,
        permissions = Permissions(
            network = true,
            display = window,
            audio = window,
            root = root,
            filesystem = listOfNotNull(
                tesseraDir?.takeIf { id == CLAUDE_CODE.id }?.let { FsGrant(it.path, TESSERA_DIR, readOnly = true) },
            ),
        ),
    )

    companion object {
        /** Where [com.daryan.tessera.container.Diagnostics] appears inside the container. */
        const val TESSERA_DIR = "/home/user/.tessera"

        fun isShortcut(app: PermissionManifest) = app.id.startsWith(ID_PREFIX)

        fun idOf(app: PermissionManifest) = app.id.removePrefix(ID_PREFIX)

        /** A new shortcut's id from its name, unique among [taken]. */
        fun newId(name: String, taken: Collection<String>): String {
            val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "command" }
            return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
        }

        /**
         * Claude Code in a terminal. The first run installs it with Anthropic's installer
         * (into ~/.local/bin, no root needed), later runs start it straight away.
         *
         * Before it starts, Tessera's notes about itself are added to ~/CLAUDE.md, which Claude
         * Code reads from the directory it runs in: how the container is built, what it cannot
         * see from inside, the problems that recur, and that a bug report is the user's to post.
         * Appended once, by a marker, so an edited CLAUDE.md is never overwritten.
         */
        val CLAUDE_CODE = Shortcut(
            id = "claude-code",
            name = "Claude Code",
            command = """
                export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
                if [ -f ~/.tessera/TESSERA.md ] && ! grep -q TESSERA-CONTEXT ~/CLAUDE.md 2>/dev/null; then
                  cat ~/.tessera/TESSERA.md >> ~/CLAUDE.md
                fi
                if ! command -v claude >/dev/null 2>&1; then
                  echo "Installing Claude Code (first run only)..."
                  curl -fsSL https://claude.ai/install.sh | bash || { echo "Install failed; see above."; exec bash -l; }
                fi
                cd ~ && exec claude
            """.trimIndent(),
            icon = "spark",
            packages = listOf("curl", "ca-certificates", "git"),
            description = "Anthropic's coding agent, in a terminal. Knows how Tessera works, and reads what just failed.",
        )
    }
}

/** The user's shortcuts, in [file]; starts with Claude Code. */
class ShortcutStore(private val file: File) {
    private val _shortcuts = MutableStateFlow(load())
    val shortcuts: StateFlow<List<Shortcut>> = _shortcuts

    /** Adds [shortcut], or replaces the one with its id. */
    fun save(shortcut: Shortcut) = edit { list ->
        if (list.any { it.id == shortcut.id }) list.map { if (it.id == shortcut.id) shortcut else it } else list + shortcut
    }

    fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    private fun edit(f: (List<Shortcut>) -> List<Shortcut>) {
        _shortcuts.update(f)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(SERIALIZER, _shortcuts.value))
        }
    }

    private fun load(): List<Shortcut> =
        if (!file.exists()) listOf(Shortcut.CLAUDE_CODE)
        else runCatching { json.decodeFromString(SERIALIZER, file.readText()) }.getOrDefault(emptyList()).map(::refreshed)

    /**
     * Tessera's own Claude Code shortcut gains abilities between versions (latest: the notes that
     * tell it how Tessera works). A stored copy that still runs the installer but lacks them is
     * replaced by the current one, keeping the user's icon and window choices. A command rewritten
     * past recognition is left alone — as is anything the user wrote themselves.
     */
    private fun refreshed(s: Shortcut): Shortcut =
        if (s.id == Shortcut.CLAUDE_CODE.id && "claude.ai/install.sh" in s.command && "TESSERA-CONTEXT" !in s.command) {
            Shortcut.CLAUDE_CODE.copy(window = s.window, root = s.root, icon = s.icon)
        } else {
            s
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val SERIALIZER = ListSerializer(Shortcut.serializer())
    }
}
