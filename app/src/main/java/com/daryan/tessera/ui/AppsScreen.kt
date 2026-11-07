@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.TextButton
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.daryan.tessera.HomeIconSize
import com.daryan.tessera.HomeLayout
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daryan.tessera.LauncherTab
import com.daryan.tessera.SHORTCUTS_TAB
import com.daryan.tessera.Shortcut
import com.daryan.tessera.container.PermissionManifest
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabPills(
    tabs: List<LauncherTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, tab ->
            val isSelected = i == selected
            val container by animateColorAsState(
                if (isSelected) colors.secondaryContainer else colors.surfaceContainerHighest,
                motion.defaultEffectsSpec(), label = "pill",
            )
            val content by animateColorAsState(
                if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                motion.defaultEffectsSpec(), label = "pillText",
            )
            Surface(
                shape = CircleShape,
                color = container,
                contentColor = content,
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { onSelect(i) },
                        onLongClick = if (tab.custom) ({ onRemove(tab.title) }) else null,
                    ),
            ) {
                Text(
                    tab.title,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .animateContentSize(motion.fastSpatialSpec())
                        // The selected pill grows a little, like the expressive button group.
                        .padding(horizontal = if (isSelected) 20.dp else 16.dp, vertical = 10.dp),
                )
            }
        }
        Surface(
            onClick = onAdd,
            shape = CircleShape,
            color = colors.surfaceContainerHighest,
            contentColor = colors.onSurfaceVariant,
        ) {
            Icon(Icons.Rounded.Add, "New tab", Modifier.padding(8.dp).size(20.dp))
        }
    }
}

@Composable
fun AppsPager(
    tabs: List<LauncherTab>,
    pagerState: PagerState,
    padding: PaddingValues,
    onOpen: (PermissionManifest) -> Unit,
    pinned: List<String>,
    favorites: List<String>,
    onTogglePin: (PermissionManifest) -> Unit,
    onToggleFavorite: (PermissionManifest) -> Unit,
    onUninstall: (PermissionManifest) -> Unit,
    onEditShortcut: (PermissionManifest) -> Unit,
    onNewShortcut: () -> Unit,
    /** Opens the trackpad, which drives a PC rather than anything in the container. */
    onTrackpad: () -> Unit,
    /** Changes the command an app runs; shortcuts edit theirs in [onEditShortcut] instead. */
    onEditExec: (PermissionManifest) -> Unit,
    /** Whether [onGraphics] has anything for windowed apps (theming, or the phone's GPU driver). */
    graphics: Boolean,
    onGraphics: (PermissionManifest) -> Unit,
    layout: HomeLayout,
    /** Whether icons are being dragged into place. */
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    /** A tab's apps, in the order they were dragged to. */
    onReorder: (String, List<String>) -> Unit,
    onHomeSettings: () -> Unit,
    onIconColor: (PermissionManifest) -> Unit,
    onHide: (PermissionManifest) -> Unit,
) {
    val context = LocalContext.current
    val homeScreen = remember { HomeShortcuts.supported(context) }
    val motion = MaterialTheme.motionScheme
    val tile: @Composable (PermissionManifest, Int?, Modifier) -> Unit = { app, rise, modifier ->
        AppTile(
            app, rise,
            pinned = app.id in pinned,
            favorite = app.id in favorites,
            size = layout.iconSize,
            showLabels = layout.showLabels,
            editing = editing,
            onCustomize = onHomeSettings,
            onIconColor = { onIconColor(app) },
            onHide = { onHide(app) },
            onClick = { onOpen(app) },
            onTogglePin = { onTogglePin(app) },
            onToggleFavorite = { onToggleFavorite(app) },
            onUninstall = { onUninstall(app) },
            onEdit = if (Shortcut.isShortcut(app)) ({ onEditShortcut(app) }) else null,
            // A shortcut's "Edit" already is its command; everything else gets this.
            onEditExec = if (Shortcut.isShortcut(app)) null else ({ onEditExec(app) }),
            onGraphics = if (graphics && app.gui) ({ onGraphics(app) }) else null,
            homeScreen = homeScreen,
            modifier = modifier,
        )
    }
    Box(Modifier.fillMaxSize()) {
        TabsPager(tabs, pagerState, padding, tile, onTrackpad, onNewShortcut, layout, editing, onEditing, onReorder, onHomeSettings)
        if (editing) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = padding.calculateBottomPadding() + 12.dp, start = 16.dp, end = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Text("Hold an icon and drag it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.size(12.dp))
                    TextButton(onClick = { onEditing(false) }) { Text("Done") }
                }
            }
        }
    }
}

