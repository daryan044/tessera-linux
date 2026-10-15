// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.tessera.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.daryan.tessera.container.ConfigFile
import com.daryan.tessera.container.ConfigKind
import com.daryan.tessera.container.ConfigStore
import com.daryan.tessera.files.ArchiveFormat
import com.daryan.tessera.files.FileBrowser
import com.daryan.tessera.files.FileEntry
import com.daryan.tessera.files.FileSort
import com.daryan.tessera.files.FileView
import com.daryan.tessera.files.GuestFiles
import com.daryan.tessera.files.Place
import com.daryan.tessera.files.PlaceKind
import com.daryan.tessera.files.Usage
import kotlinx.coroutines.launch
import java.io.File

/** Dialogs and the editor the Files tab opens; shared by its top bar, its page and LauncherApp. */
class FilesUi {
    var dialog by mutableStateOf<FilesDialog?>(null)
    /** The file whose Open with sheet is up. */
    var opening by mutableStateOf<FileEntry?>(null)
    /** The text file open in the editor. */
    var editing by mutableStateOf<FileEntry?>(null)
    /** Set while a file is being copied out to open it. */
    var busy by mutableStateOf<String?>(null)
}

sealed interface FilesDialog {
    data object NewFolder : FilesDialog
    data object NewFile : FilesDialog
    data object GoTo : FilesDialog
    data class Rename(val entry: FileEntry) : FilesDialog
    data class Delete(val entries: List<FileEntry>) : FilesDialog
    data class Properties(val entry: FileEntry) : FilesDialog
    data class Compress(val entries: List<FileEntry>) : FilesDialog
    data class Extract(val entry: FileEntry) : FilesDialog
}

// ---- Top bar ----

