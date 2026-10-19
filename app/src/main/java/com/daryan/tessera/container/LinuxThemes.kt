// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import com.daryan.tessera.store.Tar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** A GTK theme's main colors, as 0xRRGGBB; the ones Tessera's own interface is drawn with. */
@Serializable
data class ThemeColors(
    val background: Int,
    val foreground: Int,
    /** Content areas (lists, text views). */
    val view: Int,
    val accent: Int,
    val onAccent: Int,
    val headerbar: Int,
)

/** A Linux theme the user imported: one GTK theme folder, with what Tessera read from it. */
@Serializable
data class LinuxTheme(
    /** Its folder name, which is also the name GTK knows it by (e.g. Orchis-Dark). */
    val id: String,
    /** Its light and dark variants share this (Orchis). */
    val family: String,
    val dark: Boolean,
    val colors: ThemeColors,
    /** Its buttons' corner radius in pixels, when the theme sets one. */
    val radius: Int? = null,
    /** It has a GTK 4 stylesheet (most libadwaita apps still ignore it). */
    val gtk4: Boolean = false,
)

/**
 * Linux themes the user imported (Settings → Appearance), kept under [dir]: GTK themes as shared on
 * gnome-look or GitHub, in a .tar.xz, .tar.gz, .tar or .zip. Each theme folder in the archive (one
 * with gtk-3.0/gtk.css) becomes a [LinuxTheme]; variants of one family (Name, Name-Dark) pair up
 * so light and dark mode can each have theirs ([pick]). Linux apps get the theme itself
 * ([ThemeFiles]); Tessera's own interface is colored and shaped from what was read from it.
 */
class LinuxThemes(private val dir: File) {
    private val _themes = MutableStateFlow(load())
    val themes: StateFlow<List<LinuxTheme>> = _themes

    /** Where [theme]'s files are. */
    fun folder(theme: LinuxTheme) = File(dir, theme.id)

    /** [family]'s variant for light or [dark] mode: the matching one, else whichever there is. */
    fun pick(family: String, dark: Boolean): LinuxTheme? {
        val variants = _themes.value.filter { it.family == family }
        return variants.firstOrNull { it.dark == dark } ?: variants.firstOrNull()
    }

    /**
     * Imports the themes in an archive. Returns what was imported; throws when the file isn't an
     * archive Tessera reads or holds no GTK theme. Blocks; call off the main thread.
     */
    fun import(input: InputStream): List<LinuxTheme> {
        val work = File(dir, ".import").apply { deleteRecursively(); mkdirs() }
        try {
            unpack(BufferedInputStream(input), work)
            val found = work.walkTopDown().maxDepth(6)
                .filter { it.isDirectory && File(it, "gtk-3.0/gtk.css").isFile }
                .toList()
            if (found.isEmpty()) throw IOException("No GTK theme in that file (a folder with gtk-3.0/gtk.css)")
            val imported = found.mapNotNull { folder ->
                val theme = GtkThemeReader.read(folder) ?: return@mapNotNull null
                val target = File(dir, theme.id)
                target.deleteRecursively()
                if (!folder.renameTo(target)) {
                    folder.copyRecursively(target, overwrite = true)
                }
                File(target, META).writeText(json.encodeToString(LinuxTheme.serializer(), theme))
                theme
            }
            if (imported.isEmpty()) throw IOException("Couldn't read the theme's colors")
            _themes.value = load()
            return imported
        } finally {
            work.deleteRecursively()
        }
    }

    /** Removes every variant of [family]. */
    fun delete(family: String) {
        _themes.value.filter { it.family == family }.forEach { File(dir, it.id).deleteRecursively() }
        _themes.value = load()
    }

