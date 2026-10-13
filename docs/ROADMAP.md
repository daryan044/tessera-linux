# Roadmap

Status as of 2026-10-13. ✅ done and verified on a device · 🟡 partly done or built but untested · ⬜ not started.

Test device: Xiaomi 13 Ultra, Android 16, Magisk root.

## Phase 1: Container engine

| | Item | Status | Notes |
|---|---|---|---|
| 1.1 | App skeleton, NDK setup | ✅ | Kotlin + Compose, CMake, JNI pty spawner (`cpp/pty.c`), targetSdk 28 |
| 1.1b | PRoot built from source | ✅ | `scripts/proot/build.sh`: static proot + loaders + talloc for arm64, armv7, x86_64. Only arm64 tested on a device |
| 1.2 | Rootfs download and install | 🟡 | Debian 13 from Docker Hub, digest-verified, extracted through PRoot, configured (DNS, apt, user account, fake /proc, all apt components). Alpine not offered yet |
| 1.3 | PRoot engine and mounts | ✅ | Per-app permission manifests; only granted folders and devices are bound |
| 1.3b | Chroot engine (root) | ✅ | Private mount namespace, minimal /dev, uid 1000, job control, network namespace when network is denied (verified) |
| 1.4 | Terminal, foreground service | ✅ | Termux terminal-view, extra keys, pinch zoom; service keeps sessions and network alive in the background |
| 1.5 | Background process limit (Android 12+) | ✅ | Setup reads the real setting (Android 14+ directly, 12–13 through su) and only warns while it's on. With root, Lift it applies the fix; without, it shows the adb command |

## Phase 2: Display (GUI apps)

