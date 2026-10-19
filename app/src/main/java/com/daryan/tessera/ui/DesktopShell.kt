@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.areSystemBarsVisible
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.runtime.mutableStateMapOf
import com.daryan.tessera.container.DesktopEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daryan.tessera.container.DesktopItem
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.RunningSession
import com.daryan.tessera.container.XWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** What a desktop's dock, Start menu and icons ask of its session; see X11Window. */
interface DesktopActions {
    /** The desktop's session, with its windows as they are now. */
    val session: StateFlow<RunningSession?>
    /** The launcher's apps; the Start menu offers those that can run in a desktop. */
    val apps: StateFlow<List<PermissionManifest>>
    /** Launcher app ids pinned to the dock, in order. */
    val pins: StateFlow<List<String>>
    fun setPinned(appId: String, pinned: Boolean)
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
     * Puts the phone's wallpaper behind the windows, fitted to a [width] × [height] X screen
     * (where it can't be read, a gradient from [top] to [bottom], ARGB), with [icons] painted
     * over it: a picture of this view's [viewWidth] × [viewHeight], each icon at [places] in it.
     */
    suspend fun wallpaper(
        width: Int,
        height: Int,
        top: Int,
        bottom: Int,
        icons: android.graphics.Bitmap?,
        places: Map<String, android.graphics.Rect>,
        viewWidth: Int,
        viewHeight: Int,
    ): Boolean
    /** A long press or right click on a desktop icon, until the menu it asks for closes. */
    val iconMenu: StateFlow<DesktopEvent?>
    fun dismissIconMenu()
    /** What is on the desktop (~/Desktop) now. */
    fun items(): List<DesktopItem>
    fun open(item: DesktopItem)
    fun addToDesktop(app: PermissionManifest): Boolean
    /** A shortcut comes off the desktop; a file or folder goes to the trash. */
    fun removeFromDesktop(item: DesktopItem)
    /** Copies files dropped from another Android app onto the desktop; how many made it. */
    suspend fun importDropped(uris: List<Uri>): Int
    fun toggleKeyboard()
    /** Hides the phone's bars again, after something brought them back. */
    fun keepFullscreen()
    /**
     * Gives the keyboard and touch back to Linux's view: the Start menu's search field keeps
     * Android's focus after the menu closes, and the X view then stops taking input.
     */
    fun returnFocus()
    /** Ends the desktop and everything open in it. */
    fun end()
}

/**
 * Apps the Start menu can open in a desktop: not another desktop, or one that wants root (it
 * would run as the desktop's user). Flathub apps come too, in their own root as always.
 */
fun desktopApps(apps: List<PermissionManifest>): List<PermissionManifest> =
    apps.filter { !it.desktop && !it.permissions.root }

/**
 * The launcher app [window] belongs to, by its WM_CLASS against each app's program ("XTerm"
 * is xterm's, "Gimp-2.10" GIMP's); a stand-in named after the window when none matches.
 */
fun appForWindow(window: XWindow, apps: List<PermissionManifest>): PermissionManifest {
    val cls = window.windowClass.lowercase()
    val match = if (cls.isEmpty()) null else apps.firstOrNull { a ->
        val program = a.exec.firstOrNull()?.substringAfterLast('/')?.lowercase().orEmpty()
        a.id.lowercase() == cls || program == cls || (program.length > 2 && cls.startsWith(program)) || a.name.lowercase() == cls
    }
    return match ?: PermissionManifest(
        id = cls.ifEmpty { "window-${window.id}" },
        name = window.title.ifBlank { window.windowClass.ifBlank { "Window" } },
        exec = listOf("?"),
    )
}

/** An app on the dock: pinned there, or running, with its open windows. */
data class DockEntry(val app: PermissionManifest, val windows: List<XWindow>, val pinned: Boolean)

/**
 * The dock's apps, as Android's taskbar has them: the pinned ones in their order (with their
 * windows, when open), then each other app with windows open, in the order they opened. An
 * app's windows share one button.
 */
fun dockEntries(pins: List<String>, windows: List<XWindow>, apps: List<PermissionManifest>): List<DockEntry> {
    val byId = apps.associateBy { it.id }
    val groups = linkedMapOf<String, Pair<PermissionManifest, MutableList<XWindow>>>()
    windows.forEach { w ->
        val app = appForWindow(w, apps)
        groups.getOrPut(app.id) { app to mutableListOf() }.second += w
    }
    val pinned = pins.mapNotNull { id -> byId[id] ?: groups[id]?.first }
        .map { app -> DockEntry(app, groups[app.id]?.second.orEmpty(), pinned = true) }
    val running = groups.values.filter { (app, _) -> app.id !in pins }.map { (app, ws) -> DockEntry(app, ws, pinned = false) }
    return pinned + running
}

/** An app being dragged out of the Start menu, and where the finger has it, in root pixels. */
private data class Dragging(val app: PermissionManifest, val at: Offset)

/**
 * A desktop's own shell over its full-screen X screen (the phone's bars hidden; only the camera
 * cutout is kept clear), in the phone's Material look: a floating dock (a
 * bar along the bottom in portrait, a rail down the left in landscape, as Circela's) with the
 * Start button, pinned and running apps and a status corner; the Start menu; and, while no
 * window covers it, what is on the desktop (~/Desktop), as a home screen shows its icons. The
 * X screen keeps the dock's room free of maximized windows, and shows the phone's wallpaper.
 */
@Composable
fun DesktopShell(actions: DesktopActions) {
    val session by actions.session.collectAsStateWithLifecycle()
    val allApps by actions.apps.collectAsStateWithLifecycle()
    val pins by actions.pins.collectAsStateWithLifecycle()
    val apps = remember(allApps) { desktopApps(allApps) }
    val windows = session?.windows
    val main = windows?.main.orEmpty()
    val screen = windows?.screen.orEmpty()
    val config = LocalConfiguration.current
    val vertical = config.screenWidthDp > config.screenHeightDp
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val scope = rememberCoroutineScope()
    var startOpen by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(startOpen) {
        if (!startOpen) {
            focus.clearFocus(force = true)
            actions.returnFocus()
        }
    }
    // How far the dock reaches in from its edge, in this view's pixels, and where it is.
    var dockReach by remember { mutableIntStateOf(0) }
    var dockBounds by remember { mutableStateOf(Rect.Zero) }
    var dragging by remember { mutableStateOf<Dragging?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            message = null
        }
    }
    // The windows "Show desktop" put away, to bring back with a second tap.
    var putAway by remember { mutableStateOf(emptyList<Long>()) }
    val items by produceState(emptyList<DesktopItem>(), session?.session?.id) {
        while (true) {
            value = actions.items()
            delay(1500)
        }
    }
    val entries = remember(pins, main, allApps) { dockEntries(pins, main, allApps) }
    val stillStarting = "The desktop is still starting"
    // Termux:X11 shows the phone's bars again on its own (turning, settings, reconnecting);
    // a swipe's brief look at them doesn't count as showing, so this only undoes those.
    val barsShown = WindowInsets.areSystemBarsVisible
    LaunchedEffect(barsShown) {
        if (barsShown) {
            delay(300)
            actions.keepFullscreen()
        }
    }