    private fun load(): List<LinuxTheme> =
        dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString(LinuxTheme.serializer(), File(f, META).readText()) }.getOrNull()
        }.sortedWith(compareBy({ it.family.lowercase() }, { it.dark }))

    /** By its first bytes: xz, gzip, zip, or a plain tar. */
    private fun unpack(input: BufferedInputStream, into: File) {
        input.mark(8)
        val magic = ByteArray(6).also { input.read(it) }
        input.reset()
        val symlink = { target: String, link: File ->
            // Only links within the theme (assets shared between variants).
            if (!target.startsWith("/") && ".." !in target.split('/')) {
                runCatching { android.system.Os.symlink(target, link.path) }
            }
        }
        when {
            magic[0] == 0xFD.toByte() && magic[1] == '7'.code.toByte() && magic[2] == 'z'.code.toByte() ->
                Tar.extract(org.tukaani.xz.XZInputStream(input), into, symlink)
            magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> Tar.extract(GZIPInputStream(input), into, symlink)
            magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() -> unzip(input, into)
            else -> Tar.extract(input, into, symlink)
        }
    }

    private fun unzip(input: InputStream, into: File) {
        val root = into.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(root, entry.name)
                if (".." in entry.name.split('/') || !target.canonicalPath.startsWith(root.path + File.separator)) continue
                if (entry.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    private companion object {
        const val META = ".tessera-theme.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Reads what Tessera needs from a GTK theme folder: its colors (the named colors GTK 3 themes
 * define for apps, libadwaita's or the older theme_* ones), whether it's dark, and its buttons'
 * corner radius. The stylesheet's local @imports are followed.
 */
object GtkThemeReader {
    private val VARIANT = Regex("[-_ ](dark|light|darker|lighter)$", RegexOption.IGNORE_CASE)

    fun read(folder: File): LinuxTheme? {
        val css = stylesheet(File(folder, "gtk-3.0/gtk.css"), depth = 0)
        val colors = colors(css) ?: return null
        val id = folder.name.replace(Regex("[^A-Za-z0-9._+-]"), "_").take(64)
        return LinuxTheme(
            id = id,
            family = VARIANT.replace(id, "").ifEmpty { id },
            dark = luminance(colors.background) < 0.4,
            colors = colors,
            radius = radius(css),
            gtk4 = File(folder, "gtk-4.0/gtk.css").isFile,
        )
    }

    /** The stylesheet with its local @imports inlined (two levels; resources and URLs aren't). */
    internal fun stylesheet(file: File, depth: Int): String {
        val text = runCatching { file.readText() }.getOrDefault("")
        if (depth >= 2) return text
        return IMPORT.replace(text) { m ->
            val path = m.groupValues[1]
            if (path.contains(':')) "" else stylesheet(File(file.parentFile, path), depth + 1)
        }
    }

    private val IMPORT = Regex("""@import\s+url\(\s*["']?([^"')]+)["']?\s*\)\s*;""")
    private val DEFINE = Regex("""@define-color\s+([\w-]+)\s+([^;]+);""")

    /** The theme's main colors, or null when it defines none Tessera recognizes. */
    internal fun colors(css: String): ThemeColors? {
        val defined = DEFINE.findAll(css).associate { it.groupValues[1] to it.groupValues[2].trim() }
        val resolved = HashMap<String, Int?>()
        fun resolve(name: String, seen: Set<String> = emptySet()): Int? {
            if (name in seen) return null
            resolved[name]?.let { return it }
            val value = defined[name] ?: return null
            return parse(value) { ref -> resolve(ref, seen + name) }.also { resolved[name] = it }
        }
        fun first(vararg names: String) = names.firstNotNullOfOrNull { resolve(it) }
        val background = first("window_bg_color", "theme_bg_color", "bg_color") ?: return null
        val foreground = first("window_fg_color", "theme_fg_color", "fg_color") ?: return null
        val accent = first("accent_bg_color", "theme_selected_bg_color", "selected_bg_color", "accent_color") ?: return null
        return ThemeColors(
            background = background,
            foreground = foreground,
            view = first("view_bg_color", "theme_base_color", "base_color") ?: background,
            accent = accent,
            onAccent = first("accent_fg_color", "theme_selected_fg_color", "selected_fg_color")
                ?: if (luminance(accent) > 0.5) 0x000000 else 0xFFFFFF,
            headerbar = first("headerbar_bg_color") ?: background,
        )
    }

    /** A CSS color: #rgb, #rrggbb, rgb(), rgba(), @name, or shade()/alpha()/mix() of those. */
    internal fun parse(value: String, ref: (String) -> Int?): Int? {
        val v = value.trim()
        return when {
            v.startsWith("@") -> ref(v.removePrefix("@"))
            v.startsWith("#") -> v.removePrefix("#").let { hex ->
                when (hex.length) {
                    3 -> hex.map { "$it$it" }.joinToString("").toIntOrNull(16)
                    6, 8 -> hex.take(6).toIntOrNull(16)
                    else -> null
                }
            }
            v.startsWith("rgb") -> {
                val parts = v.substringAfter('(').substringBefore(')').split(',').take(3)
                    .map { it.trim().removeSuffix("%").toDoubleOrNull() ?: return null }
                if (parts.size < 3) return null
                val (r, g, b) = parts.map { it.toInt().coerceIn(0, 255) }
                (r shl 16) or (g shl 8) or b
            }
            v.startsWith("alpha(") -> parse(args(v).getOrNull(0) ?: return null, ref)
            v.startsWith("shade(") -> {
                val a = args(v)
                val base = parse(a.getOrNull(0) ?: return null, ref) ?: return null
                val f = a.getOrNull(1)?.toDoubleOrNull() ?: return base
                scale(base, f)
            }
            v.startsWith("mix(") -> {
                val a = args(v)
                val x = parse(a.getOrNull(0) ?: return null, ref) ?: return null
                val y = parse(a.getOrNull(1) ?: return null, ref) ?: return null
                mix(x, y, a.getOrNull(2)?.toDoubleOrNull() ?: 0.5)
            }
            else -> NAMED[v.lowercase()]
        }
    }

    /** The top-level arguments of "fn(a, b(c, d), e)". */
    private fun args(call: String): List<String> {
        val inner = call.substringAfter('(').substringBeforeLast(')')
        val out = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in inner) {
            when {
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { depth--; cur.append(c) }
                c == ',' && depth == 0 -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    /** The buttons' corner radius, the most common one the stylesheet gives them. */
    internal fun radius(css: String): Int? {
        val buttons = Regex("""button[^{}]*\{[^}]*?border-radius:\s*([\d.]+)px""").findAll(css)
            .mapNotNull { it.groupValues[1].toDoubleOrNull()?.toInt() }.filter { it in 0..64 }.toList()
        val all = buttons.ifEmpty {
            Regex("""border-radius:\s*([\d.]+)px""").findAll(css).mapNotNull { it.groupValues[1].toDoubleOrNull()?.toInt() }
                .filter { it in 1..64 }.toList()
        }
        return all.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    fun luminance(rgb: Int): Double {
        fun ch(shift: Int) = ((rgb shr shift) and 0xFF) / 255.0
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        fun m(shift: Int) = (ch(a, shift) * (1 - t) + ch(b, shift) * t).toInt().coerceIn(0, 255)
        return (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    private fun scale(c: Int, f: Double): Int {
        fun s(shift: Int) = (((c shr shift) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (s(16) shl 16) or (s(8) shl 8) or s(0)
    }

    private val NAMED = mapOf("white" to 0xFFFFFF, "black" to 0x000000, "transparent" to 0x000000)
}
