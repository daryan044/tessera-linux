@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.RunningSession
import com.daryan.tessera.container.XWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil

/** What a desktop's taskbar and Start menu ask of its session; see X11Window. */
interface DesktopActions {
    /** The desktop's session, with its windows as they are now. */
    val session: StateFlow<RunningSession?>
    /** The launcher's apps; the Start menu offers those that can run in a desktop. */
    val apps: StateFlow<List<PermissionManifest>>
    /** Opens [app] in the desktop; false when the desktop isn't listening yet. */
    fun launch(app: PermissionManifest): Boolean
    fun activate(windowId: Long)
    fun minimize(windowId: Long)
    fun toggleMaximize(windowId: Long)
    fun snap(windowId: Long, left: Boolean)
    fun close(windowId: Long)
    /** Keeps [px] X pixels along the [left] or bottom edge for the dock. */
    fun reserve(left: Boolean, px: Int): Boolean
    /**
     * Puts the phone's wallpaper behind the windows, fitted to a [width] × [height] X screen;
     * where it can't be read, a gradient from [top] to [bottom] (ARGB).
     */
    suspend fun wallpaper(width: Int, height: Int, top: Int, bottom: Int): Boolean
    fun toggleKeyboard()
    /** Ends the desktop and everything open in it. */
    fun end()
}

/** Apps the Start menu can open in a desktop: not another desktop, a Flathub app or one that wants root. */
fun desktopApps(apps: List<PermissionManifest>): List<PermissionManifest> =
    apps.filter { !it.desktop && it.flatpak == null && !it.permissions.root }

/**
 * The launcher app [window] belongs to, by its WM_CLASS against each app's program ("XTerm"
 * is xterm's, "Gimp-2.10" GIMP's); a stand-in named after the window when none matches.
 */
fun appForWindow(window: XWindow, apps: List<PermissionManifest>): PermissionManifest {
    val cls = window.windowClass.lowercase()
    val match = if (cls.isEmpty()) null else apps.firstOrNull { a ->
        val program = a.exec.firstOrNull()?.substringAfterLast('/')?.lowercase().orEmpty()
        a.id == cls || program == cls || (program.length > 2 && cls.startsWith(program)) || a.name.lowercase() == cls
    }
    return match ?: PermissionManifest(
        id = cls.ifEmpty { "window-${window.id}" },
        name = window.title.ifBlank { window.windowClass.ifBlank { "Window" } },
        exec = listOf("?"),
    )
}

/**
 * A desktop's own shell over its X screen, in the phone's Material look: a floating dock (a
 * bar along the bottom in portrait, a rail down the left in landscape, as Circela's) with the
 * Start button, a button per open window and the clock, and the Start menu above it. The X
 * screen keeps the dock's room free of maximized windows, and shows the phone's wallpaper.
 */
@Composable
fun DesktopShell(actions: DesktopActions) {
    val session by actions.session.collectAsStateWithLifecycle()
    val allApps by actions.apps.collectAsStateWithLifecycle()
    val apps = remember(allApps) { desktopApps(allApps) }
    val windows = session?.windows
    val screen = windows?.screen.orEmpty()
    val config = LocalConfiguration.current
    val vertical = config.screenWidthDp > config.screenHeightDp
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    var startOpen by rememberSaveable { mutableStateOf(false) }
    // How far the dock reaches in from its edge, in this view's pixels.
    var dockReach by remember { mutableIntStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewWidth = constraints.maxWidth
        val viewHeight = constraints.maxHeight
        // X pixels per pixel of this view (the X screen is the view at the app's scale).
        val ratio = if (screen.size == 2 && viewWidth > 0) screen[0].toFloat() / viewWidth else 0f
        val gap = with(density) { 8.dp.roundToPx() }
        LaunchedEffect(ratio, dockReach, vertical) {
            if (ratio <= 0f || dockReach <= 0) return@LaunchedEffect
            retrying { actions.reserve(vertical, ceil((dockReach + gap) * ratio).toInt()) }
        }
        val top = colors.primaryContainer.toArgb()
        val bottom = colors.surface.toArgb()
        LaunchedEffect(screen) {
            if (screen.size != 2) return@LaunchedEffect
            retrying { actions.wallpaper(screen[0], screen[1], top, bottom) }
        }

        if (startOpen) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { startOpen = false },
            )
        }

        Dock(
            vertical = vertical,
            windows = windows?.main.orEmpty(),
            active = windows?.active ?: 0,
            apps = allApps,
            startOpen = startOpen,
            onStart = { startOpen = !startOpen },
            actions = actions,
            maxLength = with(density) { (if (vertical) viewHeight else viewWidth).toDp() } - 32.dp,
            modifier = Modifier
                .align(if (vertical) Alignment.CenterStart else Alignment.BottomCenter)
                .then(if (vertical) Modifier.statusBarsPadding().padding(start = 12.dp) else Modifier.navigationBarsPadding().padding(bottom = 12.dp))
                .onGloballyPositioned { c ->
                    val b = c.boundsInRoot()
                    dockReach = if (vertical) b.right.toInt() else (viewHeight - b.top).toInt()
                },
        )

        AnimatedVisibility(
            visible = startOpen,
            enter = scaleIn(motion.defaultSpatialSpec(), initialScale = 0.9f, transformOrigin = startOrigin(vertical)) + fadeIn(motion.defaultEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec(), targetScale = 0.9f, transformOrigin = startOrigin(vertical)) + fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier
                .align(if (vertical) Alignment.TopStart else Alignment.BottomCenter)
                .then(
                    if (vertical) Modifier.statusBarsPadding().padding(start = with(density) { dockReach.toDp() } + 12.dp, top = 12.dp)
                    else Modifier.padding(bottom = with(density) { dockReach.toDp() } + 12.dp, start = 16.dp, end = 16.dp),
                ),
        ) {
            StartMenu(
                apps = apps,
                windows = windows?.main?.size ?: 0,
                onLaunch = { app -> if (actions.launch(app)) startOpen = false },
                onEnd = actions::end,
                maxHeight = with(density) { viewHeight.toDp() } * (if (vertical) 0.9f else 0.7f),
            )
        }
    }
}

