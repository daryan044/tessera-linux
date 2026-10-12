#!/usr/bin/env bash
# Copyright (C) 2026 Daryan
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds PRoot and its loaders for Android with the NDK and installs them into
# app/src/main/jniLibs/<abi>/ as libproot.so, libproot-loader.so and libproot-loader32.so.
#
# Mirrors termux/proot's src/GNUmakefile (and Termux's package recipe) but drives clang
# directly, so it runs the same in Git Bash on Windows and on Linux/macOS CI.
#
#   scripts/proot/build.sh                 # all ABIs
#   ABIS=arm64-v8a scripts/proot/build.sh  # just one
#
# Env: ANDROID_NDK_HOME (or ANDROID_HOME + NDK_VERSION), ABIS, JOBS.
set -euo pipefail

PROOT_VERSION=5.1.107.96
PROOT_SHA256=28e0a1275420680ace26bcea811441ecdd7dfabdd62bb451d89dcba15b9934ef
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
API=24

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$ROOT/build/proot"
JNILIBS="$ROOT/app/src/main/jniLibs"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/${NDK_VERSION:-28.2.13676358}}"
[ -d "$NDK" ] || { echo "NDK not found at '$NDK'; set ANDROID_NDK_HOME" >&2; exit 1; }
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXE=.exe ;;
  Darwin) HOST=darwin-x86_64; EXE= ;;
  *) HOST=linux-x86_64; EXE= ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
CLANG="$BIN/clang$EXE"
AR="$BIN/llvm-ar$EXE"
NM="$BIN/llvm-nm$EXE"
STRIP="$BIN/llvm-strip$EXE"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

fetch() { # url sha256 dest-dir
  local url=$1 sum=$2 dest=$3 file="$WORK/dl/$(basename "$1")"
  mkdir -p "$WORK/dl"
  [ -f "$file" ] || curl -fsSL "$url" -o "$file"
  [ "$(sha256 "$file")" = "$sum" ] || { echo "checksum mismatch for $file" >&2; rm -f "$file"; exit 1; }
  if [ ! -d "$dest" ]; then
    mkdir -p "$dest"
    tar -xzf "$file" -C "$dest" --strip-components=1
  fi
}

fetch "https://github.com/termux/proot/archive/refs/tags/v$PROOT_VERSION.tar.gz" "$PROOT_SHA256" "$WORK/src/proot"
fetch "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" "$TALLOC_SHA256" "$WORK/src/talloc"
P="$WORK/src/proot/src"
T="$WORK/src/talloc"

# Object list straight from the makefile, so upstream additions are picked up automatically.
OBJECTS=$(sed -n '/^OBJECTS += /,/^$/p' "$P/GNUmakefile" | grep -oE '[a-z0-9_/.-]+\.o')

# Value of a macro from arch.h for a given target, like the makefile's define_from_arch.h.
arch_macro() { # target name
  "$CLANG" --target="$1" -E -dM -DNO_LIBC_HEADER "$P/arch.h" | awk -v n="$2" '$2 == n { print $3 }'
}

compile() { # target cflags... -- src obj
  local target=$1; shift
  local flags=()
  while [ "$1" != -- ]; do flags+=("$1"); shift; done
  shift
  mkdir -p "$(dirname "$2")"
  "$CLANG" --target="$target" "${flags[@]}" -c "$1" -o "$2"
}

build_loader() { # target out-file
  local target=$1 out=$2 dir
  dir="$(dirname "$out")/$(basename "$out")-obj"
  local addr cflags
  addr=$(arch_macro "$target" LOADER_ADDRESS)
  cflags=(-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I"$P" -O2 -fPIC -ffreestanding $(arch_macro "$target" LOADER_ARCH_CFLAGS))
  compile "$target" "${cflags[@]}" -- "$P/loader/loader.c" "$dir/loader.o"
  compile "$target" "${cflags[@]}" -- "$P/loader/assembly.S" "$dir/assembly.o"
  "$CLANG" --target="$target" -static -nostdlib \
    -Wl,--build-id=none,-Ttext="$addr",--rosegment,-z,noexecstack \
    -o "$out" "$dir/loader.o" "$dir/assembly.o"
}

