#!/bin/sh
# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Unpacks an AppImage into /opt/appimages/<SLUG>, as root in the container, from the install
# queue. AppImages mount themselves with FUSE, which neither engine has (no /dev/fuse for an
# app, none in a chroot's /dev); unpacked once, the app starts from its AppRun like any other,
# without unpacking itself into /tmp at every start (--appimage-extract-and-run).
#
# Inputs (set by AppImages.installScript before this text): STAGED, the AppImage's path;
#   SLUG, the folder's name. Leaves, for the launcher to read: .tessera.desktop, the app's
#   desktop file, and .tessera-icon, its icon as base64 when it's a PNG.
set -e
d=/opt/appimages/$SLUG
t=$d.new
rm -rf "$t"
mkdir -p "$t"
cd "$t"
echo 'Copying the AppImage'
cp -- "$STAGED" app.AppImage
chmod 755 app.AppImage
echo 'Unpacking the AppImage'
# It unpacks itself: its first part is a program for this kind of processor, or it can't run here.
./app.AppImage --appimage-extract >/dev/null || {
  echo "error: the AppImage couldn't unpack itself (is it for this phone's processor?)"
  cd /; rm -rf "$t"
  exit 1
}
rm -f app.AppImage
# Newer runtimes (uruntime) unpack into AppDir and leave squashfs-root as a link to it (#41):
# move the folder itself, not the link, which would dangle once $t is removed. Only a folder
# inside $t, so an AppImage can't point this at anything else in the container.
src=squashfs-root
if [ -L "$src" ]; then
  src=$(readlink -f "$src") || src=
  case "$src" in "$(pwd -P)"/?*) ;; *) src= ;; esac
fi
[ -n "$src" ] && [ -d "$src" ] && [ -e "$src/AppRun" ] || {
  echo 'error: this AppImage has no AppRun to start'
  cd /; rm -rf "$t"
  exit 1
}
rm -rf "$d"
mv "$src" "$d"
cd /
rm -rf "$t"
rm -f -- "$STAGED"
# Unpacked as root; apps run as the user.
chmod -R a+rX "$d"

f=$(ls "$d"/*.desktop 2>/dev/null | head -n 1)
if [ -n "$f" ]; then cp -L -- "$f" "$d/.tessera.desktop" 2>/dev/null || true; fi

png() {
  [ -f "$1" ] || return 1
  [ "$(head -c 4 "$1" | od -An -tx1 | tr -d ' \n')" = 89504e47 ] || return 1
  [ "$(wc -c < "$1")" -lt 1500000 ]
}
n=$(sed -n 's/^Icon=//p' "$d/.tessera.desktop" 2>/dev/null | head -n 1)
for c in "$d/.DirIcon" "$d/$n.png" "$d/$n" $(find "$d/usr/share/icons" "$d/usr/share/pixmaps" -name "$n.png" 2>/dev/null | sort -r | head -n 6); do
  if png "$c"; then
    base64 "$c" | tr -d '\n' > "$d/.tessera-icon"
    break
  fi
done
echo 'Unpacked'
