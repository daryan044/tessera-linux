@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

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
import com.daryan.tessera.container.PermissionManifest

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
    /** Rays from a centre, like a spark; Claude Code's shortcut. */
    val Spark = outline("Spark") {
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0 + 22.5)
            val inner = 2.5f
            val outer = if (i % 2 == 0) 9f else 6.5f
            moveTo(12f + inner * Math.cos(a).toFloat(), 12f + inner * Math.sin(a).toFloat())
            lineTo(12f + outer * Math.cos(a).toFloat(), 12f + outer * Math.sin(a).toFloat())
        }
    }
    val History = outline("History") {
        // A clock face whose rim turns into a back arrow at the left.
        moveTo(4.5f, 12f)
        arcTo(8f, 8f, 0f, true, true, 6.8f, 17.6f)
        moveTo(2.5f, 9.5f); lineTo(4.5f, 12f); lineTo(7f, 10f)
        moveTo(12.5f, 8f); lineTo(12.5f, 12.5f); lineTo(15.5f, 14.5f)
    }
    val Pin = outline("Pin") {
        moveTo(9f, 4f); lineTo(15f, 4f)
        moveTo(10f, 4f); lineTo(10f, 10f); lineTo(7f, 14f); lineTo(17f, 14f); lineTo(14f, 10f); lineTo(14f, 4f)
        moveTo(12f, 14f); lineTo(12f, 20.5f)
    }
    val Monitor = outline("Monitor") {
        moveTo(4f, 20f); lineTo(20f, 20f)
        moveTo(7f, 16f); lineTo(7f, 11f)
        moveTo(12f, 16f); lineTo(12f, 5f)
        moveTo(17f, 16f); lineTo(17f, 8f)
    }
    /** A painter's palette: a round board with a thumb notch and paint dots; appearance. */
    val Palette = outline("Palette") {
        moveTo(12f, 3.5f)
        curveTo(7.3f, 3.5f, 3.5f, 7.3f, 3.5f, 12f)
        curveTo(3.5f, 16.7f, 7.3f, 20.5f, 12f, 20.5f)
        curveTo(13.1f, 20.5f, 13.8f, 19.7f, 13.8f, 18.7f)
        curveTo(13.8f, 17.4f, 12.6f, 16.9f, 12.6f, 15.8f)
        curveTo(12.6f, 14.8f, 13.4f, 14.2f, 14.4f, 14.2f)
        lineTo(16.5f, 14.2f)
        curveTo(18.7f, 14.2f, 20.5f, 12.4f, 20.5f, 10.2f)
        curveTo(20.5f, 6.4f, 16.7f, 3.5f, 12f, 3.5f)
        close()
        moveTo(7.8f, 11.2f); lineTo(7.8f, 11.3f)
        moveTo(10.3f, 7.6f); lineTo(10.3f, 7.7f)
        moveTo(14.7f, 7.6f); lineTo(14.7f, 7.7f)
    }
    /** A small frame and an arrow out of it to the far corner; scale. */
    val Scale = outline("Scale") {
        roundRect(3.5f, 13.5f, 10.5f, 20.5f, 1.5f)
        moveTo(13f, 11f); lineTo(20f, 4f)
        moveTo(14.5f, 4f); lineTo(20f, 4f); lineTo(20f, 9.5f)
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
    val Keyboard = outline("Keyboard") {
        roundRect(2.5f, 6f, 21.5f, 18f, 2.5f)
        moveTo(6f, 9.5f); lineTo(6.01f, 9.5f)
        moveTo(9.5f, 9.5f); lineTo(9.51f, 9.5f)
        moveTo(13f, 9.5f); lineTo(13.01f, 9.5f)
        moveTo(16.5f, 9.5f); lineTo(16.51f, 9.5f)
        moveTo(6f, 12.5f); lineTo(6.01f, 12.5f)
        moveTo(18f, 12.5f); lineTo(18.01f, 12.5f)
        moveTo(8f, 15f); lineTo(16f, 15f)
    }
    val Mouse = outline("Mouse") {
        roundRect(6f, 3f, 18f, 21f, 6f)
        moveTo(12f, 3f); lineTo(12f, 10f)
        moveTo(6f, 10f); lineTo(18f, 10f)
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

    /** The USB trident: a stem with a circle, a square and an arrow on its branches. */
    val Usb = outline("Usb") {
        moveTo(12f, 3.5f); lineTo(12f, 18f)
        moveTo(10f, 5.5f); lineTo(12f, 3.5f); lineTo(14f, 5.5f)
        moveTo(12f, 14f); lineTo(7f, 11.5f); lineTo(7f, 9f)
        moveTo(12f, 12f); lineTo(17f, 9.5f); lineTo(17f, 7.5f)
        roundRect(15.5f, 5f, 18.5f, 7.5f, 0.5f)
        moveTo(12f, 18f)
        arcTo(1.75f, 1.75f, 0f, true, true, 11.99f, 18f)
        moveTo(7f, 9f)
        arcTo(1.25f, 1.25f, 0f, true, true, 6.99f, 9f)
    }
    /** A screen with the broadcast arcs in its corner, like Android's cast symbol. */
    val Cast = outline("Cast") {
        moveTo(3f, 9f); lineTo(3f, 6.5f)
        curveTo(3f, 5.4f, 3.9f, 4.5f, 5f, 4.5f); lineTo(19f, 4.5f)
        curveTo(20.1f, 4.5f, 21f, 5.4f, 21f, 6.5f); lineTo(21f, 17.5f)
        curveTo(21f, 18.6f, 20.1f, 19.5f, 19f, 19.5f); lineTo(14f, 19.5f)
        moveTo(3f, 12.5f)
        curveTo(6.6f, 12.5f, 10f, 15.9f, 10f, 19.5f)
        moveTo(3f, 16f)
        curveTo(4.9f, 16f, 6.5f, 17.6f, 6.5f, 19.5f)
        moveTo(3f, 19.5f); lineTo(3.01f, 19.5f)
    }

    // ---- Files ----

    /** A page with a folded corner. */
    val File = outline("File") {
        moveTo(14f, 3.5f); lineTo(7f, 3.5f)
        curveTo(5.9f, 3.5f, 5f, 4.4f, 5f, 5.5f); lineTo(5f, 18.5f)
        curveTo(5f, 19.6f, 5.9f, 20.5f, 7f, 20.5f); lineTo(17f, 20.5f)
        curveTo(18.1f, 20.5f, 19f, 19.6f, 19f, 18.5f); lineTo(19f, 8.5f); close()
        moveTo(14f, 3.5f); lineTo(14f, 8.5f); lineTo(19f, 8.5f)
    }
    val TextFile = outline("TextFile") {
        moveTo(14f, 3.5f); lineTo(7f, 3.5f)
        curveTo(5.9f, 3.5f, 5f, 4.4f, 5f, 5.5f); lineTo(5f, 18.5f)
        curveTo(5f, 19.6f, 5.9f, 20.5f, 7f, 20.5f); lineTo(17f, 20.5f)
        curveTo(18.1f, 20.5f, 19f, 19.6f, 19f, 18.5f); lineTo(19f, 8.5f); close()
        moveTo(8.5f, 12.5f); lineTo(15.5f, 12.5f)
        moveTo(8.5f, 16f); lineTo(13.5f, 16f)
    }
    val Image = outline("Image") {
        roundRect(3.5f, 4.5f, 20.5f, 19.5f, 2.5f)
        moveTo(3.5f, 16.5f); lineTo(8.5f, 11.5f); lineTo(13f, 16f); lineTo(15.5f, 13.5f); lineTo(20.5f, 18.5f)
        moveTo(15.5f, 9f); lineTo(15.51f, 9f)
    }
    val Music = outline("Music") {
        moveTo(9f, 17.5f); lineTo(9f, 5.5f); lineTo(19f, 3.5f); lineTo(19f, 15.5f)
        moveTo(9f, 17.5f)
        arcTo(2.5f, 2.5f, 0f, true, true, 8.99f, 17.5f)
        moveTo(19f, 15.5f)
        arcTo(2.5f, 2.5f, 0f, true, true, 18.99f, 15.5f)
    }
    val Video = outline("Video") {
        roundRect(3f, 6f, 15.5f, 18f, 2.5f)
        moveTo(15.5f, 10.5f); lineTo(21f, 7.5f); lineTo(21f, 16.5f); lineTo(15.5f, 13.5f)
    }
    /** A box with a zipper down its middle. */
    val Archive = outline("Archive") {
        roundRect(4.5f, 3.5f, 19.5f, 20.5f, 2.5f)
        moveTo(12f, 3.5f); lineTo(12f, 5f)
        moveTo(10.5f, 6.5f); lineTo(12f, 6.5f)
        moveTo(12f, 8f); lineTo(13.5f, 8f)
        moveTo(10.5f, 9.5f); lineTo(12f, 9.5f)
        roundRect(10.5f, 12f, 13.5f, 15.5f, 1f)
    }
    val Link = outline("Link") {
        moveTo(10f, 14f); lineTo(14f, 10f)
        moveTo(11f, 7f); lineTo(12.5f, 5.5f)
        curveTo(14.4f, 3.6f, 17.5f, 3.6f, 18.5f, 5.5f)
        curveTo(20.4f, 6.5f, 20.4f, 9.6f, 18.5f, 11.5f); lineTo(17f, 13f)
        moveTo(13f, 17f); lineTo(11.5f, 18.5f)
        curveTo(9.6f, 20.4f, 6.5f, 20.4f, 5.5f, 18.5f)
        curveTo(3.6f, 17.5f, 3.6f, 14.4f, 5.5f, 12.5f); lineTo(7f, 11f)
    }
    val Copy = outline("Copy") {
        roundRect(8.5f, 8.5f, 20f, 20f, 2f)
        moveTo(15.5f, 8.5f); lineTo(15.5f, 6f)
        curveTo(15.5f, 4.9f, 14.6f, 4f, 13.5f, 4f); lineTo(6f, 4f)
        curveTo(4.9f, 4f, 4f, 4.9f, 4f, 6f); lineTo(4f, 13.5f)
        curveTo(4f, 14.6f, 4.9f, 15.5f, 6f, 15.5f); lineTo(8.5f, 15.5f)
    }
    /** Scissors. */
    val Cut = outline("Cut") {
        moveTo(6.5f, 17f)
        arcTo(2.5f, 2.5f, 0f, true, true, 6.49f, 17f)
        moveTo(17.5f, 17f)
        arcTo(2.5f, 2.5f, 0f, true, true, 17.49f, 17f)
        moveTo(7.8f, 15f); lineTo(17f, 4f)
        moveTo(16.2f, 15f); lineTo(7f, 4f)
    }
    /** A clipboard. */
    val Paste = outline("Paste") {
        moveTo(9f, 5f); lineTo(7f, 5f)
        curveTo(5.9f, 5f, 5f, 5.9f, 5f, 7f); lineTo(5f, 19f)
        curveTo(5f, 20.1f, 5.9f, 21f, 7f, 21f); lineTo(17f, 21f)
        curveTo(18.1f, 21f, 19f, 20.1f, 19f, 19f); lineTo(19f, 7f)
        curveTo(19f, 5.9f, 18.1f, 5f, 17f, 5f); lineTo(15f, 5f)
        roundRect(9f, 3f, 15f, 7f, 1f)
    }
    val NewFolder = outline("NewFolder") {
        moveTo(11f, 19f); lineTo(5.5f, 19f)
        curveTo(4.4f, 19f, 3.5f, 18.1f, 3.5f, 17f); lineTo(3.5f, 7f)
        curveTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f)
        lineTo(9.5f, 5f); lineTo(11.5f, 7f); lineTo(18.5f, 7f)
        curveTo(19.6f, 7f, 20.5f, 7.9f, 20.5f, 9f); lineTo(20.5f, 11.5f)
        moveTo(18f, 14f); lineTo(18f, 21f)
        moveTo(14.5f, 17.5f); lineTo(21.5f, 17.5f)
    }
    /** Lines of shrinking length, like a sorted list. */
    val Sort = outline("Sort") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(15f, 12f)
        moveTo(4f, 17f); lineTo(10f, 17f)
    }
    val Grid = outline("Grid") {
        roundRect(4f, 4f, 10.5f, 10.5f, 1.5f)
        roundRect(13.5f, 4f, 20f, 10.5f, 1.5f)
        roundRect(4f, 13.5f, 10.5f, 20f, 1.5f)
        roundRect(13.5f, 13.5f, 20f, 20f, 1.5f)
    }
    val Rows = outline("Rows") {
        roundRect(4f, 4.5f, 20f, 10.5f, 1.5f)
        roundRect(4f, 13.5f, 20f, 19.5f, 1.5f)
    }
    val SelectAll = outline("SelectAll") {
        roundRect(4f, 4f, 20f, 20f, 3f)
        moveTo(8f, 12.5f); lineTo(11f, 15.5f); lineTo(16.5f, 9f)
    }
    val Phone = outline("Phone") {
        roundRect(6.5f, 2.5f, 17.5f, 21.5f, 2.5f)
        moveTo(10.5f, 18.5f); lineTo(13.5f, 18.5f)
    }
    val SdCard = outline("SdCard") {
        moveTo(9f, 3.5f); lineTo(17f, 3.5f)
        curveTo(18.1f, 3.5f, 19f, 4.4f, 19f, 5.5f); lineTo(19f, 18.5f)
        curveTo(19f, 19.6f, 18.1f, 20.5f, 17f, 20.5f); lineTo(7f, 20.5f)
        curveTo(5.9f, 20.5f, 5f, 19.6f, 5f, 18.5f); lineTo(5f, 7.5f); close()
        moveTo(10f, 7f); lineTo(10f, 9.5f)
        moveTo(13f, 7f); lineTo(13f, 9.5f)
        moveTo(16f, 7f); lineTo(16f, 9.5f)
    }
    val Download = outline("Download") {
        moveTo(12f, 4f); lineTo(12f, 15f)
        moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
        moveTo(5f, 19.5f); lineTo(19f, 19.5f)
    }
    /** An arrow out of a tray: bringing files in from the phone. */
    val Upload = outline("Upload") {
        moveTo(12f, 15f); lineTo(12f, 4f)
        moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
        moveTo(4.5f, 14.5f); lineTo(4.5f, 18f)
        curveTo(4.5f, 19.1f, 5.4f, 20f, 6.5f, 20f); lineTo(17.5f, 20f)
        curveTo(18.6f, 20f, 19.5f, 19.1f, 19.5f, 18f); lineTo(19.5f, 14.5f)
    }
    val Camera = outline("Camera") {
        moveTo(3.5f, 9f)
        curveTo(3.5f, 7.9f, 4.4f, 7f, 5.5f, 7f); lineTo(7.5f, 7f); lineTo(9f, 4.5f); lineTo(15f, 4.5f)
        lineTo(16.5f, 7f); lineTo(18.5f, 7f)
        curveTo(19.6f, 7f, 20.5f, 7.9f, 20.5f, 9f); lineTo(20.5f, 17.5f)
        curveTo(20.5f, 18.6f, 19.6f, 19.5f, 18.5f, 19.5f); lineTo(5.5f, 19.5f)
        curveTo(4.4f, 19.5f, 3.5f, 18.6f, 3.5f, 17.5f); close()
        moveTo(12f, 9.5f)
        arcTo(3.5f, 3.5f, 0f, true, true, 11.99f, 9.5f)
    }
    val Bookmark = outline("Bookmark") {
        moveTo(6.5f, 5.5f)
        curveTo(6.5f, 4.4f, 7.4f, 3.5f, 8.5f, 3.5f); lineTo(15.5f, 3.5f)
        curveTo(16.6f, 3.5f, 17.5f, 4.4f, 17.5f, 5.5f); lineTo(17.5f, 20.5f)
        lineTo(12f, 16.5f); lineTo(6.5f, 20.5f); close()
    }
    /** A hard drive: the Linux system as a whole. */
    val Drive = outline("Drive") {
        roundRect(3.5f, 13f, 20.5f, 19.5f, 2f)
        moveTo(3.5f, 15f); lineTo(6.5f, 5.5f)
        curveTo(6.8f, 4.9f, 7.3f, 4.5f, 8f, 4.5f); lineTo(16f, 4.5f)
        curveTo(16.7f, 4.5f, 17.2f, 4.9f, 17.5f, 5.5f); lineTo(20.5f, 15f)
        moveTo(16.5f, 16.25f); lineTo(16.51f, 16.25f)
    }
    /** A padlock opened (or closed, with [locked]): browsing as root. */
    val Shield = outline("Shield") {
        moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f)
        curveTo(19f, 15.5f, 16f, 19.2f, 12f, 21f)
        curveTo(8f, 19.2f, 5f, 15.5f, 5f, 11f); lineTo(5f, 6f); close()
        moveTo(9f, 12f); lineTo(11f, 14f); lineTo(15f, 10f)
    }
    val Up = outline("Up") {
        moveTo(12f, 19f); lineTo(12f, 5f)
        moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f)
    }
    /** A screen on a stand with a window on it; the Linux desktop. */
    val Desktop = outline("Desktop") {
        roundRect(3f, 4f, 21f, 16f, 2.5f)
        moveTo(9f, 20f); lineTo(15f, 20f)
        moveTo(12f, 16f); lineTo(12f, 20f)
        moveTo(7f, 8.5f); lineTo(13f, 8.5f)
    }
    val Search = outline("Search") {
        moveTo(10.5f, 4f)
        arcTo(6.5f, 6.5f, 0f, true, true, 10.49f, 4f)
        moveTo(15.2f, 15.2f); lineTo(20f, 20f)
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
    "spark" -> LauncherIcons.Spark
    "desktop" -> LauncherIcons.Desktop
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
