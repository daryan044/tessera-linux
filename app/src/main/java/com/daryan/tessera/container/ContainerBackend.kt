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

    /** [env] as KEY=value, with [HELPER_ENV]; what the process is started with. */
    fun envp(): Array<String> = (env + (HELPER_ENV to "1")).map { (k, v) -> "$k=$v" }.toTypedArray()
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
    /** Theme the app with the phone's colors ([ThemeFiles]); GUI apps only. */
    val theme: Boolean = false,
    /**
     * A Flathub app ([FlatpakRef]): the session's helpers run in the container as usual, around
     * [FLATPAK_HOLD] instead of the app, and the app runs beside them in a root of its own.
     */
    val flatpak: Boolean = false,
    /** Where adb inside reaches this phone ([PhoneAdb]), when the app may and it's on. */
    val adb: String? = null,
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
    /** Starts a Flathub app in chroot mode; see [ChrootBackend.buildFlatpakLaunch]. */
    val chrootFlatpakScript get() = File(filesDir, "chroot/flatpak.sh")
    /** The empty root a PRoot Flathub app's runtime and app are bound into. */
    val flatpakSkeleton get() = File(filesDir, "flatpak-root")

    /** Private /tmp per session, so apps cannot see each other's sockets. */
    fun sessionTmp(session: Session) = File(cacheDir, "sessions/${session.id}/tmp")
    fun x11SocketDir(session: Session) = File(sessionTmp(session), ".X11-unix")
    /** The session's theme files ([ThemeFiles]); the guest's [ThemeFiles.GUEST_DIR]. */
    fun sessionTheme(session: Session) = File(x11SocketDir(session), ThemeFiles.GUEST_DIR.substringAfterLast('/'))
    /** Private /dev/shm per session; Android has none, and browsers' IPC needs one. */
    fun sessionShm(session: Session) = File(cacheDir, "sessions/${session.id}/shm")
    /** Stand-ins for a PRoot session's bridged USB devices, bound at /dev/bus/usb ([UsbBridge]). */
    fun sessionUsb(session: Session) = File(cacheDir, "sessions/${session.id}/usb")
    /** Stand-ins for /proc files Android hides from apps; see [FakeProc]. */
    val fakeProcDir get() = File(filesDir, "proc")

    /** The host environment of every PRoot run. */
    fun prootEnv() = mapOf(
        "PROOT_TMP_DIR" to cacheDir.invariantSeparatorsPath,
        "PROOT_LOADER" to prootLoader.invariantSeparatorsPath,
        "PROOT_LOADER_32" to prootLoader32.invariantSeparatorsPath,
    )

    companion object {
        // Debian's default PATH, including the games directories.
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games"
        /** Written last by [RootfsInstaller]; a rootfs without it is incomplete. */
        const val INSTALLED_MARKER = ".launcher-installed"
    }
}

/** Environment variable naming the session a guest process belongs to; see [ProcessTree.inSession]. */
const val SESSION_ENV = "LAUNCHER_SESSION"

/**
 * Environment variable every process Tessera starts carries, guest or not: the task manager
 * finds root helpers by it, even once nothing of the app is left above them (su hands the
 * caller's environment to the root shell, whose parent is su's daemon, not the app).
 */
const val HELPER_ENV = "TESSERA_HELPER"

/**
 * What lets a toolkit follow the theme ([ThemeFiles]), by a package that shows it's in use:
 * Qt's GTK platform theme (Qt apps take GTK's colors) and LibreOffice's GTK 3 front end.
 * Queued once their toolkit is installed, rather than pulling Qt or LibreOffice into every system.
 */
val THEME_COMPANIONS = mapOf(
    "libqt6widgets6" to "qt6-gtk-platformtheme",
    "libqt5widgets5t64" to "qt5-gtk-platformtheme",
    "libreoffice-core" to "libreoffice-gtk3",
)

/**
 * What Linux apps expect of a desktop system and the minimal image lacks, queued once a
 * container is missing any: OpenGL, EGL and GLES (glvnd, with Mesa's software drivers for when
 * the GPU driver is off; apps failed on a missing libGL.so.1), GLU, Vulkan's loader, glxinfo,
 * what our GPU driver ([GpuDriver]) links and Debian's Mesa doesn't pull in, and what
 * AppImages (FUSE 2) and Electron apps load.
 */
val BASE_LIBRARIES = listOf(
    "libgl1", "libegl1", "libgles2", "libglx-mesa0", "libegl-mesa0", "libgl1-mesa-dri", "libgbm1",
    "libglu1-mesa", "libvulkan1", "mesa-utils",
    // Turnip's X11 presenting links it; without it the driver doesn't load, Zink finds no
    // Vulkan device, and every GPU app fails with "failed to load driver: zink".
    "libxcb-keysyms1",
    "libfuse2t64", "libnss3", "libxkbcommon0", "libasound2t64", "libxss1", "libxtst6", "libsecret-1-0", "libgtk-3-0t64",
    "ca-certificates", "fonts-dejavu-core",
)

