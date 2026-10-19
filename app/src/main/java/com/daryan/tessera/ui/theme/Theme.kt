// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.daryan.tessera.LauncherApplication
import com.daryan.tessera.container.GtkThemeReader
import com.daryan.tessera.container.LinuxTheme
import com.daryan.tessera.container.Palette
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Fallback for Android < 12, where there is no wallpaper-derived palette.
private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFF8CD5B2),
    tertiaryContainer = Color(0xFF00513A),
    onTertiaryContainer = Color(0xFFA7F2CD),
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

private val LauncherShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // An imported Linux theme, when one is chosen, colors and shapes Tessera too.
    val container = (context.applicationContext as? LauncherApplication)?.container
    val appearance = container?.appearance?.global?.collectAsState()?.value
    val themes = container?.linuxThemes?.themes?.collectAsState()?.value
    val systemDark = isSystemInDarkTheme()
    val imported = remember(appearance, themes, systemDark) {
        appearance?.takeIf { it.enabled }?.linuxTheme?.let { family ->
            container.linuxThemes.pick(family, Palette.isDark(context, appearance.colorMode))
        }
    }
    val dark = imported?.dark ?: systemDark
    val colors = when {
        imported != null -> schemeFrom(imported)
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> expressiveLightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = imported?.radius?.let(::shapesFrom) ?: LauncherShapes,
        content = content,
    )
}

/**
 * A Material color scheme from a Linux theme: its accent for the primary colors, its window
 * background and text for the surfaces, and steps between those for the containers (lighter in
 * a dark theme, darker in a light one, as mixing toward the text color gives both).
 */
fun schemeFrom(theme: LinuxTheme): ColorScheme {
    val c = theme.colors
    fun color(rgb: Int) = Color(0xFF000000.toInt() or rgb)
    fun toText(t: Double) = color(GtkThemeReader.mix(c.background, c.foreground, t))
    fun accentOver(t: Double) = color(GtkThemeReader.mix(c.accent, c.background, t))
    val base = if (theme.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = color(c.accent),
        onPrimary = color(c.onAccent),
        primaryContainer = accentOver(0.55),
        onPrimaryContainer = color(c.foreground),
        inversePrimary = accentOver(0.35),
        secondary = color(GtkThemeReader.mix(c.accent, c.foreground, 0.3)),
        onSecondary = color(c.onAccent),
        secondaryContainer = accentOver(0.75),
        onSecondaryContainer = color(c.foreground),
        tertiary = color(c.accent),
        onTertiary = color(c.onAccent),
        tertiaryContainer = accentOver(0.65),
        onTertiaryContainer = color(c.foreground),
        background = color(c.background),
        onBackground = color(c.foreground),
        surface = color(c.background),
        onSurface = color(c.foreground),
        surfaceVariant = toText(0.10),
        onSurfaceVariant = toText(0.70),
        surfaceTint = color(c.accent),
        inverseSurface = color(c.foreground),
        inverseOnSurface = color(c.background),
        outline = toText(0.45),
        outlineVariant = toText(0.18),
        surfaceBright = toText(0.12),
        surfaceDim = color(c.background),
        surfaceContainerLowest = color(c.view),
        surfaceContainerLow = toText(0.04),
        surfaceContainer = toText(0.07),
        surfaceContainerHigh = toText(0.10),
        surfaceContainerHighest = toText(0.14),
    )
}

/**
 * Tessera's shapes from a Linux theme's button corner radius: its own size for the smallest
 * shapes, growing for larger ones the way Tessera's usual ones do. A square theme (0) gives square
 * corners throughout.
 */
fun shapesFrom(radius: Int): Shapes {
    fun r(f: Float) = RoundedCornerShape((radius * f).coerceAtMost(48f).dp)
    return Shapes(extraSmall = r(1f), small = r(1.5f), medium = r(2.5f), large = r(3.5f), extraLarge = r(4.5f))
}