/**
 * Apps matching [query], best first: names that start with it, then names with a word that
 * does, then names containing it, then a match in the description, category, packages or command.
 */
fun searchApps(apps: List<PermissionManifest>, query: String): List<PermissionManifest> {
    val q = query.trim()
    if (q.isEmpty()) return apps.sortedBy { it.name.lowercase() }
    fun rank(a: PermissionManifest): Int? {
        val name = a.name.lowercase()
        val lq = q.lowercase()
        return when {
            name.startsWith(lq) -> 0
            name.split(' ', '-', '_', '.').any { it.startsWith(lq) } -> 1
            lq in name -> 2
            a.description.contains(q, ignoreCase = true) -> 3
            a.category.contains(q, ignoreCase = true) -> 4
            a.packages.any { it.contains(q, ignoreCase = true) } -> 5
            a.exec.firstOrNull()?.substringAfterLast('/')?.contains(q, ignoreCase = true) == true -> 6
            else -> null
        }
    }
    return apps.mapNotNull { a -> rank(a)?.let { it to a } }
        .sortedWith(compareBy<Pair<Int, PermissionManifest>> { it.first }.thenBy { it.second.name.lowercase() })
        .map { it.second }
}

/**
 * The Apps tab's bar: a search pill between the menu and the tab's actions, as Android's own
 * apps have it, opening to full-screen results where the tiles slide into place as the query
 * narrows. The keyboard's search key opens the best match.
 */
@Composable
fun AppsSearchBar(
    state: SearchBarState,
    query: TextFieldState,
    count: Int,
    results: List<PermissionManifest>,
    onOpen: (PermissionManifest) -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val expanded = state.targetValue == SearchBarValue.Expanded
    // Closed, it starts over next time.
    LaunchedEffect(state.currentValue) { if (state.currentValue == SearchBarValue.Collapsed) query.clearText() }
    val open: (PermissionManifest) -> Unit = { app ->
        scope.launch { state.animateToCollapsed() }
        onOpen(app)
    }
    val input = @Composable {
        SearchBarDefaults.InputField(
            textFieldState = query,
            searchBarState = state,
            onSearch = { results.firstOrNull()?.let(open) },
            placeholder = { Text(if (count == 1) "Search 1 app" else "Search $count apps") },
            leadingIcon = {
                if (expanded) {
                    IconButton(onClick = { scope.launch { state.animateToCollapsed() } }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search")
                    }
                } else {
                    Icon(LauncherIcons.Search, null)
                }
            },
            trailingIcon = {
                if (expanded && query.text.isNotEmpty()) {
                    IconButton(onClick = { query.clearText() }) { Icon(Icons.Rounded.Close, "Clear") }
                }
            },
        )
    }
    AppBarWithSearch(
        state = state,
        inputField = input,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = SearchBarDefaults.appBarWithSearchColors(appBarContainerColor = Color.Transparent),
    )
    ExpandedFullScreenContainedSearchBar(state = state, inputField = input) {
        if (results.isEmpty()) {
            EmptyState(title = "No apps match", body = "Apps not installed yet are in the Store.", padding = PaddingValues(0.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { it.id }) { app -> CompactAppTile(app, Modifier.animateItem()) { open(app) } }
            }
        }
    }
}