/** Packages every GUI app gets with it; see [guestCommand]. */
val GUI_HELPER_PACKAGES = listOf("matchbox-window-manager", "dbus-x11", "at-spi2-core", "python3-pyatspi", "pulseaudio", "python3-xlib", "xsettingsd")

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
 * - [WINDOW_WATCHER], which reports the app's windows so each can have its own Android task;
 * - in themed sessions, [THEME_DAEMON]: the settings portal and xsettingsd, with the phone's
 *   colors and light or dark;
 * - [NOTIFY_DAEMON], whose notifications the launcher shows as Android's.
 * The helpers are stopped when the app exits so the session (and its window) ends with it.
 * A desktop ([PermissionManifest.desktop]) gets the same helpers but no matchbox: its own
 * window manager runs in [DESKTOP_SESSION], next to [DESKTOP_HELPER].
 */
internal fun guestCommand(app: PermissionManifest): List<String> = when {
    !app.gui -> app.exec
    app.desktop -> guiWrapper("desktop") + listOf("/bin/sh", "-c", DESKTOP_SESSION, "desktop", DESKTOP_HELPER) + app.exec
    else -> guiWrapper("app") + app.exec
}

/** [GUI_WRAPPER] and its helpers; [wm] is "app" (matchbox keeps it fullscreen) or "desktop". */
private fun guiWrapper(wm: String) =
    listOf("/bin/sh", "-c", GUI_WRAPPER, "launch", FOCUS_WATCHER, FOCUS_FILE_GUEST, WINDOW_WATCHER, WINDOWS_FILE_GUEST, THEME_DAEMON, wm, NOTIFY_DAEMON)

