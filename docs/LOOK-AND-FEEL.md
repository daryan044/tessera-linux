# Phase 6: Look and feel

The plan behind Phase 6 of [ROADMAP.md](ROADMAP.md), which tracks each item's status. Deepin's DTK is Qt-based, so the Qt items (6.8, 6.9) cover it.

## Goal

Apps whose toolkits look dated on a phone (LibreOffice, older GTK 3 and Qt Widgets apps, Qt Quick apps, the chrome of Firefox and Electron apps) get a modern, **Material-style** look that follows the phone's own theme (light or dark, wallpaper colors) and has touch-sized controls, **without forking or patching any toolkit**.

## Principles

1. **Theme through the channels toolkits already listen to**: XSETTINGS, dconf, environment variables, per-app config files. Each app is still drawn by its own toolkit. Patching GTK, Qt or Firefox is the line into distro territory; this phase stays on the shell side of it.
2. **One palette, many outputs.** Android's Material color scheme is the single source, written into each toolkit's own format at launch.
3. **Defaults with overrides.** A global off switch, per-app overrides, and Reset that always works.
4. **Material-like, not pixel-identical, and the docs say so.** GTK 4 / libadwaita stays GNOME-shaped (recolored); Qt Widgets gets close, not identical.
5. **Ship what Debian lacks the way the GPU driver ships**: built by a script from pinned sources, unpacked into the rootfs under `/opt`. No unsigned apt repository.
6. **Measure the cost.** Sessions without the GPU are software-rendered, and CSS-heavy themes cost frames.
7. **Everything is verified on the device, with screenshots**, light and dark.

## What draws what, and what we can achieve

| Toolkit / apps | Looks today | Approach | Expected result |
|---|---|---|---|
| **GTK 3** (Evince, GIMP 2.10, many Debian apps) | Debian's stock Adwaita for GTK 3, classic scroll bars, header bars with window buttons | A bundled `adw-gtk3` (the libadwaita look for GTK 3) plus palette CSS; settings through XSETTINGS | Close to modern libadwaita, with Material colors |
| **GTK 4 / libadwaita** (HandBrake, GNOME apps) | Already modern in shape | Recolor through `gtk-4.0/gtk.css` named colors; light/dark through dconf | GNOME shape, Material palette |
| **Qt 5/6 Widgets** (VLC, KeePassXC, Kate, Okular) | Fusion or classic, dated | `qt5ct`/`qt6ct` platform theme plus a Kvantum theme generated from the palette; `adwaita-qt` as the Qt 5 fallback | Flat and modern; closer than today, not identical |
| **Qt Quick / QML** (QML and Kirigami apps) | Whichever style the app picks | `QT_QUICK_CONTROLS_STYLE=Material` with theme and accent from the palette, for apps that don't hardcode a style | Real Material controls (the generation depends on Qt 6.8 in trixie) |
| **LibreOffice** | Debian uses the GTK 3 plugin if installed, else the dated `gen` one | Install `libreoffice-gtk3`, set the VCL plugin, pre-seed the user profile: Tabbed UI, large toolbar icons, icon style | Modern, touch-friendlier toolbar; dialogs follow the GTK theme |
| **Firefox** | GTK theme plus its own UI | Preferences: overlay scroll bars, touch density, dark mode, tabs in the title bar | Mostly fine; thin scroll bars |
| **Chromium / Electron** (VS Code) | Web UI, native frame | Flags (overlay scroll bar, force dark) and app settings (title bar style) | Page content is web-drawn and unchanged; frame and scroll bars improve |
| **Java Swing** | Metal / GTK look and feel | Evaluate FlatLaf through JVM options, case by case | Per app |
| **Tk, Xaw/Motif, FLTK, old wx** | Their own drawing | Cannot be themed meaningfully; document it | Unchanged |

## Building blocks

Checked on 2026-10-14 against packages.debian.org, Debian 13 (trixie).

| Piece | Package | In trixie? | Role |
|---|---|---|---|
| XSETTINGS daemon | `xsettingsd` 1.0.2 | ✅ arm64 | Theme, icons, font, DPI, overlay scrolling for GTK and Qt on a bare X session |
| GTK 3 modern theme | `adw-gtk3` | ❌ not in trixie | **Must be bundled by us** |
| GTK Material theme | `materia-gtk-theme` | ❌ not available in trixie | Skip, or bundle |
| GTK 2 fallback | `gnome-themes-extra` 3.28 | ✅ arm64 | Adwaita for GTK 2 |
| Qt 6 platform theme | `qt6ct` 0.10 | ✅ arm64 | Style, palette, fonts, icons for Qt 6 without a desktop |
| Kvantum | `qt6-style-kvantum` 1.1.4 | ⚠️ transitional package only; a package named `kvantum` doesn't exist | **Find the real package names before depending on it** |
| Qt 5 Adwaita style | `adwaita-qt` 1.4.2 | ✅ arm64, Qt 5 only | Qt 5 fallback |
| Qt Quick Controls | `qml6-module-qtquick-controls` 6.8.2 | ✅ arm64 | Includes the Material style |
| LibreOffice GTK 3 / Qt 6 plugins | `libreoffice-gtk3`, `libreoffice-qt6` 25.2.3 | ✅ arm64 | VCL integration |
| LibreOffice symbolic icons | `libreoffice-style-sifr` | ✅ all | Icon style |
| Icons | `papirus-icon-theme` 20250501 | ✅ all | App and system icons |
| Font | `fonts-roboto` | ✅ all (2017 release) | UI font |

Not checked: Qt 5 platform theme packages, an `adwaita-qt6` equivalent, `dconf` backend and schema packages, `fonts-inter`.

## How it works