@Composable
private fun TabsPager(
    tabs: List<LauncherTab>,
    pagerState: PagerState,
    padding: PaddingValues,
    tile: @Composable (PermissionManifest, Int?, Modifier) -> Unit,
    onTrackpad: () -> Unit,
    onNewShortcut: () -> Unit,
    layout: HomeLayout,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onReorder: (String, List<String>) -> Unit,
    onHomeSettings: () -> Unit,
) {
    // Tabs whose tiles already rose in: coming back to one (or swiping past it) shouldn't replay it.
    val shown = remember { mutableSetOf<String>() }
    // Holding the home screen itself, where no icon is: where to open the menu.
    var heldAt by remember { mutableStateOf<Offset?>(null) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val onHeld: (Offset) -> Unit = {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        heldAt = it
    }
    Box(Modifier.fillMaxSize()) {
    // The tabs either side are composed ahead, so a swipe doesn't build a whole grid mid-gesture.
    // The page count can run a frame ahead of [tabs] when a tab comes or goes (an app in a new
    // category): until this recomposes, a page past the end has no tab, rather than a crash.
    HorizontalPager(
        pagerState,
        Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        // A tab at a time while arranging: a drag toward the edge shouldn't turn the page.
        userScrollEnabled = !editing,
        key = { tabs.getOrNull(it)?.title ?: "page-$it" },
    ) { page ->
        val tab = tabs.getOrNull(page) ?: return@HorizontalPager
        val animate = remember(tab.title) { shown.add(tab.title) && page == pagerState.currentPage }
        // Only the first screenful rises in; tiles scrolled to later just appear.
        val enteredAt = remember(tab.title) { SystemClock.uptimeMillis() }
        val shortcuts = tab.title == SHORTCUTS_TAB && !tab.custom
        if (tab.apps.isEmpty() && !shortcuts) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onLongPress = onHeld) }) {
                EmptyState(
                    title = "Nothing here yet",
                    body = if (tab.custom) "Open an app and add it to “${tab.title}”." else "No apps in this category.",
                    padding = padding,
                )
            }
        } else {
            AppGrid(
                tab = tab,
                layout = layout,
                padding = padding,
                editing = editing,
                onHeld = onHeld,
                onReorder = { onReorder(tab.title, it) },
                tile = { app, index, modifier ->
                    tile(app, index.takeIf { animate && SystemClock.uptimeMillis() - enteredAt < 1000 }, modifier)
                },
                extras = {
                    if (shortcuts) {
                        item(key = "trackpad") { TrackpadTile(onTrackpad, Modifier.animateItem()) }
                        item(key = "new-shortcut") { NewShortcutTile(onNewShortcut, Modifier.animateItem()) }
                    }
                },
            )
        }
    }
    heldAt?.let { at ->
        Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.size(1.dp)) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { heldAt = null },
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    text = { Text("Edit layout") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { heldAt = null; onEditing(true) },
                )
                DropdownMenuItem(
                    text = { Text("Home settings") },
                    leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                    onClick = { heldAt = null; onHomeSettings() },
                )
            }
        }
    }
    }
}

/**
 * One tab's icons. Holding the empty space between them calls [onHeld]; while [editing], holding
 * an icon picks it up instead, and the rest make way as it's dragged. The order goes to
 * [onReorder] when it's let go, so nothing is saved for a drag that's cancelled.
 */