    val activity = LocalContext.current as? Activity
    val dropTarget = remember(actions) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                val clip = drag.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) return false
                // Reading another app's files takes its say-so for this drop.
                activity?.requestDragAndDropPermissions(drag)
                scope.launch {
                    message = when (val n = actions.importDropped(uris)) {
                        0 -> "Couldn't put that on the desktop"
                        1 -> "Added to the desktop"
                        else -> "$n files added to the desktop"
                    }
                }
                return true
            }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = { e -> e.mimeTypes().isNotEmpty() },
            target = dropTarget,
        ),
    ) {
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
        val reach = with(density) { dockReach.toDp() }

        // The desktop's icons live in its wallpaper, under every window, as on any desktop: laid
        // out and painted here off screen, then drawn into the picture the desktop's window shows.
        // The desktop's helper hears the taps on them (see DesktopActions.iconMenu).
        val picture = rememberGraphicsLayer()
        val places = remember { mutableStateMapOf<String, Rect>() }
        Box(
            Modifier.fillMaxSize()
                .drawWithContent { picture.record { this@drawWithContent.drawContent() } }
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(start = if (vertical) reach + 8.dp else 8.dp, bottom = if (vertical) 0.dp else reach + 8.dp, top = 8.dp, end = 8.dp),
        ) {
            DesktopIcons(items, allApps, onPlaced = { path, r -> places[path] = r })
        }
        LaunchedEffect(screen, items, allApps, reach, vertical, top, bottom) {
            if (screen.size != 2) return@LaunchedEffect
            // Long enough for the icons' own pictures to load.
            delay(700)
            val image = runCatching { picture.toImageBitmap().asAndroidBitmap() }.getOrNull()
            val spots = items.mapNotNull { i ->
                places[i.path]?.let { r -> i.path to android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()) }
            }.toMap()
            retrying { actions.wallpaper(screen[0], screen[1], top, bottom, image, spots, viewWidth, viewHeight) }
        }
        val openItem: (DesktopItem) -> Unit = { item ->
            val app = allApps.firstOrNull { it.id == item.app }.takeIf { item.isShortcut }
            if (app != null) actions.launch(app) else actions.open(item)
        }
        val iconMenu by actions.iconMenu.collectAsStateWithLifecycle()
        iconMenu?.let { event ->
            val item = items.firstOrNull { it.path == event.path }
            if (item != null && ratio > 0f) {
                Box(Modifier.offset { IntOffset((event.x / ratio).roundToInt(), (event.y / ratio).roundToInt()) }) {
                    val close = { actions.dismissIconMenu(); actions.returnFocus() }
                    DropdownMenu(expanded = true, onDismissRequest = close, shape = RoundedCornerShape(16.dp)) {
                        MenuTitle(item.name)
                        DropdownMenuItem(text = { Text("Open") }, onClick = { close(); openItem(item) })
                        DropdownMenuItem(
                            text = { Text(if (item.isShortcut || item.isLink) "Remove from desktop" else "Move to Trash", color = colors.error) },
                            onClick = { close(); actions.removeFromDesktop(item) },
                        )
                    }
                }
            }
        }

        Starting(session, screen.isEmpty(), Modifier.align(Alignment.Center))

        if (startOpen && dragging == null) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { startOpen = false },
            )
        }

        Dock(
            vertical = vertical,
            entries = entries,
            active = windows?.active ?: 0,
            startOpen = startOpen,
            onStart = { startOpen = it },
            launchable = { app -> apps.any { it.id == app.id } },
            onLaunch = { app -> if (!actions.launch(app)) message = stillStarting },
            onAddToDesktop = { app -> message = if (actions.addToDesktop(app)) "${app.name} is on the desktop" else stillStarting },
            showingDesktop = main.isNotEmpty() && main.all { it.hidden },
            onShowDesktop = {
                val visible = main.filterNot { it.hidden }
                if (visible.isNotEmpty()) {
                    putAway = visible.map { it.id }
                    visible.forEach { actions.minimize(it.id) }
                } else {
                    putAway.filter { id -> main.any { it.id == id } }.forEach(actions::activate)
                    putAway = emptyList()
                }
            },
            dropHere = dragging?.let { dockBounds.contains(it.at) } == true,
            actions = actions,
            maxLength = with(density) { (if (vertical) viewHeight else viewWidth).toDp() } - 32.dp,
            modifier = Modifier
                .align(if (vertical) Alignment.CenterStart else Alignment.BottomCenter)
                .then(if (vertical) Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(start = 12.dp) else Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(bottom = 12.dp))
                .onGloballyPositioned { c ->
                    val b = c.boundsInRoot()
                    dockBounds = b
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
                    if (vertical) Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(start = reach + 12.dp, top = 12.dp)
                    else Modifier.padding(bottom = reach + 12.dp, start = 16.dp, end = 16.dp),
                )
                // Out of sight while an app is dragged out of it, but still there to carry the drag.
                .graphicsLayer { alpha = if (dragging != null) 0f else 1f },
        ) {
            StartMenu(
                apps = apps,
                pins = pins,
                windows = main.size,
                wide = vertical,
                onLaunch = { app -> if (actions.launch(app)) startOpen = false else message = stillStarting },
                onAddToDesktop = { app -> message = if (actions.addToDesktop(app)) "${app.name} is on the desktop" else stillStarting },
                onPin = { app, on -> actions.setPinned(app.id, on) },
                onDragStart = { app, at -> dragging = Dragging(app, at) },
                onDrag = { delta -> dragging = dragging?.let { it.copy(at = it.at + delta) } },
                onDragEnd = {
                    dragging?.let { d ->
                        message = if (dockBounds.contains(d.at)) {
                            actions.setPinned(d.app.id, true)
                            "${d.app.name} is on the dock"
                        } else if (actions.addToDesktop(d.app)) "${d.app.name} is on the desktop" else stillStarting
                    }
                    dragging = null
                    startOpen = false
                },
                onDragCancel = { dragging = null },
                onEnd = actions::end,
                maxHeight = with(density) { viewHeight.toDp() } * (if (vertical) 0.94f else 0.7f),
            )
        }

        // The app being dragged, just above the finger.
        dragging?.let { d ->
            val size = 60.dp
            val half = with(density) { (size / 2).roundToPx() }
            val (container, onContainer) = appColors(d.app)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset(d.at.x.roundToInt() - half, d.at.y.roundToInt() - half * 2) }
                    .size(size)
                    .graphicsLayer {
                        scaleX = 1.1f
                        scaleY = 1.1f
                    }
                    .clip(appShape(d.app))
                    .background(container),
            ) { AppGlyph(d.app, 28.dp, onContainer) }
        }

        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout).padding(top = 16.dp),
        ) {
            Surface(shape = CircleShape, color = colors.inverseSurface, contentColor = colors.inverseOnSurface, shadowElevation = 6.dp) {
                Text(message.orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

private fun startOrigin(vertical: Boolean) = if (vertical) TransformOrigin(0f, 0f) else TransformOrigin(0.5f, 1f)

/** Tries [send] until it gets through (the desktop's helper starts with it), for half a minute. */
private suspend fun retrying(send: suspend () -> Boolean) {
    repeat(15) {
        if (send()) return
        delay(2000)
    }
}

/**
 * While the desktop starts (its window manager not reporting yet), what would otherwise be a
 * black screen says so; for a while at most, in case it never reports.
 */
@Composable
private fun Starting(session: RunningSession?, waiting: Boolean, modifier: Modifier) {
    val patient by produceState(true) {
        delay(25_000)
        value = false
    }
    AnimatedVisibility(visible = waiting && patient && session?.running == true, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Starting the desktop…", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}

@Composable
private fun DesktopIcons(
    items: List<DesktopItem>,
    allApps: List<PermissionManifest>,
    /** Where each icon is, by its path, in this view's pixels. */
    onPlaced: (String, Rect) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)) {
                Text(
                    "Hold an app in the Start menu and drag it here.\nFiles in your Desktop folder show up here too.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
        return
    }
    // Down the side and then across, as a Linux desktop lays them out.
    FlowColumn(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxHeight()) {
        items.forEach { item ->
            key(item.path) {
                Box(Modifier.width(84.dp)) {
                    DesktopIcon(item, allApps.firstOrNull { it.id == item.app }?.takeIf { item.isShortcut }, onPlaced)
                }
            }
        }
    }
}

@Composable
private fun DesktopIcon(item: DesktopItem, app: PermissionManifest?, onPlaced: (String, Rect) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .onGloballyPositioned { onPlaced(item.path, it.boundsInRoot()) }
                .padding(vertical = 6.dp, horizontal = 4.dp)
                .fillMaxWidth(),
        ) {
            if (app != null) {
                val (container, onContainer) = appColors(app)
                Box(Modifier.size(52.dp).clip(appShape(app)).background(container), contentAlignment = Alignment.Center) {
                    AppGlyph(app, 24.dp, onContainer)
                }
            } else {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (item.isFolder) colors.primaryContainer else colors.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            item.isShortcut -> LauncherIcons.Terminal
                            item.isLink -> LauncherIcons.Link
                            else -> fileIcon(item)
                        },
                        null,
                        tint = if (item.isFolder) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // White with a shadow, as on a home screen: readable on any wallpaper.
            Text(
                item.name,
                style = MaterialTheme.typography.labelMedium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 4f)),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A file's icon by what its name says it is. */
private fun fileIcon(item: DesktopItem): ImageVector {
    if (item.isFolder) return LauncherIcons.Folder
    return when (item.name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "xcf" -> LauncherIcons.Image
        "mp3", "ogg", "flac", "wav", "m4a", "opus" -> LauncherIcons.Music
        "mp4", "mkv", "webm", "avi", "mov" -> LauncherIcons.Video
        "zip", "tar", "gz", "xz", "bz2", "7z", "deb", "zst" -> LauncherIcons.Archive
        "txt", "md", "sh", "py", "json", "conf", "log", "csv", "odt", "doc", "docx", "pdf" -> LauncherIcons.TextFile
        else -> LauncherIcons.File
    }
}

@Composable
private fun Dock(
    vertical: Boolean,
    entries: List<DockEntry>,
    active: Long,
    startOpen: Boolean,
    onStart: (Boolean) -> Unit,
    launchable: (PermissionManifest) -> Boolean,
    onLaunch: (PermissionManifest) -> Unit,
    onAddToDesktop: (PermissionManifest) -> Unit,
    showingDesktop: Boolean,
    onShowDesktop: () -> Unit,
    dropHere: Boolean,
    actions: DesktopActions,
    maxLength: Dp,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val shape = RoundedCornerShape(28.dp)
    val grow by animateFloatAsState(if (dropHere) 1.06f else 1f, motion.fastSpatialSpec(), label = "dockDrop")
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    Surface(
        shape = shape,
        color = if (dropHere) colors.primaryContainer.copy(alpha = 0.96f) else colors.surfaceContainer.copy(alpha = 0.94f),
        contentColor = colors.onSurface,
        shadowElevation = 12.dp,
        modifier = modifier
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
            }
            .then(if (vertical) Modifier.heightIn(max = maxLength) else Modifier.widthIn(max = maxLength))
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.35f), shape)
            // Swipe in from the dock, as from a phone's taskbar, for the Start menu.
            .pointerInput(vertical) {
                var travel = 0f
                if (vertical) {
                    detectHorizontalDragGestures(onDragStart = { travel = 0f }) { _, dx ->
                        travel += dx
                        if (travel > threshold) onStart(true)
                    }
                } else {
                    detectVerticalDragGestures(onDragStart = { travel = 0f }) { _, dy ->
                        travel += dy
                        if (travel < -threshold) onStart(true)
                    }
                }
            },
    ) {
        val content = @Composable {
            StartButton(startOpen, compact = vertical) { onStart(!startOpen) }
            if (entries.isNotEmpty()) Separator(vertical)
            entries.forEach { e ->
                key(e.app.id) { DockApp(e, active, launchable(e.app), onLaunch, onAddToDesktop, actions, compact = vertical) }
            }
            Separator(vertical)
            Status(vertical, showingDesktop, onShowDesktop, actions::toggleKeyboard)
        }
        if (vertical) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp),
            ) { content() }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            ) { content() }
        }
    }
}

