#!/usr/bin/env bash
# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the ARM Mali GPU driver bundle (mesa-panvk.tgz) for Tessera Linux:
# Open Mesa PanVK (Vulkan) for Android's proprietary mali_kbase (/dev/mali0)
# and Zink (OpenGL on Vulkan).
#
# Generates:
#   build/gpu/assets/gpu/mesa-panvk.tgz
#   build/gpu/assets/gpu/VERSION-panvk
set -euo pipefail

PANVK_VERSION=${PANVK_VERSION:-26.3.1}
PREFIX=/opt/tessera-gpu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../build/gpu/assets/gpu"
WORK="/tmp/panvk-work"
STAGE="$WORK/stage"

mkdir -p "$OUT" "$WORK"
rm -rf "$STAGE" && mkdir -p "$STAGE"

# Ensure host tools are available
if ! command -v patchelf >/dev/null 2>&1 || ! command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
  echo "Installing patchelf and gcc-aarch64-linux-gnu..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq patchelf gcc-aarch64-linux-gnu curl >/dev/null
fi

# Ensure base Zink / EGL / GLX files from mesa-turnip.tgz are present
if [ ! -f "$OUT/mesa-turnip.tgz" ]; then
  echo "Error: $OUT/mesa-turnip.tgz not found. Run scripts/gpu/build-mesa.sh first."
  exit 1
fi

echo "Extracting base Mesa libraries..."
tar -xzf "$OUT/mesa-turnip.tgz" -C "$STAGE"

# Remove Adreno/Turnip specific files
rm -f "$STAGE$PREFIX/lib/libvulkan_freedreno.so"
rm -f "$STAGE$PREFIX/share/vulkan/icd.d/freedreno_icd.aarch64.json"
rm -f "$STAGE$PREFIX/share/drirc.d/00-turnip-defaults.conf"

# Fetch verified PanVK glibc binary for Android mali_kbase
PANVK_URL="https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/panvk-kbase-v${PANVK_VERSION}/libvulkan_panfrost-glibc-aarch64.so"
echo "Fetching PanVK driver ($PANVK_URL)..."
curl -fsSL "$PANVK_URL" -o "$STAGE$PREFIX/lib/libvulkan_panfrost.so"

# Patch DT_RUNPATH to ensure dependencies inside /opt/tessera-gpu/lib are always resolved
patchelf --set-rpath '$ORIGIN:/opt/tessera-gpu/lib' "$STAGE$PREFIX/lib/libvulkan_panfrost.so"

# Compile libSPIRV-Tools stub with Wayland compatibility symbols for Debian 12 (Bookworm)
cat > "$WORK/spirv_stub.c" <<'EOF'
void wl_fixes_interface(void) {}
void wl_display_create_queue_with_name(void) {}
void wl_display_dispatch_queue_timeout(void) {}
void spvBinaryToText(void) {}
void spvContextCreate(void) {}
void spvDiagnosticDestroy(void) {}
void spvDiagnosticPrint(void) {}
void spvTextDestroy(void) {}
EOF

aarch64-linux-gnu-gcc -shared -fPIC -Wl,-soname,libSPIRV-Tools.so -o "$STAGE$PREFIX/lib/libSPIRV-Tools.so" "$WORK/spirv_stub.c"
aarch64-linux-gnu-strip --strip-unneeded "$STAGE$PREFIX/lib/libSPIRV-Tools.so"

# Write Vulkan ICD manifest for PanVK
mkdir -p "$STAGE$PREFIX/share/vulkan/icd.d"
cat > "$STAGE$PREFIX/share/vulkan/icd.d/panfrost_icd.aarch64.json" <<JSON
{
    "file_format_version": "1.0.0",
    "ICD": {
        "library_path": "$PREFIX/lib/libvulkan_panfrost.so",
        "api_version": "1.3.0",
        "library_arch": "64"
    }
}
JSON

# Package mesa-panvk.tgz and write VERSION-panvk
tar --format=ustar -C "$STAGE" -czf "$OUT/mesa-panvk.tgz" "${PREFIX#/}"
echo "$PANVK_VERSION" > "$OUT/VERSION-panvk"

rm -rf "$WORK"
echo "✓ Successfully built $OUT/mesa-panvk.tgz and $OUT/VERSION-panvk"
ls -lh "$OUT"