build_abi() {
  local abi=$1 target target32=""
  case "$abi" in
    arm64-v8a)   target=aarch64-linux-android$API; target32=armv7a-linux-androideabi$API ;;
    armeabi-v7a) target=armv7a-linux-androideabi$API ;;
    x86_64)      target=x86_64-linux-android$API;  target32=i686-linux-android$API ;;
    x86)         target=i686-linux-android$API ;;
    *) echo "unsupported ABI $abi" >&2; exit 1 ;;
  esac
  local B="$WORK/out/$abi"
  rm -rf "$B"; mkdir -p "$B/include"
  echo "== $abi ($target)"

  # talloc: one translation unit plus our config.h instead of waf.
  cp "$HERE/talloc-config.h" "$B/include/config.h"
  compile "$target" -O2 -D_GNU_SOURCE -D__STDC_WANT_LIB_EXT1__=1 -I"$B/include" -I"$T/lib/replace" -I"$T" -w \
    -- "$T/talloc.c" "$B/talloc/talloc.o"
  compile "$target" -O2 -- "$HERE/talloc-compat.c" "$B/talloc/compat.o"
  "$AR" rcs "$B/libtalloc.a" "$B/talloc/talloc.o" "$B/talloc/compat.o"

  # build.h: version + the two feature probes the makefile runs.
  {
    echo "#ifndef BUILD_H"
    echo "#define BUILD_H"
    echo "#undef VERSION"
    echo "#define VERSION \"$PROOT_VERSION\""
    for f in process_vm seccomp_filter; do
      if "$CLANG" --target="$target" -D_GNU_SOURCE -I"$P" "$P/.check_$f.c" -o "$B/check_$f" >/dev/null 2>&1; then
        echo "#define HAVE_$(echo "$f" | tr a-z A-Z)"
      fi
    done
    echo "#endif"
  } > "$B/include/build.h"

  local cflags=(
    -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I"$B/include" -I"$P" -I"$T"
    -DARG_MAX=131072
    # A few upstream files use string.h functions without including it.
    -include string.h
    # The loaders ship next to libproot.so; ProotBackend passes them via PROOT_LOADER(_32).
    '-DPROOT_UNBUNDLE_LOADER="/proot-loader-not-set"'
    -O2 -Wall -Wno-unused-parameter -Wno-sign-compare
  )
  local objs=() o running=0
  for o in $OBJECTS; do
    objs+=("$B/obj/$o")
    compile "$target" "${cflags[@]}" -- "$P/${o%.o}.c" "$B/obj/$o" &
    running=$((running + 1))
    if [ "$running" -ge "$JOBS" ]; then wait -n || true; running=$((running - 1)); fi
  done
  wait || true
  # clang deletes its output on failure, so a missing object means a failed compile.
  for o in "${objs[@]}"; do [ -f "$o" ] || { echo "failed to compile ${o#"$B/obj/"}" >&2; exit 1; }; done

  build_loader "$target" "$B/loader"
  [ -n "$target32" ] && build_loader "$target32" "$B/loader32"

  if [ "$(arch_macro "$target" HAS_POKEDATA_WORKAROUND)" = true ]; then
    # Offset of pokedata_workaround from _start in the (unstripped) loader; replaces loader-info.awk.
    local pw st
    pw=$("$NM" "$B/loader" | awk '$3 == "pokedata_workaround" { print $1 }')
    st=$("$NM" "$B/loader" | awk '$3 == "_start" { print $1 }')
    printf '#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=%d;\n' \
      $(( 16#$pw - 16#$st )) > "$B/loader-info.c"
    compile "$target" "${cflags[@]}" -- "$B/loader-info.c" "$B/obj/loader/loader-info.o"
    objs+=("$B/obj/loader/loader-info.o")
  fi

  "$CLANG" --target="$target" -static -o "$B/proot" "${objs[@]}" "$B/libtalloc.a" -Wl,-z,noexecstack

  mkdir -p "$JNILIBS/$abi"
  "$STRIP" -o "$JNILIBS/$abi/libproot.so" "$B/proot"
  "$STRIP" -o "$JNILIBS/$abi/libproot-loader.so" "$B/loader"
  if [ -n "$target32" ]; then
    "$STRIP" -o "$JNILIBS/$abi/libproot-loader32.so" "$B/loader32"
  fi
  ls -l "$JNILIBS/$abi"
}

for abi in $ABIS; do build_abi "$abi"; done
