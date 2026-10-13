// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import com.daryan.tessera.container.PermissionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val bitmaps = LruCache<String, ImageBitmap>(256)

/** Decodes a PNG off the main thread, once per path. */
@Composable
fun rememberFileBitmap(path: String?): ImageBitmap? =
    produceState(path?.let { bitmaps.get(it) }, path) {
        if (path == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
            ?.also { bitmaps.put(path, it) }
    }.value

/** An app's own icon when it has one (store apps), else its glyph from the launcher's icon set. */
@Composable
fun AppGlyph(app: PermissionManifest, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val bitmap = rememberFileBitmap(app.iconFile)
    if (bitmap != null) {
        // Real icons carry their own colors and padding; let them fill more of the shape.
        Image(bitmap, contentDescription = null, modifier = modifier.size(size * 1.7f))
    } else {
        Icon(appIcon(app.icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
    }
}
