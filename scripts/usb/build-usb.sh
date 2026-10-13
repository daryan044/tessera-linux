#!/usr/bin/env bash
# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the guest half of the USB bridge for PRoot mode, for Debian trixie arm64:
#  - llusb.so (scripts/usb/llusb.c), preloaded into bridged apps: opening a granted
#    /dev/bus/usb path returns the device the launcher opened through Android's USB permission.
#  - libusb without udev, with scripts/usb/patches: under the bridge it finds devices in
#    /dev/bus/usb and skips sysfs and hotplug, which Android keeps apps out of. Bridged apps
#    load it ahead of Debian's through LD_LIBRARY_PATH.
#
# Cross-compiles inside a Debian trixie root (the same one as scripts/gpu/build-mesa.sh) and
# packs the result as build/usb/assets/usb/usb-bridge.tgz (bundled into the APK by
# app/build.gradle.kts; builds without it have no USB bridge), unpacked into a container's
# /opt/linuxlauncher-usb.
#
# Needs a Linux host (WSL is fine) and root, for the build root:
#   sudo scripts/usb/build-usb.sh
# Env: TRIXIE (the build root, default /opt/trixie).
set -euo pipefail

LIBUSB_VERSION=1.0.29
LIBUSB_SHA256=5977fc950f8d1395ccea9bd48c06b3f808fd3c2c961b44b0c2e6e29fc3a70a85
TRIXIE=${TRIXIE:-/opt/trixie}
PREFIX=/opt/linuxlauncher-usb
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../build/usb/assets/usb"

if [ ! -x "$TRIXIE/usr/bin/apt-get" ]; then
  apt-get install -y debootstrap
  debootstrap --variant=buildd --include=ca-certificates trixie "$TRIXIE" http://deb.debian.org/debian
fi

# Everything below runs inside the build root.
cat > "$TRIXIE/build-usb.sh" <<EOF
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
dpkg --add-architecture arm64
apt-get update -qq
apt-get install -y -qq --no-install-recommends crossbuild-essential-arm64 bzip2 curl patch >/dev/null

mkdir -p /src && cd /src
rm -rf libusb-$LIBUSB_VERSION
curl -fsSL -o libusb.tar.bz2 https://github.com/libusb/libusb/releases/download/v$LIBUSB_VERSION/libusb-$LIBUSB_VERSION.tar.bz2
echo "$LIBUSB_SHA256  libusb.tar.bz2" | sha256sum -c -
tar xjf libusb.tar.bz2
cd libusb-$LIBUSB_VERSION
for p in /usb/patches/*.patch; do patch -p1 < "\$p"; done
./configure --host=aarch64-linux-gnu --prefix=$PREFIX --disable-udev --disable-static >/dev/null
make -j"\$(nproc)" >/dev/null
rm -rf /stage && make install DESTDIR=/stage >/dev/null

aarch64-linux-gnu-gcc -O2 -shared -fPIC -U_FORTIFY_SOURCE -o /stage$PREFIX/lib/llusb.so /usb/llusb.c -ldl
aarch64-linux-gnu-strip --strip-unneeded /stage$PREFIX/lib/*.so*
EOF
rm -rf "$TRIXIE/usb" && mkdir -p "$TRIXIE/usb" && cp -r "$HERE/patches" "$HERE/llusb.c" "$TRIXIE/usb/"
cp /etc/resolv.conf "$TRIXIE/etc/resolv.conf"
mountpoint -q "$TRIXIE/proc" || mount -t proc proc "$TRIXIE/proc"
trap 'umount "$TRIXIE/proc" 2>/dev/null || true' EXIT
chroot "$TRIXIE" /bin/bash /build-usb.sh

# Only the libraries go in; headers, pkg-config files and libtool archives are for building.
rm -rf "$TRIXIE/stage$PREFIX/include" "$TRIXIE/stage$PREFIX/lib/pkgconfig" "$TRIXIE/stage$PREFIX"/lib/*.la
mkdir -p "$OUT"
tar --format=ustar -C "$TRIXIE/stage" -czf "$OUT/usb-bridge.tgz" "${PREFIX#/}"
# Bump the suffix when the shim or patches change, so phones unpack the new build.
echo "$LIBUSB_VERSION-ll1" > "$OUT/VERSION"
ls -l "$OUT"