@Composable
private fun StartButton(open: Boolean, compact: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (open) 16.dp else 24.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "start")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(if (compact) 44.dp else 48.dp)
            .clip(RoundedCornerShape(corner))
            .background(if (open) colors.primary else colors.primaryContainer)
            .clickable(onClickLabel = "Start", onClick = onClick),
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
 * An app on the dock: its icon, with Android's taskbar dash under it while it has windows
 * (longer while one is focused, and a dot more for each further window). Tap to open it, bring
 * its window up or put it away; with several windows, to pick one. Hold for the app's menu.
 */
@Composable
private fun DockApp(
    entry: DockEntry,
    active: Long,
    launchable: Boolean,
    onLaunch: (PermissionManifest) -> Unit,
    onAddToDesktop: (PermissionManifest) -> Unit,
    actions: DesktopActions,
    /** In landscape's rail: a little smaller, the height is short. */
    compact: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val app = entry.app
    val windows = entry.windows
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val focused = windows.any { it.id == active && !it.hidden }
    val (container, onContainer) = appColors(app)
    val dash by animateDpAsState(if (focused) 16.dp else 6.dp, motion.defaultSpatialSpec(), label = "dash")
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClickLabel = app.name,
                    onClick = {
                        when {
                            windows.isEmpty() -> if (launchable) onLaunch(app)
                            windows.size > 1 -> picker = true
                            focused -> actions.minimize(windows[0].id)
                            else -> actions.activate(windows[0].id)
                        }
                    },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(if (compact) 38.dp else 44.dp)
                    .alpha(if (windows.isNotEmpty() && windows.all { it.hidden }) 0.55f else 1f)
                    .clip(appShape(app)).background(container),
            ) {
                AppGlyph(app, if (compact) 19.dp else 22.dp, onContainer)
            }
            Spacer(Modifier.height(if (compact) 3.dp else 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(3.dp)) {
                if (windows.isNotEmpty()) {
                    Box(
                        Modifier.width(dash).fillMaxHeight().clip(CircleShape)
                            .background(if (focused) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.6f)),
                    )
                    repeat((windows.size - 1).coerceAtMost(2)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(colors.onSurfaceVariant.copy(alpha = 0.6f)))
                    }
                }
            }
        }
        // Several windows: which one.
        DropdownMenu(expanded = picker, onDismissRequest = { picker = false; actions.returnFocus() }, shape = RoundedCornerShape(16.dp)) {
            MenuTitle(app.name)
            windows.forEach { w ->
                DropdownMenuItem(
                    text = {
                        Text(
                            w.title.ifBlank { app.name },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (w.id == active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                    },
                    onClick = { picker = false; actions.activate(w.id); actions.returnFocus() },
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false; actions.returnFocus() }, shape = RoundedCornerShape(16.dp)) {
            val only = windows.singleOrNull()
            MenuTitle(only?.title?.ifBlank { null } ?: app.name)
            val choices = buildList<Pair<String, () -> Unit>> {
                if (only != null) {
                    add((if (only.maximized) "Restore" else "Maximize") to { actions.toggleMaximize(only.id) })
                    add("Left half" to { actions.snap(only.id, left = true) })
                    add("Right half" to { actions.snap(only.id, left = false) })
                    if (!only.hidden) add("Minimize" to { actions.minimize(only.id) })
                }
                if (launchable) {
                    add((if (windows.isEmpty()) "Open" else "New window") to { onLaunch(app) })
                    add("Add to desktop" to { onAddToDesktop(app) })
                    add((if (entry.pinned) "Unpin from dock" else "Pin to dock") to { actions.setPinned(app.id, !entry.pinned) })
                }
            }
            choices.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action(); actions.returnFocus() })
            }
            if (windows.isNotEmpty()) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (windows.size == 1) "Close" else "Close all ${windows.size}", color = colors.error) },
                    leadingIcon = { Icon(Icons.Rounded.Close, null, tint = colors.error) },
                    onClick = { menu = false; windows.forEach { actions.close(it.id) } },
                )
            }
        }
    }
}