@Composable
fun FilesTopBar(
    b: FileBrowser,
    ui: FilesUi,
    onMenu: () -> Unit,
    onMessage: (String) -> Unit,
    onTerminalHere: (String) -> Unit,
    /** Whether the engine can run as real root (chroot mode); PRoot's root is make-believe. */
    realRoot: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inFolder = b.dir != FileBrowser.PLACES
    val motion = MaterialTheme.motionScheme
    // Browsing, searching and selecting each have their own bar; one morphs into the next.
    val barMode = when {
        b.selection.isNotEmpty() -> BarMode.SELECT
        b.query != null -> BarMode.SEARCH
        else -> BarMode.BROWSE
    }
    AnimatedContent(
        targetState = barMode,
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { -it / 3 })
                .togetherWith(fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { it / 4 })
        },
        label = "filesBar",
    ) { mode ->
    when (mode) {
        BarMode.SELECT -> {
            val selected = b.selected()
            var more by remember { mutableStateOf(false) }
            // Leaving, the bar keeps the last count rather than reading "0 selected".
            val count = b.selection.size
            val last = remember { mutableIntStateOf(count) }
            SideEffect { if (count > 0) last.intValue = count }
            TopAppBar(
                navigationIcon = { IconButton(onClick = { b.selection = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") } },
                title = {
                    AnimatedContent(
                        targetState = if (count > 0) count else last.intValue,
                        transitionSpec = {
                            val up = targetState > initialState
                            (slideInVertically(motion.fastSpatialSpec()) { if (up) it else -it } + fadeIn(motion.fastEffectsSpec()))
                                .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { if (up) -it else it } + fadeOut(motion.fastEffectsSpec()))
                        },
                        label = "selectedCount",
                    ) { n -> Text("$n selected") }
                },
                actions = {
                    IconButton(onClick = b::selectAll) { Icon(LauncherIcons.SelectAll, "Select all") }
                    IconButton(onClick = { b.copySelection(cut = false); onMessage("Copied. Open a folder and paste") }) { Icon(LauncherIcons.Copy, "Copy") }
                    IconButton(onClick = { b.copySelection(cut = true); onMessage("Cut. Open a folder and paste") }) { Icon(LauncherIcons.Cut, "Cut") }
                    IconButton(onClick = { ui.dialog = FilesDialog.Delete(selected) }) { Icon(Icons.Rounded.Delete, "Delete") }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            val one = selected.singleOrNull()
                            if (one != null) {
                                MenuItem("Rename", Icons.Rounded.Edit) { more = false; ui.dialog = FilesDialog.Rename(one) }
                                MenuItem("Properties", Icons.Rounded.Info) { more = false; ui.dialog = FilesDialog.Properties(one) }
                            }
                            if (selected.any { !it.isDir }) {
                                MenuItem("Share", Icons.Rounded.Share) {
                                    more = false
                                    scope.launch {
                                        ui.busy = "Getting files ready"
                                        runCatching { b.export(selected) }
                                            .onSuccess { share(context, it, onMessage) }
                                            .onFailure { onMessage(it.message ?: "Couldn't share") }
                                        ui.busy = null
                                    }
                                }
                            }
                            MenuItem("Compress", LauncherIcons.Archive) { more = false; ui.dialog = FilesDialog.Compress(selected) }
                            MenuItem("Copy path", LauncherIcons.Link) {
                                more = false
                                copyText(context, selected.joinToString("\n") { it.path })
                                onMessage(if (selected.size == 1) "Path copied" else "Paths copied")
                            }
                            if (one?.isDir == true) {
                                MenuItem("Open terminal here", LauncherIcons.Terminal) { more = false; onTerminalHere(one.path) }
                            }
                        }
                    }
                },
            )
        }
        BarMode.SEARCH -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            TopAppBar(
                navigationIcon = { IconButton(onClick = b::closeSearch) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
                title = {
                    val q = b.query.orEmpty()
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (q.isEmpty()) {
                            Text(
                                "Search ${b.dir.substringAfterLast('/').ifEmpty { "/" }}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = q,
                            onValueChange = b::search,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                },
                actions = {
                    if (!b.query.isNullOrEmpty()) IconButton(onClick = { b.search("") }) { Icon(Icons.Rounded.Close, "Clear") }
                },
            )
        }
        BarMode.BROWSE -> {
            var more by remember { mutableStateOf(false) }
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Rounded.Menu, "Configuration") } },
                title = {
                    val title = if (inFolder) prettyName(b.dir, b.dir.substringAfterLast('/').ifEmpty { "Linux" }) else "Files"
                    AnimatedContent(
                        targetState = title,
                        transitionSpec = {
                            (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 2 })
                                .togetherWith(fadeOut(motion.fastEffectsSpec()))
                        },
                        label = "folderTitle",
                    ) { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                subtitle = { Text(if (inFolder) prettyPath(b.dir) else "Android and Linux", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (inFolder) {
                        IconButton(onClick = b::openSearch) { Icon(LauncherIcons.Search, "Search") }
                        IconButton(onClick = { b.showAs(if (b.view == FileView.LIST) FileView.GRID else FileView.LIST) }) {
                            AnimatedContent(
                                targetState = b.view,
                                transitionSpec = {
                                    (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.5f))
                                        .togetherWith(fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.5f))
                                },
                                label = "viewToggle",
                            ) { v ->
                                if (v == FileView.LIST) Icon(LauncherIcons.Grid, "Show as grid") else Icon(LauncherIcons.Rows, "Show as list")
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            if (inFolder) {
                                Text(
                                    "Sort by",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                FileSort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.label + if (b.sort == s) (if (b.descending) "  ↓" else "  ↑") else "") },
                                        leadingIcon = { RadioButton(selected = b.sort == s, onClick = null) },
                                        onClick = { b.sortBy(s) },
                                    )
                                }
                                HorizontalDivider()
                                CheckItem("Folders first", b.foldersFirst) { b.groupFolders(it) }
                            }
                            CheckItem("Show hidden files", b.showHidden) { b.showHiddenFiles(it) }
                            CheckItem(if (realRoot) "Browse as root" else "Browse as root (PRoot)", b.root) { more = false; b.useRoot(it) }
                            if (inFolder) {
                                HorizontalDivider()
                                val marked = b.dir in b.bookmarks
                                MenuItem(if (marked) "Remove bookmark" else "Bookmark this folder", LauncherIcons.Bookmark) {
                                    more = false
                                    b.toggleBookmark()
                                    onMessage(if (marked) "Bookmark removed" else "Added to Places")
                                }
                                MenuItem("Open terminal here", LauncherIcons.Terminal) { more = false; onTerminalHere(b.dir) }
                                MenuItem("Copy path", LauncherIcons.Link) { more = false; copyText(context, b.dir); onMessage("Path copied") }
                            }
                            MenuItem("Go to folder…", LauncherIcons.Folder) { more = false; ui.dialog = FilesDialog.GoTo }
                            if (inFolder) MenuItem("Refresh", Icons.Rounded.Refresh) { more = false; b.reload() }
                        }
                    }
                },
            )
        }
    }
    }
}

private enum class BarMode { BROWSE, SEARCH, SELECT }

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun CheckItem(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}

// ---- Page ----

