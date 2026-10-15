#!/usr/bin/env bash
# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Phase 6's test matrix (docs/ROADMAP.md, 6.1): opens each app on a connected phone in light and
# dark mode and saves a screenshot of its window, for before/after comparisons of the theming.
#
#   scripts/look/screenshots.sh <label> [app-id...]
#
# Saves docs/look/<label>/<app-id>-<light|dark>.png. With no app ids, the matrix below is used;
# apps that aren't installed are skipped. ADB_SERIAL picks the phone when several are attached.
# Running sessions are ended between apps (the launcher is force-stopped, and in chroot mode the
# leftover guest processes are killed through su).
set -euo pipefail
export MSYS_NO_PATHCONV=1

label=${1:?usage: $0 <label> [app-id...]}
shift
# One per toolkit row of the matrix; ids are the launcher's (store apps by package, system apps
# as system-<desktop file>).
apps=("$@")
[ ${#apps[@]} -gt 0 ] || apps=(
  gimp                        # GTK 3
  nemo                        # GTK 3
  nautilus                    # GTK 4 / libadwaita
  librepcb                    # Qt 6 Widgets
  libreoffice-writer          # LibreOffice (VCL)
  libreoffice-impress
  firefox-esr                     # GTK 3 + own UI
  system-com.microsoft.vscode # Electron
)
wait_s=${WAIT:-15}
pkg=com.daryan.tessera
out="$(cd "$(dirname "$0")/../.." && pwd)/docs/look/$label"
mkdir -p "$out"
adb() { command adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }

installed=$(adb shell "su -c 'ls /data/data/$pkg/files/apps; ls /data/local/linux/debian/usr/share/applications'" 2>/dev/null || true)

stop_all() {
  adb shell am force-stop $pkg
  # Chroot sessions run under su and outlive the app; every guest process carries LAUNCHER_SESSION.
  adb shell "su -c 'for p in /proc/[0-9]*; do grep -qa LAUNCHER_SESSION= \$p/environ 2>/dev/null && kill -9 \${p#/proc/}; done'" >/dev/null 2>&1 || true
}

# The screen must stay on for the whole run; the phone's own settings come back afterwards.
night=$(adb shell cmd uimode night | tr -d "\r" | awk '{print $NF}')
adb shell svc power stayon true >/dev/null
trap 'adb shell svc power stayon false >/dev/null; adb shell cmd uimode night "$night" >/dev/null' EXIT

for mode in light dark; do
  adb shell cmd uimode night $([ $mode = dark ] && echo yes || echo no) >/dev/null
  for app in "${apps[@]}"; do
    file=${app#system-}
    if ! grep -qx -e "$app.json" -e "$file.desktop" <<<"$installed" && [ "$app" != firefox-esr ] && [ "$app" != gimp ]; then
      echo "skip $app (not installed)"; continue
    fi
    stop_all
    adb shell "su -c 'input keyevent KEYCODE_WAKEUP'" >/dev/null || true
    # The launcher first, so it has read its app list before it's asked for one of them.
    adb shell am start -n $pkg/.MainActivity >/dev/null
    sleep 4
    adb shell am start -a $pkg.LAUNCH_APP -n $pkg/.MainActivity --es app_id "$app" >/dev/null
    # Only once the app's own window (X11Window) is in front; anything else would be captured.
    front=
    for _ in $(seq 1 20); do
      front=$(adb shell "dumpsys activity activities 2>/dev/null | grep topResumedActivity | head -1" 2>/dev/null)
      grep -q X11Window <<<"$front" && break
      sleep 1
    done
    if ! grep -q X11Window <<<"$front"; then
      echo "fail $app ($mode): its window never came up"; continue
    fi
    sleep "$wait_s"
    # And still in front after the wait (the app may have crashed, or the phone been used).
    if ! adb shell "dumpsys activity activities 2>/dev/null | grep topResumedActivity | head -1" | grep -q X11Window; then
      echo "fail $app ($mode): its window left the screen"; continue
    fi
    adb exec-out screencap -p >"$out/$app-$mode.png"
    echo "$out/$app-$mode.png"
  done
done
stop_all

# Half size and 256 colors, small enough to keep in git (needs Pillow).
shrink='
import glob, sys
from PIL import Image
for f in glob.glob(sys.argv[1] + "/*.png"):
    im = Image.open(f).convert("RGB")
    if im.width > 720:
        im = im.resize((720, im.height * 720 // im.width), Image.LANCZOS)
    im.quantize(256, method=Image.Quantize.MEDIANCUT).save(f, optimize=True)
'
python3 -c "$shrink" "$out" 2>/dev/null || python -c "$shrink" "$out" 2>/dev/null || echo "kept full size (needs Python with Pillow)"
