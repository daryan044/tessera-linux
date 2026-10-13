@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.ui

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
import com.daryan.linuxlauncher.BackendMode
import com.daryan.linuxlauncher.LauncherViewModel
import com.daryan.linuxlauncher.TerminalActivity
import com.daryan.linuxlauncher.container.ConfigFile
import com.daryan.linuxlauncher.store.CatalogState
import com.daryan.linuxlauncher.store.StoreApp
import com.daryan.linuxlauncher.container.PermissionManifest
import com.daryan.linuxlauncher.Shortcut
import com.daryan.linuxlauncher.store.SystemApps
import kotlinx.coroutines.launch

enum class Destination(val label: String, val title: String, val icon: ImageVector) {
    Apps("Apps", "Linux", LauncherIcons.Apps),
    Store("Store", "Store", LauncherIcons.Store),
    Sessions("Sessions", "Sessions", LauncherIcons.Terminal),
    Setup("Setup", "Setup", Icons.Rounded.Settings),
}

@Composable
fun LauncherApp(vm: LauncherViewModel = viewModel()) {
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
    var showSettings by remember { mutableStateOf(false) }
    val queue by vm.queue.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
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
    val storageRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingLaunch?.let(launchApp)
        pendingLaunch = null
    }
    val startApp: (PermissionManifest) -> Unit = { app ->
        val storage = app.permissions.filesystem.any { it.path.startsWith("/storage") || it.path.startsWith("/sdcard") }
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

    // An app's icon on the phone's home screen asks for it to start, once Linux is checked.
    val launchRequest by vm.launchRequests.collectAsStateWithLifecycle()
    LaunchedEffect(launchRequest, apps, status) {
        val id = launchRequest ?: return@LaunchedEffect
        if (status == null || apps.isEmpty()) return@LaunchedEffect
        vm.launchRequests.value = null
        val app = apps.firstOrNull { it.id == id }
        if (app != null) startApp(app) else snackbar.showSnackbar("That app isn't installed anymore")
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
            SideMenu(
                onSettings = {
                    scope.launch { drawerState.close() }
                    showSettings = true
                },
                onAbout = {
                    scope.launch { drawerState.close() }
                    showAbout = true
                },
            )
        },
    ) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.hazeBlur(HazeInput.Sources(hazeState), glass)) {
                val title = @Composable { Text(destination.title) }
                val subtitle = @Composable {
                    Text(
                        when (destination) {
                            Destination.Apps -> "${mode.label} · ${apps.size} apps"
                            Destination.Store -> (catalog as? CatalogState.Ready)?.let { "${it.apps.size} apps from Debian" } ?: "Apps from Debian"
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
                    if (destination == Destination.Store) {
                        val active = queue.count { it.active }
                        IconButton(onClick = { showQueue = true }) {
                            BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                                Icon(LauncherIcons.History, "Recent installs")
                            }
                        }
                    }
                }
                // The large flexible bar would eat half a landscape screen; go compact there.
                if (shortScreen) {
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
                    graphics = gpuGraphics,
                    onGraphics = { graphicsApp = it },
                )
                Destination.Store -> StoreScreen(
                    catalog = catalog,
                    installed = installedPackages,
                    padding = padding,
                    iconFor = vm::storeIcon,
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
            runningSessions = running,
            onStopAll = vm.sessions::stopAll,
            onAbout = { showAbout = true },
            onClose = { showSettings = false },
        )
    }

    openStoreApp?.let { app ->
        // The launcher entry for this package, once it's been installed from here or bundled.
        val entry = apps.firstOrNull { app.pkg in it.packages }
        StoreAppSheet(
            app = app,
            icon = vm.storeIcon(app),
            screenshots = { vm.storeScreenshots(app) },
            installed = installedPackages?.contains(app.pkg),
            blocker = blocker,
            job = queue.lastOrNull { app.pkg in it.packages && it.active },
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
        if (gpu == null) graphicsApp = null
        else GraphicsFixesDialog(
            appName = app.name,
            gpu = gpu,
            onGpu = { vm.setGpuFor(app, it) },
            fixes = fixes,
            onFix = { fix, on -> vm.setGpuFix(app, fix, on) },
            onDismiss = { graphicsApp = null },
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
            onLaunch = { startApp(app) },
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
