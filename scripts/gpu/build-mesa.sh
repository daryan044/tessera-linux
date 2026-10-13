#!/usr/bin/env bash
# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the GPU driver the containers use on Adreno phones: Mesa's Turnip (Vulkan) for
# Android's KGSL GPU interface, and Zink (OpenGL on Vulkan), for Debian trixie arm64. Debian's
# own Mesa only knows the desktop DRM interface, which Android doesn't give apps.
#
# Cross-compiles inside a Debian trixie root (so the libraries match the containers exactly)
# and packs the result as build/gpu/assets/gpu/mesa-turnip.tgz (bundled into the APK by
# app/build.gradle.kts; builds without it just have no GPU acceleration), which the app unpacks
# into a container's /opt/linuxlauncher-gpu when GPU acceleration is on.
#
# Needs a Linux host (WSL is fine) and root, for the build root:
#   sudo scripts/gpu/build-mesa.sh
# Env: MESA_VERSION (default below), TRIXIE (the build root, default /opt/trixie).
set -euo pipefail

MESA_VERSION=${MESA_VERSION:-26.2.4}
TRIXIE=${TRIXIE:-/opt/trixie}
PREFIX=/opt/linuxlauncher-gpu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../build/gpu/assets/gpu"

if [ ! -x "$TRIXIE/usr/bin/apt-get" ]; then
  apt-get install -y debootstrap
  debootstrap --variant=buildd --include=ca-certificates trixie "$TRIXIE" http://deb.debian.org/debian
fi

# Everything below runs inside the build root.
cat > "$TRIXIE/build-mesa.sh" <<EOF
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
dpkg --add-architecture arm64
apt-get update -qq
apt-get install -y -qq --no-install-recommends \
  crossbuild-essential-arm64 meson ninja-build pkgconf pkgconf:arm64 bison flex xz-utils curl \
  python3-mako python3-yaml python3-packaging glslang-tools patch \
  libdrm-dev:arm64 libexpat1-dev:arm64 zlib1g-dev:arm64 libzstd-dev:arm64 \
  libx11-dev:arm64 libxext-dev:arm64 libxfixes-dev:arm64 libxrandr-dev:arm64 libxxf86vm-dev:arm64 \
  libxshmfence-dev:arm64 libx11-xcb-dev:arm64 libxcb1-dev:arm64 libxcb-glx0-dev:arm64 libxcb-shm0-dev:arm64 \
  libxcb-dri3-dev:arm64 libxcb-present-dev:arm64 libxcb-randr0-dev:arm64 libxcb-sync-dev:arm64 \
  libxcb-xfixes0-dev:arm64 libxcb-keysyms1-dev:arm64 libglvnd-dev:arm64 >/dev/null

mkdir -p /src && cd /src
[ -d mesa-$MESA_VERSION ] || curl -fsSL https://archive.mesa3d.org/mesa-$MESA_VERSION.tar.xz | tar xJ
cd mesa-$MESA_VERSION
# Our fixes (scripts/gpu/patches); -N skips ones a reused source tree already has.
for p in /patches/*.patch; do patch -p1 -N < "\$p" || true; done

cat > /src/arm64.cross <<CROSS
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
pkg-config = 'aarch64-linux-gnu-pkg-config'
[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
CROSS

rm -rf build
meson setup build --cross-file /src/arm64.cross \
  --prefix=$PREFIX --libdir=lib --buildtype=release -Db_ndebug=true \
  -Dplatforms=x11 \
  -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl \
  -Dgallium-drivers=zink \
  -Dglx=dri -Degl=enabled -Dgles1=disabled -Dgles2=enabled -Dglvnd=enabled \
  -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dvideo-codecs= -Dtools= -Dvulkan-layers=
ninja -C build
rm -rf /stage && DESTDIR=/stage ninja -C build install
aarch64-linux-gnu-strip --strip-unneeded \$(find /stage -name '*.so*' -type f)
EOF
rm -rf "$TRIXIE/patches" && cp -r "$HERE/patches" "$TRIXIE/patches"
cp /etc/resolv.conf "$TRIXIE/etc/resolv.conf"
mountpoint -q "$TRIXIE/proc" || mount -t proc proc "$TRIXIE/proc"
trap 'umount "$TRIXIE/proc" 2>/dev/null || true' EXIT
chroot "$TRIXIE" /bin/bash /build-mesa.sh

# Headers and pkg-config files are for building against it; apps only need the libraries.
rm -rf "$TRIXIE/stage$PREFIX/include" "$TRIXIE/stage$PREFIX/lib/pkgconfig"
mkdir -p "$OUT"
tar --format=ustar -C "$TRIXIE/stage" -czf "$OUT/mesa-turnip.tgz" "${PREFIX#/}"
# Bump the suffix when the patches change, so phones unpack the new build.
echo "$MESA_VERSION-ll3" > "$OUT/VERSION"
ls -l "$OUT"
