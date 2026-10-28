# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
# Runs a Flathub app, as root inside a fresh mount namespace (and network namespace when network
# is denied), started by ChrootBackend.buildFlatpakLaunch via:
#   su -c 'export ...; exec unshare -m [-n] sh flatpak.sh'
# It builds the app's own root in a tmpfs, the way `flatpak run` would without bubblewrap: the
# runtime at /usr, the app at /app, the runtime's GL extension, the session's X11 directory (its
# bus and sound live there too), the user's home, then runs the app there as APP_UID.
#
# Inputs (env): R, FP_RUNTIME, FP_APP, FP_GL, FP_GL_MOUNT, BINDS, GPU_DEVICE, X11_DIR, APP_UID, DNS,
#   NET_GROUPS, GUEST_ENV, CMD (paths are the container's, without $R)
# Mounts made here vanish with the namespace; never mount in the global namespace.
set -e

for bb in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
  if [ -x "$bb" ]; then break; fi
done
[ -x "$bb" ] || { echo "busybox from Magisk/KernelSU/APatch not found" >&2; exit 1; }
# Every tool from busybox, never the phone's: some phones' root shell reaches none of toybox's
# (no unshare, tr or touch on a Galaxy Z Fold5 with KernelSU, #5).
for c in mount touch mkdir ln rm sed grep head cat chmod chown stat dirname find ls sort tr chroot; do
  eval "$c() { \"\$bb\" $c \"\$@\"; }"
done
# Shares host path $1 at $2 with what's mounted inside it (rbind: the phone's / alone is
# Android's read-only root, its /data and /storage empty), read-only when $3 is ro.
share() {
  mount -o rbind "$1" "$2"
  [ "$3" = ro ] || return 0
  # Read-only all the way down in one go: ro=recursive (mount_setattr, Linux 5.12+), from the
  # container's util-linux. Not one remount per mount: busybox crashes on stacked mounts, and
  # toybox's reaches the filesystem itself, turning Android's own copy read-only too.
  # Without it, the folder alone, read-only, as before.
  if ! chroot "$R" /usr/bin/mount -o remount,bind,ro=recursive "${2#"$R"}" 2>/dev/null; then
    "$bb" umount -l "$2"; mount --bind "$1" "$2"; mount -o remount,bind,ro "$2"
  fi
}
mount --make-rprivate /

[ -d "$R$FP_RUNTIME" ] || { echo "runtime not found: $FP_RUNTIME" >&2; exit 1; }
[ -d "$R$FP_APP" ] || { echo "app not found: $FP_APP" >&2; exit 1; }

# The new root, in a tmpfs over the container's /run (only in this namespace).
mount -t tmpfs -o mode=755 tmpfs "$R/run"
F="$R/run/flatpak-root"
mkdir -p "$F"
mount -t tmpfs -o mode=755 tmpfs "$F"
cd "$F"
mkdir -p usr app proc sys dev tmp home/user opt run/host etc var/tmp media mnt
for l in bin:usr/bin lib:usr/lib sbin:usr/sbin lib64:usr/lib; do ln -s "${l#*:}" "${l%%:*}"; done

mount --bind "$R$FP_RUNTIME" usr
mount -o remount,bind,ro usr
mount --bind "$R$FP_APP" app
mount -o remount,bind,ro app
# zypak (Flathub's Electron apps: VS Code, Discord, Obsidian) sandboxes Electron through
# Flatpak's own services, which aren't here: it aborts ("Assertion failed: bus"). A stand-in runs
# the app straight; Electron's own sandbox needs user namespaces, so it's off too.
ZYPAK=
for z in zypak-wrapper.sh zypak-wrapper; do
  [ -e "app/bin/$z" ] || continue
  [ -n "$ZYPAK" ] || { ZYPAK=run/host/tessera-zypak; printf '#!/bin/sh\nexec "$@"\n' > "$ZYPAK"; chmod 755 "$ZYPAK"; }
  mount --bind "$ZYPAK" "app/bin/$z"
done
if [ -n "$FP_GL" ] && [ -d "$R$FP_GL" ]; then
  # The extension's folder doesn't exist in the read-only runtime; a tmpfs over its parent
  # makes room for it.
  mount -t tmpfs -o mode=755 tmpfs "$F$(dirname "$FP_GL_MOUNT")"
  mkdir -p "$F$FP_GL_MOUNT"
  mount --bind "$R$FP_GL" "$F$FP_GL_MOUNT"
fi

