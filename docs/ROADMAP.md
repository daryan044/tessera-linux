# Roadmap

Status as of 2026-10-15. ✅ done and verified on a device · 🟡 partly done or built but untested · ⬜ not started.

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
| 2.12 | Window panel and scale | ✅ | The swipe-in panel over each app's window: right and middle click, keys, input mode, cast, USB, permissions, display, close; readable in light and dark, and laid out wider (six tiles a row) in landscape. Scale resizes the app's X screen live from a slider (100 to 400%) and keeps the value per app; Default goes back to the display setting. Confirmed on the device |

## Phase 3: Launcher

| | Item | Status | Notes |
|---|---|---|---|
| 3.1 | Launcher UI and tabs | ✅ | Material 3 Expressive, dynamic color, blur, floating dock, category and custom tabs |
| 3.2 | App store | ✅ | Store tab built on Debian's AppStream catalog (1,951 desktop apps in main, plus contrib and non-free), with icons, search, categories and screenshots (from Flathub, matched by AppStream id or exact name; Debian's own screenshot links are broken). Installing reads the app's `.desktop` file and adds a launcher entry with category-based permissions. Installs run in a background queue shown under the app bar. Screenshots are a nice-to-have, not checked on a device |
| 3.3 | One app per window | ✅ | Separate X server, display and Android window per app |
| 3.4 | Install apps from the launcher | 🟡 | Manifests list their packages; the app sheet offers Install (apt in a root terminal) when the app is missing. Built, untested on a device. Only checked in PRoot mode |
| 3.5 | Edit permissions in the UI | ✅ | The app sheet's switches (network, sound, root) and lists (shared Android folders with read-only, devices) change an app's permissions from its next launch. Stored apart from the manifest (`files/permissions.json`), so Reset restores the manifest's and store reinstalls keep the user's |
| 3.6 | Storage permission prompt | ✅ | Asked when an app is granted a folder under /storage |
| 3.8 | Installs page | ✅ | From Settings, the install bar, or the Store's history button: running, waiting and finished jobs with live apt output; failed ones can be retried, and a failed apt run first finishes interrupted package setup and tries once more |
| 3.9 | Pin and favorite apps | ✅ | Hold an app: pin it (pinned apps lead every tab) or add it to Favorites (a tab after All) |
| 3.12 | Icons on the phone's home screen | 🟡 | Hold an app: Add to home screen pins an icon (its launcher colors and glyph) that starts it through the launcher. Built, untested on a device |
| 3.10 | Shortcuts | ✅ | A Shortcuts tab of commands as icons (terminal or window, optionally root), made and edited from the tab and the long-press menu. Starts with Claude Code, which installs itself on first run (curl, git and CA certificates are offered for install first). Confirmed on the device |
| 3.11 | Apps installed outside the Store | ✅ | The launcher lists the container's own menu entries (/usr/share/applications), so apps installed with apt in a terminal or from a .deb show up, rescanned whenever the launcher comes back; uninstalling finds their package from dpkg. In chroot mode the menus, icons and dpkg lists are read through su (rescans at most once a minute); that part is untested on a device |
| 3.13 | Files tab | 🟡 | A file manager in the dock. Places has Android (phone storage, Downloads, Camera, Pictures, Documents, Music, SD cards, with free space) and Linux (home, root's home, the whole system, bookmarks). List or grid with picture thumbnails, sort, hidden files, search in a folder or every folder below it, multi-select, copy/cut/paste with progress and Stop, rename, delete, new file/folder, compress (tar.gz, tar.xz, zip) and extract, properties with a permissions editor, text files in the built-in editor, everything else opened or shared with Android apps, files added from the phone, terminal here, browse as root. Runs through a bash inside the container (GuestShell), so it sees what apps see in both engines. The Places page was seen on the device; browsing isn't confirmed yet |
| 3.14 | Task manager | 🟡 | The Sessions page samples every container process (ProcessMonitor): each app's CPU and memory, background work (the Files tab's shells, apt), End process and Close all. Built, untested on a device |
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
| 5.1 | GPU acceleration (Turnip / Zink) | ✅ | Our Mesa build (scripts/gpu/build-mesa.sh: Turnip for Android's KGSL, Zink) ships in the APK and unpacks to /opt/tessera-gpu; on by default on Adreno phones, switchable globally and per app. Chroot mode too: the driver is unpacked there through su and GPU sessions get /dev/kgsl-3d0 (untested on a device). Frames reach Termux:X11 as images (Kopper DRI2 for GLX, Vulkan WSI sw without SysV shm). On an Adreno 740: OpenGL 4.6, glxgears ~240 fps, Firefox and HandBrake draw. Hold a windowed app for Look and graphics: per-app GPU switch and Turnip workarounds. UBWC is off by default (VS Code frames came out as a grid of black blocks; confirmed fixed on the device) |
| 5.2 | Phantom process killer safeguards | ✅ | Foreground service, plus lifting the limit from Setup (one tap with root, an adb command without) |
| 5.3 | Open source release | 🟡 | Public GitHub repo, GPL-3.0, copyright headers, third-party credits. Release builds signed with a key from keystore.properties or the environment (the debug key without one). GitHub Actions (.github/workflows/build.yml) builds every native part from source and the APK, and publishes v* tags as releases; not run yet. No release key or published release yet, not on F-Droid |

## Phase 6: Look and feel

Modern, Material-style looks for Linux apps that follow the phone's theme (light or dark, wallpaper colors), through the settings channels toolkits already read, without patching any toolkit. The plan, building blocks and test matrix are in [LOOK-AND-FEEL.md](LOOK-AND-FEEL.md).

| | Item | Status | Notes |
|---|---|---|---|
| 6.1 | Test matrix and baseline screenshots | ✅ | `scripts/look/screenshots.sh <label>` opens each app of the matrix ([LOOK-AND-FEEL.md](LOOK-AND-FEEL.md#test-matrix-61)) in light and dark mode, waits for its window to be in front, and saves shrunk screenshots: `docs/look/before/` (unthemed) and `docs/look/after/` (M1). Wireshark doesn't open its window in chroot mode, themed or not; no QML or Qt 5 app on the test phone yet |
| 6.2 | Appearance settings | 🟡 | Settings → Appearance: theming on or off, Follow Android / Light / Dark, wallpaper colors (Android 12+); per app, Look and graphics (the long-press menu, merged with the GPU fixes) → Phone's theme. Stored in `files/appearance.json`; changes reach running apps at once. Density, scroll bars, window buttons, font and icons aren't settings yet |
| 6.3 | Palette bridge | ✅ | `Palette`: Android's Material color scheme (wallpaper colors on Android 12+, a fixed palette otherwise) mapped onto libadwaita's named colors; `ThemeFiles` writes it per session as a GTK theme, xsettingsd settings and the portal's values, and rewrites it for every running app when Android's dark mode or colors change. Confirmed on the device: Nemo switches light and dark within seconds |
| 6.4 | Settings daemon in the session | ✅ | `THEME_DAEMON` (Python with GLib) runs xsettingsd and serves `org.freedesktop.portal.Settings` (color-scheme, accent-color) on the session bus, and reloads both when the launcher rewrites the files. xsettingsd joined `GUI_HELPER_PACKAGES` and is queued for existing systems. No dconf backend needed: libadwaita reads the portal |
| 6.5 | Theme asset | ✅ | `scripts/theme/build-theme.sh`: adw-gtk3 6.5 (LGPL-2.1) from its release, SHA-256 checked, as `theme/tessera-theme.tgz` (120 KB); unpacked to `/opt/tessera-theme` like the GPU driver (`OptBundle`, now shared by all three bundles), through su in chroot mode. Built in CI. No Kvantum theme or cursors yet |
| 6.6 | GTK 3 | 🟡 | adw-gtk3 recolored with the palette, light and dark, window buttons hidden (menu only). Confirmed on the device with Nemo; GIMP 3 keeps its own theme. Touch-sized rows and popovers not done |
| 6.7 | GTK 4 / libadwaita | ✅ | Light and dark (and the accent, which libadwaita maps onto its own accent colors) through the settings portal, live. libadwaita ignores GTK themes, so surfaces stay Adwaita's grays; recoloring them would mean writing the user's own `~/.config/gtk-4.0/gtk.css`, which isn't done. Confirmed on the device with Nautilus |
| 6.8 | Qt Widgets (5 and 6) | 🟡 | Qt's GTK platform theme (`qt6-gtk-platformtheme`, `qt5-gtk-platformtheme`, queued once Qt is installed) gives Qt apps the GTK theme's colors; without it, light or dark from the portal (`xdgdesktopportal`). Confirmed on the device with LibrePCB (Qt 6). No Kvantum theme; no Qt 5 app tried |
| 6.9 | Qt Quick / QML | ⬜ | `QT_QUICK_CONTROLS_STYLE=Material`, `QT_QUICK_CONTROLS_MATERIAL_THEME` and `…_ACCENT` from the palette. Per-app opt-out for apps that hardcode a style |
| 6.10 | LibreOffice | 🟡 | `libreoffice-gtk3` (queued once LibreOffice is installed) and `SAL_USE_VCLPLUGIN=gtk3`: the GTK 3 front end with the themed colors, light and dark, instead of the bare X one. Confirmed on the device with Writer and Impress. The profile isn't pre-seeded (Tabbed UI, icon style) |
| 6.11 | Firefox, Chromium, Electron | 🟡 | Firefox follows the GTK theme and the portal's light or dark (confirmed on the device). Its "closed unexpectedly" Safe Mode prompt, which ending sessions used to trigger after three stops, is off (`MOZ_DISABLE_AUTO_SAFE_MODE`). Chromium and Electron untested; no flags or seeded settings yet |
| 6.12 | Android-drawn window chrome | ⬜ | A Material 3 title strip in `X11Window`: the window's title (the window watcher already reports it) and a close button (`close <id>` already exists). Hides desktop buttons. Stretch: map dialogs to Material sheets |
| 6.13 | Fonts, icons, cursors | 🟡 | Cursor done: every windowed app has a pointer (the window manager no longer blanks it) and it's Bibata Modern Ice, white with rounded corners, from the theme pack (sizes 16 to 32 kept; `default` and `Adwaita` point at it, as apps ask for those by name). Confirmed on the device in GTK 3 and Qt apps. Roboto and Noto, fontconfig for OLED, Papirus icons not done |
| 6.14 | Touch ergonomics | ⬜ | Minimum hit sizes through GTK CSS and Kvantum metrics, kinetic scrolling where the toolkit supports it, a *Density* option that drives them. Touch and hold to right-click is done (Termux:X11 fork, in the touchpad and touchscreen-mouse modes); text-selection handles still to check |
| 6.15 | Compatibility registry | ⬜ | A shipped JSON of per-app overrides for apps that break under a theme or hardcode a style. Surfaced like Graphics fixes (*Use classic scroll bars*, *Don't theme this app*), with a "report a problem" path |
| 6.16 | Unthemeable toolkits | ⬜ | Document Tk, Xaw/Motif, FLTK and old wx limits. Stretch: a toolkit note in the app sheet |
| 6.17 | Docs and credits | ⬜ | An *Appearance* chapter in the documentation; new third-party entries in the license table; a short guide to adding an app profile |

## Phase 7: Flathub apps

Flathub as a second source in the Store, for newer versions and apps Debian lacks. The phone's kernel has no user namespaces (`CONFIG_USER_NS` unset on the test device, as on many Android kernels), so Flatpak's bubblewrap can't run, and setuid bubblewrap would break chroot mode's no-new-privs. Tessera instead runs Flathub's files itself: the runtime bound at /usr, the app at /app, with the app's permission manifest applied as for any other app.

| | Item | Status | Notes |
|---|---|---|---|
| 7.1 | Spike: one app by hand | ✅ | Done in chroot mode on the test phone (no user namespaces). Debian's flatpak 1.16.6, run as root, installs from Flathub (mpv, Freedesktop 25.08 runtime, its GL extension, aarch64); only the post-install triggers fail, as they need bubblewrap. Run by hand inside a session's mount namespace: a tmpfs root with the runtime at /usr, the app at /app, symlinks for /bin and /lib, the runtime's /etc, the GL extension under a tmpfs at /usr/lib/aarch64-linux-gnu/GL/default, and the session's /proc, /dev, /sys and X socket. mpv opened its window on the session's display, with an Android task of its own. With our Turnip build bound in at /opt/tessera-gpu and /dev/kgsl-3d0 bound into the root, it ran OpenGL 4.6 on zink over Turnip (Adreno 740); our driver needs nothing the runtime lacks. Not tried: PRoot mode, dropping to the app's uid (the runtime has no setpriv), sound, a GTK or Qt app. mpv with its runtime, GL and codecs took 2.2 GB |
| 7.2 | Download and update | ⬜ | ostree pulls from Flathub (or `flatpak --user` if its triggers run without bubblewrap) into a shared store; runtimes shared between apps; updates and cleanup of unused runtimes |
| 7.3 | Launch | ⬜ | Both backends bind the runtime at /usr, the app at /app and the runtime's /etc; the GUI helpers (window manager, D-Bus, focus and window watchers) come from Tessera, not the runtime |
| 7.4 | GPU | ⬜ | Works in the spike: our Turnip/Zink build (built against Debian 13) loads inside Freedesktop 25.08 with its own environment, bound at /opt/tessera-gpu. Older runtimes (older glibc) not checked |
| 7.5 | Store integration | ⬜ | Flathub's catalog beside Debian's: a source badge, "newer on Flathub", only apps with aarch64 builds; default permissions from the app's finish-args |
| 7.6 | Portals and theming | ⬜ | xdg-desktop-portal for file pickers; Phase 6's theme bound into each runtime |

## Known issues

- Alpine isn't offered: the Store, the app manifests, package installs and the GPU driver (built against glibc) are all Debian's.
- Session ids restart at `s1` with each app process; stale session directories are cleared at startup.
- Builds need WSL (or Linux) once for the X server's native library.
