// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.Build
import com.daryan.tessera.container.ConfigStore
import com.daryan.tessera.container.FsGrant
import com.daryan.tessera.files.FileBrowser
import com.daryan.tessera.files.FilesEngine
import com.daryan.tessera.files.GuestFiles
import com.daryan.tessera.store.CatalogState
import com.daryan.tessera.store.QueueAction
import com.daryan.tessera.store.QueueJob
import com.daryan.tessera.store.StoreApp
import com.daryan.tessera.store.StoreCatalog
import com.daryan.tessera.store.StoreRegistry
import com.daryan.tessera.store.StoreSource
import com.daryan.tessera.container.Flatpak
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.daryan.tessera.container.ContainerBackend
import com.daryan.tessera.container.DirectConfigStore
import com.daryan.tessera.container.GUI_HELPER_PACKAGES
import com.daryan.tessera.container.THEME_COMPANIONS
import com.daryan.tessera.container.ThemePack
import com.daryan.tessera.container.SuConfigStore
import com.daryan.tessera.container.Distro
import com.daryan.tessera.container.HostProbe
import com.daryan.tessera.container.HostStatus
import com.daryan.tessera.container.InstallState
import com.daryan.tessera.container.PackageInstall
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.container.ProcessTree
import com.daryan.tessera.container.PhantomLimit
import com.daryan.tessera.container.GpuDriver
import com.daryan.tessera.container.GpuFix
import com.daryan.tessera.container.ProotBackend
import com.daryan.tessera.container.UsbBridge
import com.daryan.tessera.container.Permissions
import com.daryan.tessera.container.RunningSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where USB drives and SD cards appear in apps allowed to see them. */
private const val DRIVES = "/media/user"

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

    /** Where the diagnostics appear inside a container that is granted them. */
    private fun shortcutManifest(s: Shortcut) = s.toManifest(container.diagnostics.dir)

    /** Bundled apps, those installed from the store and the user's shortcuts, as their manifests describe them. */
    private val manifests: StateFlow<List<PermissionManifest>> =
        combine(container.registry.userApps, container.shortcuts.shortcuts, container.systemApps.apps) { user, shortcuts, system ->
            val known = bundled + user.filterNot { u -> bundled.any { it.id == u.id } } + shortcuts.map(::shortcutManifest)
            known + newSystemApps(system, known)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, bundled + container.shortcuts.shortcuts.value.map(::shortcutManifest))

    /**
     * The container's own menu entries, minus what the launcher lists already: same name, or
     * the same program (Firefox's desktop file next to the bundled Firefox entry).
     */
    private fun newSystemApps(system: List<PermissionManifest>, known: List<PermissionManifest>): List<PermissionManifest> {
        fun program(m: PermissionManifest) = m.exec.firstOrNull()?.substringAfterLast('/')
        val names = known.map { it.name.lowercase() }.toSet()
        val programs = known.mapNotNull(::program).toSet() + known.flatMap { it.packages }
        return system.filterNot { it.name.lowercase() in names || program(it) in programs }
    }

    /** When chroot's menus were last read: that takes su, so coming back doesn't always redo it. */
    private var chrootScannedAt = 0L

    /**
     * Re-reads the container's menu entries; done whenever the launcher comes back, at most once
     * a minute in chroot mode unless [force]d (after installs and removals).
     */
    fun scanSystemApps(force: Boolean = false) {
        val status = _status.value ?: return
        // Each mode lists its own system's apps; chroot's is read through su.
        val su = status.su
        when {
            _mode.value == BackendMode.PROOT && status.prootRootfs ->
                viewModelScope.launch { container.systemApps.scan(paths.prootRootfs) }
            _mode.value == BackendMode.CHROOT && su != null && status.chrootRootfs == true -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!force && now - chrootScannedAt < 60_000) return
                chrootScannedAt = now
                viewModelScope.launch { container.systemApps.scanAsRoot(su, paths.chrootRootfs.path, File(getApplication<Application>().cacheDir, "chroot-icons")) }
            }
            else -> container.systemApps.clear()
        }
    }

    val shortcuts: StateFlow<List<Shortcut>> = container.shortcuts.shortcuts

    /** Adds [shortcut], or saves changes to it; a new one gets an id from its name. */
    fun saveShortcut(shortcut: Shortcut) {
        val s = if (shortcut.id.isNotEmpty()) shortcut
        else shortcut.copy(id = Shortcut.newId(shortcut.name, container.shortcuts.shortcuts.value.map { it.id }))
        container.shortcuts.save(s)
    }

    fun deleteShortcut(app: PermissionManifest) {
        container.shortcuts.delete(Shortcut.idOf(app))
        container.permissions.reset(app.id)
        container.exec.reset(app.id)
    }

    /** [app] with the permissions and the command the user set, where they changed either. */
    private fun withOverrides(app: PermissionManifest) = container.exec.apply(container.permissions.apply(app))

    /** Every app, with the permissions and commands the user set where they changed any. */
    val apps: StateFlow<List<PermissionManifest>> =
        combine(manifests, container.permissions.overrides, container.exec.overrides) { list, _, _ ->
            list.map(::withOverrides)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, bundled.map(::withOverrides))

    /** The permissions [appId]'s manifest asks for, before any change by the user. */
    fun defaultPermissions(appId: String): Permissions? = manifests.value.firstOrNull { it.id == appId }?.permissions

    /** Changes [app]'s permissions from its next launch; returns what's wrong with them, if anything. */
    fun setPermissions(app: PermissionManifest, permissions: Permissions): List<String> =
        container.permissions.set(app, permissions, defaultPermissions(app.id) ?: app.permissions)

    fun resetPermissions(appId: String) = container.permissions.reset(appId)

    val permissionOverrides: StateFlow<Map<String, Permissions>> = container.permissions.overrides

    /** The command [appId]'s manifest asks for, before any change by the user. */
    fun defaultExec(appId: String): List<String>? = manifests.value.firstOrNull { it.id == appId }?.exec

    /** Changes [app]'s command from its next launch; returns what's wrong with it, if anything. */
    fun setExec(app: PermissionManifest, exec: List<String>): List<String> =
        container.exec.set(app, exec, defaultExec(app.id) ?: app.exec)

    fun resetExec(appId: String) = container.exec.reset(appId)

    val catalog: StateFlow<CatalogState> = container.catalog.state
    val flathubCatalog: StateFlow<CatalogState> = container.flathub.state

    private val _storeSource = MutableStateFlow(
        runCatching { StoreSource.valueOf(app.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(KEY_STORE_SOURCE, null)!!) }
            .getOrDefault(StoreSource.DEBIAN),
    )
    /** Which store the Store tab shows. */
    val storeSource: StateFlow<StoreSource> = _storeSource

    private val _flathubReady = MutableStateFlow<Boolean?>(null)
    /** Whether Flathub is set up in the active container; null until checked. */
    val flathubReady: StateFlow<Boolean?> = _flathubReady

    /**
     * The Flathub apps installed from the store (they have launcher entries) whose files are in
     * the active container, by Flathub id.
     */
    val flathubInstalled: StateFlow<Set<String>> = combine(container.registry.userApps, container.registry.flatpaks) { list, present ->
        list.mapNotNull { it.flatpak?.app }.filter { present == null || it in present }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun setStoreSource(source: StoreSource) {
        _storeSource.value = source
        prefs.edit().putString(KEY_STORE_SOURCE, source.name).apply()
        openStore()
    }
    val installedPackages: StateFlow<Set<String>?> = container.registry.installed

    private val _status = MutableStateFlow<HostStatus?>(null)
    val status: StateFlow<HostStatus?> = _status

    private val prefs = app.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        runCatching { BackendMode.valueOf(prefs.getString(KEY_MODE, null)!!) }.getOrDefault(BackendMode.PROOT),
    )
    val mode: StateFlow<BackendMode> = _mode

    /** Whether su has worked here before (in chroot mode), so using it again asks nothing. */
    var rootGranted = prefs.getBoolean(KEY_ROOT_GRANTED, false)
        private set

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** The first-run introduction; also openable again from Setup. */
    private val _introSeen = MutableStateFlow(prefs.getBoolean(LauncherContainer.KEY_INTRO_SEEN, false))
    val introSeen: StateFlow<Boolean> = _introSeen

    fun setIntroSeen(seen: Boolean) {
        prefs.edit().putBoolean(LauncherContainer.KEY_INTRO_SEEN, seen).apply()
        _introSeen.value = seen
    }

    private val _diagnosticsEnabled = MutableStateFlow(prefs.getBoolean(LauncherContainer.KEY_DIAGNOSTICS, true))

    /** Whether ended sessions are recorded for the user (and for whoever helps them). */
    val diagnosticsEnabled: StateFlow<Boolean> = _diagnosticsEnabled

    fun setDiagnosticsEnabled(on: Boolean) {
        prefs.edit().putBoolean(LauncherContainer.KEY_DIAGNOSTICS, on).apply()
        _diagnosticsEnabled.value = on
        // Off means gone, not just paused: these name the user's apps and folders.
        if (!on) viewModelScope.launch(Dispatchers.IO) { container.diagnostics.clear() }
    }

    /**
     * Everything an assistant needs to diagnose this phone, as Markdown: how Tessera works, how
     * it is set up here, and the sessions that ended most recently. For the clipboard, and
     * written where a container that was granted it can read the same thing.
     */
    suspend fun diagnosticsReport(): String = withContext(Dispatchers.IO) {
        val state = diagnosticsState()
        container.diagnostics.writeState(state)
        container.diagnostics.report(state)
    }

    /** How this phone is set up, in the user's terms. */
    private fun diagnosticsState(): String {
        val status = _status.value
        val apps = apps.value
        val context = getApplication<Application>()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return buildString {
            appendLine("## This phone")
            appendLine()
            appendLine("- Tessera $version")
            appendLine("- ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("- ABI: ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine("- Engine: ${_mode.value.label}${if (status?.su != null) " (root available)" else " (no root)"}")
            appendLine("- Debian installed: PRoot ${status?.prootRootfs}, chroot ${status?.chrootRootfs}")
            appendLine("- PRoot binary present: ${status?.prootBinary}")
            appendLine("- Background process limit on: ${status?.phantomProcessLimit} (on means Android may kill apps in the background)")
            appendLine("- GPU driver available: ${gpuAvailable()}, acceleration on: ${_gpuEnabled.value}")
            appendLine()
            appendLine("## Apps in the launcher (${apps.size})")
            appendLine()
            apps.forEach { a ->
                val overridden = if (a.id in container.permissions.overrides.value) " [permissions changed by the user]" else ""
                appendLine("- ${a.name} (`${a.id}`): `${a.exec.joinToString(" ")}`${if (a.gui) ", windowed" else ", terminal"}$overridden")
            }
        }
    }

    init {
        refresh()
        viewModelScope.launch { apps.collect { container.launcherApps.value = it } }
        // Re-probe once an install finishes so Setup and the launch buttons update.
        viewModelScope.launch {
            installState.collect { if (it == InstallState.Done) refresh() }
        }
    }

    /** Installs Linux for the current mode: into app storage for PRoot, as root for chroot. */
    fun install(distro: Distro = Distro.DEBIAN) {
        val su = _status.value?.su
        if (_mode.value == BackendMode.CHROOT && su != null) container.installer.start(distro, su)
        else container.installer.start(distro)
    }

    fun cancelInstall() = container.installer.cancel()

    /** Whether this phone and build can use hardware graphics at all. */
    fun gpuAvailable() = GpuDriver.available(getApplication())

    private val _gpuEnabled = MutableStateFlow(sessions.gpuEnabled())
    val gpuEnabled: StateFlow<Boolean> = _gpuEnabled

    private val _gpuApps = MutableStateFlow(0)
    /** Bumped when an app's own GPU switch or fixes change, so sheets re-read them. */
    val gpuApps: StateFlow<Int> = _gpuApps

    /** [app]'s GPU acceleration, or null when it can't have any. */
    fun gpuFor(app: PermissionManifest): Boolean? =
        if (app.gui && gpuAvailable()) sessions.gpuFor(app.id) else null

    fun setGpuFor(app: PermissionManifest, on: Boolean) {
        sessions.setGpuFor(app.id, on)
        _gpuApps.value++
    }

    fun gpuFixesFor(app: PermissionManifest): Set<GpuFix> = sessions.gpuFixesFor(app.id)

    fun setGpuFix(app: PermissionManifest, fix: GpuFix, on: Boolean) {
        val fixes = sessions.gpuFixesFor(app.id)
        sessions.setGpuFixesFor(app.id, if (on) fixes + fix else fixes - fix)
        _gpuApps.value++
    }

    /** How Linux apps look; see docs/LOOK-AND-FEEL.md. */
    val appearance = container.appearance.global

    /** Changes how Linux apps look; running ones follow at once. */
    fun setAppearance(appearance: com.daryan.tessera.container.Appearance) {
        container.appearance.setGlobal(appearance)
        sessions.refreshThemes()
    }

    /** Linux themes the user imported. */
    val linuxThemes = container.linuxThemes.themes

    /**
     * Imports the Linux themes in the archive at [uri] and switches to the first, so Linux apps
     * and Tessera take it at once. Says what happened either way.
     */
    fun importTheme(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { container.linuxThemes.import(it) }
                        ?: error("Couldn't open that file")
                }
            }
            result.onSuccess { imported ->
                val families = imported.map { it.family }.distinct()
                setAppearance(container.appearance.global.value.copy(enabled = true, linuxTheme = families.first()))
                _messages.send("Imported ${families.joinToString()}" + if (imported.size > families.size) " (light and dark)" else "")
            }.onFailure { _messages.send("Couldn't import: ${it.message}") }
        }
    }

    /** Removes an imported theme; back to the phone's colors if it was in use. */
    fun deleteTheme(family: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.linuxThemes.delete(family) }
            val look = container.appearance.global.value
            if (look.linuxTheme == family) setAppearance(look.copy(linuxTheme = null))
        }
    }

    /** Apps the user left out of theming. */
    val unthemed = container.appearance.unthemed

    fun setThemed(app: PermissionManifest, themed: Boolean) = container.appearance.setThemed(app.id, themed)

    fun setGpuEnabled(on: Boolean) {
        sessions.setGpuEnabled(on)
        _gpuEnabled.value = on
    }

    /** Lifts Android's background process limit through su, then checks again. */
    fun liftPhantomLimit() {
        val su = _status.value?.su ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { PhantomLimit.lift(su) }
            if (!ok) _messages.send("Couldn't change it; was root access refused?")
            refresh()
        }
    }

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
        val apps = apps.filterNot { it.desktop }
        val byId = apps.associateBy { it.id }
        val pins = pinned.mapNotNull { byId[it] }
        fun arranged(list: List<PermissionManifest>) = pins.filter { it in list } + list.filterNot { it.id in pinned }
        return buildList {
            add(LauncherTab("All", arranged(apps), custom = false))
            val favs = favorites.mapNotNull { byId[it] }
            if (favs.isNotEmpty()) add(LauncherTab(FAVORITES_TAB, arranged(favs), custom = false))
            // Always there, even empty: it's where new shortcuts are made.
            add(LauncherTab(SHORTCUTS_TAB, arranged(apps.filter { it.category == SHORTCUTS_TAB }), custom = false))
            apps.map { it.category }.filter { it != SHORTCUTS_TAB }.distinct().sorted().forEach { cat ->
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
            val s = withContext(Dispatchers.IO) {
                probe.probe().let { s ->
                    s.copy(
                        phantomProcessLimit = PhantomLimit.lifted(getApplication(), s.su) != true,
                        // Only in chroot mode: it takes su, which PRoot users shouldn't be asked for.
                        chrootRootfs = if (_mode.value == BackendMode.CHROOT && s.su != null) probe.chrootInstalled(s.su) else null,
                    )
                }
            }
            _status.value = s
            // su answered (chroot mode found or didn't find its system): root was granted, so
            // the task manager may use it in PRoot mode too, without a prompt nobody asked for.
            if (s.chrootRootfs != null && !rootGranted) {
                rootGranted = true
                prefs.edit().putBoolean(KEY_ROOT_GRANTED, true).apply()
            }
            if (s.su == null && _mode.value == BackendMode.CHROOT) _mode.value = BackendMode.PROOT
            // Chroot sessions an earlier run of the app left behind (an update, being killed):
            // once a run, before anything starts. Only root can see them.
            if (_mode.value == BackendMode.CHROOT && s.su != null && !container.chrootSwept && sessions.sessions.value.none { it.running }) {
                container.chrootSwept = true
                withContext(Dispatchers.IO) { ProcessTree.killSessionAsRoot(s.su, null) }
            }
            // Unpack the GPU driver ahead of launches (after Linux is installed, or an app update).
            if (s.prootRootfs && GpuDriver.available(getApplication())) {
                withContext(Dispatchers.IO) { GpuDriver.install(getApplication(), paths.prootRootfs) }
            }
            if (s.prootRootfs && UsbBridge.available(getApplication())) {
                withContext(Dispatchers.IO) { UsbBridge.install(getApplication(), paths.prootRootfs) }
            }
            if (s.prootRootfs && ThemePack.bundle.available(getApplication())) {
                withContext(Dispatchers.IO) { ThemePack.bundle.install(getApplication(), paths.prootRootfs) }
            }
            if (_mode.value == BackendMode.CHROOT && s.su != null && s.chrootRootfs == true && GpuDriver.available(getApplication())) {
                withContext(Dispatchers.IO) { GpuDriver.installAsRoot(getApplication(), paths.chrootRootfs, s.su) }
            }
            if (_mode.value == BackendMode.CHROOT && s.su != null && s.chrootRootfs == true && ThemePack.bundle.available(getApplication())) {
                withContext(Dispatchers.IO) { ThemePack.bundle.installAsRoot(getApplication(), paths.chrootRootfs, s.su) }
            }
            addMissingGuiHelpers()
            configStore(_status.value, _mode.value)?.let { container.registry.refreshInstalled(it) }
            scanSystemApps(force = true)
        }
    }

    /**
     * Containers set up before a GUI helper was added (the focus watcher's AT-SPI packages)
     * get it queued once: only where GUI apps are in use, i.e. the window manager is there.
     * Theme companions ([THEME_COMPANIONS]) of installed toolkits come along.
     */
    private suspend fun addMissingGuiHelpers() {
        if (_mode.value.name in container.guiHelpersChecked) return
        val store = configStore(_status.value, _mode.value) ?: return
        val backend = backend() ?: return
        container.guiHelpersChecked += _mode.value.name
        container.registry.refreshInstalled(store)
        val installed = container.registry.installed.value ?: return
        if ("matchbox-window-manager" !in installed) return
        // And what lets Qt and LibreOffice, where installed, follow the theme.
        val companions = THEME_COMPANIONS.filterKeys { it in installed }.values.filterNot { it in installed }
        val missing = GUI_HELPER_PACKAGES.filterNot { it in installed } + companions
        if (missing.isEmpty()) return
        container.queue.enqueue("Sound, keyboard and theme helpers", missing, QueueAction.INSTALL, null, backend) {
            container.registry.refreshInstalled(store)
        }
    }

    fun setMode(mode: BackendMode) {
        _mode.value = mode
        // Each mode's system has its own Flathub setup.
        _flathubReady.value = null
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        // Each mode has its own system: check it, and what's installed in it.
        refresh()
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
        mode == BackendMode.CHROOT && status.chrootRootfs == false -> "No Linux system for chroot mode yet"
        else -> null
    }

    /**
     * Whether [app] is installed in the container. Null when that can't be checked: chroot
     * rootfs isn't readable by the app, or the manifest names no packages.
     */
    fun installed(app: PermissionManifest, status: HostStatus?, mode: BackendMode): Boolean? = when {
        // A Flathub app's entry is shared by both engines; its files may be in the other's system.
        app.flatpak != null -> container.registry.flatpaks.value?.let { app.flatpak.app in it }
        // A shortcut's command is bash; what it needs is its packages.
        Shortcut.isShortcut(app) ->
            if (app.packages.isEmpty()) null
            else container.registry.installed.value?.let { have -> app.packages.all { it in have } }
        mode == BackendMode.PROOT && status?.prootRootfs == true -> PackageInstall.isInstalled(app, paths.prootRootfs)
        // Chroot mode has its own system, read through su: its dpkg status says what's there.
        mode == BackendMode.CHROOT && app.packages.isNotEmpty() ->
            container.registry.installed.value?.let { have -> app.packages.all { it in have } }
        else -> null
    }

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
        if (_storeSource.value == StoreSource.FLATHUB) {
            viewModelScope.launch {
                val ready = checkFlathub(store)
                if (ready) container.flathub.load(refresh)
            }
            return
        }
        viewModelScope.launch {
            val release = runCatching { store?.read("/etc/os-release") }.getOrNull()
            val suite = release?.lineSequence()?.firstOrNull { it.startsWith("VERSION_CODENAME=") }
                ?.substringAfter('=')?.trim('"')?.takeIf { it.isNotBlank() } ?: "trixie"
            container.catalog.load(suite, StoreCatalog.debianArch(Build.SUPPORTED_ABIS.first()), refresh)
            container.registry.refreshInstalled(store)
        }
    }

    fun storeIcon(app: StoreApp) =
        if (app.source == StoreSource.FLATHUB) container.flathub.iconFile(app) else container.catalog.iconFile(app)

    /** [app]'s icon, downloading it first for catalogs that don't pack them (Flathub's). */
    suspend fun loadStoreIcon(app: StoreApp): File? =
        if (app.source == StoreSource.FLATHUB) container.flathub.icon(app) else container.catalog.iconFile(app)

    /** What [app]'s jobs are called in the install queue's package lists. */
    fun storeJobKey(app: StoreApp) = if (app.source == StoreSource.FLATHUB) flatpakJob(app.id) else app.pkg

    /** Flathub's setup job, while it's waiting or running. */
    fun flathubSetupJob(jobs: List<QueueJob>) = jobs.lastOrNull { FLATHUB_SETUP in it.packages }

    /** Whether Flathub is set up in the active container (flatpak and its remote); also updates [flathubReady]. */
    private suspend fun checkFlathub(store: ConfigStore?): Boolean {
        val ready = store != null && Flatpak.isSetUp(runCatching { store.read(Flatpak.REPO_CONFIG) }.getOrNull())
        _flathubReady.value = ready
        return ready
    }

    /**
     * Sets Flathub up in the active container, once: flatpak, the Flathub remote and the GUI
     * helpers, in the install queue. Then its catalog loads. False when no container is ready.
     */
    fun setupFlathub(): Boolean {
        val backend = backend() ?: return false
        if (blocker(_status.value, _mode.value) != null) return false
        val store = configStore(_status.value, _mode.value)
        container.queue.enqueue(
            "Flathub", listOf(FLATHUB_SETUP), QueueAction.INSTALL, null, backend,
            script = Flatpak.setupScript(GUI_HELPER_PACKAGES),
        ) {
            if (checkFlathub(store)) container.flathub.load()
            container.registry.refreshInstalled(store)
        }
        return true
    }

    /** [app]'s screenshots, downloaded; empty when there are none. */
    suspend fun storeScreenshots(app: StoreApp): List<File> = container.screenshots.forApp(app)

    val queue: StateFlow<List<QueueJob>> = container.queue.jobs
    val showAppRequests = container.showAppRequests
    val launchRequests = container.launchRequests

    /**
     * Queues a store app's install; it runs in the background after any before it, and its
     * launcher entry is added when apt succeeds. False when no container is ready.
     */
    fun storeInstall(app: StoreApp): Boolean {
        val backend = backend() ?: return false
        if (blocker(_status.value, _mode.value) != null) return false
        val store = configStore(_status.value, _mode.value)
        if (app.source == StoreSource.FLATHUB) {
            if (!Flatpak.validId(app.id) || store == null) return false
            container.queue.enqueue(
                app.name, listOf(flatpakJob(app.id)), QueueAction.INSTALL, container.flathub.iconFile(app)?.path, backend,
                script = Flatpak.installScript(app.id),
            ) { container.registry.registerFlatpak(app, store, container.flathub.icon(app)) }
            return true
        }
        container.queue.enqueue(
            app.name, listOf(app.pkg) + GUI_HELPER_PACKAGES, QueueAction.INSTALL,
            container.catalog.iconFile(app)?.path, backend,
        ) { store?.let { container.registry.register(app, it) } }
        return true
    }

    fun storeUninstall(app: StoreApp): Boolean {
        val backend = backend() ?: return false
        val store = configStore(_status.value, _mode.value)
        if (app.source == StoreSource.FLATHUB) {
            if (!Flatpak.validId(app.id)) return false
            container.queue.enqueue(
                app.name, listOf(flatpakJob(app.id)), QueueAction.REMOVE, container.flathub.iconFile(app)?.path, backend,
                script = Flatpak.uninstallScript(app.id),
            ) { container.registry.unregister(StoreRegistry.idFor(app), store) }
            return true
        }
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
        app.flatpak?.let { ref ->
            val backend = backend() ?: return false
            val store = configStore(_status.value, _mode.value)
            if (!Flatpak.validId(ref.app)) return false
            container.queue.enqueue(
                app.name, listOf(flatpakJob(ref.app)), QueueAction.REMOVE, app.iconFile, backend,
                script = Flatpak.uninstallScript(ref.app),
            ) { container.registry.unregister(app.id, store) }
            return true
        }
        if (app.packages.isEmpty()) return false
        val backend = backend() ?: return false
        val store = configStore(_status.value, _mode.value)
        val fromStore = container.registry.isStoreApp(app.id)
        container.queue.enqueue(app.name, app.packages, QueueAction.REMOVE, app.iconFile, backend) {
            if (fromStore) container.registry.unregister(app.id, store) else container.registry.refreshInstalled(store)
            scanSystemApps(force = true)
        }
        return true
    }

    /**
     * Uninstalls an app found in the container's menus: its package is looked up from dpkg's
     * file lists first. False when there's no container or no owning package.
     */
    suspend fun uninstallSystemApp(app: PermissionManifest): Boolean {
        val su = _status.value?.su
        val pkg = (
            if (_mode.value == BackendMode.CHROOT && su != null) container.systemApps.packageOfAsRoot(app, su, paths.chrootRootfs.path)
            else container.systemApps.packageOf(app, paths.prootRootfs)
        ) ?: return false
        return uninstall(app.copy(packages = listOf(pkg)))
    }

    fun cancelJob(id: Int) = container.queue.cancel(id)

    fun clearFinishedJobs() = container.queue.clearFinished()

    fun retryJob(id: Int) = container.queue.retry(id)

    /** Queues the packages behind a launcher app that isn't installed yet. */
    fun install(app: PermissionManifest): Boolean {
        val backend = backend() ?: return false
        val store = configStore(_status.value, _mode.value)
        app.flatpak?.let { ref ->
            if (!Flatpak.validId(ref.app) || store == null) return false
            container.queue.enqueue(
                app.name, listOf(flatpakJob(ref.app)), QueueAction.INSTALL, app.iconFile, backend,
                script = Flatpak.installScript(ref.app),
            ) { container.registry.reresolveFlatpak(app, store) }
            return true
        }
        val packages = app.packages + if (app.gui) GUI_HELPER_PACKAGES else emptyList()
        container.queue.enqueue(app.name, packages, QueueAction.INSTALL, app.iconFile, backend) {
            container.registry.refreshInstalled(store)
        }
        return true
    }

    /** The Files tab; it lives here so its folder, clipboard and running copies survive the screen. */
    val files = FileBrowser(app, viewModelScope, paths)

    /** Where the Files tab runs for [mode], or why it can't. */
    fun filesEngine(status: HostStatus?, mode: BackendMode): FilesEngine {
        val problem = blocker(status, mode)
        return FilesEngine(mode.name, if (problem == null) backend(mode) else null, problem)
    }

    /**
     * Opens a terminal in [dir]: the Terminal app's, or the root shell's when [root]. A folder
     * of the phone's storage is shared with it for the occasion.
     */
    fun terminalIn(dir: String, root: Boolean, onLaunched: (RunningSession) -> Unit) {
        val base = apps.value.firstOrNull { it.id == if (root) "root-shell" else "bash" } ?: return
        val grants = if (GuestFiles.onAndroid(dir)) base.permissions.filesystem + FsGrant(dir) else base.permissions.filesystem
        val app = base.copy(
            exec = listOf("/bin/bash", "-c", "cd \"\$1\" && exec /bin/bash -l", "bash", dir),
            permissions = base.permissions.copy(filesystem = grants),
        )
        launch(app, onLaunched)
    }

    /** The launcher's entry for a Linux app that opens files, so it runs with its own permissions. */
    fun linuxAppFor(desktopFile: String, entry: com.daryan.tessera.store.DesktopEntry): PermissionManifest? {
        val id = com.daryan.tessera.store.SystemApps.manifestFor(desktopFile, entry, null).id
        val program = entry.exec.firstOrNull()?.substringAfterLast('/')
        return apps.value.firstOrNull { it.id == id }
            ?: apps.value.firstOrNull { program != null && !com.daryan.tessera.Shortcut.isShortcut(it) && it.exec.firstOrNull()?.substringAfterLast('/') == program }
    }

    /** Starts [opener] on [path]; a folder of the phone's storage is shared with it for the occasion. */
    fun openInLinux(opener: com.daryan.tessera.ui.LinuxOpener, path: String, onLaunched: (RunningSession) -> Unit) {
        val base = opener.app ?: com.daryan.tessera.store.SystemApps.manifestFor(opener.desktopFile, opener.entry, null)
        val dir = path.substringBeforeLast('/').ifEmpty { "/" }
        val grants = if (GuestFiles.onAndroid(dir) && base.permissions.filesystem.none { it.mountAt == dir }) {
            base.permissions.filesystem + FsGrant(dir)
        } else base.permissions.filesystem
        launch(base.copy(exec = base.exec + path, permissions = base.permissions.copy(filesystem = grants)), onLaunched)
    }

    /** Queues installing a .deb from the Files tab, with whatever it depends on. */
    fun installDeb(entry: com.daryan.tessera.files.FileEntry, info: com.daryan.tessera.files.DebInfo) {
        viewModelScope.launch {
            val backend = backend()
            val problem = blocker(_status.value, _mode.value)
            if (backend == null || problem != null) {
                _messages.send(problem ?: "Linux isn't ready")
                return@launch
            }
            val staged = runCatching { files.stageDeb(entry, info) }.getOrElse {
                _messages.send(it.message ?: "Couldn't get ${entry.name} ready")
                return@launch
            }
            val store = configStore(_status.value, _mode.value)
            container.queue.enqueue(info.pkg, listOf(staged), QueueAction.INSTALL, null, backend) {
                files.removeStaged(staged)
                container.registry.refreshInstalled(store)
                scanSystemApps(force = true)
            }
            _messages.send("Installing ${info.pkg}; follow it under Installs")
        }
    }

    /**
     * [app] with the drives plugged in right now shared at /media/user/<name>, when it may see
     * drives; Android has already mounted them, so they're folders like any other.
     */
    private fun withDrives(app: PermissionManifest): PermissionManifest {
        if (!app.permissions.drives) return app
        files.refreshVolumes()
        val taken = mutableSetOf<String>()
        val grants = files.storage.filterNot { it.primary }.map { v ->
            var name = v.title.replace('/', '_').filterNot { it.isISOControl() }.trim().ifEmpty { v.path.substringAfterLast('/') }
            if (!taken.add(name)) name = "$name (${v.path.substringAfterLast('/')})".also { taken += it }
            FsGrant(v.path, "$DRIVES/$name", readOnly = v.readOnly)
        }
        val keep = app.permissions.filesystem.filterNot { it.mountAt.startsWith("$DRIVES/") }
        return app.copy(permissions = app.permissions.copy(filesystem = keep + grants))
    }

    /**
     * PRoot's take on all USB devices: those plugged in now, opened through the bridge like any
     * listed device. Chroot shares the whole folder instead (enter.sh).
     */
    private fun withPluggedInUsb(app: PermissionManifest): PermissionManifest {
        if (!app.permissions.usb) return app
        val usb = getApplication<Application>().getSystemService(android.hardware.usb.UsbManager::class.java)
        val plugged = usb?.deviceList?.values?.map { it.deviceName }.orEmpty().filter { it.startsWith(UsbBridge.USB_PATH) }
        return app.copy(permissions = app.permissions.copy(devices = (app.permissions.devices + plugged).distinct()))
    }

    fun launch(app: PermissionManifest, onLaunched: (RunningSession) -> Unit) {
        val requested = app
        viewModelScope.launch {
            val backend = backend()
            val problem = blocker(_status.value, _mode.value)
            if (backend == null || problem != null) {
                _messages.send(problem ?: "Backend unavailable")
                return@launch
            }
            val app = withDrives(requested).let { if (backend is ProotBackend) withPluggedInUsb(it) else it }
            // PRoot apps reach their USB devices through the bridge: open them first (this may
            // ask the user for each device's permission).
            val usb = if (backend is ProotBackend && app.permissions.devices.any { it.startsWith(UsbBridge.USB_PATH) }) {
                UsbBridge.open(getApplication(), app.permissions.devices).also { opened ->
                    if (opened.missing.isNotEmpty()) {
                        _messages.send("${app.name}: couldn't open ${opened.missing.joinToString()} (unplugged, or permission refused)")
                    }
                }
            } else null
            // Main thread: TerminalSession binds to the caller's Looper. Spawning is a quick fork.
            runCatching { sessions.launch(app, backend, usb) }
                .onSuccess(onLaunched)
                .onFailure {
                    usb?.close()
                    _messages.send("Couldn't start ${app.name}: ${it.message}")
                }
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
        const val KEY_ROOT_GRANTED = "root_granted"
        const val KEY_STORE_SOURCE = "store_source"
        /** What stands for Flathub's setup and its apps in the install queue's package lists. */
        const val FLATHUB_SETUP = "flathub-setup"
        fun flatpakJob(id: String) = "flathub:$id"
    }
}
