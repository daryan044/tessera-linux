// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File

/**
 * A GUI session's theme, written by the launcher into the session's X11 directory (the guest's
 * /tmp/.X11-unix in both engines, so no su is needed) and followed live by [THEME_DAEMON]:
 * - a GTK theme per palette, `Tessera-<generation>`: adw-gtk3 from the theme pack ([ThemePack])
 *   recolored with the [Palette] for GTK 3, the same colors as libadwaita's named colors for
 *   GTK 4. Each change of palette gets a new name, since GTK only reloads a theme whose name
 *   changes;
 * - xsettingsd's settings: that theme's name, icons, window buttons;
 * - `portal`: light or dark and the accent color, which the daemon serves as the desktop's
 *   settings portal (org.freedesktop.appearance), read by libadwaita, Qt, Firefox and Chromium.
 *   Written last: the daemon picks a change up from it.
 */
object ThemeFiles {
    /** The directory, as the guest sees it; on the host, see [ContainerPaths.sessionTheme]. */
    const val GUEST_DIR = "/tmp/.X11-unix/tessera-theme"
    const val PORTAL_FILE = "portal"
    const val XSETTINGS_FILE = "xsettingsd.conf"
    private const val THEME_PREFIX = "Tessera-"

    /** What a themed session's environment gets on top of the usual. */
    val ENV = mapOf(
        "TESSERA_THEME" to GUEST_DIR,
        // Where GTK looks for themes by name; Debian's own dirs after ours.
        "XDG_DATA_DIRS" to "$GUEST_DIR/share:/usr/local/share:/usr/share",
    )

    /** The files for [palette], by path under the directory. [pack]: the theme pack is installed. */
    fun files(palette: Palette, generation: Int, pack: Boolean): Map<String, String> {
        val theme = "$THEME_PREFIX$generation"
        return linkedMapOf(
            "share/themes/$theme/index.theme" to "[Desktop Entry]\nType=X-GNOME-Metatheme\nName=$theme\n",
            "share/themes/$theme/gtk-3.0/gtk.css" to gtk3(palette, pack),
            "share/themes/$theme/gtk-4.0/gtk.css" to gtk4(palette),
            XSETTINGS_FILE to xsettings(theme),
            PORTAL_FILE to portal(palette),
        )
    }

    /**
     * Writes [palette]'s files into [dir] and drops themes older than the one before (a window
     * may still be switching away from that). Returns the generation written.
     */
    fun write(dir: File, palette: Palette, pack: Boolean): Int {
        dir.mkdirs()
        val themes = File(dir, "share/themes")
        val existing = themes.list().orEmpty().mapNotNull { it.removePrefix(THEME_PREFIX).toIntOrNull() }
        val generation = (existing.maxOrNull() ?: 0) + 1
        files(palette, generation, pack).forEach { (path, text) -> writeWhole(File(dir, path), text) }
        existing.filter { it < generation - 1 }.forEach { File(themes, "$THEME_PREFIX$it").deleteRecursively() }
        return generation
    }

    /**
     * Writes the session's settings for an imported Linux theme ([LinuxThemes]): the theme itself,
     * copied in (GTK looks for it by name in the directory's share/themes), named in xsettingsd's
     * settings, and light or dark with its accent for the portal, from [palette] (its colors).
     */
    fun writeImported(dir: File, theme: LinuxTheme, folder: File, palette: Palette) {
        dir.mkdirs()
        val target = File(dir, "share/themes/${theme.id}")
        if (!target.isDirectory) folder.copyRecursively(target, overwrite = true)
        writeWhole(File(dir, XSETTINGS_FILE), xsettings(theme.id))
        writeWhole(File(dir, PORTAL_FILE), portal(palette))
    }

