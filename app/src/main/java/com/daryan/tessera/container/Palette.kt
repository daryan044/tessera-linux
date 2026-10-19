// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The colors Linux apps are themed with: Android's Material color scheme, named the way the
 * toolkits' themes name their colors (libadwaita's, which adw-gtk3 shares). Colors are 0xRRGGBB.
 */
data class Palette(
    val dark: Boolean,
    /** Buttons and selections that stand out, and text on them. */
    val accent: Int,
    val onAccent: Int,
    /** The window's background and text. */
    val window: Int,
    val onWindow: Int,
    /** Content areas: lists, text views, grids. */
    val view: Int,
    /** Header bars and toolbars. */
    val headerbar: Int,
    val sidebar: Int,
    val card: Int,
    /** Popovers, menus and dialogs. */
    val popover: Int,
    val border: Int,
    val error: Int,
    val onError: Int,
) {
    companion object {
        /** The palette for [mode] with the phone's current colors (the wallpaper's with [dynamic]). */
        fun current(context: Context, mode: ColorMode, dynamic: Boolean): Palette {
            val dark = when (mode) {
                ColorMode.DARK -> true
                ColorMode.LIGHT -> false
                ColorMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            val scheme = when {
                dynamic && Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> FALLBACK_DARK
                else -> FALLBACK_LIGHT
            }
            return from(scheme, dark)
        }

        fun from(s: ColorScheme, dark: Boolean) = Palette(
            dark = dark,
            accent = s.primary.rgb(),
            onAccent = s.onPrimary.rgb(),
            window = s.surface.rgb(),
            onWindow = s.onSurface.rgb(),
            // Content sits a step apart from the window: lighter in light mode, darker in dark.
            view = s.surfaceContainerLowest.rgb(),
            headerbar = s.surfaceContainer.rgb(),
            sidebar = s.surfaceContainerLow.rgb(),
            card = (if (dark) s.surfaceContainerHigh else s.surfaceContainerLowest).rgb(),
            popover = s.surfaceContainerHigh.rgb(),
            border = s.outlineVariant.rgb(),
            error = s.error.rgb(),
            onError = s.onError.rgb(),
        )

        /** An imported Linux theme's colors as a palette (its light or dark from its background). */
        fun from(theme: LinuxTheme) = theme.colors.let { c ->
            Palette(
                dark = theme.dark,
                accent = c.accent,
                onAccent = c.onAccent,
                window = c.background,
                onWindow = c.foreground,
                view = c.view,
                headerbar = c.headerbar,
                sidebar = c.background,
                card = c.view,
                popover = c.background,
                border = GtkThemeReader.mix(c.foreground, c.background, 0.8),
                error = 0xE01B24,
                onError = 0xFFFFFF,
            )
        }

        /** Whether Android is in dark mode now, or [mode] says which. */
        fun isDark(context: Context, mode: ColorMode) = when (mode) {
            ColorMode.DARK -> true
            ColorMode.LIGHT -> false
            ColorMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }

        private fun Color.rgb() = toArgb() and 0xFFFFFF

        // Android < 12 has no wallpaper colors: the launcher's own fallback blue.
        private val FALLBACK_DARK = darkColorScheme(
            primary = Color(0xFFA8C7FA),
            onPrimary = Color(0xFF062E6F),
            surface = Color(0xFF111318),
            onSurface = Color(0xFFE2E2E9),
            surfaceContainerLowest = Color(0xFF0C0E13),
            surfaceContainerLow = Color(0xFF191C20),
            surfaceContainer = Color(0xFF1D2024),
            surfaceContainerHigh = Color(0xFF282A2F),
            outlineVariant = Color(0xFF44474E),
        )
        private val FALLBACK_LIGHT = lightColorScheme(
            primary = Color(0xFF0B57D0),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFFF9F9FF),
            onSurface = Color(0xFF191C20),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF3F3FA),
            surfaceContainer = Color(0xFFEDEDF4),
            surfaceContainerHigh = Color(0xFFE7E8EE),
            outlineVariant = Color(0xFFC4C6D0),
        )
    }
}
