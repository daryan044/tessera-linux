# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
# Runs a Flathub app, as root inside a fresh mount namespace (and network namespace when network
# is denied), started by ChrootBackend.buildFlatpakLaunch via:
#   su -c 'export ...; exec unshare -m [-n] sh flatpak.sh'
# It builds the app's own root in a tmpfs, the way `flatpak run` would without bubblewrap: the
# runtime at /usr, the app at /app, the runtime's GL extension, the session's X11 directory (its
# bus and sound live there too), the user's home, then runs the app there as APP_UID.
#
# Inputs (env): R, FP_RUNTIME, FP_APP, FP_GL, FP_GL_MOUNT, BINDS, GPU_DEVICE, X11_DIR, APP_UID,
#   NET_GROUPS, GUEST_ENV, CMD (paths are the container's, without $R)
# Mounts made here vanish with the namespace; never mount in the global namespace.
set -e

for bb in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
  if [ -x "$bb" ]; then break; fi
done
[ -x "$bb" ] || { echo "busybox from Magisk/KernelSU/APatch not found" >&2; exit 1; }
mount() { "$bb" mount "$@"; }
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
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > etc/resolv.conf

mount -t proc proc proc
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
  mount --bind "$src" "$F$dst"
  if [ "$mode" = ro ]; then mount -o remount,bind,ro "$F$dst"; fi
done

set --
while IFS= read -r kv; do [ -n "$kv" ] && set -- "$@" "$kv"; done <<ENVEOF
$GUEST_ENV
ENVEOF

# The runtime has no setpriv; the container's chroot (run with the container's own loader,
# since this is still Android's side) changes root and drops to the app's uid in one go.
LD="$R/usr/lib/ld-linux-aarch64.so.1"
[ -x "$LD" ] || LD="$R/lib/ld-linux-aarch64.so.1"
GROUPS_ARG=
[ -n "$NET_GROUPS" ] && GROUPS_ARG="--groups=$NET_GROUPS"
cd /
exec "$LD" --library-path "$R/usr/lib/aarch64-linux-gnu" "$R/usr/sbin/chroot" \
  --userspec="$U:$U" $GROUPS_ARG "$F" \
  /usr/bin/env -i "$@" /bin/sh -c "cd \"\$HOME\" 2>/dev/null; exec $CMD"
