@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

// Copyright (C) 2026 Daryan
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
import com.daryan.linuxlauncher.container.PermissionManifest
import kotlinx.coroutines.launch

enum class Destination(val label: String, val title: String, val icon: ImageVector) {
    Apps("Apps", "Linux", LauncherIcons.Apps),
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
    val tabs = remember(customTabs) { vm.tabs(customTabs) }

    var destination by rememberSaveable { mutableStateOf(Destination.Apps) }
    var openApp by remember { mutableStateOf<PermissionManifest?>(null) }
    var addingTab by remember { mutableStateOf(false) }
    var removingTab by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    var openConfig by remember { mutableStateOf<ConfigFile?>(null) }
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

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

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
                engine = mode.label,
                unavailable = when {
                    store != null -> null
                    mode == BackendMode.CHROOT -> "Root access is needed to edit the chroot container"
                    else -> "Install Linux in Setup to edit its configuration"
                },
                onOpenFile = {
                    scope.launch { drawerState.close() }
                    openConfig = it
                },
                onDisplaySettings = {
                    scope.launch { drawerState.close() }
                    context.startActivity(Intent().setClassName(context, "com.termux.x11.LoriePreferences"))
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
                            Destination.Apps -> "${mode.label} · ${vm.apps.size} apps"
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
                // The large flexible bar would eat half a landscape screen; go compact there.
                if (shortScreen) {
                    TopAppBar(title = title, subtitle = subtitle, navigationIcon = menuButton, colors = barColors)
                } else {
                    LargeFlexibleTopAppBar(
                        title = title,
                        subtitle = subtitle,
                        navigationIcon = menuButton,
                        colors = barColors,
                        scrollBehavior = scrollBehavior,
                    )
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
                Destination.Apps -> AppsPager(tabs, pagerState, padding, onOpen = { openApp = it })
                Destination.Sessions -> SessionsScreen(
                    sessions,
                    padding,
                    onOpen = { id ->
                        // Terminal apps open in-app; GUI apps live in their own X11 window.
                        if (sessions.any { it.session.id == id && it.terminal != null }) TerminalActivity.open(context, id)
                        else vm.sessions.showWindow(id)
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
                )
            }
        }
    }
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

    openApp?.let { app ->
        AppSheet(
            app = app,
            backend = vm.backend(mode) ?: vm.backend(BackendMode.PROOT)!!,
            blocker = blocker,
            installed = vm.installed(app, status, mode),
            customTabs = customTabs,
            onToggleTab = { vm.tabStore.toggle(it, app.id) },
            onLaunch = {
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
            },
            onInstall = {
                vm.install(app) { started ->
                    openApp = null
                    destination = Destination.Sessions
                    TerminalActivity.open(context, started.session.id)
                }
            },
            onDismiss = { openApp = null },
        )
    }

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
