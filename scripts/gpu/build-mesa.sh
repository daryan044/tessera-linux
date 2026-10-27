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
# into a container's /opt/tessera-gpu when GPU acceleration is on.
#
# Needs a Linux host (WSL is fine) and root, for the build root:
#   sudo scripts/gpu/build-mesa.sh
# Or, on a Debian trixie host that has the packages installed below (as F-Droid's build server
# does, from its recipe), without a build root or root:
#   TESSERA_ON_HOST=1 scripts/gpu/build-mesa.sh
# Env: MESA_VERSION (default below), TRIXIE (the build root, default /opt/trixie).
set -euo pipefail

MESA_VERSION=${MESA_VERSION:-26.2.4}
TRIXIE=${TRIXIE:-/opt/trixie}
PREFIX=/opt/tessera-gpu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../build/gpu/assets/gpu"
ON_HOST=${TESSERA_ON_HOST:-}
# Where the build works: in the build root, or a folder of our own on the host.
if [ -n "$ON_HOST" ]; then
  mkdir -p "$HERE/../../build/gpu-work"
  WORK=$(cd "$HERE/../../build/gpu-work" && pwd)
  SCRIPT="$WORK/build-mesa.sh"
else
  WORK="$TRIXIE/work"
  SCRIPT="$TRIXIE/build-mesa.sh"
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
apt-get install -y -qq --no-install-recommends \
  crossbuild-essential-arm64 meson ninja-build pkgconf pkgconf:arm64 bison flex xz-utils curl \
  python3-mako python3-yaml python3-packaging glslang-tools patch \
  libdrm-dev:arm64 libexpat1-dev:arm64 zlib1g-dev:arm64 libzstd-dev:arm64 \
  libx11-dev:arm64 libxext-dev:arm64 libxfixes-dev:arm64 libxrandr-dev:arm64 libxxf86vm-dev:arm64 \
  libxshmfence-dev:arm64 libx11-xcb-dev:arm64 libxcb1-dev:arm64 libxcb-glx0-dev:arm64 libxcb-shm0-dev:arm64 \
  libxcb-dri3-dev:arm64 libxcb-present-dev:arm64 libxcb-randr0-dev:arm64 libxcb-sync-dev:arm64 \
  libxcb-xfixes0-dev:arm64 libxcb-keysyms1-dev:arm64 libglvnd-dev:arm64 >/dev/null
fi

mkdir -p \$WORK/src && cd \$WORK/src
[ -d mesa-$MESA_VERSION ] || curl -fsSL https://archive.mesa3d.org/mesa-$MESA_VERSION.tar.xz | tar xJ
cd mesa-$MESA_VERSION
# Our fixes (scripts/gpu/patches). One a reused source tree already has is skipped; one that
# doesn't apply stops the build, rather than shipping a driver without it.
for p in \$WORK/patches/*.patch; do
  if patch -p1 -R --dry-run -s -f < "\$p" >/dev/null 2>&1; then
    echo "already applied: \$(basename "\$p")"
  else
    patch -p1 -N < "\$p"
  fi
done

cat > \$WORK/src/arm64.cross <<CROSS
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
meson setup build --cross-file \$WORK/src/arm64.cross \
  --prefix=$PREFIX --libdir=lib --buildtype=release -Db_ndebug=true \
  -Dplatforms=x11 \
  -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl \
  -Dgallium-drivers=zink \
  -Dglx=dri -Degl=enabled -Dgles1=disabled -Dgles2=enabled -Dglvnd=enabled \
  -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dvideo-codecs= -Dtools= -Dvulkan-layers=
ninja -C build
rm -rf \$WORK/stage && DESTDIR=\$WORK/stage ninja -C build install
aarch64-linux-gnu-strip --strip-unneeded \$(find \$WORK/stage -name '*.so*' -type f)
EOF
mkdir -p "$WORK"
rm -rf "$WORK/patches" && cp -r "$HERE/patches" "$WORK/patches"
if [ -n "$ON_HOST" ]; then
  bash "$SCRIPT" "$WORK"
else
  cp /etc/resolv.conf "$TRIXIE/etc/resolv.conf"
  mountpoint -q "$TRIXIE/proc" || mount -t proc proc "$TRIXIE/proc"
  trap 'umount "$TRIXIE/proc" 2>/dev/null || true' EXIT
  chroot "$TRIXIE" /bin/bash /build-mesa.sh /work
fi

# Headers and pkg-config files are for building against it; apps only need the libraries.
rm -rf "$WORK/stage$PREFIX/include" "$WORK/stage$PREFIX/lib/pkgconfig"
mkdir -p "$OUT"
tar --format=ustar -C "$WORK/stage" -czf "$OUT/mesa-turnip.tgz" "${PREFIX#/}"
# Bump the suffix when the patches change, so phones unpack the new build.
echo "$MESA_VERSION-t3" > "$OUT/VERSION"
ls -l "$OUT"
