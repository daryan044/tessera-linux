// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File

enum class Capability(val label: String) {
    FILESYSTEM("Files"),
    NETWORK("Network"),
    DISPLAY("Display"),
    AUDIO("Audio"),
    DEVICES("Devices"),
}

/** How far a backend can actually hold an app to its manifest. Shown to users as-is. */
enum class Enforcement {
    /** Kernel-enforced (namespaces, uid, missing mounts). */
    ENFORCED,
    /** Honoured by path translation only; a determined process can get around it. */
    ADVISORY,
    /** The backend cannot restrict this at all. */
    UNAVAILABLE,
}

/** Everything [Pty.spawn] needs. argv[0] is the executable. */
data class LaunchSpec(
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: String,
) {
    val executable: String get() = argv.first()
}

/** Per-launch state that is not part of the app's manifest. */
data class Session(
    val id: String,
    /** X display number, when the app has the display permission. */
    val display: Int? = null,
    val term: String = "xterm-256color",
    /** Use the hardware graphics driver ([GpuDriver]). */
    val gpu: Boolean = false,
    /** Workarounds for the driver's drawing bugs, when [gpu] is on. */
    val gpuFixes: Set<GpuFix> = emptySet(),
    /** USB devices opened for a PRoot app ([UsbBridge]): guest path -> inherited fd. */
    val usbFds: Map<String, Int> = emptyMap(),
)

/** Host-side locations a backend needs. Built from a Context in the app, by hand in tests. */
data class ContainerPaths(
    val nativeLibDir: File,
    val filesDir: File,
    val cacheDir: File,
    /** rootfs used in PRoot mode; lives in app storage. */
    val prootRootfs: File,
    /** rootfs used in chroot mode; outside app storage so root-owned files stay root-owned. */
    val chrootRootfs: File,
) {
    val proot get() = File(nativeLibDir, "libproot.so")
    val prootLoader get() = File(nativeLibDir, "libproot-loader.so")
    val prootLoader32 get() = File(nativeLibDir, "libproot-loader32.so")
    val chrootScript get() = File(filesDir, "chroot/enter.sh")

    /** Private /tmp per session, so apps cannot see each other's sockets. */
    fun sessionTmp(session: Session) = File(cacheDir, "sessions/${session.id}/tmp")
    fun x11SocketDir(session: Session) = File(sessionTmp(session), ".X11-unix")
    /** Private /dev/shm per session; Android has none, and browsers' IPC needs one. */
    fun sessionShm(session: Session) = File(cacheDir, "sessions/${session.id}/shm")
    /** Stand-ins for a PRoot session's bridged USB devices, bound at /dev/bus/usb ([UsbBridge]). */
    fun sessionUsb(session: Session) = File(cacheDir, "sessions/${session.id}/usb")
    /** Stand-ins for /proc files Android hides from apps; see [FakeProc]. */
    val fakeProcDir get() = File(filesDir, "proc")

    companion object {
        // Debian's default PATH, including the games directories.
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games"
        /** Written last by [RootfsInstaller]; a rootfs without it is incomplete. */
        const val INSTALLED_MARKER = ".launcher-installed"
    }
}

/** Environment variable naming the session a guest process belongs to; see [ProcessTree.inSession]. */
const val SESSION_ENV = "LAUNCHER_SESSION"

/** Packages every GUI app gets with it; see [guestCommand]. */
val GUI_HELPER_PACKAGES = listOf("matchbox-window-manager", "dbus-x11", "at-spi2-core", "python3-pyatspi", "pulseaudio", "python3-xlib")

/**
 * Where the focus watcher reports to, as the guest sees it. /tmp/.X11-unix is the session's
 * X11 socket directory on the host in both backends ([ContainerPaths.x11SocketDir]).
 */
const val FOCUS_FILE_GUEST = "/tmp/.X11-unix/launcher-focus"
const val FOCUS_FILE_NAME = "launcher-focus"
/** The FIFO the session's PulseAudio plays into, in the same directory; see AudioPipe. */
const val AUDIO_FIFO_NAME = "launcher-audio"
const val AUDIO_RATE = 48000

