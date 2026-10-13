// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.daryan.tessera.MainActivity
import com.daryan.tessera.container.PermissionManifest

/** Icons for apps on the phone's own home screen, which start them through the launcher. */
object HomeShortcuts {
    /** Whether the phone's home screen takes pinned icons. */
    fun supported(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the home screen to add [app] with [icon]; it shows its own confirmation. */
    fun pin(context: Context, app: PermissionManifest, icon: Bitmap): Boolean {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LAUNCH_APP)
            .putExtra(MainActivity.EXTRA_APP_ID, app.id)
        val info = ShortcutInfoCompat.Builder(context, "app:${app.id}")
            .setShortLabel(app.name)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }
}

/**
 * Draws [app]'s icon as Android's adaptive icons want it: its launcher colors filling the square,
 * the glyph (or its own icon) inside the middle two thirds, which every icon mask keeps.
 */
@Composable
fun rememberHomeIcon(app: PermissionManifest): () -> Bitmap {
    val (container, onContainer) = appColors(app)
    val glyph = rememberVectorPainter(appIcon(app.icon))
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    return {
        val side = 432 // 108dp at 4x
        val image = ImageBitmap(side, side)
        val own = app.iconFile?.let { BitmapFactory.decodeFile(it) }?.asImageBitmap()
        CanvasDrawScope().draw(density, layoutDirection, Canvas(image), Size(side.toFloat(), side.toFloat())) {
            drawRect(container)
            if (own != null) {
                val inner = (side * 0.5f).toInt()
                drawImage(
                    own,
                    dstOffset = IntOffset((side - inner) / 2, (side - inner) / 2),
                    dstSize = IntSize(inner, inner),
                )
            } else {
                val inner = side * 0.36f
                translate((side - inner) / 2, (side - inner) / 2) {
                    with(glyph) { draw(Size(inner, inner), colorFilter = ColorFilter.tint(onContainer)) }
                }
            }
        }
        image.asAndroidBitmap()
    }
}