Each themed GUI session gets a theme directory, written by the launcher into the session's X11 directory, which the guest sees as `/tmp/.X11-unix/tessera-theme` in both engines (no su needed). `ThemeFiles` writes it from a `Palette` (Android's Material color scheme, from the wallpaper on Android 12+):

- `share/themes/Tessera-<n>/`: a GTK theme. GTK 3 gets adw-gtk3 from the theme pack (`/opt/tessera-theme`, built by `scripts/theme/build-theme.sh`) with libadwaita's named colors set from the palette. Each new palette gets a new name, because GTK only reloads a theme whose name changed. The session's `XDG_DATA_DIRS` puts this directory first.
- `xsettingsd.conf`: the theme's name, icons, and window buttons (only the menu button; windows are closed from Recents).
- `portal`: light or dark and the accent color. Written last.

In the session, before the app starts, `THEME_DAEMON` (Python with GLib, already there for the focus watcher) runs xsettingsd and serves `org.freedesktop.portal.Settings` on the session bus with `org.freedesktop.appearance`'s `color-scheme` and `accent-color`. libadwaita, Qt 6, Firefox and Chromium read light or dark there. When `portal` changes, the daemon emits `SettingChanged` and makes xsettingsd reload, so running apps restyle. The launcher rewrites every running session's directory when Android's configuration changes (dark mode, wallpaper colors) or the user changes the settings.

The wrapper also points Qt at its GTK platform theme (`QT_QPA_PLATFORMTHEME=gtk3`) where installed, falling back to `xdgdesktopportal`, and LibreOffice at its GTK 3 front end (`SAL_USE_VCLPLUGIN=gtk3`). The packages that provide them (`qt6-gtk-platformtheme`, `qt5-gtk-platformtheme`, `libreoffice-gtk3`) are queued with the GUI helpers once their toolkit is installed (`THEME_COMPANIONS`).

Settings: Settings → Appearance (on or off, Follow Android, Light or Dark, wallpaper colors), and per app in its long-press menu, Look and graphics → Phone's theme. Stored in `files/appearance.json`.

## Items

The items (6.1 to 6.17) and their status are in [ROADMAP.md](ROADMAP.md#phase-6-look-and-feel).

## Milestones

| | Milestone | Items | Done when |
|---|---|---|---|
| M1 | Foundation | 6.1 to 6.5, basics of 6.13 | A GTK 3 app and a Qt app recolor within seconds of Android switching dark mode. The off switch and Reset work. Permissions and enforcement are untouched. APK growth is inside the agreed budget |
| M2 | GTK and LibreOffice | 6.6, 6.7, 6.10 | Writer, Calc and Impress open in the Tabbed UI with no dated-looking dialogs, in light and dark. GTK 3 and 4 apps pass the matrix |
| M3 | Qt | 6.8, 6.9 | The Qt apps in the matrix pass; QML apps use the Material style |
| M4 | Browsers and chrome | 6.11, 6.12, 6.14 | Firefox and VS Code have thin scroll bars and no stray title bars; the Android title strip works with multi-window apps |
| M5 | Polish | 6.15 to 6.17 | The registry, docs and credits are in; screenshots re-taken for all of the matrix |

## Test matrix (6.1)

`scripts/look/screenshots.sh <label>` opens each of these on a connected phone, in light and dark mode, and saves `docs/look/<label>/<app>-<light|dark>.png`. `docs/look/before/` is the baseline.

| App | Toolkit | Look for |
|---|---|---|
| GIMP 3 | GTK 3 | Dense toolbars, dialogs, docks |
| Nemo | GTK 3 | Scroll bars, sidebar, list rows, dark mode |
| Nautilus | GTK 4 / libadwaita | Palette applied, dark mode |
| LibrePCB | Qt 6 Widgets | Controls, menus, scroll bars |
| LibreOffice Writer, Impress | VCL | Tabbed UI, dialog scaling, keyboard |
| Firefox | GTK + own UI | Scroll bars, density, dark mode |
| VS Code | Electron | Frame, scroll bars, dark mode |

A QML app and a Qt 5 app (VLC, KeePassXC) join the matrix once they're installed on the test phone. Wireshark was meant as a second Qt app, but doesn't open its window in chroot mode, themed or not.

## Risks and decisions

- **Bundling cost.** `adw-gtk3` is not in trixie and Kvantum's naming changed, so Tessera has to ship and maintain some of this itself. Decide an APK size budget, and whether the theme pack is bundled or downloaded on demand.
- **Upstream churn.** `adw-gtk3` tracks GTK and libadwaita versions, and libadwaita can rename its named colors. Pin versions and re-run the matrix on each Debian update.
- **Performance.** Themes with heavy CSS cost frames in software-rendered sessions. Measure on 6.1's baseline and keep a *classic* option.
- **GTK 4 limits.** Only the palette changes; the shape stays GNOME's. Say so in the docs.
- **Qt Widgets limits.** Kvantum can approximate Material but a faithful generated theme is custom work and could be large. Prototype before committing (6.8).
- **Dynamic color needs Android 12 or newer.** Below that, use a static palette.
- **Parity between modes.** PRoot writes into the app's own home; chroot needs `su` for anything under `/opt` or `/etc`. Test both.
- **Open decisions:**
  - Default on, or opt-in for the first release?
  - Default density: Comfortable or Touch?
  - Accent source: wallpaper-derived or a fixed brand color?
  - Which LibreOffice plugin: GTK 3 or Qt 6?

## Not doing

- Patching GTK, Qt, Firefox or LibreOffice.
- A Wayland compositor.
- Theming Tk, Xaw/Motif and FLTK apps beyond what their own resources allow.
- An apt repository of our own for theme packages.