private fun startOrigin(vertical: Boolean) =
    if (vertical) androidx.compose.ui.graphics.TransformOrigin(0f, 0f) else androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)

/** Tries [send] until it gets through (the desktop's helper starts with it), for half a minute. */
private suspend fun retrying(send: suspend () -> Boolean) {
    repeat(15) {
        if (send()) return
        delay(2000)
    }
}

@Composable
private fun Dock(
    vertical: Boolean,
    windows: List<XWindow>,
    active: Long,
    apps: List<PermissionManifest>,
    startOpen: Boolean,
    onStart: () -> Unit,
    actions: DesktopActions,
    maxLength: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = colors.surfaceContainer.copy(alpha = 0.94f),
        contentColor = colors.onSurface,
        shadowElevation = 12.dp,
        modifier = modifier
            .then(if (vertical) Modifier.heightIn(max = maxLength) else Modifier.widthIn(max = maxLength))
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.35f), shape),
    ) {
        val items = @Composable {
            StartButton(startOpen, onStart)
            Separator(vertical)
            windows.forEach { w ->
                TaskButton(w, appForWindow(w, apps), focused = w.id == active && !w.hidden, vertical = vertical, actions = actions)
            }
            if (windows.isNotEmpty()) Separator(vertical)
            IconButton(onClick = actions::toggleKeyboard) { Icon(LauncherIcons.Keyboard, "Keyboard") }
            Clock(vertical)
        }
        if (vertical) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp),
            ) { items() }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            ) { items() }
        }
    }
}

@Composable
private fun StartButton(open: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(if (open) RoundedCornerShape(16.dp) else CircleShape)
            .background(if (open) colors.primary else colors.primaryContainer)
            .clickable(onClick = onClick),
    ) {
        Icon(LauncherIcons.Apps, "Start", tint = if (open) colors.onPrimary else colors.onPrimaryContainer)
    }
}

@Composable
private fun Separator(vertical: Boolean) {
    Box(
        Modifier.padding(4.dp)
            .then(if (vertical) Modifier.width(24.dp).height(1.dp) else Modifier.width(1.dp).height(24.dp))
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * An open window: its app's icon, with Android's taskbar dash under it (longer while focused).
 * Tap to bring it up, or put it away when it's already in front; hold for the window's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskButton(window: XWindow, app: PermissionManifest, focused: Boolean, vertical: Boolean, actions: DesktopActions) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    var menu by remember { mutableStateOf(false) }
    val (container, onContainer) = appColors(app)
    val dash by animateDpAsState(if (focused) 16.dp else 6.dp, motion.defaultSpatialSpec(), label = "dash")
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClickLabel = window.title,
                    onClick = { if (focused) actions.minimize(window.id) else actions.activate(window.id) },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).alpha(if (window.hidden) 0.5f else 1f).clip(appShape(app)).background(container),
            ) {
                AppGlyph(app, 22.dp, onContainer)
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.width(dash).height(3.dp).clip(CircleShape)
                    .background(if (focused) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
            Text(
                window.title.ifBlank { app.name },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val items = buildList {
                add((if (window.maximized) "Restore" else "Maximize") to { actions.toggleMaximize(window.id) })
                add("Left half" to { actions.snap(window.id, left = true) })
                add("Right half" to { actions.snap(window.id, left = false) })
                if (!window.hidden) add("Minimize" to { actions.minimize(window.id) })
            }
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Close", color = colors.error) },
                leadingIcon = { Icon(Icons.Rounded.Close, null, tint = colors.error) },
                onClick = { menu = false; actions.close(window.id) },
            )
        }
    }
    if (vertical) Spacer(Modifier.height(2.dp))
}

@Composable
private fun Clock(vertical: Boolean) {
    val time by produceState("") {
        val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        while (true) {
            value = format.format(java.util.Date())
            delay(10_000)
        }
    }
    Text(
        time,
        style = if (vertical) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun StartMenu(
    apps: List<PermissionManifest>,
    windows: Int,
    onLaunch: (PermissionManifest) -> Unit,
    onEnd: () -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp,
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(apps, query) { searchApps(apps, query) }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        shadowElevation = 16.dp,
        modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(28.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(52.dp).clip(CircleShape)
                    .background(colors.surfaceContainerHighest).padding(horizontal = 16.dp),
            ) {
                Icon(LauncherIcons.Search, null, tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search apps", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { shown.firstOrNull()?.let(onLaunch) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (shown.isEmpty()) {
                Text(
                    if (apps.isEmpty()) "No apps yet. Install some from the Store." else "No apps match",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 84.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(shown, key = { it.id }) { app -> CompactAppTile(app) { onLaunch(app) } }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Linux desktop", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (windows) {
                            0 -> "Nothing open"
                            1 -> "1 window open"
                            else -> "$windows windows open"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                // Two taps, like the window panel's Close: the first asks.
                var armed by rememberArmed()
                TextButton(onClick = { if (armed) onEnd() else armed = true }) {
                    Icon(Icons.Rounded.Close, null, tint = colors.error)
                    Spacer(Modifier.width(6.dp))
                    Text(if (armed) "End desktop?" else "End", color = colors.error)
                }
            }
        }
    }
}
