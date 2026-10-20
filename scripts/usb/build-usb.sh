#!/usr/bin/env bash
# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the guest half of the USB bridge for PRoot mode, for Debian trixie arm64:
#  - tessera-usb.so (scripts/usb/tessera-usb.c), preloaded into bridged apps: opening a granted
#    /dev/bus/usb path returns the device the launcher opened through Android's USB permission.
#  - libusb without udev, with scripts/usb/patches: under the bridge it finds devices in
#    /dev/bus/usb and skips sysfs and hotplug, which Android keeps apps out of. Bridged apps
#    load it ahead of Debian's through LD_LIBRARY_PATH.
#
# Cross-compiles inside a Debian trixie root (the same one as scripts/gpu/build-mesa.sh) and
# packs the result as build/usb/assets/usb/usb-bridge.tgz (bundled into the APK by
# app/build.gradle.kts; builds without it have no USB bridge), unpacked into a container's
# /opt/tessera-usb.
#
# Needs a Linux host (WSL is fine) and root, for the build root:
#   sudo scripts/usb/build-usb.sh
# Or, on a Debian trixie host with crossbuild-essential-arm64 installed (as F-Droid's build
# server has, from its recipe), without a build root or root:
#   TESSERA_ON_HOST=1 scripts/usb/build-usb.sh
# Env: TRIXIE (the build root, default /opt/trixie).
set -euo pipefail

LIBUSB_VERSION=1.0.29
LIBUSB_SHA256=5977fc950f8d1395ccea9bd48c06b3f808fd3c2c961b44b0c2e6e29fc3a70a85
TRIXIE=${TRIXIE:-/opt/trixie}
PREFIX=/opt/tessera-usb
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../build/usb/assets/usb"
ON_HOST=${TESSERA_ON_HOST:-}
# Where the build works: in the build root, or a folder of our own on the host.
if [ -n "$ON_HOST" ]; then
  mkdir -p "$HERE/../../build/usb-work"
  WORK=$(cd "$HERE/../../build/usb-work" && pwd)
  SCRIPT="$WORK/build-usb.sh"
else
  WORK="$TRIXIE/work"
  SCRIPT="$TRIXIE/build-usb.sh"
fi

if [ -z "$ON_HOST" ] && [ ! -x "$TRIXIE/usr/bin/apt-get" ]; then
  apt-get install -y debootstrap
  debootstrap --variant=buildd --include=ca-certificates trixie "$TRIXIE" http://deb.debian.org/debian
fi

# Everything below runs inside the build root (working in /work), or on the host.
cat > "$SCRIPT" <<EOF
set -euo pipefail
WORK=\$1
if [ -z "$ON_HOST" ]; then
export DEBIAN_FRONTEND=noninteractive
dpkg --add-architecture arm64
apt-get update -qq
apt-get install -y -qq --no-install-recommends crossbuild-essential-arm64 bzip2 curl patch >/dev/null
fi

mkdir -p \$WORK/src && cd \$WORK/src
rm -rf libusb-$LIBUSB_VERSION
curl -fsSL -o libusb.tar.bz2 https://github.com/libusb/libusb/releases/download/v$LIBUSB_VERSION/libusb-$LIBUSB_VERSION.tar.bz2
echo "$LIBUSB_SHA256  libusb.tar.bz2" | sha256sum -c -
tar xjf libusb.tar.bz2
cd libusb-$LIBUSB_VERSION
for p in \$WORK/usb/patches/*.patch; do patch -p1 < "\$p"; done
./configure --host=aarch64-linux-gnu --prefix=$PREFIX --disable-udev --disable-static >/dev/null
make -j"\$(nproc)" >/dev/null
rm -rf \$WORK/stage && make install DESTDIR=\$WORK/stage >/dev/null

aarch64-linux-gnu-gcc -O2 -shared -fPIC -U_FORTIFY_SOURCE -o \$WORK/stage$PREFIX/lib/tessera-usb.so \$WORK/usb/tessera-usb.c -ldl
aarch64-linux-gnu-strip --strip-unneeded \$WORK/stage$PREFIX/lib/*.so*
EOF
mkdir -p "$WORK"
rm -rf "$WORK/usb" && mkdir -p "$WORK/usb" && cp -r "$HERE/patches" "$HERE/tessera-usb.c" "$WORK/usb/"
if [ -n "$ON_HOST" ]; then
  bash "$SCRIPT" "$WORK"
else
  cp /etc/resolv.conf "$TRIXIE/etc/resolv.conf"
  mountpoint -q "$TRIXIE/proc" || mount -t proc proc "$TRIXIE/proc"
  trap 'umount "$TRIXIE/proc" 2>/dev/null || true' EXIT
  chroot "$TRIXIE" /bin/bash /build-usb.sh /work
fi

# Only the libraries go in; headers, pkg-config files and libtool archives are for building.
rm -rf "$WORK/stage$PREFIX/include" "$WORK/stage$PREFIX/lib/pkgconfig" "$WORK/stage$PREFIX"/lib/*.la
mkdir -p "$OUT"
tar --format=ustar -C "$WORK/stage" -czf "$OUT/usb-bridge.tgz" "${PREFIX#/}"
# Bump the suffix when the shim or patches change, so phones unpack the new build.
echo "$LIBUSB_VERSION-t1" > "$OUT/VERSION"
ls -l "$OUT"
