// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * Google Photos-style navigation: a frosted pill floating over the content. The selected
 * destination grows to show its label; the rest stay as icons.
 */
@Composable
fun FloatingDock(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    hazeState: HazeState,
    glass: HazeBlurStyle,
    badge: @Composable (Destination) -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp, top = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .shadow(16.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.4f))
                .clip(CircleShape)
                .hazeBlur(HazeInput.Sources(hazeState), glass)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), CircleShape)
                .padding(6.dp)
                .selectableGroup(),
        ) {
            Destination.entries.forEach { d ->
                DockItem(d, d == selected, onClick = { onSelect(d) }, badge = { badge(d) })
            }
        }
    }
}

@Composable
private fun DockItem(d: Destination, selected: Boolean, onClick: () -> Unit, badge: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else Color.Transparent, motion.defaultEffectsSpec(), label = "dock",
    )
    val content by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant, motion.defaultEffectsSpec(), label = "dockFg",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        BadgedBox(badge = { badge() }) {
            Icon(d.icon, contentDescription = d.label, tint = content)
        }
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkHorizontally(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(d.label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
            }
        }
    }
}
