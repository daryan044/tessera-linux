// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class RunningSession(
    val session: Session,
    val app: PermissionManifest,
    val backend: String,
    val pid: Int,
    /** Interactive sessions (command-line apps). Null for GUI apps, which get a plain pty. */
    val terminal: TerminalSession? = null,
    /** Pty output tail for GUI apps; terminal sessions read their screen instead. */
    val output: String = "",
    val title: String? = null,
    val exitCode: Int? = null,
    /** Why it ended, when the user ended it; null while running or when the app exited itself. */
    val endedBy: EndReason? = null,
    /** Bumped on every screen change so card previews recompose. */
    val revision: Int = 0,
    /** A GUI app's X windows, as its window watcher last reported them. */
    val windows: XWindows = XWindows(),
    /** A desktop's icons (~/Desktop), as its helper last listed them. */
    val desktopItems: List<DesktopItem> = emptyList(),
) {
    val running get() = exitCode == null

    /** "Running", "Stopped", "Killed by user" or "Exited (code)". */
    val statusLabel: String
        get() = when {
            running && endedBy != null -> "Stopping"
            running -> "Running"
            endedBy != null -> endedBy.label
            else -> "Exited ($exitCode)"
        }

    /** Last lines currently on screen, for the session card. */
    fun preview(lines: Int = 8): String = terminal?.emulator?.screen?.transcriptText
        ?.lines()?.dropLastWhile { it.isBlank() }?.takeLast(lines)?.joinToString("\n")
        ?: output
}

enum class EndReason(val label: String) {
    /** Stop button, notification's Stop all, or dismissing the card. */
    STOPPED("Stopped"),
    /** The app's card was swiped away in Recents. */
    CLOSED_FROM_RECENTS("Killed by user"),
}

