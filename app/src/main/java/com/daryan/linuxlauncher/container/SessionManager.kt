// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

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

/** Owns launched processes; lives in [com.daryan.linuxlauncher.LauncherContainer]. */
class SessionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val paths: ContainerPaths,
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

    /** Called when a session's screen changes; set by whichever terminal view shows it. */
    private val screenListeners = ConcurrentHashMap<String, () -> Unit>()

    fun setScreenListener(id: String, listener: (() -> Unit)?) {
        if (listener == null) screenListeners.remove(id) else screenListeners[id] = listener
    }

    /** Must be called on the main thread: TerminalSession binds to the caller's Looper. */
    @Throws(IOException::class)
    fun launch(app: PermissionManifest, backend: ContainerBackend): RunningSession {
        val n = nextId.getAndIncrement()
        val session = Session(id = "s$n", display = if (app.permissions.display) n else null)
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
        // A stopped Firefox leaves a profile lock naming a pid Android will soon reuse.
        if (backend is ProotBackend) runCatching { StaleLocks.clean(paths.prootRootfs, android.os.Process.myUid()) }
        val spec = backend.buildLaunch(app, session)

        val running = if (app.gui) launchGui(app, backend, session, spec) else launchTerminal(app, backend, session, spec)
        _sessions.update { it + running }
        return running
    }

    private fun launchTerminal(app: PermissionManifest, backend: ContainerBackend, session: Session, spec: LaunchSpec): RunningSession {
        val terminal = TerminalSession(
            spec.executable,
            spec.cwd,
            spec.argv.toTypedArray(),
            spec.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
            TRANSCRIPT_ROWS,
            Client(session.id),
        )
        // Start the process now rather than when a view first attaches; the view resizes it.
        terminal.initializeEmulator(80, 24, 12, 24)
        return RunningSession(session, app, backend.name, terminal.pid, terminal = terminal)
    }

    /**
     * GUI apps: start the session's own X server, open its window, then run the app on a pty
     * with DISPLAY pointing at it. Everything after the server start happens off the main thread.
     */
    private fun launchGui(app: PermissionManifest, backend: ContainerBackend, session: Session, spec: LaunchSpec): RunningSession {
        scope.launch(Dispatchers.IO) {
            try {
                servers[session.id] = x11.start(session)
                withContext(Dispatchers.Main) { x11.showWindow(session) }
                val pid = IntArray(1)
                val fd = Pty.spawn(spec, rows = 24, cols = 80, pidOut = pid)
                update(session.id) { it.copy(pid = pid[0]) }
                launch { readOutput(session.id, fd) }
                val code = Pty.waitFor(pid[0])
                update(session.id) { it.copy(exitCode = code) }
                val tail = _sessions.value.firstOrNull { it.session.id == session.id }?.output?.takeLast(600)
                Log.i(TAG, "GUI session ${session.id} (${app.id}) exited $code: $tail")
            } catch (e: Exception) {
                Log.w(TAG, "GUI session ${session.id} failed", e)
                update(session.id) { it.copy(output = "Couldn't start: ${e.message}", exitCode = -1) }
            } finally {
                servers.remove(session.id)?.destroy()
                withContext(Dispatchers.Main + NonCancellable) { x11.closeWindow(session) }
            }
        }
        return RunningSession(session, app, backend.name, pid = 0)
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
    }

    fun stopAll() = _sessions.value.filter { it.running }.forEach { stop(it.session.id) }

    fun dismiss(id: String) {
        stop(id)
        screenListeners.remove(id)
        _sessions.update { list -> list.filterNot { it.session.id == id } }
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
        const val TAG = "Sessions"
        const val MAX_OUTPUT = 16 * 1024
        const val TRANSCRIPT_ROWS = 5000
    }
}
