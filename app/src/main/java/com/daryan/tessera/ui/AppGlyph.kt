// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** An app as a small tile, its icon over its name: in search results and the desktop's Start menu. */
@Composable
fun CompactAppTile(app: PermissionManifest, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (container, onContainer) = appColors(app)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(52.dp).clip(appShape(app)).background(container),
        ) {
            AppGlyph(app, 24.dp, onContainer)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