@Composable
private fun MenuTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The dock's corner of what a phone's status bar has: show desktop, the keyboard, battery and time. */
@Composable
private fun Status(vertical: Boolean, showingDesktop: Boolean, onShowDesktop: () -> Unit, onKeyboard: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onShowDesktop) {
        Icon(
            LauncherIcons.Desktop,
            if (showingDesktop) "Bring windows back" else "Show desktop",
            tint = if (showingDesktop) colors.primary else colors.onSurfaceVariant,
        )
    }
    IconButton(onClick = onKeyboard) { Icon(LauncherIcons.Keyboard, "Keyboard", tint = colors.onSurfaceVariant) }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp)) {
            Battery()
            Spacer(Modifier.height(6.dp))
            Clock(vertical)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Battery()
            Spacer(Modifier.width(8.dp))
            Clock(vertical)
        }
    }
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
        modifier = Modifier.padding(end = if (vertical) 0.dp else 8.dp),
    )
}

/** The phone's battery, as its status bar draws it: a small cell filled to the charge. */
@Composable
private fun Battery() {
    val context = LocalContext.current
    val state by produceState<Pair<Int, Boolean>?>(null) {
        while (true) {
            val i: Intent? = ContextCompat.registerReceiver(
                context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                if (level >= 0) value = level * 100 / scale to charging
            }
            delay(30_000)
        }
    }
    val (percent, charging) = state ?: return
    val colors = MaterialTheme.colorScheme
    val fill = when {
        charging -> colors.primary
        percent <= 15 -> colors.error
        else -> colors.onSurface
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(22.dp).height(11.dp).border(1.5.dp, colors.onSurfaceVariant, RoundedCornerShape(3.dp)).padding(2.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).clip(RoundedCornerShape(1.dp)).background(fill))
        }
        Box(Modifier.width(2.dp).height(5.dp).clip(RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp)).background(colors.onSurfaceVariant))
        Spacer(Modifier.width(4.dp))
        Text("$percent%", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StartMenu(
    apps: List<PermissionManifest>,
    pins: List<String>,
    windows: Int,
    /** Landscape: wider, as tall as the screen allows, more apps to a row. */
    wide: Boolean,
    onLaunch: (PermissionManifest) -> Unit,
    onAddToDesktop: (PermissionManifest) -> Unit,
    onPin: (PermissionManifest, Boolean) -> Unit,
    onDragStart: (PermissionManifest, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onEnd: () -> Unit,
    maxHeight: Dp,
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = remember(apps) { apps.map { it.category }.distinct().sorted() }
    val shown = remember(apps, query, category) {
        searchApps(if (query.isBlank() && category != null) apps.filter { it.category == category } else apps, query)
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        shadowElevation = 16.dp,
        modifier = Modifier.widthIn(max = if (wide) 620.dp else 440.dp).fillMaxWidth().heightIn(max = maxHeight)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(28.dp)),
    ) {
        Column(Modifier.padding(if (wide) 12.dp else 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(if (wide) 44.dp else 52.dp).clip(CircleShape)
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
            if (query.isBlank() && categories.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") }) }
                    items(categories) { c ->
                        FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c) })
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            if (shown.isEmpty()) {
                Text(
                    if (apps.isEmpty()) "No apps yet. Install some from the Store." else "No apps match",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (wide) 80.dp else 84.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(shown, key = { it.id }) { app ->
                    StartTile(app, pinned = app.id in pins, onLaunch, onAddToDesktop, onPin, onDragStart, onDrag, onDragEnd, onDragCancel)
                }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Linux desktop", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (windows) {
                            0 -> "Nothing open · hold an app to drag it out"
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

/**
 * An app in the Start menu. Hold it for its menu; hold and drag it out, as from a phone's app
 * drawer, onto the desktop for a shortcut or onto the dock to pin it.
 */
@Composable
private fun StartTile(
    app: PermissionManifest,
    pinned: Boolean,
    onLaunch: (PermissionManifest) -> Unit,
    onAddToDesktop: (PermissionManifest) -> Unit,
    onPin: (PermissionManifest, Boolean) -> Unit,
    onDragStart: (PermissionManifest, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val far = with(LocalDensity.current) { 16.dp.toPx() }
    Box(
        Modifier
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .pointerInput(app.id) {
                // Held still, the menu; moved far enough while held, a drag instead.
                var start = Offset.Zero
                var travel = Offset.Zero
                var dragging = false
                detectDragGesturesAfterLongPress(
                    onDragStart = { at ->
                        start = at
                        travel = Offset.Zero
                        dragging = false
                        menu = true
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (dragging) {
                            onDrag(amount)
                        } else {
                            travel += amount
                            if (travel.getDistance() > far) {
                                dragging = true
                                menu = false
                                onDragStart(app, origin + start + travel)
                            }
                        }
                    },
                    onDragEnd = { if (dragging) onDragEnd() },
                    onDragCancel = { if (dragging) onDragCancel() },
                )
            },
    ) {
        CompactAppTile(app) { onLaunch(app) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
            MenuTitle(app.name)
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onLaunch(app) })
            DropdownMenuItem(text = { Text("Add to desktop") }, onClick = { menu = false; onAddToDesktop(app) })
            DropdownMenuItem(text = { Text(if (pinned) "Unpin from dock" else "Pin to dock") }, onClick = { menu = false; onPin(app, !pinned) })
        }
    }
}