/**
 * What actually runs in the container. GUI apps get, when installed:
 * - matchbox-window-manager: one app per display, kept fullscreen, without title bars;
 * - a session D-Bus, which also carries the accessibility bus (AT-SPI);
 * - [FOCUS_WATCHER], which reports whether the focused widget takes text, so the window can
 *   show Android's keyboard for text fields the way a native app does;
 * - with the audio permission, PulseAudio playing into a FIFO the app reads (AudioPipe);
 * - [WINDOW_WATCHER], which reports the app's windows so each can have its own Android task.
 * The helpers are stopped when the app exits so the session (and its window) ends with it.
 */
internal fun guestCommand(app: PermissionManifest): List<String> =
    if (!app.gui) app.exec
    else listOf("/bin/sh", "-c", GUI_WRAPPER, "launch", FOCUS_WATCHER, FOCUS_FILE_GUEST, WINDOW_WATCHER, WINDOWS_FILE_GUEST) + app.exec

private const val GUI_WRAPPER = """
watcher=${'$'}1; out=${'$'}2; windows=${'$'}3; windows_out=${'$'}4; shift 4
if [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && command -v dbus-launch >/dev/null 2>&1; then
  eval "${'$'}(dbus-launch --sh-syntax)"; bus=${'$'}DBUS_SESSION_BUS_PID
fi
if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && [ -d /usr/lib/python3/dist-packages/pyatspi ]; then
  python3 -c "${'$'}watcher" "${'$'}out" & fw=${'$'}!
fi
if [ -n "${'$'}LAUNCHER_AUDIO" ] && command -v pulseaudio >/dev/null 2>&1; then
  mkdir -p "${'$'}PULSE_RUNTIME_PATH"
  pulseaudio -n --daemonize=no --exit-idle-time=-1 --disable-shm=yes \
    --load="module-native-protocol-unix auth-anonymous=1" \
    --load="module-pipe-sink file=/tmp/.X11-unix/$AUDIO_FIFO_NAME format=s16le rate=$AUDIO_RATE channels=2 sink_name=android" \
    --load="module-suspend-on-idle timeout=2" >/dev/null 2>&1 & pa=${'$'}!
  # Apps that find no server at startup stay silent, so give it a moment.
  for i in 1 2 3 4 5 6 7 8 9 10; do [ -S "${'$'}PULSE_RUNTIME_PATH/native" ] && break; sleep 0.2; done
fi
if command -v matchbox-window-manager >/dev/null 2>&1; then
  matchbox-window-manager -use_titlebar no -use_cursor no & wm=${'$'}!
  if [ -d /usr/lib/python3/dist-packages/Xlib ]; then
    # Once the manager owns the screen, or the window list it keeps isn't there yet.
    ( sleep 0.5; exec python3 -c "${'$'}windows" "${'$'}windows_out" ) & ww=${'$'}!
  fi
fi
started=${'$'}(date +%s)
"${'$'}@"; status=${'$'}?
# Launchers such as VS Code's `code` start the real app in the background and exit at once.
# After such a quick, clean exit, the session lasts while the app's windows are open.
if [ "${'$'}status" = 0 ] && [ -n "${'$'}ww" ] && [ ${'$'}((${'$'}(date +%s) - started)) -lt 5 ]; then
  i=0
  while [ ${'$'}i -lt 30 ] && ! grep -q '"id"' "${'$'}windows_out" 2>/dev/null; do sleep 0.5; i=${'$'}((i + 1)); done
  while grep -q '"id"' "${'$'}windows_out" 2>/dev/null; do sleep 1; done
fi
kill ${'$'}ww ${'$'}wm ${'$'}fw ${'$'}pa ${'$'}bus 2>/dev/null
exit ${'$'}status
"""

/**
 * Listens on AT-SPI for focus changes and writes "text" when the focused widget is editable,
 * "none" when it isn't. Only changes are written, one per line.
 */
private const val FOCUS_WATCHER = """
import sys, pyatspi
out = open(sys.argv[1], "a", buffering=1)
last = None
def focused(event):
    global last
    if not event.detail1:
        return
    try:
        state = event.source.getState()
        text = state.contains(pyatspi.STATE_EDITABLE) and not state.contains(pyatspi.STATE_READ_ONLY)
    except Exception:
        return
    now = "text" if text else "none"
    if now != last:
        print(now, file=out)
        last = now
pyatspi.Registry.registerEventListener(focused, "object:state-changed:focused")
pyatspi.Registry.start()
"""

interface ContainerBackend {
    val name: String
    fun enforcement(capability: Capability, app: PermissionManifest): Enforcement
    fun buildLaunch(app: PermissionManifest, session: Session): LaunchSpec
}