    /** Whole files only: the daemon or GTK may read at any moment. */
    private fun writeWhole(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun gtk3(p: Palette, pack: Boolean): String = buildString {
        val base = when {
            pack -> "file://${ThemePack.GUEST_DIR}/share/themes/adw-gtk3${if (p.dark) "-dark" else ""}/gtk-3.0/gtk.css"
            // Without the pack, GTK's own Adwaita; it draws mostly with fixed colors, so only
            // light or dark really changes.
            else -> "resource:///org/gtk/libgtk/theme/Adwaita/gtk-contained${if (p.dark) "-dark" else ""}.css"
        }
        appendLine("@import url(\"$base\");")
        namedColors(p).forEach { (name, value) -> appendLine("@define-color $name $value;") }
        // GTK 3's own names, which older apps and Adwaita use.
        appendLine("@define-color theme_bg_color ${hex(p.window)};")
        appendLine("@define-color theme_fg_color ${hex(p.onWindow)};")
        appendLine("@define-color theme_base_color ${hex(p.view)};")
        appendLine("@define-color theme_text_color ${hex(p.onWindow)};")
        appendLine("@define-color theme_selected_bg_color ${hex(p.accent)};")
        appendLine("@define-color theme_selected_fg_color ${hex(p.onAccent)};")
    }

    /** libadwaita's named colors: as GTK 4.16+ CSS variables, and the older @define-color. */
    private fun gtk4(p: Palette): String = buildString {
        val colors = namedColors(p)
        colors.forEach { (name, value) -> appendLine("@define-color $name $value;") }
        appendLine(":root {")
        colors.forEach { (name, value) -> appendLine("  --${name.replace('_', '-')}: $value;") }
        appendLine("}")
    }

    private fun namedColors(p: Palette): List<Pair<String, String>> = listOf(
        "accent_color" to hex(p.accent),
        "accent_bg_color" to hex(p.accent),
        "accent_fg_color" to hex(p.onAccent),
        "window_bg_color" to hex(p.window),
        "window_fg_color" to hex(p.onWindow),
        "view_bg_color" to hex(p.view),
        "view_fg_color" to hex(p.onWindow),
        "headerbar_bg_color" to hex(p.headerbar),
        "headerbar_fg_color" to hex(p.onWindow),
        "headerbar_backdrop_color" to hex(p.headerbar),
        "headerbar_border_color" to hex(p.border),
        "sidebar_bg_color" to hex(p.sidebar),
        "sidebar_fg_color" to hex(p.onWindow),
        "sidebar_backdrop_color" to hex(p.sidebar),
        "sidebar_border_color" to hex(p.border),
        "card_bg_color" to hex(p.card),
        "card_fg_color" to hex(p.onWindow),
        "dialog_bg_color" to hex(p.popover),
        "dialog_fg_color" to hex(p.onWindow),
        "popover_bg_color" to hex(p.popover),
        "popover_fg_color" to hex(p.onWindow),
        "destructive_bg_color" to hex(p.error),
        "destructive_fg_color" to hex(p.onError),
        "error_bg_color" to hex(p.error),
        "error_fg_color" to hex(p.onError),
    )

    private fun xsettings(theme: String) = """
        Net/ThemeName "$theme"
        Net/IconThemeName "Adwaita"
        Gtk/CursorThemeName "${ThemePack.CURSOR}"
        Gtk/CursorThemeSize ${ThemePack.CURSOR_SIZE}
        Net/EnableEventSounds 0
        Gtk/DecorationLayout "menu:"
    """.trimIndent() + "\n"

    /** color-scheme as the portal has it: 1 prefers dark, 2 prefers light; the accent as 0..1 RGB. */
    private fun portal(p: Palette): String {
        val rgb = listOf(16, 8, 0).joinToString(",") { "%.4f".format(java.util.Locale.ROOT, ((p.accent shr it) and 0xFF) / 255.0) }
        return "color-scheme=${if (p.dark) 1 else 2}\naccent=$rgb\n"
    }

    private fun hex(rgb: Int) = "#%06x".format(rgb and 0xFFFFFF)
}

/**
 * The theme pack (scripts/theme/build-theme.sh): adw-gtk3 and the Bibata Modern Ice cursor,
 * which Debian doesn't package.
 */
object ThemePack {
    const val GUEST_DIR = "/opt/tessera-theme"
    val bundle = OptBundle(GUEST_DIR, "theme/tessera-theme.tgz", "theme/VERSION", "ThemePack")

    /** A white pointer with rounded corners, in X screen pixels. */
    const val CURSOR = "Bibata-Modern-Ice"
    const val CURSOR_SIZE = 24

    /**
     * What gives every windowed app the pack's pointer, themed or not: libX11 swaps the window
     * manager's and apps' standard cursors for the theme's. Without the pack (an older build)
     * the theme isn't found and X's own cursors stay.
     */
    val CURSOR_ENV = mapOf(
        "XCURSOR_THEME" to CURSOR,
        // X's own cursors too (what the window manager puts on the screen, and what every
        // window that sets none shows); libXcursor leaves them alone unless asked.
        "XCURSOR_THEME_CORE" to "1",
        "XCURSOR_SIZE" to "$CURSOR_SIZE",
        // The usual places after ours.
        "XCURSOR_PATH" to "$GUEST_DIR/share/icons:~/.local/share/icons:~/.icons:/usr/share/icons:/usr/share/pixmaps",
    )
}