private const val GUI_WRAPPER = """
watcher=${'$'}1; out=${'$'}2; windows=${'$'}3; windows_out=${'$'}4; theme=${'$'}5; wm=${'$'}6; notify=${'$'}7; shift 7
if { [ -n "${'$'}TESSERA_FLATPAK" ] || [ "${'$'}wm" = desktop ]; } && [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && command -v dbus-daemon >/dev/null 2>&1; then
  # A Flathub app runs in a root of its own, which shares only the X11 directory with this
  # one: the bus lives there. So does a desktop's, which Flathub apps open in it share.
  bus=${'$'}(dbus-daemon --session --address=unix:path=/tmp/.X11-unix/bus --fork --print-pid)
  export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/.X11-unix/bus
elif [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && command -v dbus-launch >/dev/null 2>&1; then
  eval "${'$'}(dbus-launch --sh-syntax)"; bus=${'$'}DBUS_SESSION_BUS_PID
fi
if [ -n "${'$'}TESSERA_THEME" ] && [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && [ -d /usr/lib/python3/dist-packages/gi ]; then
  python3 -c "${'$'}theme" "${'$'}TESSERA_THEME" & td=${'$'}!
  # The app reads its theme as it starts, and libadwaita only listens to a portal it found
  # then. A cold start of Python with GLib can take seconds.
  i=0
  while [ ${'$'}i -lt 50 ] && [ ! -e "${'$'}TESSERA_THEME/ready" ]; do sleep 0.1; i=${'$'}((i + 1)); done
fi
if [ -n "${'$'}TESSERA_THEME" ]; then
  # Qt: GTK's (themed) colors through its GTK platform theme where installed, else at least
  # light or dark from the settings portal. LibreOffice: its GTK 3 look rather than the bare X one.
  if [ -z "${'$'}QT_QPA_PLATFORMTHEME" ]; then
    if ls /usr/lib/*/qt6/plugins/platformthemes/libqgtk3.so /usr/lib/*/qt5/plugins/platformthemes/libqgtk3.so >/dev/null 2>&1; then
      export QT_QPA_PLATFORMTHEME=gtk3
    else
      export QT_QPA_PLATFORMTHEME=xdgdesktopportal
    fi
  fi
  [ -z "${'$'}SAL_USE_VCLPLUGIN" ] && [ -e /usr/lib/libreoffice/program/libvclplug_gtk3lo.so ] && export SAL_USE_VCLPLUGIN=gtk3
fi
# Firefox doesn't know Zink and draws pages on the CPU, which can't keep up with video or
# scrolling at the phone's resolution. With the GPU driver its profiles get a block in user.js
# turning GPU drawing on; without it the block goes, as forcing it onto Mesa's software
# rasterizer would be slower than Firefox's own.
for p in "${'$'}HOME"/.mozilla/firefox/*/ "${'$'}HOME"/.mozilla/firefox-esr/*/ "${'$'}HOME"/.config/mozilla/firefox/*/ "${'$'}HOME"/.var/app/org.mozilla.firefox/.mozilla/firefox/*/; do
  [ -f "${'$'}p/prefs.js" ] || continue
  u="${'$'}p/user.js"
  {
    [ -f "${'$'}u" ] && sed '/^\/\/ tessera-gpu {/,/^\/\/ } tessera-gpu/d' "${'$'}u"
    if [ "${'$'}GALLIUM_DRIVER" = zink ]; then
      printf '%s\n' '// tessera-gpu { (written by Tessera at every start)' 'user_pref("gfx.webrender.all", true);' 'user_pref("layers.acceleration.force-enabled", true);' '// } tessera-gpu'
    fi
  } > "${'$'}u.tessera" && mv "${'$'}u.tessera" "${'$'}u"
done
if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && [ -d /usr/lib/python3/dist-packages/gi ]; then
  python3 -c "${'$'}notify" /tmp/.X11-unix & nd=${'$'}!
fi
if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && [ -d /usr/lib/python3/dist-packages/pyatspi ]; then
  python3 -c "${'$'}watcher" "${'$'}out" & fw=${'$'}!
fi
if [ -n "${'$'}LAUNCHER_AUDIO" ] && command -v pulseaudio >/dev/null 2>&1; then
  mkdir -p "${'$'}PULSE_RUNTIME_PATH"
  pulseaudio -n --daemonize=no --exit-idle-time=-1 --disable-shm=yes \
    --load="module-native-protocol-unix auth-anonymous=1" \
    --load="module-pipe-sink file=/tmp/.X11-unix/$AUDIO_FIFO_NAME format=s16le rate=$AUDIO_RATE channels=2 sink_name=android use_system_clock_for_timing=yes" \
    --load="module-suspend-on-idle timeout=2" >/dev/null 2>&1 & pa=${'$'}!
  # Apps that find no server at startup stay silent, so give it a moment.
  for i in 1 2 3 4 5 6 7 8 9 10; do [ -S "${'$'}PULSE_RUNTIME_PATH/native" ] && break; sleep 0.2; done
fi
if [ "${'$'}wm" = desktop ]; then
  # The desktop's own window manager starts with it, below; the watcher follows its windows
  # for the taskbar once it owns the screen.
  if [ -d /usr/lib/python3/dist-packages/Xlib ]; then
    ( sleep 1.5; exec python3 -c "${'$'}windows" "${'$'}windows_out" ) & ww=${'$'}!
  fi
elif command -v matchbox-window-manager >/dev/null 2>&1; then
  # With a cursor of its own on the screen: "-use_cursor no" blanks it, and apps that don't
  # set one (most outside GTK and Qt) then showed no pointer, even in the touchpad modes.
  matchbox-window-manager -use_titlebar no -use_cursor yes & wm=${'$'}!
  if [ -d /usr/lib/python3/dist-packages/Xlib ]; then
    # Once the manager owns the screen, or the window list it keeps isn't there yet.
    ( sleep 0.5; exec python3 -c "${'$'}windows" "${'$'}windows_out" ) & ww=${'$'}!
  fi
fi
started=${'$'}(date +%s)
"${'$'}@"; status=${'$'}?
# Launchers such as VS Code's `code` start the real app in the background and exit at once.
# After such a quick, clean exit, the session lasts while the app's windows are open.
if [ "${'$'}wm" = app ] && [ "${'$'}status" = 0 ] && [ -n "${'$'}ww" ] && [ ${'$'}((${'$'}(date +%s) - started)) -lt 5 ]; then
  i=0
  while [ ${'$'}i -lt 30 ] && ! grep -q '"id"' "${'$'}windows_out" 2>/dev/null; do sleep 0.5; i=${'$'}((i + 1)); done
  while grep -q '"id"' "${'$'}windows_out" 2>/dev/null; do sleep 1; done
fi
kill ${'$'}ww ${'$'}wm ${'$'}fw ${'$'}pa ${'$'}td ${'$'}nd ${'$'}bus 2>/dev/null
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

/**
 * What a Flathub app's session runs in the container in place of the app: it hands the app
 * what the session set up (its bus, sound, theme settings) through the X11 directory, which
 * both roots share, then waits for the launcher to report the app's exit there.
 */
internal const val FLATPAK_HOLD = """
d=/tmp/.X11-unix
env > ${'$'}d/flatpak-env.tmp && mv ${'$'}d/flatpak-env.tmp ${'$'}d/flatpak-env
while [ ! -e ${'$'}d/flatpak-exit ]; do sleep 0.5; done
exit "${'$'}(cat ${'$'}d/flatpak-exit)"
"""

const val FLATPAK_ENV_FILE = "flatpak-env"
const val FLATPAK_EXIT_FILE = "flatpak-exit"

interface ContainerBackend {
    val name: String
    fun enforcement(capability: Capability, app: PermissionManifest): Enforcement
    fun buildLaunch(app: PermissionManifest, session: Session): LaunchSpec

    /**
     * Starts [app]'s Flathub files ([PermissionManifest.flatpak]) in a root of their own: its
     * runtime at /usr, the app at /app, the session's /tmp/.X11-unix shared, [env] as its whole
     * environment. Runs beside the session [buildLaunch] started around [FLATPAK_HOLD].
     */
    fun buildFlatpakLaunch(app: PermissionManifest, session: Session, env: Map<String, String>): LaunchSpec =
        throw UnsupportedOperationException("$name can't run Flathub apps")
}