@Composable
private fun AppGrid(
    tab: LauncherTab,
    layout: HomeLayout,
    padding: PaddingValues,
    editing: Boolean,
    onHeld: (Offset) -> Unit,
    onReorder: (List<String>) -> Unit,
    tile: @Composable (PermissionManifest, Int, Modifier) -> Unit,
    extras: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    val grid = rememberLazyGridState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // A copy to move icons around in while dragging, until the saved order catches up.
    val apps = remember(tab.apps) { tab.apps.toMutableStateList() }
    var dragged by remember { mutableStateOf<String?>(null) }
    // The finger, and where on the icon it took hold, in the grid's own coordinates.
    var finger by remember { mutableStateOf(Offset.Zero) }
    var grip by remember { mutableStateOf(Offset.Zero) }
    val startPad = with(density) { GRID_SIDE_PADDING.toPx() }
    val topPad = with(density) { (padding.calculateTopPadding() + GRID_TOP_PADDING).toPx() }
    // Item offsets leave out the content padding; pointers don't.
    fun inGrid(at: Offset) = at - Offset(startPad, topPad)
    fun itemAt(at: Offset) = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        at.x >= item.offset.x && at.x < item.offset.x + item.size.width &&
            at.y >= item.offset.y && at.y < item.offset.y + item.size.height
    }
    fun moveTo(target: Any?) {
        val from = apps.indexOfFirst { it.id == dragged }
        val to = apps.indexOfFirst { it.id == target }
        if (from >= 0 && to >= 0 && from != to) apps.add(to, apps.removeAt(from))
    }
    // Near the top or bottom edge the grid scrolls on its own, for icons a screen away.
    LaunchedEffect(dragged) {
        if (dragged == null) return@LaunchedEffect
        val edge = with(density) { 96.dp.toPx() }
        val step = with(density) { 6.dp.toPx() }
        while (true) {
            withFrameMillis { }
            val height = grid.layoutInfo.viewportSize.height
            val y = finger.y + topPad
            val delta = when {
                y < edge + topPad -> -step
                y > height - edge - with(density) { padding.calculateBottomPadding().toPx() } -> step
                else -> 0f
            }
            if (delta != 0f) {
                grid.dispatchRawDelta(delta)
                moveTo(itemAt(finger)?.key)
            }
        }
    }
    LazyVerticalGrid(
        state = grid,
        columns = if (layout.columns > 0) GridCells.Fixed(layout.columns) else GridCells.Adaptive(minSize = 104.dp),
        // Held still by the drag, which moves the icon rather than the grid.
        userScrollEnabled = dragged == null,
        contentPadding = PaddingValues(
            start = GRID_SIDE_PADDING,
            end = GRID_SIDE_PADDING,
            top = padding.calculateTopPadding() + GRID_TOP_PADDING,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            // Keyed on the list: each saved order makes a new one, which a handler still holding
            // the old one would rearrange out of sight, and then save.
            .pointerInput(editing, apps) {
                if (editing) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            val item = itemAt(inGrid(at))
                            if (item != null && apps.any { it.id == item.key }) {
                                finger = inGrid(at)
                                grip = finger - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
                                dragged = item.key as String
                            }
                        },
                        onDrag = { change, amount ->
                            if (dragged != null) {
                                change.consume()
                                finger += amount
                                moveTo(itemAt(finger)?.key)
                            }
                        },
                        onDragEnd = {
                            if (dragged != null) onReorder(apps.map { it.id })
                            dragged = null
                        },
                        onDragCancel = {
                            dragged = null
                            apps.clear()
                            apps.addAll(tab.apps)
                        },
                    )
                } else {
                    detectTapGestures(onLongPress = { at -> if (itemAt(inGrid(at)) == null) onHeld(at) })
                }
            },
    ) {
        itemsIndexed(apps, key = { _, app -> app.id }) { index, app ->
            val lifted = dragged == app.id
            tile(
                app, index,
                if (lifted) {
                    Modifier.zIndex(1f).graphicsLayer {
                        // Wherever the grid has put it by now, it's drawn under the finger.
                        val at = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == app.id }?.offset
                        if (at != null) {
                            translationX = finger.x - grip.x - at.x
                            translationY = finger.y - grip.y - at.y
                        }
                        scaleX = 1.08f
                        scaleY = 1.08f
                    }
                } else Modifier.animateItem(),
            )
        }
        extras()
    }
}

private val GRID_SIDE_PADDING = 12.dp
private val GRID_TOP_PADDING = 8.dp

