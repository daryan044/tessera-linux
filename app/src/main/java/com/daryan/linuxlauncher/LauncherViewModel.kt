// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.Build
import com.daryan.linuxlauncher.container.ConfigStore
import com.daryan.linuxlauncher.store.CatalogState
import com.daryan.linuxlauncher.store.QueueAction
import com.daryan.linuxlauncher.store.QueueJob
import com.daryan.linuxlauncher.store.StoreApp
import com.daryan.linuxlauncher.store.StoreCatalog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.daryan.linuxlauncher.container.ContainerBackend
import com.daryan.linuxlauncher.container.DirectConfigStore
import com.daryan.linuxlauncher.container.GUI_HELPER_PACKAGES
import com.daryan.linuxlauncher.container.SuConfigStore
import com.daryan.linuxlauncher.container.Distro
import com.daryan.linuxlauncher.container.HostProbe
import com.daryan.linuxlauncher.container.HostStatus
import com.daryan.linuxlauncher.container.InstallState
import com.daryan.linuxlauncher.container.PackageInstall
import com.daryan.linuxlauncher.container.PermissionManifest
import com.daryan.linuxlauncher.container.RunningSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackendMode(val label: String) { PROOT("PRoot"), CHROOT("Chroot (root)") }

/** A launcher tab: either a category from the manifests or one the user made. */
data class LauncherTab(val title: String, val apps: List<PermissionManifest>, val custom: Boolean)