| | Item | Status | Notes |
|---|---|---|---|
| 2.1 | X11 engine | ✅ | Termux:X11 (`third_party/termux-x11`, our fork), libXlorie built in WSL by `scripts/x11/build-native.sh` |
| 2.2 | Android surface | ✅ | Termux:X11's window, one Recents entry per app |
| 2.3 | Per-app display socket | ✅ | Each GUI app gets its own X server and `:N` socket in its private /tmp |
| 2.4 | Fullscreen, readable size | ✅ | matchbox-window-manager per display; scaled resolution from screen density; direct touch |
| 2.5 | GUI apps in chroot mode | ✅ | Confirmed on the device (xterm). Apps run as the launcher's own uid, not Debian's 1000 (Android's "system"); setsid --wait keeps the session alive while the app runs |
| 2.6 | Audio | ✅ | Apps with `audio` get their own PulseAudio in the session; its pipe sink feeds an Android AudioTrack (AudioPipe). Confirmed on the device |
| 2.7 | Clipboard between Android and X apps | ✅ | Termux:X11's clipboard sync, fixed in our fork for clips with several types (browsers) and for text copied before the window opened. Confirmed both ways on the device |
| 2.8 | Firefox | ✅ | Runs under PRoot with a private /dev/shm and its sandbox off; touch scrolling via XInput2 |
| 2.9 | Recents integration | ✅ | Every running app (GUI and terminal) has its own card, "<App> (Linux)" with its icon; closing the card stops it ("Killed by user") |
| 2.10 | Keyboard for text fields | ✅ | A focus watcher on the accessibility bus (AT-SPI) reports text fields; the window shows Android's keyboard after a tap into one and hides it after a tap elsewhere. Confirmed on the device |
| 2.11 | A task per window | ✅ | Every main X window an app opens after its first (Firefox's Library, a second browser window) gets its own Android task and Recents card, titled with the window's title; all share the app's X server, and the one in front brings its window forward. Closing a card closes that window. Confirmed on the device |

## Phase 3: Launcher

| | Item | Status | Notes |
|---|---|---|---|
| 3.1 | Launcher UI and tabs | ✅ | Material 3 Expressive, dynamic color, blur, floating dock, category and custom tabs |
| 3.2 | App store | 🟡 | Store tab built on Debian's AppStream catalog (1,951 desktop apps in main, plus contrib and non-free), with icons, search, categories and screenshots (from Flathub, matched by AppStream id or exact name; Debian's own screenshot links are broken). Installing reads the app's `.desktop` file and adds a launcher entry with category-based permissions. Installs run in a background queue shown under the app bar. Screenshots untested on a device |
| 3.3 | One app per window | ✅ | Separate X server, display and Android window per app |
| 3.4 | Install apps from the launcher | 🟡 | Manifests list their packages; the app sheet offers Install (apt in a root terminal) when the app is missing. Built, untested on a device. Only checked in PRoot mode |
| 3.5 | Edit permissions in the UI | ✅ | The app sheet's switches (network, sound, root) and lists (shared Android folders with read-only, devices) change an app's permissions from its next launch. Stored apart from the manifest (`files/permissions.json`), so Reset restores the manifest's and store reinstalls keep the user's |
| 3.6 | Storage permission prompt | ✅ | Asked when an app is granted a folder under /storage |
| 3.8 | Installs page | ✅ | From Settings, the install bar, or the Store's history button: running, waiting and finished jobs with live apt output; failed ones can be retried, and a failed apt run first finishes interrupted package setup and tries once more |
| 3.9 | Pin and favorite apps | ✅ | Hold an app: pin it (pinned apps lead every tab) or add it to Favorites (a tab after All) |
| 3.12 | Icons on the phone's home screen | 🟡 | Hold an app: Add to home screen pins an icon (its launcher colors and glyph) that starts it through the launcher. Built, untested on a device |
| 3.10 | Shortcuts | ✅ | A Shortcuts tab of commands as icons (terminal or window, optionally root), made and edited from the tab and the long-press menu. Starts with Claude Code, which installs itself on first run (curl, git and CA certificates are offered for install first). Confirmed on the device |
| 3.11 | Apps installed outside the Store | ✅ | The launcher lists the container's own menu entries (/usr/share/applications), so apps installed with apt in a terminal or from a .deb show up, rescanned whenever the launcher comes back; uninstalling finds their package from dpkg. In chroot mode the menus, icons and dpkg lists are read through su (rescans at most once a minute); that part is untested on a device |
| 3.7 | Config editor (side menu) | 🟡 | Hamburger menu: .bashrc (root, user), bash.bashrc, profile, environment, apt sources and settings, resolv.conf, hosts, and Termux:X11's settings. PRoot edits files directly, chroot via su; first save keeps a .bak-launcher. Built, untested on a device |

## Phase 4: Hardware and root

| | Item | Status | Notes |
|---|---|---|---|
| 4.1 | USB permission listener and pass-through | 🟡 | The app sheet's Add a device lists plugged-in USB devices (and the GPU node). In chroot mode the node is bound in and its group (root:usb) added to the app's, so it can open just that device. PRoot apps go through the libusb bridge (4.2). Not yet tried with a real USB device |
| 4.2 | libusb bridge | 🟡 | For PRoot apps: at launch the launcher asks Android's USB permission for each granted device, opens it, and the app's processes inherit the files. Inside, a preloaded shim (scripts/usb/tessera-usb.c) answers opens of those /dev/bus/usb paths with them, stand-in files there let scans find them, and our libusb build (no udev; patched to skip sysfs and hotplug under the bridge) finds them through usbfs. The shim was checked on Linux with a file in place of a device; untested with a real USB device |
| 4.3 | Root detection, `/dev` device mounts | 🟡 | `su` detection and per-app device binds are built; not tested with real devices (GPU, USB) |
| 4.4 | Chroot rootfs install from the app | ✅ | Setup offers Debian for chroot mode when /data/local/linux/debian has none: downloaded like PRoot's, unpacked and configured as root, moved into place; an existing system is never replaced. The install script was run on the device into a test folder (ownership kept, apt update works as root, apps run as the user); the Setup button itself hasn't been pressed end to end |

## Phase 5: Performance and release

| | Item | Status | Notes |
|---|---|---|---|
| 5.1 | GPU acceleration (Turnip / Zink) | ✅ | Our Mesa build (scripts/gpu/build-mesa.sh: Turnip for Android's KGSL, Zink) ships in the APK and unpacks to /opt/tessera-gpu; on by default on Adreno phones, switchable globally and per app. Chroot mode too: the driver is unpacked there through su and GPU sessions get /dev/kgsl-3d0 (untested on a device). Frames reach Termux:X11 as images (Kopper DRI2 for GLX, Vulkan WSI sw without SysV shm). On an Adreno 740: OpenGL 4.6, glxgears ~240 fps, Firefox and HandBrake draw. Hold a windowed app for Graphics fixes: per-app GPU switch and Turnip workarounds. UBWC is off by default (VS Code frames came out as a grid of black blocks; confirmed fixed on the device) |
| 5.2 | Phantom process killer safeguards | ✅ | Foreground service, plus lifting the limit from Setup (one tap with root, an adb command without) |
| 5.3 | Open source release | 🟡 | Public GitHub repo, GPL-3.0, copyright headers, third-party credits. Release builds signed with a key from keystore.properties or the environment (the debug key without one). GitHub Actions (.github/workflows/build.yml) builds every native part from source and the APK, and publishes v* tags as releases; not run yet. No release key or published release yet, not on F-Droid |

## Known issues

- Alpine isn't offered: the Store, the app manifests, package installs and the GPU driver (built against glibc) are all Debian's.
- Session ids restart at `s1` with each app process; stale session directories are cleared at startup.
- Builds need WSL (or Linux) once for the X server's native library.
