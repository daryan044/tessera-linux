// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.ui.customIconColors

/**
 * App icons for the phone's widgets, which can't draw Compose: the app's own picture on a tile of
 * its color, or its first letter when it has none. Small, as all of a widget's bitmaps together
 * travel through a binder call with a size limit.
 */
object WidgetIcons {
    private const val SIDE = 112

    /** Tones for apps with no color of their own; an app keeps the same one everywhere. */
    private val TONES = intArrayOf(0xFF6750A4.toInt(), 0xFF00696B.toInt(), 0xFF8C4A60.toInt(), 0xFF4A6500.toInt(), 0xFF00639B.toInt(), 0xFF8B5000.toInt())

    fun badge(context: Context, app: PermissionManifest): Bitmap {
        val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = customIconColors.value[app.id] ?: TONES[Math.floorMod(app.id.hashCode(), TONES.size)]
        canvas.drawRoundRect(RectF(0f, 0f, SIDE.toFloat(), SIDE.toFloat()), SIDE * 0.28f, SIDE * 0.28f, paint)
        val own = app.iconFile?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        if (own != null) {
            val inset = SIDE / 8
            canvas.drawBitmap(own, null, Rect(inset, inset, SIDE - inset, SIDE - inset), paint)
        } else {
            paint.color = Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = SIDE * 0.5f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(initial(app.name), SIDE / 2f, SIDE / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
        return bitmap
    }

    /** The letter that stands for an app with no picture. */
    internal fun initial(name: String): String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
}