const val FAVORITES_TAB = "Favorites"

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LauncherApplication).container
    val paths = container.paths
    private val probe = HostProbe(paths)
    val tabStore = TabStore(app)
    val sessions = container.sessions
    val installState: StateFlow<InstallState> = container.installer.state

    /** Apps that ship with the launcher. */
    private val bundled: List<PermissionManifest> = loadApps(app)

    /** Bundled apps plus those installed from the store. */
    val apps: StateFlow<List<PermissionManifest>> = container.registry.userApps
        .map { user -> bundled + user.filterNot { u -> bundled.any { it.id == u.id } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, bundled)

    val catalog: StateFlow<CatalogState> = container.catalog.state
    val installedPackages: StateFlow<Set<String>?> = container.registry.installed

    private val _status = MutableStateFlow<HostStatus?>(null)
    val status: StateFlow<HostStatus?> = _status

    private val prefs = app.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        runCatching { BackendMode.valueOf(prefs.getString(KEY_MODE, null)!!) }.getOrDefault(BackendMode.PROOT),
    )
    val mode: StateFlow<BackendMode> = _mode

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        refresh()
        // Re-probe once an install finishes so Setup and the launch buttons update.
        viewModelScope.launch {
            installState.collect { if (it == InstallState.Done) refresh() }
        }
    }

    fun install(distro: Distro = Distro.DEBIAN) = container.installer.start(distro)

    fun cancelInstall() = container.installer.cancel()

    /**
     * All, then Favorites (once there are any), the categories and the user's own tabs. Pinned
     * apps lead every tab, in the order they were pinned.
     */
    fun tabs(
        apps: List<PermissionManifest>,
        custom: List<CustomTab>,
        pinned: List<String> = emptyList(),
        favorites: List<String> = emptyList(),
    ): List<LauncherTab> {
        val byId = apps.associateBy { it.id }
        val pins = pinned.mapNotNull { byId[it] }
        fun arranged(list: List<PermissionManifest>) = pins.filter { it in list } + list.filterNot { it.id in pinned }
        return buildList {
            add(LauncherTab("All", arranged(apps), custom = false))
            val favs = favorites.mapNotNull { byId[it] }
            if (favs.isNotEmpty()) add(LauncherTab(FAVORITES_TAB, arranged(favs), custom = false))
            apps.map { it.category }.distinct().sorted().forEach { cat ->
                add(LauncherTab(cat, arranged(apps.filter { it.category == cat }), custom = false))
            }
            custom.forEach { tab ->
                add(LauncherTab(tab.name, arranged(apps.filter { it.id in tab.appIds }), custom = true))
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _status.value = null
            val s = withContext(Dispatchers.IO) { probe.probe() }
            _status.value = s
            if (s.su == null && _mode.value == BackendMode.CHROOT) _mode.value = BackendMode.PROOT
            addMissingGuiHelpers()
        }
    }

    /**
     * Containers set up before a GUI helper was added (the focus watcher's AT-SPI packages)
     * get it queued once: only where GUI apps are in use, i.e. the window manager is there.
     */
    private suspend fun addMissingGuiHelpers() {
        if (container.guiHelpersChecked) return
        val store = configStore(_status.value, _mode.value) ?: return
        val backend = backend() ?: return
        container.guiHelpersChecked = true
        container.registry.refreshInstalled(store)
        val installed = container.registry.installed.value ?: return
        if ("matchbox-window-manager" !in installed || GUI_HELPER_PACKAGES.all { it in installed }) return
        container.queue.enqueue("Sound and keyboard helpers", GUI_HELPER_PACKAGES, QueueAction.INSTALL, null, backend) {
            container.registry.refreshInstalled(store)
        }
    }

    fun setMode(mode: BackendMode) {
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun backend(mode: BackendMode = _mode.value): ContainerBackend? = when (mode) {
        BackendMode.PROOT -> container.proot()
        BackendMode.CHROOT -> _status.value?.su?.let { container.chroot(it) }
    }

    /** Why [mode] cannot launch apps yet, or null when it can. */
    fun blocker(status: HostStatus?, mode: BackendMode): String? = when {
        status == null -> "Checking the system"
        mode == BackendMode.PROOT && !status.prootBinary -> "PRoot isn't bundled in this build yet"
        mode == BackendMode.PROOT && !status.prootRootfs -> "No Linux system installed yet"
        mode == BackendMode.CHROOT && status.su == null -> "Root access not found"
        else -> null
    }

    /**
     * Whether [app] is installed in the container. Null when that can't be checked: chroot
     * rootfs isn't readable by the app, or the manifest names no packages.
     */
    fun installed(app: PermissionManifest, status: HostStatus?, mode: BackendMode): Boolean? =
        if (mode == BackendMode.PROOT && status?.prootRootfs == true) PackageInstall.isInstalled(app, paths.prootRootfs)
        else null

    /**
     * Where the side menu's config files are read and written for [mode], or null when that
     * container isn't available (PRoot rootfs not installed, or no root for chroot).
     */
    fun configStore(status: HostStatus?, mode: BackendMode): ConfigStore? = when {
        status == null -> null
        mode == BackendMode.PROOT && status.prootRootfs -> DirectConfigStore(paths.prootRootfs)
        mode == BackendMode.CHROOT && status.su != null -> SuConfigStore(status.su, paths.chrootRootfs.path)
        else -> null
    }

    /** Loads the store catalog for the active container's release, and what it has installed. */
    fun openStore(refresh: Boolean = false) {
        val store = configStore(_status.value, _mode.value)
        viewModelScope.launch {
            val release = runCatching { store?.read("/etc/os-release") }.getOrNull()
            val suite = release?.lineSequence()?.firstOrNull { it.startsWith("VERSION_CODENAME=") }
                ?.substringAfter('=')?.trim('"')?.takeIf { it.isNotBlank() } ?: "trixie"
            container.catalog.load(suite, StoreCatalog.debianArch(Build.SUPPORTED_ABIS.first()), refresh)
            container.registry.refreshInstalled(store)
        }
    }

    fun storeIcon(app: StoreApp) = container.catalog.iconFile(app)

    val queue: StateFlow<List<QueueJob>> = container.queue.jobs
    val showAppRequests = container.showAppRequests

    /**
     * Queues a store app's install; it runs in the background after any before it, and its
     * launcher entry is added when apt succeeds. False when no container is ready.
     */
    fun storeInstall(app: StoreApp): Boolean {
        val backend = backend() ?: return false
        if (blocker(_status.value, _mode.value) != null) return false
        val store = configStore(_status.value, _mode.value)
        container.queue.enqueue(
            app.name, listOf(app.pkg) + GUI_HELPER_PACKAGES, QueueAction.INSTALL,
            container.catalog.iconFile(app)?.path, backend,
        ) { store?.let { container.registry.register(app, it) } }
        return true
    }

    fun storeUninstall(app: StoreApp): Boolean {
        val backend = backend() ?: return false
        val store = configStore(_status.value, _mode.value)
        container.queue.enqueue(app.name, listOf(app.pkg), QueueAction.REMOVE, container.catalog.iconFile(app)?.path, backend) {
            container.registry.unregister(app, store)
        }
        return true
    }

    /**
     * Queues removing a launcher app's packages. Store apps also leave the launcher; bundled
     * ones stay, offering Install again. False when there's nothing to remove.
     */
    fun uninstall(app: PermissionManifest): Boolean {
        if (app.packages.isEmpty()) return false
        val backend = backend() ?: return false
        val store = configStore(_status.value, _mode.value)
        val fromStore = container.registry.isStoreApp(app.id)
        container.queue.enqueue(app.name, app.packages, QueueAction.REMOVE, app.iconFile, backend) {
            if (fromStore) container.registry.unregister(app.id, store) else container.registry.refreshInstalled(store)
        }
        return true
    }

    fun cancelJob(id: Int) = container.queue.cancel(id)

    fun clearFinishedJobs() = container.queue.clearFinished()

    fun retryJob(id: Int) = container.queue.retry(id)

    /** Queues the packages behind a launcher app that isn't installed yet. */
    fun install(app: PermissionManifest): Boolean {
        val backend = backend() ?: return false
        val store = configStore(_status.value, _mode.value)
        val packages = app.packages + if (app.gui) GUI_HELPER_PACKAGES else emptyList()
        container.queue.enqueue(app.name, packages, QueueAction.INSTALL, app.iconFile, backend) {
            container.registry.refreshInstalled(store)
        }
        return true
    }

    fun launch(app: PermissionManifest, onLaunched: (RunningSession) -> Unit) {
        viewModelScope.launch {
            val backend = backend()
            val problem = blocker(_status.value, _mode.value)
            if (backend == null || problem != null) {
                _messages.send(problem ?: "Backend unavailable")
                return@launch
            }
            // Main thread: TerminalSession binds to the caller's Looper. Spawning is a quick fork.
            runCatching { sessions.launch(app, backend) }
                .onSuccess(onLaunched)
                .onFailure { _messages.send("Couldn't start ${app.name}: ${it.message}") }
        }
    }

    private fun loadApps(app: Application): List<PermissionManifest> {
        val assets = app.assets
        return assets.list("apps").orEmpty().filter { it.endsWith(".json") }.mapNotNull { file ->
            val manifest = runCatching {
                PermissionManifest.parse(assets.open("apps/$file").reader().use { it.readText() })
            }.onFailure { Log.w(TAG, "skipping apps/$file", it) }.getOrNull()
            val errors = manifest?.validate().orEmpty()
            if (errors.isNotEmpty()) Log.w(TAG, "skipping apps/$file: $errors")
            manifest?.takeIf { errors.isEmpty() }
        }.sortedBy { it.name.lowercase() }
    }

    private companion object {
        const val TAG = "Launcher"
        const val KEY_MODE = "backend_mode"
    }
}