/** One app in the grid. Holding it opens its menu: pin, favorite, home screen, graphics, command, uninstall. */
@Composable
private fun AppTile(
    app: PermissionManifest,
    /** Its place in the grid, for the staggered entrance; null shows it at once. */
    index: Int?,
    pinned: Boolean,
    favorite: Boolean,
    size: HomeIconSize,
    showLabels: Boolean,
    /** While icons are being arranged, holding one lifts it (the grid's doing): no opening, no menu. */
    editing: Boolean,
    onCustomize: () -> Unit,
    onIconColor: () -> Unit,
    onHide: () -> Unit,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUninstall: () -> Unit,
    /** For shortcuts: edit it (and Delete replaces Uninstall). */
    onEdit: (() -> Unit)?,
    /** For everything else: change the command it runs. */
    onEditExec: (() -> Unit)?,
    /** For windowed apps with the GPU driver: their graphics settings. */
    onGraphics: (() -> Unit)?,
    /** Whether the phone's home screen takes icons. */
    homeScreen: Boolean,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, motion.fastSpatialSpec(), label = "press")
    val spin by animateFloatAsState(if (pressed) 30f else 0f, motion.defaultSpatialSpec(), label = "spin")

    // Staggered entrance: tiles rise and fade in one after another.
    val appear = remember { Animatable(if (index == null) 1f else 0f) }
    if (index != null) {
        LaunchedEffect(Unit) {
            delay(index * 40L)
            appear.animateTo(1f, motion.slowSpatialSpec())
        }
    }

    val (container, onContainer) = appColors(app)
    Box(modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * 48.dp.toPx()
            }
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                enabled = !editing,
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    menu = true
                },
            )
            .padding(vertical = 14.dp, horizontal = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size.iconDp.dp)
                .graphicsLayer { rotationZ = spin }
                .clip(appShape(app))
                .background(container),
        ) {
            AppGlyph(app, size.glyphDp.dp, onContainer, Modifier.graphicsLayer { rotationZ = -spin })
        }
        if (pinned || favorite) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val tint = MaterialTheme.colorScheme.primary
                if (pinned) Icon(LauncherIcons.Pin, "Pinned", Modifier.size(14.dp), tint = tint)
                if (favorite) Icon(Icons.Rounded.Star, "Favorite", Modifier.size(14.dp), tint = tint)
            }
            Spacer(Modifier.height(2.dp))
        } else {
            Spacer(Modifier.height(10.dp))
        }
        if (showLabels) {
            Text(
                app.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                if (app.gui) "Window" else "Terminal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    DropdownMenu(
        expanded = menu,
        onDismissRequest = { menu = false },
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        DropdownMenuItem(
            text = { Text(if (pinned) "Unpin" else "Pin to top") },
            leadingIcon = { Icon(LauncherIcons.Pin, null) },
            onClick = { menu = false; onTogglePin() },
        )
        DropdownMenuItem(
            text = { Text(if (favorite) "Remove from favorites" else "Add to favorites") },
            leadingIcon = { Icon(Icons.Rounded.Star, null) },
            onClick = { menu = false; onToggleFavorite() },
        )
        if (homeScreen) {
            val context = LocalContext.current
            val homeIcon = rememberHomeIcon(app)
            DropdownMenuItem(
                text = { Text("Add to home screen") },
                leadingIcon = { Icon(Icons.Rounded.Home, null) },
                onClick = { menu = false; HomeShortcuts.pin(context, app, homeIcon()) },
            )
        }
        DropdownMenuItem(
            text = { Text("Icon color") },
            leadingIcon = { Icon(Icons.Rounded.Create, null) },
            onClick = { menu = false; onIconColor() },
        )
        DropdownMenuItem(
            text = { Text("Hide") },
            leadingIcon = { Icon(Icons.Rounded.Close, null) },
            onClick = { menu = false; onHide() },
        )
        DropdownMenuItem(
            text = { Text("Customize home") },
            leadingIcon = { Icon(Icons.Rounded.Settings, null) },
            onClick = { menu = false; onCustomize() },
        )
        if (onGraphics != null) {
            DropdownMenuItem(
                text = { Text("Look and graphics") },
                leadingIcon = { Icon(LauncherIcons.Monitor, null) },
                onClick = { menu = false; onGraphics() },
            )
        }
        if (onEditExec != null) {
            DropdownMenuItem(
                text = { Text("Edit command") },
                leadingIcon = { Icon(LauncherIcons.Code, null) },
                onClick = { menu = false; onEditExec() },
            )
        }
        HorizontalDivider()
        if (onEdit != null) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                onClick = { menu = false; onEdit() },
            )
        }
        DropdownMenuItem(
            text = { Text(if (onEdit != null) "Delete" else "Uninstall") },
            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
            onClick = { menu = false; onUninstall() },
        )
    }
    }
}

/** The last tile in the Shortcuts tab: make a new shortcut. */
@Composable
private fun NewShortcutTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(10.dp))
        Text("New shortcut", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Text("A command", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The phone as a mouse and keyboard for a PC: not a Linux app, so it gets a tile of its own. */
@Composable
private fun TrackpadTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Icon(LauncherIcons.Mouse, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(10.dp))
        Text("Trackpad", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Text("For a PC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(title: String, body: String, padding: PaddingValues) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .clip(MaterialShapes.SoftBurst.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(
                LauncherIcons.Apps, null, Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