# /etc: the runtime's, with the user, name resolution and hosts from the container.
for e in usr/etc/*; do ln -s "/$e" "etc/${e#usr/etc/}"; done
rm -f etc/passwd etc/group etc/hosts etc/resolv.conf
U=${APP_UID:?APP_UID not set}
printf 'root:x:0:0:root:/root:/bin/sh\nuser:x:%s:%s:Tessera user:/home/user:/bin/sh\n' "$U" "$U" > etc/passwd
printf 'root:x:0:\nuser:x:%s:\n' "$U" > etc/group
printf '127.0.0.1 localhost\n::1 localhost\n' > etc/hosts
# The container's DNS servers: Android's while its resolv.conf is automatic (as in enter.sh),
# or the user's own.
resolv="$R/etc/resolv.conf"
if [ -n "$DNS" ]; then
  first=$(head -n 1 "$resolv" 2>/dev/null || true)
  if [ -L "$resolv" ] || [ ! -e "$resolv" ] || [ "${first#\# Kept to Android}" != "$first" ] \
    || [ "$(cat "$resolv")" = "$(printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8')" ]; then
    rm -f "$resolv"; printf '%s' "$DNS" > "$resolv"; chmod 644 "$resolv"
  fi
fi
if [ -f "$resolv" ] && [ ! -L "$resolv" ]; then
  cat "$R/etc/resolv.conf" > etc/resolv.conf
else
  printf '%s' "${DNS:-nameserver 1.1.1.1
}" > etc/resolv.conf
fi

mount -t proc proc proc
# LLVM (llvmpipe's shader JIT) picks instructions by core model, not by what the kernel enables:
# Cortex-X3 and A715 have SVE, Android kernels leave it off, and OpenGL apps drawing in software
# died of SIGILL. Every core is named a Cortex-A78 (as FakeProc does for PRoot) unless SVE is on.
if ! grep '^Features' /proc/cpuinfo | grep -qw sve; then
  sed 's/^\(CPU part[[:space:]]*:[[:space:]]*\).*/\10xd41/' /proc/cpuinfo > "$R/run/cpuinfo"
  mount --bind "$R/run/cpuinfo" proc/cpuinfo
  # Shaders compiled before that are in Mesa's disk cache, which doesn't key them by core.
  for c in "$R/root/.cache" "$R/home/user/.cache"; do
    if [ -d "$c" ] && [ ! -e "$c/.tessera-no-sve" ]; then
      rm -rf "$c/mesa_shader_cache" "$c/mesa_shader_cache_db"; touch "$c/.tessera-no-sve"
    fi
  done
fi
mount -t sysfs -o ro sys sys

# Minimal /dev, as enter.sh makes it.
mount -t tmpfs -o mode=755,nosuid tmpfs dev
for n in null zero full random urandom tty; do
  touch "dev/$n"; mount --bind "/dev/$n" "dev/$n"
done
mkdir -p dev/pts dev/shm
mount -t devpts -o newinstance,ptmxmode=0666,mode=620 devpts dev/pts
ln -sf pts/ptmx dev/ptmx
mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs dev/shm
ln -sf /proc/self/fd dev/fd
if [ -n "$GPU_DEVICE" ] && [ -e "$GPU_DEVICE" ]; then
  touch "dev/${GPU_DEVICE#/dev/}"; mount --bind "$GPU_DEVICE" "dev/${GPU_DEVICE#/dev/}"
fi

mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs tmp
if [ -n "$X11_DIR" ]; then
  mkdir -p tmp/.X11-unix; mount --bind "$X11_DIR" tmp/.X11-unix
fi

# The same home (and app data, ~/.var/app/<id>) as in the container.
mkdir -p "$R/home/user"
mount --bind "$R/home/user" home/user
[ -d "$R/usr/share/fonts" ] && { mkdir -p run/host/fonts; mount --bind "$R/usr/share/fonts" run/host/fonts; }
printf '<?xml version="1.0"?>\n<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">\n<fontconfig>\n  <dir>/run/host/fonts</dir>\n</fontconfig>\n' > run/host/font-dirs.xml
for o in tessera-gpu tessera-theme; do
  [ -d "$R/opt/$o" ] && { mkdir -p "opt/$o"; mount --bind "$R/opt/$o" "opt/$o"; }
done

printf '%s\n' "$BINDS" | while IFS=: read -r src dst mode; do
  [ -n "$src" ] && [ -e "$src" ] || continue
  if [ -d "$src" ]; then mkdir -p "$F$dst"; else mkdir -p "$F$(dirname "$dst")"; touch "$F$dst"; fi
  share "$src" "$F$dst" "$mode"
done

set --
while IFS= read -r kv; do [ -n "$kv" ] && set -- "$@" "$kv"; done <<ENVEOF
$GUEST_ENV
ENVEOF
[ -n "$ZYPAK" ] && set -- "$@" ELECTRON_DISABLE_SANDBOX=1

# The runtime has no setpriv; the container's chroot (run with the container's own loader,
# since this is still Android's side) changes root and drops to the app's uid in one go.
LD="$R/usr/lib/ld-linux-aarch64.so.1"
[ -x "$LD" ] || LD="$R/lib/ld-linux-aarch64.so.1"
GROUPS_ARG=
[ -n "$NET_GROUPS" ] && GROUPS_ARG="--groups=$NET_GROUPS"
cd /
# The app lasts while any process in its root runs, not just the first: VS Code's `code` starts
# the editor apart and exits at once. Its root tells them apart (this tmpfs is the app's alone),
# not their environment: Chromium writes its process titles over it.
exec "$LD" --library-path "$R/usr/lib/aarch64-linux-gnu" "$R/usr/sbin/chroot" \
  --userspec="$U:$U" $GROUPS_ARG "$F" \
  /usr/bin/env -i "$@" /bin/sh -c "cd \"\$HOME\" 2>/dev/null; $CMD; s=\$?
    while :; do
      left=0
      for p in /proc/[0-9]*; do
        [ \"\${p#/proc/}\" = \$\$ ] && continue
        [ \$p/root/. -ef / ] 2>/dev/null && { left=1; break; }
      done
      [ \$left = 0 ] && exit \$s
      sleep 1
    done"
