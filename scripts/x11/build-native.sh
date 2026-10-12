#!/usr/bin/env bash
# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds Termux:X11's X server (libXlorie.so) for every ABI and copies it to
# build/x11/jniLibs/<abi>/, which gradle.properties points lorie.prebuiltJniLibs at,
# and stages the xkb keyboard data into build/x11/assets/ for the app to bundle.
#
# Needs a Linux host (WSL is fine): the Xorg build compiles and runs helper tools
# such as makekeys with the host gcc. Everything else builds on any OS.
#
#   scripts/x11/build-native.sh
#   X11_SRC=~/src/termux-x11 scripts/x11/build-native.sh   # faster: a checkout on the Linux filesystem
#
# Env: ANDROID_HOME, X11_SRC (defaults to the third_party/termux-x11 submodule).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="${X11_SRC:-$ROOT/third_party/termux-x11}"
OUT="$ROOT/build/x11/jniLibs"

[ "$(uname -s)" = Linux ] || { echo "needs a Linux host (WSL works); see the header of this script" >&2; exit 1; }
[ -n "${ANDROID_HOME:-}" ] || { echo "set ANDROID_HOME" >&2; exit 1; }
[ -f "$SRC/lorie/build.gradle" ] || { echo "no termux-x11 checkout at $SRC (git submodule update --init)" >&2; exit 1; }

# The Xorg sources are nested submodules; only this build needs them.
git -C "$SRC" submodule update --init --depth 1 --jobs 8
[ -f "$SRC/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$SRC/local.properties"

(cd "$SRC" && ./gradlew --console=plain :lorie:mergeReleaseNativeLibs)

LIBS="$SRC/lorie/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib"
rm -rf "$OUT"
for dir in "$LIBS"/*/; do
  abi=$(basename "$dir")
  mkdir -p "$OUT/$abi"
  cp "$dir/libXlorie.so" "$OUT/$abi/"
done
ls -l "$OUT"/*/

# Keyboard layouts the X server compiles keymaps from. Bundled in the APK rather than read
# from the container so GUI apps also work in chroot mode, whose files the app can't read.
ASSETS="$ROOT/build/x11/assets"
rm -rf "$ASSETS" && mkdir -p "$ASSETS"
TMP=$(mktemp -d)
(cd "$TMP" && apt-get download xkb-data >/dev/null && dpkg-deb -x xkb-data_*.deb root)
cp -rL "$TMP/root/usr/share/X11/xkb" "$ASSETS/xkb"  # -L: assets have no symlinks
dpkg-deb -f "$TMP"/xkb-data_*.deb Version > "$ASSETS/xkb/VERSION"  # no leading dot: aapt drops dotfiles from assets
rm -rf "$TMP"
du -sh "$ASSETS/xkb"
