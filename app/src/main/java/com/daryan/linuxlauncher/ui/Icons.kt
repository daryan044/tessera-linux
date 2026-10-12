@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.daryan.linuxlauncher.container.PermissionManifest

/** Outline icons drawn in the Material Symbols style; icons-core has no terminal/globe/etc. */
object LauncherIcons {
    val Terminal = outline("Terminal") {
        roundRect(3f, 4.5f, 21f, 19.5f, 2.5f)
        moveTo(7f, 9.5f); lineTo(10f, 12f); lineTo(7f, 14.5f)
        moveTo(12.5f, 15f); lineTo(17f, 15f)
    }
    val Apps = outline("Apps") {
        roundRect(4f, 4f, 10.5f, 10.5f, 2f)
        roundRect(13.5f, 4f, 20f, 10.5f, 2f)
        roundRect(4f, 13.5f, 10.5f, 20f, 2f)
        roundRect(13.5f, 13.5f, 20f, 20f, 2f)
    }
    val Monitor = outline("Monitor") {
        moveTo(4f, 20f); lineTo(20f, 20f)
        moveTo(7f, 16f); lineTo(7f, 11f)
        moveTo(12f, 16f); lineTo(12f, 5f)
        moveTo(17f, 16f); lineTo(17f, 8f)
    }
    val Code = outline("Code") {
        moveTo(8.5f, 7f); lineTo(3.5f, 12f); lineTo(8.5f, 17f)
        moveTo(15.5f, 7f); lineTo(20.5f, 12f); lineTo(15.5f, 17f)
        moveTo(13.5f, 5f); lineTo(10.5f, 19f)
    }
    val Globe = outline("Globe") {
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, true, true, 11.99f, 3f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f)
        curveTo(9.5f, 6f, 8.5f, 9f, 8.5f, 12f)
        curveTo(8.5f, 15f, 9.5f, 18f, 12f, 21f)
        moveTo(12f, 3f)
        curveTo(14.5f, 6f, 15.5f, 9f, 15.5f, 12f)
        curveTo(15.5f, 15f, 14.5f, 18f, 12f, 21f)
    }
    val Store = outline("Store") {
        moveTo(5f, 8f); lineTo(19f, 8f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(9f, 8f); lineTo(9f, 6.5f)
        curveTo(9f, 4.8f, 10.3f, 3.5f, 12f, 3.5f)
        curveTo(13.7f, 3.5f, 15f, 4.8f, 15f, 6.5f); lineTo(15f, 8f)
    }
    val Folder = outline("Folder") {
        moveTo(3.5f, 7f)
        curveTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f)
        lineTo(9.5f, 5f); lineTo(11.5f, 7f); lineTo(18.5f, 7f)
        curveTo(19.6f, 7f, 20.5f, 7.9f, 20.5f, 9f)
        lineTo(20.5f, 17f)
        curveTo(20.5f, 18.1f, 19.6f, 19f, 18.5f, 19f)
        lineTo(5.5f, 19f)
        curveTo(4.4f, 19f, 3.5f, 18.1f, 3.5f, 17f)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t); lineTo(r - rad, t)
        arcTo(rad, rad, 0f, false, true, r, t + rad); lineTo(r, b - rad)
        arcTo(rad, rad, 0f, false, true, r - rad, b); lineTo(l + rad, b)
        arcTo(rad, rad, 0f, false, true, l, b - rad); lineTo(l, t + rad)
        arcTo(rad, rad, 0f, false, true, l + rad, t); close()
    }

    private fun outline(name: String, block: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()
}

fun appIcon(key: String): ImageVector = when (key) {
    "monitor" -> LauncherIcons.Monitor
    "code" -> LauncherIcons.Code
    "globe" -> LauncherIcons.Globe
    "palette" -> Icons.Rounded.Create
    "folder" -> LauncherIcons.Folder
    else -> LauncherIcons.Terminal
}

private val AppShapes = listOf(
    MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.SoftBurst,
    MaterialShapes.Cookie12Sided, MaterialShapes.Sunny, MaterialShapes.Gem,
    MaterialShapes.Slanted, MaterialShapes.Arch, MaterialShapes.Pill,
)

/** Each app keeps the same expressive shape and tone everywhere it appears. */
@Composable
fun appShape(app: PermissionManifest): Shape =
    AppShapes[Math.floorMod(app.id.hashCode(), AppShapes.size)].toShape()

@Composable
fun appColors(app: PermissionManifest): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (Math.floorMod(app.id.hashCode() / 7, 3)) {
        0 -> c.primaryContainer to c.onPrimaryContainer
        1 -> c.secondaryContainer to c.onSecondaryContainer
        else -> c.tertiaryContainer to c.onTertiaryContainer
    }
}