/** Owns launched processes; lives in [com.daryan.tessera.LauncherContainer]. */
class SessionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val paths: ContainerPaths,
    /** Where ended sessions are recorded for the user to read; null turns that off. */
    private val diagnostics: Diagnostics? = null,
    /** How Linux apps look; null leaves them unthemed. */
    private val appearance: AppearanceSettings? = null,
    /** Imported Linux themes, one of which [appearance] may name. */
    private val linuxThemes: LinuxThemes? = null,
) {
    private val nextId = AtomicInteger(1)
    private val _sessions = MutableStateFlow<List<RunningSession>>(emptyList())
    val sessions: StateFlow<List<RunningSession>> = _sessions

    private val x11 = X11Server(context, paths)

    init {
        // Nothing from a previous process is still running; its session dirs are just litter.
        java.io.File(paths.cacheDir, "sessions").deleteRecursively()
    }
    /** Running X servers, by session id. */
    private val servers = ConcurrentHashMap<String, Process>()
    /** What GUI sessions' helpers report, by session id. */
    private val windowReports = ConcurrentHashMap<String, SessionFiles>()

    private val _focusChanges = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 16)
    /** (session id, whether a text field now has focus), from each GUI session's focus watcher. */
    val focusChanges: kotlinx.coroutines.flow.SharedFlow<Pair<String, Boolean>> = _focusChanges
    private val _desktopEvents = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, DesktopEvent>>(extraBufferCapacity = 16)
    /** (session id, a tap or a menu request on a desktop icon), from each desktop's helper. */
    val desktopEvents: kotlinx.coroutines.flow.SharedFlow<Pair<String, DesktopEvent>> = _desktopEvents

    /** Linux apps' notifications, shown as Android's. */
    private val notifier by lazy { LinuxNotifier(context) }

    /** A notification's [action] ("default" for a tap), for the app that sent it. */
    fun notificationAction(sessionId: String, id: Int, action: String) {
        val running = byId(sessionId) ?: return
        sendLine(File(paths.x11SocketDir(running.session), NOTIFY_COMMANDS_NAME), "action $id ${action.filterNot { it.isWhitespace() }}")
    }

    fun notificationDismissed(sessionId: String, id: Int) {
        val running = byId(sessionId) ?: return
        sendLine(File(paths.x11SocketDir(running.session), NOTIFY_COMMANDS_NAME), "dismissed $id")
    }

    /** Per GUI session, the X windows that have an Android task of their own. */
    private val windowTasks = ConcurrentHashMap<String, MutableSet<Long>>()

    /** Called when a session's screen changes; set by whichever terminal view shows it. */
    private val screenListeners = ConcurrentHashMap<String, () -> Unit>()

    fun setScreenListener(id: String, listener: (() -> Unit)?) {
        if (listener == null) screenListeners.remove(id) else screenListeners[id] = listener
    }

    /** The user's GPU acceleration setting; on by default where it's available. */
    fun gpuEnabled() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GPU, true)

    fun setGpuEnabled(on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GPU, on).apply()

    /** GPU acceleration for [appId]: its own switch when the user set one, else the global setting. */
    fun gpuFor(appId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_GPU_APP + appId)) prefs.getBoolean(KEY_GPU_APP + appId, false) else gpuEnabled()
    }

    fun setGpuFor(appId: String, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GPU_APP + appId, on).apply()

    /** [appId]'s graphics fixes: the ones the user chose, else the defaults. */
    fun gpuFixesFor(appId: String): Set<GpuFix> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_GPU_FIXES + appId, null)
            ?: return GpuFix.entries.filter { it.default }.toSet()
        return GpuFix.entries.filter { it.name in saved }.toSet()
    }

    fun setGpuFixesFor(appId: String, fixes: Set<GpuFix>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_GPU_FIXES + appId, fixes.map { it.name }.toSet()).apply()

    /** Must be called on the main thread: TerminalSession binds to the caller's Looper. */
    /** USB devices open for running sessions, closed when they end. */
    private val usbDevices = ConcurrentHashMap<String, UsbBridge.Opened>()

    /**
     * [usb]: the app's USB devices, opened for it when it runs in PRoot ([UsbBridge]). [adb]:
     * where it reaches this phone's adbd ([PhoneAdb]).
     */
    @Throws(IOException::class)
    fun launch(app: PermissionManifest, backend: ContainerBackend, usb: UsbBridge.Opened? = null, adb: String? = null): RunningSession {
        // An id whose leftovers can be cleared: a chroot session's files are root's, which the app
        // can't delete, and a PRoot session reusing its directory found the window watcher's
        // command pipe there unusable (PermissionError, and no Android task per window).
        val n = generateSequence { nextId.getAndIncrement() }.first { id ->
            File(paths.cacheDir, "sessions/s$id").let { it.deleteRecursively() || !it.exists() }
        }
        // Hardware graphics for windowed apps, when it's on and the driver is in that system.
        val gpu = app.gui && gpuFor(app.id) && when (backend) {
            is ProotBackend -> GpuDriver.installed(context, paths.prootRootfs)
            is ChrootBackend -> GpuDriver.installedAsRoot(context)
            else -> false
        }
        val session = Session(
            id = "s$n",
            display = if (app.permissions.display) n else null,
            gpu = gpu,
            gpuFixes = if (gpu) gpuFixesFor(app.id) else emptySet(),
            usbFds = if (backend is ProotBackend) usb?.fds.orEmpty() else emptyMap(),
            theme = app.gui && appearance?.forApp(app.id) != null,
            flatpak = app.gui && app.flatpak != null,
            adb = adb,
        )
        // Ids restart with the process, so a previous run's s1 may have left a dead X socket here.
        paths.sessionTmp(session).apply {
            deleteRecursively()
            mkdirs()
        }
        paths.sessionShm(session).apply {
            deleteRecursively()
            mkdirs()
        }
        if (app.permissions.display) paths.x11SocketDir(session).mkdirs()
        // The helpers' command pipes are made here, not by the helpers: in chroot mode a pipe
        // the guest makes carries su's SELinux label, and Android refuses the app writing to it.
        if (app.gui) {
            val pipes = listOf(WINDOW_COMMANDS_NAME, NOTIFY_COMMANDS_NAME) + if (app.desktop) listOf(DESKTOP_COMMANDS_NAME) else emptyList()
            pipes.forEach { makeFifo(File(paths.x11SocketDir(session), it)) }
        }
        if (session.theme) writeTheme(app, session, backend.name)
        if (session.usbFds.isNotEmpty()) UsbBridge.makeTree(paths.sessionUsb(session), session.usbFds)
        usb?.let { usbDevices[session.id] = it }
        // A stopped Firefox leaves a profile lock naming a pid Android will soon reuse.
        if (backend is ProotBackend) runCatching { StaleLocks.clean(paths.prootRootfs, android.os.Process.myUid()) }
        // A Flathub app: the session runs around FLATPAK_HOLD, and the app beside it.
        val spec = backend.buildLaunch(if (session.flatpak) app.copy(exec = listOf("/bin/sh", "-c", FLATPAK_HOLD)) else app, session)

        if (app.desktop) desktopBackends[session.id] = backend
        if (backend is ChrootBackend) rootKill[session.id] = backend.su
        val running = if (app.gui) launchGui(app, backend, session, spec) else launchTerminal(app, backend, session, spec)
        _sessions.update { it + running }
        return running
    }

    private fun launchTerminal(app: PermissionManifest, backend: ContainerBackend, session: Session, spec: LaunchSpec): RunningSession {
        val terminal = TerminalSession(
            spec.executable,
            spec.cwd,
            spec.argv.toTypedArray(),
            spec.envp(),
            TRANSCRIPT_ROWS,
            Client(session.id),
        )
        // Start the process now rather than when a view first attaches; the view resizes it.
        terminal.initializeEmulator(80, 24, 12, 24)
        usbDevices[session.id]?.closeInherited()
        return RunningSession(session, app, backend.name, terminal.pid, terminal = terminal)
    }

    /**
     * GUI apps: start the session's own X server, open its window, then run the app on a pty
     * with DISPLAY pointing at it. Everything after the server start happens off the main thread.
     */
    private fun launchGui(app: PermissionManifest, backend: ContainerBackend, session: Session, spec: LaunchSpec): RunningSession {
        val audio = if (app.permissions.audio) AudioPipe(File(paths.x11SocketDir(session), AUDIO_FIFO_NAME)) else null
        scope.launch(Dispatchers.IO) {
            try {
                servers[session.id] = x11.start(session)
                followWindows(session, ownTasks = !app.desktop)
                audio?.let { launch { it.run() } }
                withContext(Dispatchers.Main) { x11.showWindow(session) }
                awaitWindow(session)
                val pid = IntArray(1)
                val fd = try {
                    Pty.spawn(spec, rows = 24, cols = 80, pidOut = pid, keep = session.usbFds.values.toIntArray())
                } finally {
                    usbDevices[session.id]?.closeInherited()
                }
                update(session.id) { it.copy(pid = pid[0]) }
                launch { readOutput(session.id, fd) }
                if (session.flatpak) launch { runFlatpak(app, backend, session) }
                val code = Pty.waitFor(pid[0])
                update(session.id) { it.copy(exitCode = code) }
                val output = _sessions.value.firstOrNull { it.session.id == session.id }?.output.orEmpty()
                Log.i(TAG, "GUI session ${session.id} (${app.id}) exited $code: ${output.takeLast(600)}")
                // The whole output, not just the tail: logcat is out of reach from inside.
                diagnostics?.recordSession(app, session, backend.name, code, output)
            } catch (e: Exception) {
                Log.w(TAG, "GUI session ${session.id} failed", e)
                update(session.id) { it.copy(output = "Couldn't start: ${e.message}", exitCode = -1) }
                diagnostics?.recordSession(app, session, backend.name, -1, "Couldn't start: ${e.stackTraceToString()}")
            } finally {
                windowReports.remove(session.id)?.stopWatching()
                notifier.clear(session.id)
                desktopBackends.remove(session.id)
                windowTasks.remove(session.id)
                audio?.stop()
                // The app is gone; its session's detached daemons go with it.
                killDetached(session.id, done = true)
                servers.remove(session.id)?.destroy()
                usbDevices.remove(session.id)?.close()
                withContext(Dispatchers.Main + NonCancellable) { x11.closeWindow(session) }
            }
        }
        return RunningSession(session, app, backend.name, pid = 0)
    }

    /**
     * A Flathub app's own process: once the session (around [FLATPAK_HOLD]) has published what
     * it set up, starts the app with that, beside it, and hands its exit code back, which ends
     * the session. If it can't start, the session ends with the reason in its output.
     */
    private suspend fun runFlatpak(app: PermissionManifest, backend: ContainerBackend, session: Session) {
        val dir = paths.x11SocketDir(session)
        val exit = File(dir, FLATPAK_EXIT_FILE)
        val code = try {
            val published = File(dir, FLATPAK_ENV_FILE)
            val ready = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                while (!published.isFile) kotlinx.coroutines.delay(100)
                true
            }
            checkNotNull(ready) { "the session didn't start" }
            val shared = published.readLines().mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
            }.toMap().filterKeys { it in FLATPAK_SHARED_ENV }
            val base = guestEnv(app, session) + shared
            val env = base + Flatpak.env(requireNotNull(app.flatpak), session.gpu, base)
            val spec = backend.buildFlatpakLaunch(app, session, env)
            val pid = IntArray(1)
            val fd = Pty.spawn(spec, rows = 24, cols = 80, pidOut = pid)
            scope.launch(Dispatchers.IO) { readOutput(session.id, fd) }
            Pty.waitFor(pid[0])
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Flathub app ${app.id} couldn't start", e)
            update(session.id) { it.copy(output = it.output + "\nCouldn't start ${app.name}: ${e.message}") }
            127
        }
        runCatching { exit.writeText("$code") }
    }

    /**
     * Waits (up to 3 s) for the session's Android window to connect to its X server. Only then
     * does the X screen get its real size and a monitor: an app started before that finds none,
     * and GIMP, restoring its windows onto "the" monitor, crashed on a cold start in chroot mode,
     * where apps start quicker than the window does.
     */
    private suspend fun awaitWindow(session: Session) {
        val tag = X11Server.windowTag(session)
        val connected = kotlinx.coroutines.withTimeoutOrNull(3000) {
            while (withContext(Dispatchers.Main) { com.termux.x11.MainActivity.getInstance(tag)?.lorieView?.connected() } != true) {
                kotlinx.coroutines.delay(50)
            }
            true
        }
        // The size goes over with the connection's first messages; give it a moment to land.
        if (connected == true) kotlinx.coroutines.delay(250)
        else Log.w(TAG, "session ${session.id}: its window hadn't connected after 3 s; starting the app anyway")
    }

    private fun readOutput(id: String, fd: Int) {
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd)).use { input ->
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    val chunk = String(buf, 0, r)
                    update(id) { it.copy(output = (it.output + chunk).takeLast(MAX_OUTPUT)) }
                }
            } catch (_: IOException) {
                // EIO once the child side closes; normal end of stream for a pty.
            }
        }
    }

    /** Writes [session]'s theme files for its app's current [Appearance]; see [ThemeFiles]. */
    private fun writeTheme(app: PermissionManifest, session: Session, backend: String) {
        val look = appearance?.forApp(app.id) ?: return
        // In chroot mode the pack's presence is only known through su, as refresh() left it.
        val pack = if (backend == ChrootBackend.NAME) ThemePack.bundle.installedAsRoot(context)
            else ThemePack.bundle.installed(context, paths.prootRootfs)
        runCatching {
            // An imported theme (its light or dark variant), else the phone's own colors.
            val imported = look.linuxTheme?.let { linuxThemes?.pick(it, Palette.isDark(context, look.colorMode)) }
            if (imported != null) {
                ThemeFiles.writeImported(paths.sessionTheme(session), imported, linuxThemes!!.folder(imported), Palette.from(imported), app.desktop)
            } else {
                ThemeFiles.write(paths.sessionTheme(session), Palette.current(context, look.colorMode, look.dynamicColor), pack, app.desktop)
            }
            if (app.desktop) writeDesktopTheme(app, session, look, imported)
        }.onFailure { Log.w(TAG, "couldn't write the theme for ${session.id}", it) }
    }

    private fun writeDesktopTheme(app: PermissionManifest, session: Session, look: Appearance, imported: LinuxTheme?) {
        val palette = imported?.let(Palette::from) ?: Palette.current(context, look.colorMode, look.dynamicColor)
        DesktopFiles.writeTheme(paths.sessionTheme(session), palette, desktopTitlePx(app))
    }

    /**
     * Redraws a running desktop's window theme for its scale as it is now, so title bars and
     * their buttons stay the same size on the phone whatever size Scale makes everything else.
     */
    fun rescaleDesktop(sessionId: String) {
        val running = byId(sessionId)?.takeIf { it.running && it.app.desktop && it.session.theme } ?: return
        val look = appearance?.forApp(running.app.id) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val imported = look.linuxTheme?.let { linuxThemes?.pick(it, Palette.isDark(context, look.colorMode)) }
                writeDesktopTheme(running.app, running.session, look, imported)
            }.onFailure { Log.w(TAG, "couldn't rescale the desktop ${running.session.id}", it) }
        }
    }

    /**
     * A desktop's title bars in X pixels: [DESKTOP_TITLE_DP] on the phone, through the scale
     * its X screen is drawn at (X pixels are the view's × 100 / scale).
     */
    private fun desktopTitlePx(app: PermissionManifest): Int {
        val scale = com.daryan.tessera.X11Scale.forApp(context, app.id) ?: com.daryan.tessera.X11Scale.default(context)
        return (DESKTOP_TITLE_DP * context.resources.displayMetrics.density * 100 / scale.coerceAtLeast(1)).toInt()
    }

    /**
     * Rewrites the theme of every running themed session, after Android's dark mode or colors
     * changed or the user's appearance settings did; the apps restyle without restarting. A
     * session whose app was since left out of theming keeps what it had until it restarts.
     */
    fun refreshThemes() {
        val running = _sessions.value.filter { it.running && it.session.theme }
        if (running.isNotEmpty()) scope.launch(Dispatchers.IO) { running.forEach { writeTheme(it.app, it.session, it.backend) } }
    }

    /** The session shown by the X11 window tagged [tag], if any. */
    fun forWindowTag(tag: String?): RunningSession? =
        _sessions.value.firstOrNull { X11Server.windowTag(it.session) == tag }

    fun byId(id: String?): RunningSession? = _sessions.value.firstOrNull { it.session.id == id }

    /** Brings a GUI session's window back to the front. */
    fun showWindow(id: String) {
        _sessions.value.firstOrNull { it.session.id == id && it.terminal == null }?.let { x11.showWindow(it.session) }
    }

    fun stop(id: String, reason: EndReason = EndReason.STOPPED) {
        val s = _sessions.value.firstOrNull { it.session.id == id && it.running } ?: return
        update(id) { it.copy(endedBy = reason) }
        // The whole tree: killing proot alone leaves its tracees running as orphans.
        when {
            s.terminal != null -> ProcessTree.killTree(s.terminal.pid)
            s.pid > 0 -> ProcessTree.killTree(s.pid)
            // Still starting its X server: stopping the server ends the launch.
            else -> servers.remove(id)?.destroy()
        }
        // And what detached from the tree on the way (D-Bus and accessibility-bus daemons).
        killDetached(id, done = false)
    }

    fun stopAll() = _sessions.value.filter { it.running }.forEach { stop(it.session.id) }

    fun dismiss(id: String) {
        stop(id)
        screenListeners.remove(id)
        _sessions.update { list -> list.filterNot { it.session.id == id } }
    }

    /**
     * Every main window an app opens after its first gets an Android task of its own (its own
     * card in Recents), the way a native app's second activity would. The first main window
     * stays with the session's own window, see [primaryWindow]. Not so in a desktop
     * ([ownTasks] false): its windows stay in it, on its taskbar.
     */
    private fun followWindows(session: Session, ownTasks: Boolean) {
        val tasks = windowTasks.getOrPut(session.id) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        val reports = SessionFiles(
            paths.x11SocketDir(session),
            onFocus = { _focusChanges.tryEmit(session.id to it) },
            onDesktop = if (ownTasks) null else ({ _desktopEvents.tryEmit(session.id to it) }),
            onDesktopItems = if (ownTasks) null else ({ items -> update(session.id) { it.copy(desktopItems = items) } }),
            // Tessera targets API 28, so Android 13 and later ask for notifications by themselves.
            onNotify = { n -> byId(session.id)?.let { notifier.handle(it, n) } },
        ) { windows ->
            update(session.id) { it.copy(windows = windows) }
            if (!ownTasks) return@SessionFiles
            val primary = primaryWindow(windows, tasks)
            tasks.retainAll(windows.main.map { it.id }.toSet())
            val fresh = windows.main.filter { it.id != primary && it.id !in tasks }
            tasks += fresh.map { it.id }
            if (fresh.isNotEmpty()) scope.launch(Dispatchers.Main) { fresh.forEach { x11.showWindow(session, it.id) } }
        }
        windowReports[session.id] = reports
        reports.startWatching()
    }

    /** The main window the session's own window shows: the first one without a task of its own. */
    private fun primaryWindow(windows: XWindows, tasks: Set<Long>): Long? =
        windows.main.firstOrNull { it.id !in tasks }?.id

    /**
     * The X window the Android window for [windowId] shows; null [windowId] means the session's
     * own. None for a desktop, which shows them all.
     */
    fun windowFor(sessionId: String, windowId: Long?): Long? {
        if (windowId != null) return windowId
        val running = byId(sessionId)?.takeUnless { it.app.desktop } ?: return null
        return primaryWindow(running.windows, windowTasks[sessionId].orEmpty())
    }

    /** Brings [windowId] to the front of its app's screen. */
    fun activateWindow(sessionId: String, windowId: Long) {
        windowReports[sessionId]?.activate(windowId)
    }

    /** Asks the app to close [windowId], as its own close button would. */
    fun closeWindow(sessionId: String, windowId: Long) {
        windowReports[sessionId]?.close(windowId)
    }

    fun minimizeWindow(sessionId: String, windowId: Long) {
        windowReports[sessionId]?.minimize(windowId)
    }

    fun toggleMaximizeWindow(sessionId: String, windowId: Long) {
        windowReports[sessionId]?.toggleMaximize(windowId)
    }

    fun snapWindow(sessionId: String, windowId: Long, left: Boolean) {
        windowReports[sessionId]?.snap(windowId, left)
    }

    fun showWorkspace(sessionId: String, n: Int) {
        windowReports[sessionId]?.workspace(n)
    }

    fun moveToWorkspace(sessionId: String, windowId: Long, n: Int) {
        windowReports[sessionId]?.moveTo(windowId, n)
    }

    /** Chroot sessions' su, to end their processes with: the app can't see them itself. */
    private val rootKill = ConcurrentHashMap<String, String>()

    /** What detached from a session's tree on the way (D-Bus and accessibility-bus daemons). */
    private fun killDetached(id: String, done: Boolean) {
        ProcessTree.killSession(id)
        val su = if (done) rootKill.remove(id) else rootKill[id]
        su?.let { scope.launch(Dispatchers.IO) { ProcessTree.killSessionAsRoot(it, id) } }
    }

    /** Each running desktop's backend, for the Flathub apps opened in it. */
    private val desktopBackends = ConcurrentHashMap<String, ContainerBackend>()

    /**
     * Opens the Flathub [app] in the running desktop [sessionId]: in a root of its own as
     * always, with its own permissions, but on the desktop's display and bus (the desktop
     * publishes its environment as a Flathub app's session does). False when the desktop isn't
     * up yet.
     */
    fun launchFlatpakInDesktop(sessionId: String, app: PermissionManifest): Boolean {
        val running = byId(sessionId)?.takeIf { it.running && it.app.desktop } ?: return false
        val ref = app.flatpak ?: return false
        val backend = desktopBackends[sessionId] ?: return false
        val published = File(paths.x11SocketDir(running.session), FLATPAK_ENV_FILE)
        if (!published.isFile) return false
        scope.launch(Dispatchers.IO) {
            runCatching {
                val session = running.session
                val shared = published.readLines().mapNotNull { line ->
                    line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
                }.toMap().filterKeys { it in FLATPAK_SHARED_ENV }
                val base = guestEnv(app, session) + shared
                val spec = backend.buildFlatpakLaunch(app, session, base + Flatpak.env(ref, session.gpu, base))
                val pid = IntArray(1)
                val fd = Pty.spawn(spec, rows = 24, cols = 80, pidOut = pid)
                launch { readOutput(sessionId, fd) }
                Pty.waitFor(pid[0])
            }.onFailure { e ->
                Log.w(TAG, "Flathub app ${app.id} couldn't start in ${running.session.id}", e)
                update(sessionId) { it.copy(output = it.output + "\nCouldn't start ${app.name}: ${e.message}") }
            }
        }
        return true
    }

    /** What a running desktop session's taskbar can ask of it; null for anything else. */
    fun desktop(sessionId: String): DesktopFiles? =
        byId(sessionId)?.takeIf { it.running && it.app.desktop }?.let { DesktopFiles(paths.x11SocketDir(it.session)) }

    private fun makeFifo(file: File) {
        runCatching {
            android.system.Os.mkfifo(file.path, "666".toInt(8))
            // Whatever the umask took away: the guest may run as another user.
            android.system.Os.chmod(file.path, "666".toInt(8))
        }.onFailure { Log.w(TAG, "couldn't make ${file.name}", it) }
    }

    private fun update(id: String, f: (RunningSession) -> RunningSession) =
        _sessions.update { list -> list.map { if (it.session.id == id) f(it) else it } }

    /** Bridges one TerminalSession's callbacks into [sessions]. Runs on the main thread. */
    private inner class Client(private val id: String) : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            screenListeners[id]?.invoke()
            update(id) { it.copy(revision = it.revision + 1) }
        }

        override fun onTitleChanged(changedSession: TerminalSession) =
            update(id) { it.copy(title = changedSession.title) }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            screenListeners[id]?.invoke()
            update(id) { it.copy(exitCode = finishedSession.exitStatus) }
            byId(id)?.let { s ->
                val transcript = finishedSession.emulator?.screen?.transcriptText.orEmpty()
                diagnostics?.recordSession(s.app, s.session, s.backend, s.exitCode, transcript.trim())
            }
            killDetached(id, done = true)
            usbDevices.remove(id)?.close()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Terminal", text ?: return))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) = onTextChanged(session)
        override fun onTerminalCursorStateChange(state: Boolean) {
            screenListeners[id]?.invoke()
        }
        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
    }

    private companion object {
        /** What a Flathub app takes from its session's environment, as [FLATPAK_HOLD] saw it. */
        val FLATPAK_SHARED_ENV = setOf(
            "DBUS_SESSION_BUS_ADDRESS", "QT_QPA_PLATFORMTHEME", "SAL_USE_VCLPLUGIN", "AT_SPI_BUS_ADDRESS",
        )
        const val TAG = "Sessions"
        /** A desktop's title bars, tall enough to hit their buttons with a finger. */
        const val DESKTOP_TITLE_DP = 40
        const val PREFS = "launcher"
        const val KEY_GPU = "gpu_acceleration"
        const val KEY_GPU_APP = "gpu_acceleration_"
        const val KEY_GPU_FIXES = "gpu_fixes_"
        const val MAX_OUTPUT = 16 * 1024
        const val TRANSCRIPT_ROWS = 5000
    }
}
