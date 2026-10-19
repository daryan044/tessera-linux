@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Badge
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.daryan.tessera.BackendMode
import com.daryan.tessera.LauncherViewModel
import com.daryan.tessera.TerminalActivity
import com.daryan.tessera.container.ConfigFile
import com.daryan.tessera.store.CatalogState
import com.daryan.tessera.store.StoreApp
import com.daryan.tessera.store.StoreSource
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.Shortcut
import com.daryan.tessera.store.SystemApps
import kotlinx.coroutines.launch

enum class Destination(val label: String, val title: String, val icon: ImageVector) {
    Apps("Apps", "Linux", LauncherIcons.Apps),
    Files("Files", "Files", LauncherIcons.Folder),
    Store("Store", "Store", LauncherIcons.Store),
    Sessions("Sessions", "Sessions", LauncherIcons.Terminal),
    Setup("Setup", "Setup", Icons.Rounded.Settings),
}

@Composable
fun LauncherApp(vm: LauncherViewModel = viewModel()) {
    val introSeen by vm.introSeen.collectAsStateWithLifecycle()
    // Before anything else on a first run: the launcher behind it has nothing in it yet.
    if (!introSeen) {
        IntroScreen(onDone = { vm.setIntroSeen(true) })
        return
    }
    val status by vm.status.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val customTabs by vm.tabStore.tabs.collectAsStateWithLifecycle()
    val sessions by vm.sessions.sessions.collectAsStateWithLifecycle()
    val install by vm.installState.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    // Apps installed from a terminal while the launcher was away show up when it's back.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.scanSystemApps() }
    val pinned by vm.tabStore.pinned.collectAsStateWithLifecycle()
    val permissionOverrides by vm.permissionOverrides.collectAsStateWithLifecycle()
    val gpuEnabled by vm.gpuEnabled.collectAsStateWithLifecycle()
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    val diagnosticsEnabled by vm.diagnosticsEnabled.collectAsStateWithLifecycle()
    val gpuApps by vm.gpuApps.collectAsStateWithLifecycle()
    // Phones that have the GPU driver (see LauncherViewModel.gpuFor).
    val gpuGraphics = remember { vm.gpuAvailable() }
    val favorites by vm.tabStore.favorites.collectAsStateWithLifecycle()
    val tabs = remember(apps, customTabs, pinned, favorites) { vm.tabs(apps, customTabs, pinned, favorites) }

    var destination by rememberSaveable { mutableStateOf(Destination.Apps) }
    var openApp by remember { mutableStateOf<PermissionManifest?>(null) }
    var addingTab by remember { mutableStateOf(false) }
    var removingTab by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    var openConfig by remember { mutableStateOf<ConfigFile?>(null) }
    var openStoreApp by remember { mutableStateOf<StoreApp?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var logSession by remember { mutableStateOf<String?>(null) }
    var uninstallApp by remember { mutableStateOf<PermissionManifest?>(null) }
    var editingShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var graphicsApp by remember { mutableStateOf<PermissionManifest?>(null) }
    var execApp by remember { mutableStateOf<PermissionManifest?>(null) }
    var showTrackpad by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val filesUi = remember { FilesUi() }
    /** The Apps tab's search: a pill in its bar, opening to full-screen results. */
    val appSearch = rememberSearchBarState()
    val appQuery = rememberTextFieldState()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val flathubCatalog by vm.flathubCatalog.collectAsStateWithLifecycle()
    val storeSource by vm.storeSource.collectAsStateWithLifecycle()
    val flathubReady by vm.flathubReady.collectAsStateWithLifecycle()
    val flathubInstalled by vm.flathubInstalled.collectAsStateWithLifecycle()
    val installedPackages by vm.installedPackages.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val launchApp: (PermissionManifest) -> Unit = { app ->
        vm.launch(app) { started ->
            openApp = null
            destination = Destination.Sessions
            if (started.terminal != null) TerminalActivity.open(context, started.session.id)
        }
    }
    // Apps granted shared-storage folders need Android's storage permission first. Launch
    // either way: if it's denied, the folder is simply empty inside the container.
    var pendingLaunch by remember { mutableStateOf<PermissionManifest?>(null) }
    val linuxThemes by vm.linuxThemes.collectAsStateWithLifecycle()
    // Any file: theme archives come as .tar.xz, .tar.gz or .zip, with types phones rarely know.
    val themePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importTheme)
    }
    val storageRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingLaunch?.let(launchApp)
        pendingLaunch = null
    }
    val startApp: (PermissionManifest) -> Unit = { app ->
        val storage = app.permissions.drives ||
            app.permissions.filesystem.any { it.path.startsWith("/storage") || it.path.startsWith("/sdcard") }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (storage && !granted) {
            pendingLaunch = app
            storageRequest.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        } else {
            launchApp(app)
        }
    }


    // An X11 window's Permissions tile asks for an app's sheet.
    val requestedApp by vm.showAppRequests.collectAsStateWithLifecycle()
    LaunchedEffect(requestedApp, apps) {
        val id = requestedApp ?: return@LaunchedEffect
        apps.firstOrNull { it.id == id }?.let { openApp = it; destination = Destination.Apps }
        vm.showAppRequests.value = null
    }

    // The store loads (or refreshes its week-old cache) when first opened.
    LaunchedEffect(destination, status) {
        if (destination == Destination.Store && status != null) vm.openStore()
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // The Files tab follows the engine: PRoot's system or chroot's, once it's usable.
    LaunchedEffect(status, mode) { vm.files.useEngine(vm.filesEngine(status, mode)) }
    // Search belongs to the Apps tab; leaving it closes the search.
    LaunchedEffect(destination) { if (destination != Destination.Apps) appSearch.snapTo(0f) }
    val appResults = remember(apps, appQuery.text) { searchApps(apps, appQuery.text.toString()) }
    // The task manager samples only while Sessions is on screen; chroot mode reads as root to
    // see apps running as root too.
    val monitorSu = if (mode == BackendMode.CHROOT) status?.su else null
    val processes by androidx.compose.runtime.produceState<List<com.daryan.tessera.container.GuestProcess>?>(null, destination, monitorSu) {
        value = null
        if (destination != Destination.Sessions) return@produceState
        com.daryan.tessera.container.ProcessMonitor(monitorSu).watch().collect { value = it }
    }
    val messageScope = rememberCoroutineScope()
    val filesMessage: (String) -> Unit = { m -> messageScope.launch { snackbar.showSnackbar(m) } }
    val terminalHere: (String) -> Unit = { dir ->
        vm.terminalIn(dir, vm.files.root) { started ->
            if (started.terminal != null) TerminalActivity.open(context, started.session.id)
        }
    }

    // An app's icon on the phone's home screen asks for it to start, once Linux is checked.
    val launchRequest by vm.launchRequests.collectAsStateWithLifecycle()
    LaunchedEffect(launchRequest, apps, status) {
        val id = launchRequest ?: return@LaunchedEffect
        if (status == null || apps.isEmpty()) return@LaunchedEffect
        vm.launchRequests.value = null
        val app = apps.firstOrNull { it.id == id }
        if (app != null) startApp(app) else snackbar.showSnackbar("That app isn't installed anymore")
    }

    // A screen of its own, like the trackpad a cast gets: it drives a PC, not anything in here.
    if (showTrackpad) {
        PcTrackpadScreen(su = status?.su, onClose = { showTrackpad = false })
        return
    }

    val hazeState = rememberHazeState()
    val glass = HazeMaterials.thin(MaterialTheme.colorScheme.surfaceContainer)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val blocker = vm.blocker(status, mode)
    val running = sessions.count { it.exitCode == null }
    val config = LocalConfiguration.current
    val shortScreen = config.screenHeightDp < 480

    val store = vm.configStore(status, mode)
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // Every action closes the menu first, then does its thing.
            fun closing(action: () -> Unit): () -> Unit = { scope.launch { drawerState.close() }; action() }
            fun launchById(id: String) {
                val app = apps.firstOrNull { it.id == id }
                if (app != null) startApp(app) else scope.launch { snackbar.showSnackbar("That app isn't installed") }
            }
            SideMenu(
                status = MenuStatus(
                    mode = mode,
                    blocker = blocker.takeIf { status != null },
                    running = running,
                    activeInstalls = queue.count { it.active },
                    rootAvailable = status?.su != null,
                    destination = destination,
                ),
                actions = MenuActions(
                    go = { d -> scope.launch { drawerState.close() }; destination = d },
                    terminal = closing { launchById("bash") },
                    rootShell = closing { launchById("root-shell") },
                    newShortcut = closing { destination = Destination.Apps; editingShortcut = Shortcut(id = "", name = "", command = "") },
                    trackpad = closing { showTrackpad = true },
                    installs = closing { showQueue = true },
                    systemFiles = closing { showSettings = true },
                    displaySettings = closing { context.startActivity(Intent().setClassName(context, "com.termux.x11.LoriePreferences")) },
                    copyDiagnostics = closing {
                        scope.launch {
                            val report = vm.diagnosticsReport()
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Tessera diagnostics", report))
                            snackbar.showSnackbar("Diagnostics copied, ready to paste")
                        }
                    },
                    setMode = vm::setMode,
                    settings = closing { showSettings = true },
                    about = closing { showAbout = true },
                    intro = closing { vm.setIntroSeen(false) },
                ),
            )
        },
    ) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = topBar@{
            if (destination == Destination.Files) {
                Column(Modifier.hazeBlur(HazeInput.Sources(hazeState), glass, performanceMode = BLUR_MODE)) {
                    FilesTopBar(
                        b = vm.files,
                        ui = filesUi,
                        onMenu = { scope.launch { drawerState.open() } },
                        onMessage = filesMessage,
                        onTerminalHere = terminalHere,
                        realRoot = mode == BackendMode.CHROOT,
                    )
                    AnimatedVisibility(
                        visible = queue.any { it.active },
                        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                        exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                    ) {
                        QueueStrip(queue, onOpenQueue = { showQueue = true })
                    }
                }
                return@topBar
            }
            Column(Modifier.hazeBlur(HazeInput.Sources(hazeState), glass, performanceMode = BLUR_MODE)) {
                val title = @Composable { Text(destination.title) }
                val subtitle = @Composable {
                    Text(
                        when (destination) {
                            Destination.Apps -> "${mode.label} · ${apps.size} apps"
                            Destination.Files -> ""
                            Destination.Store -> ((if (storeSource == StoreSource.FLATHUB) flathubCatalog else catalog) as? CatalogState.Ready)
                                ?.let { "${it.apps.size} apps from ${storeSource.label}" } ?: "Apps from ${storeSource.label}"
                            Destination.Sessions -> if (running == 0) "Nothing running" else "$running running"
                            Destination.Setup -> blocker ?: "Ready to launch apps"
                        },
                    )
                }
                val menuButton = @Composable {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Rounded.Menu, "Configuration")
                    }
                }
                val barColors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                )
                // The Store's history: every install and removal, with apt's output.
                val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                    // Where search used to be: the desktop, which opens its sheet like any app.
                    if (destination == Destination.Apps) {
                        apps.firstOrNull { it.desktop }?.let { desktop ->
                            IconButton(onClick = { openApp = desktop }) { Icon(LauncherIcons.Desktop, desktop.name) }
                        }
                    }
                    if (destination == Destination.Store) {
                        val active = queue.count { it.active }
                        IconButton(onClick = { showQueue = true }) {
                            BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                                Icon(LauncherIcons.History, "Recent installs")
                            }
                        }
                    }
                }
                if (destination == Destination.Apps) {
                    AppsSearchBar(
                        state = appSearch,
                        query = appQuery,
                        count = apps.size,
                        results = appResults,
                        onOpen = { openApp = it },
                        navigationIcon = menuButton,
                        actions = actions,
                    )
                } else if (shortScreen) {
                    // The large flexible bar would eat half a landscape screen; go compact there.
                    TopAppBar(title = title, subtitle = subtitle, navigationIcon = menuButton, actions = actions, colors = barColors)
                } else {
                    LargeFlexibleTopAppBar(
                        title = title,
                        subtitle = subtitle,
                        navigationIcon = menuButton,
                        actions = actions,
                        colors = barColors,
                        scrollBehavior = scrollBehavior,
                    )
                }
                AnimatedVisibility(
                    visible = queue.any { it.active },
                    enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                ) {
                    QueueStrip(queue, onOpenQueue = { showQueue = true })
                }
                AnimatedVisibility(
                    visible = destination == Destination.Apps,
                    enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                ) {
                    TabPills(
                        tabs = tabs,
                        selected = pagerState.targetPage,
                        onSelect = { i -> scope.launch { pagerState.animateScrollToPage(i, animationSpec = motion.defaultSpatialSpec()) } },
                        onAdd = { addingTab = true },
                        onRemove = { removingTab = it },
                    )
                }
            }
        },
        bottomBar = {
            FloatingDock(
                selected = destination,
                onSelect = { destination = it },
                hazeState = hazeState,
                glass = HazeMaterials.regular(MaterialTheme.colorScheme.surfaceContainerHigh),
                badge = { d ->
                    if (d == Destination.Sessions && running > 0) Badge { Text("$running") }
                    if (d == Destination.Setup && blocker != null && status != null) Badge()
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.94f))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
            },
            label = "destination",
        ) { d ->
            when (d) {
                Destination.Apps -> AppsPager(
                    tabs, pagerState, padding,
                    onOpen = { openApp = it },
                    pinned = pinned,
                    favorites = favorites,
                    onTogglePin = { vm.tabStore.togglePinned(it.id) },
                    onToggleFavorite = { vm.tabStore.toggleFavorite(it.id) },
                    onEditShortcut = { app -> vm.shortcuts.value.firstOrNull { it.id == Shortcut.idOf(app) }?.let { editingShortcut = it } },
                    onNewShortcut = { editingShortcut = Shortcut(id = "", name = "", command = "") },
                    onTrackpad = { showTrackpad = true },
                    onUninstall = { app ->
                        val shortcut = vm.shortcuts.value.firstOrNull { Shortcut.isShortcut(app) && it.id == Shortcut.idOf(app) }
                        if (shortcut != null) {
                            // Just the icon: the packages it needed may serve other things.
                            vm.deleteShortcut(app)
                            scope.launch {
                                val undo = snackbar.showSnackbar("Deleted ${app.name}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                if (undo == SnackbarResult.ActionPerformed) vm.saveShortcut(shortcut)
                            }
                        } else if (app.packages.isEmpty() && !SystemApps.isSystemApp(app)) {
                            scope.launch { snackbar.showSnackbar("${app.name} is part of the Linux system and can't be uninstalled") }
                        } else {
                            uninstallApp = app
                        }
                    },
                    onEditExec = { execApp = it },
                    graphics = gpuGraphics || appearance.enabled,
                    onGraphics = { graphicsApp = it },
                )
                Destination.Files -> FilesScreen(vm.files, filesUi, padding, onMessage = filesMessage)
                Destination.Store -> StoreScreen(
                    source = storeSource,
                    onSource = vm::setStoreSource,
                    catalog = if (storeSource == StoreSource.FLATHUB) flathubCatalog else catalog,
                    installed = { app ->
                        if (app.source == StoreSource.FLATHUB) app.id in flathubInstalled else installedPackages?.contains(app.pkg)
                    },
                    flathubReady = flathubReady,
                    flathubSetup = vm.flathubSetupJob(queue),
                    onSetUpFlathub = {
                        if (!vm.setupFlathub()) scope.launch { snackbar.showSnackbar(blocker ?: "Linux isn't ready yet") }
                    },
                    padding = padding,
                    loadIcon = vm::loadStoreIcon,
                    onRetry = { vm.openStore(refresh = true) },
                    onOpenApp = { openStoreApp = it },
                )
                Destination.Sessions -> SessionsScreen(
                    sessions,
                    padding,
                    onOpen = { id ->
                        // Terminal apps open in-app; GUI apps live in their own X11 window.
                        val s = sessions.firstOrNull { it.session.id == id }
                        when {
                            s?.terminal != null -> TerminalActivity.open(context, id)
                            s?.running == true -> vm.sessions.showWindow(id)
                            // A GUI app that stopped: show what it printed.
                            else -> logSession = id
                        }
                    },
                    onStop = vm.sessions::stop,
                    onDismiss = vm.sessions::dismiss,
                    processes = processes,
                    onCloseAll = vm.sessions::stopAll,
                    onEndProcess = { p -> scope.launch { com.daryan.tessera.container.ProcessMonitor.end(p.pid, p.uid, monitorSu) } },
                )
                Destination.Setup -> SetupScreen(
                    status = status,
                    mode = mode,
                    install = install,
                    padding = padding,
                    onMode = vm::setMode,
                    onRefresh = vm::refresh,
                    onInstall = { vm.install() },
                    onCancelInstall = vm::cancelInstall,
                    onLiftPhantomLimit = vm::liftPhantomLimit,
                    diagnosticsEnabled = diagnosticsEnabled,
                    onDiagnostics = vm::setDiagnosticsEnabled,
                    onCopyDiagnostics = {
                        scope.launch {
                            val report = vm.diagnosticsReport()
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Tessera diagnostics", report))
                            snackbar.showSnackbar("Diagnostics copied, ready to paste")
                        }
                    },
                    onShowIntro = { vm.setIntroSeen(false) },
                )
            }
        }
    }
    }

    AnimatedVisibility(
        visible = showSettings,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
    ) {
        SettingsScreen(
            filesUnavailable = when {
                store != null -> null
                mode == BackendMode.CHROOT -> "Root access is needed to edit the chroot container"
                else -> "Install Linux in Setup to edit its files"
            },
            onOpenFile = { openConfig = it },
            customTabs = customTabs,
            onAddTab = { addingTab = true },
            onRemoveTab = { removingTab = it },
            catalog = catalog,
            onRefreshCatalog = { vm.openStore(refresh = true) },
            activeInstalls = queue.count { it.active },
            onOpenQueue = { showQueue = true },
            onDisplaySettings = { context.startActivity(Intent().setClassName(context, "com.termux.x11.LoriePreferences")) },
            gpuEnabled = gpuEnabled.takeIf { vm.gpuAvailable() },
            onGpuEnabled = vm::setGpuEnabled,
            appearance = appearance,
            onAppearance = vm::setAppearance,
            linuxThemes = linuxThemes,
            onImportTheme = { themePicker.launch(arrayOf("*/*")) },
            onDeleteTheme = vm::deleteTheme,
            runningSessions = running,
            onStopAll = vm.sessions::stopAll,
            onAbout = { showAbout = true },
            onClose = { showSettings = false },
        )
    }

    openStoreApp?.let { app ->
        // The launcher entry for this package (or Flathub app), once it's been installed from here or bundled.
        val flathub = app.source == StoreSource.FLATHUB
        val entry = apps.firstOrNull { if (flathub) it.flatpak?.app == app.id else app.pkg in it.packages }
        val key = vm.storeJobKey(app)
        StoreAppSheet(
            app = app,
            icon = vm.storeIcon(app),
            screenshots = { vm.storeScreenshots(app) },
            installed = if (flathub) app.id in flathubInstalled else installedPackages?.contains(app.pkg),
            blocker = blocker,
            job = queue.lastOrNull { key in it.packages && it.active },
            onInstall = {
                if (vm.storeInstall(app)) {
                    openStoreApp = null
                    scope.launch { snackbar.showSnackbar("${app.name} added to installs") }
                }
            },
            onOpen = entry?.let { e -> { openStoreApp = null; launchApp(e) } },
            onUninstall = {
                if (vm.storeUninstall(app)) {
                    openStoreApp = null
                    scope.launch { snackbar.showSnackbar("Removing ${app.name}") }
                }
            },
            onDismiss = { openStoreApp = null },
        )
    }

    graphicsApp?.let { app ->
        // Read again whenever a switch changes (gpuApps), so the dialog follows them.
        val gpu = remember(app.id, gpuApps, gpuEnabled, mode) { vm.gpuFor(app) }
        val fixes = remember(app.id, gpuApps) { vm.gpuFixesFor(app) }
        val unthemed by vm.unthemed.collectAsStateWithLifecycle()
        val themed = (app.id !in unthemed).takeIf { appearance.enabled }
        if (gpu == null && themed == null) graphicsApp = null
        else GraphicsFixesDialog(
            appName = app.name,
            themed = themed,
            onThemed = { vm.setThemed(app, it) },
            gpu = gpu,
            onGpu = { vm.setGpuFor(app, it) },
            fixes = fixes,
            onFix = { fix, on -> vm.setGpuFix(app, fix, on) },
            onDismiss = { graphicsApp = null },
        )
    }

    execApp?.let { app ->
        ExecDialog(
            appName = app.name,
            // From the apps list, so it is already the user's command where they changed it.
            exec = app.exec,
            default = vm.defaultExec(app.id) ?: app.exec,
            onSave = { vm.setExec(app, it) },
            onReset = { vm.resetExec(app.id) },
            onDismiss = { execApp = null },
        )
    }

    editingShortcut?.let { s ->
        ShortcutDialog(s, onDismiss = { editingShortcut = null }, onSave = { vm.saveShortcut(it); editingShortcut = null })
    }

    uninstallApp?.let { app ->
        AlertDialog(
            onDismissRequest = { uninstallApp = null },
            icon = { AppGlyph(app, 28.dp, MaterialTheme.colorScheme.onSurface) },
            title = { Text("Uninstall ${app.name}?") },
            text = {
                Text(
                    "Removes ${app.packages.joinToString()} from Linux. " +
                        "Its settings in your home folder stay, in case you install it again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = if (SystemApps.isSystemApp(app)) {
                        // Its package is looked up from dpkg first.
                        scope.launch {
                            val queued = vm.uninstallSystemApp(app)
                            snackbar.showSnackbar(if (queued) "Removing ${app.name}" else "Couldn't tell which package ${app.name} came from")
                        }
                        uninstallApp = null
                        return@TextButton
                    } else vm.uninstall(app)
                    uninstallApp = null
                    scope.launch { snackbar.showSnackbar(if (ok) "Removing ${app.name}" else "Install Linux first") }
                }) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { uninstallApp = null }) { Text("Cancel") } },
        )
    }

    sessions.firstOrNull { it.session.id == logSession }?.let { s ->
        SessionLogSheet(s, onDismiss = { logSession = null })
    }

    AnimatedVisibility(
        visible = showQueue,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
    ) {
        InstallsScreen(
            jobs = queue,
            onCancel = vm::cancelJob,
            onRetry = vm::retryJob,
            onClearFinished = vm::clearFinishedJobs,
            onClose = { showQueue = false },
        )
    }

    AnimatedVisibility(
        visible = showAbout,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
    ) {
        AboutScreen(onClose = { showAbout = false })
    }

    // Config editor slides up over the launcher, like a full-screen sheet.
    val editing = openConfig
    AnimatedVisibility(
        visible = editing != null && store != null,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
    ) {
        val shown = remember { mutableStateOf(editing) }
        if (editing != null) shown.value = editing
        val file = shown.value
        if (file != null && store != null) {
            ConfigEditor(file, store, engine = mode.label, onClose = { openConfig = null })
        }
    }

    FilesDialogs(vm.files, filesUi, onMessage = filesMessage)

    filesUi.opening?.let { entry -> key(entry.path) {
        val done = { if (filesUi.opening?.path == entry.path) filesUi.opening = null }
        OpenWithSheet(
            b = vm.files,
            entry = entry,
            launcherApp = vm::linuxAppFor,
            onDismiss = done,
            onEdit = { done(); filesUi.editing = entry },
            onExtract = { done(); filesUi.dialog = FilesDialog.Extract(entry) },
            onInstallDeb = { info -> done(); vm.installDeb(entry, info) },
            onOpenLinux = { opener ->
                done()
                vm.openInLinux(opener, entry.path) { started ->
                    if (started.terminal != null) TerminalActivity.open(context, started.session.id)
                }
            },
            onMessage = filesMessage,
        )
    } }

    // A text file from the Files tab, in the same editor as the system files.
    val editingFile = filesUi.editing
    AnimatedVisibility(
        visible = editingFile != null,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
    ) {
        val shown = remember { mutableStateOf(editingFile) }
        if (editingFile != null) shown.value = editingFile
        shown.value?.let { e ->
            val store = remember(e.path) { vm.files.textStore() }
            ConfigEditor(
                editorFile(e), store, engine = mode.label,
                onClose = { filesUi.editing = null; vm.files.reload() },
                savedMessage = "Saved",
            )
        }
    }

    // Looked up by id so the sheet follows permission changes made in it.
    // Keyed by app: opening another app while one sheet is still sliding away gets a sheet of
    // its own, rather than taking over the closing one (whose end would then close it too).
    apps.firstOrNull { it.id == openApp?.id }?.let { app -> key(app.id) {
        AppSheet(
            app = app,
            backend = vm.backend(mode) ?: vm.backend(BackendMode.PROOT)!!,
            blocker = blocker,
            installed = vm.installed(app, status, mode),
            customTabs = customTabs,
            onToggleTab = { vm.tabStore.toggle(it, app.id) },
            // One desktop at a time: opening it again goes back to the one running.
            onLaunch = {
                val runningDesktop = if (app.desktop) sessions.firstOrNull { it.app.id == app.id && it.running } else null
                if (runningDesktop != null) {
                    openApp = null
                    vm.sessions.showWindow(runningDesktop.session.id)
                } else startApp(app)
            },
            onInstall = {
                if (vm.install(app)) {
                    openApp = null
                    scope.launch { snackbar.showSnackbar("${app.name} added to installs") }
                }
            },
            // Only if it's still this app's sheet that's open.
            onDismiss = { if (openApp?.id == app.id) openApp = null },
            permissionsChanged = app.id in permissionOverrides,
            onPermissions = { vm.setPermissions(app, it) },
            onResetPermissions = { vm.resetPermissions(app.id) },
            gpu = remember(app.id, gpuApps, gpuEnabled, mode) { vm.gpuFor(app) },
            onGpu = { vm.setGpuFor(app, it) },
            extras = if (app.desktop) ({ DesktopSheetNotes(running = sessions.any { it.app.id == app.id && it.running }) }) else null,
        )
    } }

    if (addingTab) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingTab = false },
            title = { Text("New tab") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.tabStore.add(name)
                    addingTab = false
                    scope.launch { pagerState.animateScrollToPage(tabs.size) }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { addingTab = false }) { Text("Cancel") } },
        )
    }

    removingTab?.let { tab ->
        AlertDialog(
            onDismissRequest = { removingTab = null },
            title = { Text("Remove “$tab”?") },
            text = { Text("The apps stay installed; only the tab goes away.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.tabStore.remove(tab)
                    removingTab = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removingTab = null }) { Text("Cancel") } },
        )
    }
}