@Composable
fun FilesScreen(
    b: FileBrowser,
    ui: FilesUi,
    padding: PaddingValues,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler(enabled = b.query != null || b.selection.isNotEmpty() || b.canGoBack) { b.back() }

    // Android's storage needs the storage permission, for PRoot and chroot's user alike.
    var pendingPlace by remember { mutableStateOf<String?>(null) }
    val storageRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val path = pendingPlace
        pendingPlace = null
        if (path != null) {
            if (result.values.all { it }) b.open(path) else onMessage("Allow storage access to see the phone's files")
        }
    }
    val openPlace: (String) -> Unit = { path ->
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (GuestFiles.onAndroid(path) && !granted) {
            pendingPlace = path
            storageRequest.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else {
            b.open(path)
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) b.import(uris) { onMessage(it ?: "Added ${uris.size} from the phone") }
    }

    val openEntry: (FileEntry) -> Unit = { e ->
        when {
            e.isDir -> b.open(e.path)
            e.broken -> onMessage("${e.name} points to ${e.linkTarget}, which isn't there")
            else -> ui.opening = e
        }
    }

    val motion = MaterialTheme.motionScheme
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        AnimatedVisibility(
            visible = b.dir != FileBrowser.PLACES,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            PathBar(b, openPlace)
        }
        AnimatedVisibility(
            visible = b.query != null && b.dir != FileBrowser.PLACES,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = b.deepSearch,
                    onClick = { b.setDeep(!b.deepSearch) },
                    label = { Text("Include subfolders") },
                    leadingIcon = if (b.deepSearch) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                )
                AnimatedVisibility(b.searching, enter = fadeIn(motion.defaultEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(12.dp))
                        LoadingIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Searching", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val op = b.operation
        val status = op?.title ?: ui.busy
        // Keeps its words while it folds away.
        val shownStatus = remember { mutableStateOf(status) }.apply { if (status != null) value = status }
        AnimatedVisibility(
            visible = status != null,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(shownStatus.value.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    val p = op?.progress
                    if (p != null) {
                        val eased by animateFloatAsState(p, motion.defaultEffectsSpec(), label = "progress")
                        LinearWavyProgressIndicator(progress = { eased }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (op != null) TextButton(onClick = b::cancelOperation) { Text("Stop") } else Spacer(Modifier.height(48.dp))
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val bottom = padding.calculateBottomPadding()
            // Material's shared axis: deeper slides in from the end, back up from the start.
            AnimatedContent(
                targetState = b.dir,
                transitionSpec = {
                    val deeper = initialState == FileBrowser.PLACES ||
                        (targetState != FileBrowser.PLACES && targetState.startsWith(initialState.trimEnd('/') + "/"))
                    val d = if (deeper) 1 else -1
                    (slideInHorizontally(motion.defaultSpatialSpec()) { d * it / 4 } + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(slideOutHorizontally(motion.fastSpatialSpec()) { -d * it / 8 } + fadeOut(motion.fastEffectsSpec()))
                },
                label = "folder",
                modifier = Modifier.fillMaxSize(),
            ) { d ->
                if (d == FileBrowser.PLACES) PlacesPage(b, bottom, openPlace)
                else FolderPage(b, d, bottom, openEntry)
            }
            // The button for new things, and what's waiting to be pasted, above the dock.
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = bottom + 8.dp, end = 16.dp, start = 16.dp),
            ) {
                AnimatedVisibility(
                    visible = b.dir != FileBrowser.PLACES && b.selection.isEmpty(),
                    enter = scaleIn(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                    exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                ) {
                    NewButton(ui, onImport = { importPicker.launch(arrayOf("*/*")) })
                }
                val clip = b.clip
                val shownClip = remember { mutableStateOf(clip) }.apply { if (clip != null) value = clip }
                AnimatedVisibility(
                    visible = clip != null && b.dir != FileBrowser.PLACES,
                    enter = slideInVertically(motion.defaultSpatialSpec()) { it * 2 } + fadeIn(motion.defaultEffectsSpec()),
                    exit = slideOutVertically(motion.fastSpatialSpec()) { it * 2 } + fadeOut(motion.fastEffectsSpec()),
                ) {
                    shownClip.value?.let { c ->
                        PasteBar(
                            text = "${if (c.cut) "Move" else "Copy"} ${if (c.paths.size == 1) c.paths[0].substringAfterLast('/') else "${c.paths.size} items"}",
                            onCancel = { b.clip = null },
                            onPaste = { b.paste { onMessage(it ?: if (c.cut) "Moved" else "Copied") } },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** What a folder page shows; kept by the page sliding away so it doesn't show the next one's files. */
private data class FolderShown(val items: List<FileEntry>, val loading: Boolean, val error: String?, val view: FileView)

@Composable
private fun FolderPage(b: FileBrowser, dir: String, bottom: androidx.compose.ui.unit.Dp, onOpen: (FileEntry) -> Unit) {
    val motion = MaterialTheme.motionScheme
    val live = dir == b.dir
    val kept = remember { mutableStateOf<FolderShown?>(null) }
    val now = if (live) FolderShown(b.visible(), b.loading && b.entries.isEmpty(), b.error, b.view)
    else kept.value ?: FolderShown(emptyList(), false, null, b.view)
    if (live) SideEffect { kept.value = now }
    val refresh = rememberPullToRefreshState()
    val refreshing = live && b.loading && b.entries.isNotEmpty()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = b::reload,
        state = refresh,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(state = refresh, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter))
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        val kind = when {
            now.loading -> 0
            now.error != null -> 1
            now.items.isEmpty() -> 2
            now.view == FileView.GRID -> 3
            else -> 4
        }
        AnimatedContent(
            targetState = kind,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
            },
            label = "folderState",
            modifier = Modifier.fillMaxSize(),
        ) { k ->
            when (k) {
                0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                1 -> ErrorState(b, now.error.orEmpty())
                2 -> EmptyState(b)
                3 -> FileGrid(b, now.items, bottom, onOpen)
                else -> FileList(b, now.items, bottom, onOpen)
            }
        }
    }
}

/** Material 3 Expressive's FAB menu: + turns to × and the choices rise out of it. */
@Composable
private fun NewButton(ui: FilesUi, onImport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    BackHandler(enabled = open) { open = false }
    FloatingActionButtonMenu(
        expanded = open,
        button = {
            ToggleFloatingActionButton(checked = open, onCheckedChange = { open = it }) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add } }
                Icon(icon, if (open) "Close" else "New", modifier = Modifier.animateIcon({ checkedProgress }))
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { open = false; onImport() },
            icon = { Icon(LauncherIcons.Upload, null) },
            text = { Text("Add from the phone") },
        )
        FloatingActionButtonMenuItem(
            onClick = { open = false; ui.dialog = FilesDialog.NewFile },
            icon = { Icon(LauncherIcons.File, null) },
            text = { Text("New file") },
        )
        FloatingActionButtonMenuItem(
            onClick = { open = false; ui.dialog = FilesDialog.NewFolder },
            icon = { Icon(LauncherIcons.NewFolder, null) },
            text = { Text("New folder") },
        )
    }
}

@Composable
private fun PasteBar(text: String, onCancel: () -> Unit, onPaste: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
            Icon(LauncherIcons.Paste, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.inverseOnSurface) }
            FilledTonalButton(onClick = onPaste) { Text("Paste here") }
        }
    }
}

/** The folder's path as tappable steps, from the place it's in; new steps grow in. */
@Composable
private fun PathBar(b: FileBrowser, onOpen: (String) -> Unit) {
    val motion = MaterialTheme.motionScheme
    val crumbs = remember(b.dir, b.storage) { crumbs(b.dir, b.storage) }
    val scroll = rememberScrollState()
    LaunchedEffect(b.dir) { scroll.animateScrollTo(scroll.maxValue, motion.defaultSpatialSpec()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Crumb("Places", Icons.Rounded.Home, current = false) { onOpen(FileBrowser.PLACES) }
        crumbs.forEachIndexed { i, (title, path) ->
            key(path) {
                val appear = remember { MutableTransitionState(false) }.apply { targetState = true }
                AnimatedVisibility(
                    visibleState = appear,
                    enter = expandHorizontally(motion.defaultSpatialSpec(), expandFrom = Alignment.Start) + fadeIn(motion.defaultEffectsSpec()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Crumb(title, null, current = i == crumbs.lastIndex) { onOpen(path) }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun Crumb(title: String, icon: ImageVector?, current: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val bg by animateColorAsState(if (current) colors.secondaryContainer else Color.Transparent, motion.defaultEffectsSpec(), label = "crumb")
    val fg by animateColorAsState(if (current) colors.onSecondaryContainer else colors.onSurfaceVariant, motion.defaultEffectsSpec(), label = "crumbText")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Text(title, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

// ---- Places ----

@Composable
private fun PlacesPage(b: FileBrowser, bottom: androidx.compose.ui.unit.Dp, onOpen: (String) -> Unit) {
    // In case a drive came or went while no one was listening.
    LaunchedEffect(Unit) { b.refreshVolumes() }
    val context = LocalContext.current
    // Drives say how full they are; everything else where it is.
    val detail: (Place) -> String = { p ->
        if (p.kind == PlaceKind.USB || p.kind == PlaceKind.SD_CARD) {
            b.space(android = true, path = p.path)?.let {
                "${Formatter.formatShortFileSize(context, it.free)} free of ${Formatter.formatShortFileSize(context, it.total)}"
            } ?: p.path
        } else prettyPath(p.path)
    }
    val places = b.places()
    var removing by remember { mutableStateOf<Place?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        val android = places.filter { it.android && it.kind != PlaceKind.PHONE && it.kind != PlaceKind.BOOKMARK }
        val linux = places.filter { !it.android && it.kind != PlaceKind.BOOKMARK && it.kind != PlaceKind.SYSTEM }
        val marks = places.filter { it.kind == PlaceKind.BOOKMARK }
        // Each step rises in a beat after the one above it.
        item { Section("Android", "The phone's own storage", Modifier.rise(0)) }
        item { SpaceCard("Phone storage", b.space(android = true), LauncherIcons.Phone, Modifier.rise(1)) { onOpen(FileBrowser.PHONE) } }
        item { PlaceGroup(android, onOpen, first = 2, detail = detail) }
        item { Section("Linux", "Debian, as your apps see it", Modifier.rise(2 + android.size)) }
        item { SpaceCard("Linux system", b.space(android = false), LauncherIcons.Drive, Modifier.rise(3 + android.size)) { onOpen("/") } }
        item { PlaceGroup(linux, onOpen, first = 4 + android.size) }
        if (marks.isNotEmpty()) {
            item { Section("Bookmarks", "Hold one to remove it", Modifier.rise(4 + android.size + linux.size)) }
            item { PlaceGroup(marks, onOpen, first = 5 + android.size + linux.size, onLongClick = { removing = it }) }
        }
        item {
            Text(
                "Android's folders are the same ones apps see when you share them in an app's permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            )
        }
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove bookmark?") },
            text = { Text(p.path) },
            confirmButton = { TextButton(onClick = { b.toggleBookmark(p.path); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpaceCard(title: String, space: com.daryan.tessera.files.Space?, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.KeyboardArrowRight, null)
            }
            if (space != null && space.total > 0) {
                Spacer(Modifier.height(14.dp))
                val used = 1f - space.free.toFloat() / space.total
                LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "${Formatter.formatShortFileSize(context, space.free)} free of ${Formatter.formatShortFileSize(context, space.total)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceGroup(
    places: List<Place>,
    onOpen: (String) -> Unit,
    first: Int,
    onLongClick: ((Place) -> Unit)? = null,
    detail: (Place) -> String = { prettyPath(it.path) },
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
        places.forEachIndexed { i, p ->
            val last = i == places.lastIndex
            val interaction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .rise(first + i)
                    .pressScale(interaction)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = if (last) 24.dp else 6.dp, bottomEnd = if (last) 24.dp else 6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        onClick = { onOpen(p.path) },
                        onLongClick = onLongClick?.let { { it(p) } },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Icon(placeIcon(p.kind), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyLarge)
                    Text(detail(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun placeIcon(kind: PlaceKind): ImageVector = when (kind) {
    PlaceKind.PHONE -> LauncherIcons.Phone
    PlaceKind.DOWNLOADS -> LauncherIcons.Download
    PlaceKind.PICTURES -> LauncherIcons.Image
    PlaceKind.CAMERA -> LauncherIcons.Camera
    PlaceKind.DOCUMENTS -> LauncherIcons.TextFile
    PlaceKind.MUSIC -> LauncherIcons.Music
    PlaceKind.SD_CARD -> LauncherIcons.SdCard
    PlaceKind.USB -> LauncherIcons.Usb
    PlaceKind.HOME -> Icons.Rounded.Home
    PlaceKind.ROOT_HOME -> LauncherIcons.Shield
    PlaceKind.SYSTEM -> LauncherIcons.Drive
    PlaceKind.BOOKMARK -> LauncherIcons.Bookmark
}

// ---- Folder contents ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(b: FileBrowser, items: List<FileEntry>, bottom: androidx.compose.ui.unit.Dp, onOpen: (FileEntry) -> Unit) {
    val motion = MaterialTheme.motionScheme
    val context = LocalContext.current
    val deep = b.query != null && b.deepSearch
    val selecting = b.selection.isNotEmpty()
    LazyColumn(contentPadding = PaddingValues(bottom = bottom + 96.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.path }, contentType = { "file" }) { e ->
            val selected = e.path in b.selection
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val bg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, motion.defaultEffectsSpec(), label = "rowBg",
            )
            // Selected rows round off into pills; pressed ones lean that way.
            val corner by animateDpAsState(if (selected) 32.dp else if (pressed) 24.dp else 16.dp, motion.defaultSpatialSpec(), label = "rowCorner")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = motion.defaultEffectsSpec(),
                        placementSpec = motion.defaultSpatialSpec(),
                        fadeOutSpec = motion.fastEffectsSpec(),
                    )
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .pressScale(interaction)
                    .clip(RoundedCornerShape(corner))
                    .background(bg)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        onClick = { if (selecting) b.toggle(e.path) else onOpen(e) },
                        onLongClick = { b.toggle(e.path) },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                FileIcon(b, e, 44.dp, selected)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (deep) prettyPath(e.path.substringBeforeLast('/').ifEmpty { "/" }) else details(context, e),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AnimatedVisibility(
                    visible = selecting,
                    enter = expandHorizontally(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec()),
                    exit = shrinkHorizontally(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec()),
                ) {
                    Checkbox(checked = selected, onCheckedChange = { b.toggle(e.path) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGrid(b: FileBrowser, items: List<FileEntry>, bottom: androidx.compose.ui.unit.Dp, onOpen: (FileEntry) -> Unit) {
    val motion = MaterialTheme.motionScheme
    val selecting = b.selection.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottom + 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.path }, contentType = { "file" }) { e ->
            val selected = e.path in b.selection
            val interaction = remember { MutableInteractionSource() }
            val bg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, motion.defaultEffectsSpec(), label = "tileBg",
            )
            val scale by animateFloatAsState(if (selected) 0.93f else 1f, motion.defaultSpatialSpec(), label = "tileScale")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = motion.defaultEffectsSpec(),
                        placementSpec = motion.defaultSpatialSpec(),
                        fadeOutSpec = motion.fastEffectsSpec(),
                    )
                    .padding(4.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .pressScale(interaction)
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        onClick = { if (selecting) b.toggle(e.path) else onOpen(e) },
                        onLongClick = { b.toggle(e.path) },
                    )
                    .padding(8.dp),
            ) {
                FileIcon(b, e, 80.dp, selected)
                Spacer(Modifier.height(8.dp))
                Text(
                    e.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(34.dp),
                )
            }
        }
    }
}

private enum class IconFace { ICON, PICTURE, CHECK }

/**
 * The file's kind as a tinted tile, or a thumbnail for pictures. Selecting turns it into a
 * check in a circle; thumbnails fade in over the icon when they're ready.
 */
@Composable
private fun FileIcon(b: FileBrowser, e: FileEntry, size: androidx.compose.ui.unit.Dp, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val px = with(LocalDensity.current) { size.roundToPx() }
    var thumb by remember(e.path, e.modified) { mutableStateOf<ImageBitmap?>(b.cachedThumbnail(e)) }
    if (e.extension in FileBrowser.IMAGES && thumb == null) {
        LaunchedEffect(e.path, e.modified) { thumb = b.thumbnail(e, px) }
    }
    val (bgTarget, fgTarget) = when {
        selected -> colors.primary to colors.onPrimary
        e.isDir -> colors.primaryContainer to colors.onPrimaryContainer
        e.extension in FileBrowser.IMAGES || e.extension in VIDEO -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    val bg by animateColorAsState(bgTarget, motion.defaultEffectsSpec(), label = "iconBg")
    val fg by animateColorAsState(fgTarget, motion.defaultEffectsSpec(), label = "iconFg")
    val corner by animateDpAsState(if (selected) size / 2 else size / 3.5f, motion.defaultSpatialSpec(), label = "iconCorner")
    val face = when {
        selected -> IconFace.CHECK
        thumb != null -> IconFace.PICTURE
        else -> IconFace.ICON
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(RoundedCornerShape(corner)).background(bg)) {
        AnimatedContent(
            targetState = face,
            transitionSpec = {
                if (targetState == IconFace.PICTURE) fadeIn(motion.slowEffectsSpec()).togetherWith(fadeOut(motion.slowEffectsSpec()))
                else (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.4f))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.4f))
            },
            contentAlignment = Alignment.Center,
            label = "iconFace",
            modifier = Modifier.fillMaxSize(),
        ) { f ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                when (f) {
                    IconFace.PICTURE -> thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    IconFace.CHECK -> Icon(Icons.Rounded.Check, null, tint = fg, modifier = Modifier.size(size * 0.5f))
                    IconFace.ICON -> Icon(fileIcon(e), null, tint = fg, modifier = Modifier.size(size * 0.5f))
                }
            }
        }
        if (e.isLink && !selected) {
            Icon(
                LauncherIcons.Link, "Link",
                tint = if (e.broken) colors.error else fg,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(size * 0.3f)
                    .clip(CircleShape).background(bg).padding(1.dp),
            )
        }
    }
}

/** Shrinks a touch while pressed and springs back, the expressive way. */
@Composable
private fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Rises and fades in, a beat after the item before it. */
@Composable
private fun Modifier.rise(index: Int): Modifier {
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(12) * 30L)
        shown.animateTo(1f, spec)
    }
    return graphicsLayer {
        alpha = shown.value.coerceIn(0f, 1f)
        translationY = (1f - shown.value) * 40.dp.toPx()
    }
}

private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp")
private val AUDIO = setOf("mp3", "ogg", "opus", "flac", "wav", "m4a", "aac")

private fun fileIcon(e: FileEntry): ImageVector = when {
    e.isDir -> LauncherIcons.Folder
    e.extension in FileBrowser.IMAGES -> LauncherIcons.Image
    e.extension in VIDEO -> LauncherIcons.Video
    e.extension in AUDIO -> LauncherIcons.Music
    e.extension in FileBrowser.ARCHIVES || e.extension == "deb" || e.extension == "apk" -> LauncherIcons.Archive
    e.extension in setOf("sh", "py", "js", "ts", "kt", "c", "h", "cpp", "rs", "go", "java", "html", "css", "json", "xml") -> LauncherIcons.Code
    e.extension in FileBrowser.TEXT -> LauncherIcons.TextFile
    e.mode and 0b001_001_001 != 0 -> LauncherIcons.Terminal
    else -> LauncherIcons.File
}

private fun details(context: Context, e: FileEntry): String {
    val date = DateUtils.getRelativeTimeSpanString(e.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    return when {
        e.broken -> "Broken link to ${e.linkTarget}"
        e.isLink -> "Link to ${e.linkTarget}"
        e.isDir -> "Folder · $date"
        else -> "${Formatter.formatShortFileSize(context, e.size)} · $date"
    }
}

@Composable
private fun ErrorState(b: FileBrowser, error: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
    ) {
        Icon(LauncherIcons.Folder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text("Couldn't open this folder", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = b::reload) { Text("Try again") }
            if (!b.root && "denied" in error.lowercase()) FilledTonalButton(onClick = { b.useRoot(true) }) { Text("Browse as root") }
        }
    }
}

@Composable
private fun EmptyState(b: FileBrowser) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
    ) {
        Icon(if (b.query != null) LauncherIcons.Search else LauncherIcons.Folder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        val hidden = !b.showHidden && b.entries.any { it.hidden }
        Text(
            when {
                b.query != null && b.searching -> "Searching…"
                b.query != null -> "Nothing matches"
                hidden -> "Only hidden files here"
                else -> "This folder is empty"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (hidden && b.query == null) TextButton(onClick = { b.showHiddenFiles(true) }) { Text("Show hidden files") }
    }
}

// ---- Dialogs ----

@Composable
fun FilesDialogs(b: FileBrowser, ui: FilesUi, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val close = { ui.dialog = null }
    when (val d = ui.dialog) {
        null -> {}
        FilesDialog.NewFolder -> NameDialog("New folder", "", "Create", close) { name ->
            scope.launch { b.newFolder(name)?.let(onMessage) }
        }
        FilesDialog.NewFile -> NameDialog("New file", "", "Create", close) { name ->
            scope.launch { b.newFile(name)?.let(onMessage) }
        }
        FilesDialog.GoTo -> NameDialog("Go to folder", if (b.dir == FileBrowser.PLACES) "/" else b.dir, "Go", close, label = "Path") { path ->
            b.open(path.trim().trimEnd('/').ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" })
        }
        is FilesDialog.Rename -> NameDialog("Rename", d.entry.name, "Rename", close) { name ->
            if (name != d.entry.name) scope.launch { b.rename(d.entry, name)?.let(onMessage) }
        }
        is FilesDialog.Delete -> AlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(if (d.entries.size == 1) "Delete ${d.entries[0].name}?" else "Delete ${d.entries.size} items?") },
            text = {
                Text(
                    if (d.entries.any { it.isDir }) "Folders go with everything in them. This can't be undone."
                    else "This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    close()
                    b.delete(d.entries) { onMessage(it ?: "Deleted") }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        is FilesDialog.Compress -> CompressDialog(b, d.entries, close) { name, format ->
            b.compress(d.entries, name, format) { onMessage(it ?: "Compressed") }
        }
        is FilesDialog.Extract -> AlertDialog(
            onDismissRequest = close,
            icon = { Icon(LauncherIcons.Archive, null) },
            title = { Text(d.entry.name) },
            text = { Text("Unpack it into a new folder next to it?") },
            confirmButton = {
                TextButton(onClick = {
                    close()
                    b.extract(d.entry) { onMessage(it ?: "Extracted") }
                }) { Text("Extract") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        is FilesDialog.Properties -> PropertiesDialog(b, d.entry, close, onMessage)
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    label: String = "Name",
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.substringBeforeLast('.').takeIf { '.' in initial && !initial.startsWith('.') }?.length ?: initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = text.text.isNotBlank() && (label == "Path" || ('/' !in text.text && text.text != "." && text.text != ".."))
    val submit = { if (valid) { onDismiss(); onDone(text.text.trim()) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) },
                isError = text.text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = submit) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CompressDialog(b: FileBrowser, entries: List<FileEntry>, onDismiss: () -> Unit, onDone: (String, ArchiveFormat) -> Unit) {
    val initial = if (entries.size == 1) entries[0].name.substringBeforeLast('.').ifEmpty { entries[0].name }
    else b.dir.substringAfterLast('/').ifEmpty { "archive" }
    var name by remember { mutableStateOf(initial) }
    var format by remember { mutableStateOf(ArchiveFormat.TAR_GZ) }
    var zip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { zip = b.canZip() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(LauncherIcons.Archive, null) },
        title = { Text("Compress") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    suffix = { Text(format.extension) },
                )
                Spacer(Modifier.height(12.dp))
                ArchiveFormat.entries.forEach { f ->
                    val usable = f != ArchiveFormat.ZIP || zip
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .combinedClickable(enabled = usable, onClick = { format = f }),
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f }, enabled = usable)
                        Column {
                            Text(f.label)
                            Text(
                                when (f) {
                                    ArchiveFormat.TAR_GZ -> "Quick; keeps Linux permissions"
                                    ArchiveFormat.TAR_XZ -> "Smaller, slower"
                                    ArchiveFormat.ZIP -> if (zip) "Opens anywhere, Windows included" else "Install the zip package first"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && '/' !in name,
                onClick = { onDismiss(); onDone(name.trim(), format) },
            ) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PropertiesDialog(b: FileBrowser, entry: FileEntry, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<Usage?>(null) }
    var mode by remember { mutableStateOf(entry.mode and 0x1ff) }
    var recursive by remember { mutableStateOf(false) }
    LaunchedEffect(entry.path) { if (entry.isDir) usage = b.usage(entry) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(fileIcon(entry), null) },
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Prop("Where", prettyPath(entry.path.substringBeforeLast('/').ifEmpty { "/" }))
                Prop("Full path", entry.path, mono = true)
                if (entry.isLink) Prop(if (entry.broken) "Links to (missing)" else "Links to", entry.linkTarget.orEmpty(), mono = true)
                Prop(
                    "Size",
                    when {
                        !entry.isDir -> "${Formatter.formatFileSize(context, entry.size)} (${"%,d".format(entry.size)} bytes)"
                        usage == null -> "Counting…"
                        else -> "${Formatter.formatFileSize(context, usage!!.bytes)} · ${usage!!.files} files, ${usage!!.folders} folders"
                    },
                )
                Prop("Modified", DateUtils.formatDateTime(context, entry.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR))
                if (entry.owner.isNotEmpty()) Prop("Owner", "${entry.owner} · group ${entry.group}")
                Spacer(Modifier.height(12.dp))
                Text("Permissions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (GuestFiles.onAndroid(entry.path)) {
                    Text(
                        "Android's storage doesn't keep Linux permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PermissionGrid(mode) { mode = it }
                    Text(
                        "${modeString(mode, entry.isDir)}   ${"%03o".format(mode)}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (entry.isDir) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Everything inside too", modifier = Modifier.weight(1f))
                            Switch(checked = recursive, onCheckedChange = { recursive = it })
                        }
                    }
                }
            }
        },
        confirmButton = {
            val changed = mode != (entry.mode and 0x1ff) || recursive
            TextButton(onClick = {
                onDismiss()
                if (changed && !GuestFiles.onAndroid(entry.path)) scope.launch {
                    onMessage(b.chmod(entry, mode, recursive) ?: "Permissions changed")
                }
            }) { Text(if (changed) "Apply" else "Done") }
        },
        dismissButton = {
            TextButton(onClick = { copyText(context, entry.path); onMessage("Path copied") }) { Text("Copy path") }
        },
    )
}

@Composable
private fun Prop(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

/** Read, write and run for owner, group and everyone else. */
@Composable
private fun PermissionGrid(mode: Int, onChange: (Int) -> Unit) {
    val who = listOf("Owner" to 6, "Group" to 3, "Others" to 0)
    val what = listOf("Read" to 4, "Write" to 2, "Run" to 1)
    Column {
        Row {
            Spacer(Modifier.width(64.dp))
            what.forEach { (w, _) ->
                Text(w, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
            }
        }
        who.forEach { (name, shift) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                what.forEach { (_, bit) ->
                    val mask = bit shl shift
                    Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                        Checkbox(checked = mode and mask != 0, onCheckedChange = { onChange(if (it) mode or mask else mode and mask.inv()) })
                    }
                }
            }
        }
    }
}

private fun modeString(mode: Int, dir: Boolean) = buildString {
    append(if (dir) 'd' else '-')
    for (shift in listOf(6, 3, 0)) {
        append(if (mode and (4 shl shift) != 0) 'r' else '-')
        append(if (mode and (2 shl shift) != 0) 'w' else '-')
        append(if (mode and (1 shl shift) != 0) 'x' else '-')
    }
}

/** The text editor's view of the Files tab's shell. */
fun FileBrowser.textStore() = object : ConfigStore {
    override suspend fun read(path: String) = readText(path)
    override suspend fun write(path: String, text: String) = writeText(path, text)
}

fun editorFile(e: FileEntry) = ConfigFile("Files", e.name, e.path, prettyPath(e.path.substringBeforeLast('/').ifEmpty { "/" }), ConfigKind.TEXT)

// ---- Helpers ----

/** Paths the way people know them: the phone's storage and the home folder by name. */
fun prettyPath(path: String): String = when {
    path == FileBrowser.PHONE -> "Phone storage"
    path.startsWith(FileBrowser.PHONE + "/") -> "Phone storage" + path.removePrefix(FileBrowser.PHONE)
    path == FileBrowser.HOME -> "Home"
    path.startsWith(FileBrowser.HOME + "/") -> "Home" + path.removePrefix(FileBrowser.HOME)
    else -> path
}

private fun prettyName(path: String, name: String) = when (path) {
    FileBrowser.PHONE -> "Phone storage"
    FileBrowser.HOME -> "Home"
    "/" -> "Linux"
    else -> name
}

/** Steps from the place [path] is in down to it, as (title, path). */
private fun crumbs(path: String, volumes: List<com.daryan.tessera.files.Volume>): List<Pair<String, String>> {
    val (rootTitle, rootPath) = when {
        path.startsWith(FileBrowser.PHONE) -> "Phone storage" to FileBrowser.PHONE
        volumes.any { !it.primary && (path == it.path || path.startsWith(it.path + "/")) } ->
            volumes.first { !it.primary && (path == it.path || path.startsWith(it.path + "/")) }.let { it.title to it.path }
        path.startsWith(FileBrowser.HOME) -> "Home" to FileBrowser.HOME
        else -> "Linux" to "/"
    }
    val rest = path.removePrefix(rootPath).trim('/').split('/').filter { it.isNotEmpty() }
    return buildList {
        add(rootTitle to rootPath)
        var at = rootPath.trimEnd('/')
        rest.forEach { at = "$at/$it"; add(it to at) }
    }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Path", text))
}

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private fun share(context: Context, files: List<File>, onMessage: (String) -> Unit) {
    if (files.isEmpty()) return onMessage("Folders can't be shared; compress them first")
    val uris = ArrayList(files.map { uriFor(context, it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = "*/*"
    intent.clipData = ClipData.newUri(context.contentResolver, "Files", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share"))
}
