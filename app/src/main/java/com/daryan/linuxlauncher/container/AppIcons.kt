// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Icons for Linux apps as Android sees them (Recents, task switcher): the app's own icon from
 * the container's icon theme when we can read it, otherwise a colored tile with its initial.
 */
object AppIcons {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    /** [rootfs] is null when the container's files aren't readable (chroot mode). */
    fun forApp(app: PermissionManifest, rootfs: File?): Bitmap =
        cache.getOrPut("${app.id}@${rootfs?.path}") { rootfs?.let { find(app, it) } ?: tile(app) }

    /** Freedesktop icon names worth trying, most specific first. */
    internal fun candidateNames(app: PermissionManifest): List<String> = listOfNotNull(
        app.exec.firstOrNull()?.substringAfterLast('/'),
        app.id,
    ) + app.packages

    private fun find(app: PermissionManifest, rootfs: File): Bitmap? {
        for (name in candidateNames(app).distinct()) {
            val files = SIZES.map { File(rootfs, "usr/share/icons/hicolor/$it/apps/$name.png") } +
                File(rootfs, "usr/share/pixmaps/$name.png")
            for (f in files) {
                if (!f.isFile) continue
                BitmapFactory.decodeFile(f.path)?.let { return it }
            }
        }
        return null
    }

    private fun tile(app: PermissionManifest): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = TILE_COLORS[Math.floorMod(app.id.hashCode(), TILE_COLORS.size)]
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 104f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val initial = app.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        canvas.drawText(initial, size / 2f, size / 2f - (text.ascent() + text.descent()) / 2, text)
        return bmp
    }

    private val SIZES = listOf("256x256", "192x192", "128x128", "96x96", "64x64", "48x48")
    private val TILE_COLORS = listOf(0xFF3F6BC4, 0xFF8E4585, 0xFF2E7D5B, 0xFFB5552B, 0xFF5B5BA8, 0xFF8A6D1F).map { it.toInt() }
}
