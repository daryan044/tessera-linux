# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
# Runs as root inside a fresh mount namespace (and network namespace when network is denied),
# started by ChrootBackend via:  su -c 'export ...; exec unshare -m [-n] sh enter.sh'
#
# Inputs (env): R, BINDS, DEVICES, X11_DIR, PULSE, RUN_AS_ROOT, NET_GROUPS, GUEST_ENV, CMD
# Derived from termux-linux-layer/linux. Mounts made here vanish with the namespace; never
# mount or unmount in the global namespace, Android's mounts are shared and changes propagate.
set -e

for bb in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
  [ -x "$bb" ] && break
done
[ -x "$bb" ] || { echo "busybox from Magisk/KernelSU/APatch not found" >&2; exit 1; }
mount() { "$bb" mount "$@"; }
mount --make-rprivate /

[ -d "$R/usr" ] || { echo "rootfs not found at $R" >&2; exit 1; }

mount -t proc proc "$R/proc"
mount -t sysfs -o ro sys "$R/sys"

# Minimal /dev: only the basics plus what the manifest grants. No rbind of the host /dev.
mount -t tmpfs -o mode=755,nosuid tmpfs "$R/dev"
for n in null zero full random urandom tty; do
  touch "$R/dev/$n"; mount --bind "/dev/$n" "$R/dev/$n"
done
mkdir -p "$R/dev/pts" "$R/dev/shm"
mount -t devpts -o newinstance,ptmxmode=0666,mode=620 devpts "$R/dev/pts"
ln -sf pts/ptmx "$R/dev/ptmx"
mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$R/dev/shm"
ln -sf /proc/self/fd "$R/dev/fd"

mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$R/tmp"
mount -t tmpfs -o mode=755,nosuid,nodev tmpfs "$R/run"

printf '%s\n' "$DEVICES" | while IFS= read -r d; do
  [ -n "$d" ] && [ -e "$d" ] || continue
  mkdir -p "$R$(dirname "$d")"; touch "$R$d"; mount --bind "$d" "$R$d"
done

printf '%s\n' "$BINDS" | while IFS=: read -r src dst mode; do
  [ -n "$src" ] && [ -e "$src" ] || continue
  if [ -d "$src" ]; then mkdir -p "$R$dst"; else mkdir -p "$R$(dirname "$dst")"; touch "$R$dst"; fi
  mount --bind "$src" "$R$dst"
  [ "$mode" = ro ] && mount -o remount,bind,ro "$R$dst"
done

if [ -n "$X11_DIR" ]; then
  mkdir -p "$R/tmp/.X11-unix"; mount --bind "$X11_DIR" "$R/tmp/.X11-unix"
fi
if [ -n "$PULSE" ] && [ -e "$PULSE" ]; then
  mkdir -p "$R/run/pulse"; touch "$R/run/pulse/native"; mount --bind "$PULSE" "$R/run/pulse/native"
fi

printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$R/run/resolv.conf"
[ -e "$R/etc/resolv.conf" ] || ln -s /run/resolv.conf "$R/etc/resolv.conf"

# GUEST_ENV is one KEY=VALUE per line; turn it into positional args for env -i.
set --
while IFS= read -r kv; do [ -n "$kv" ] && set -- "$@" "$kv"; done <<ENVEOF
$GUEST_ENV
ENVEOF

# su hands us the terminal without making it our controlling tty, so bash would run without
# job control (no Ctrl+C/Ctrl+Z). setsid -c starts a session that owns the terminal.
SETSID=
[ -x "$R/usr/bin/setsid" ] && SETSID="/usr/bin/setsid -c"
# Start in $HOME; expanded by the guest shell, not this one.
START='cd "$HOME" 2>/dev/null; exec '

if [ "$RUN_AS_ROOT" = 1 ]; then
  exec chroot "$R" $SETSID /usr/bin/env -i "$@" /bin/sh -c "$START$CMD"
fi
mkdir -p "$R/home/user"; chown 1000:1000 "$R/home/user"
# Supplementary groups: Android's network groups when network is granted, none otherwise.
if [ -n "$NET_GROUPS" ]; then GROUP_ARG="--groups=$NET_GROUPS"; else GROUP_ARG=--clear-groups; fi
# setpriv is in util-linux (essential on Debian). --no-new-privs keeps setuid binaries inert.
exec chroot "$R" $SETSID /usr/bin/setpriv --reuid=1000 --regid=1000 \
  "$GROUP_ARG" --no-new-privs --inh-caps=-all \
  /usr/bin/env -i "$@" /bin/sh -c "$START$CMD"
