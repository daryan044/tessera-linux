# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs as root inside enter.sh's per-app mount namespace. Build a Flatpak-compatible root in this
# app's private /tmp: runtime at /usr, app at /app, plus just the session and permissions already
# granted to the app. This mirrors chroot/flatpak.sh, but all sources are paths in the Droidspaces
# container (not the Android host's chroot rootfs).
set -e

for c in mount umount mkdir ln rm sed grep cat chmod dirname chroot setpriv; do
  command -v "$c" >/dev/null 2>&1 || { echo "Tessera Flatpak launcher: $c isn't installed in this container" >&2; exit 127; }
done

# These paths are generated from Flatpak's deployment metadata. Keep the custom root builder
# confined to Flatpak deployments; malformed metadata must never turn a mount target into /etc,
# /proc, or another path outside this app's root.
case "$FP_RUNTIME" in /var/lib/flatpak/runtime/*/active/files) ;; *) echo "Invalid Flatpak runtime path" >&2; exit 1 ;; esac
case "$FP_APP" in /var/lib/flatpak/app/*/active/files) ;; *) echo "Invalid Flatpak app path" >&2; exit 1 ;; esac
case "/$FP_RUNTIME/" in *"/../"*|*"/./"*) echo "Invalid Flatpak runtime path" >&2; exit 1 ;; esac
case "/$FP_APP/" in *"/../"*|*"/./"*) echo "Invalid Flatpak app path" >&2; exit 1 ;; esac
if [ -n "$FP_GL" ] || [ -n "$FP_GL_MOUNT" ]; then
  case "$FP_GL" in /var/lib/flatpak/runtime/*/active/files) ;; *) echo "Invalid Flatpak GL path" >&2; exit 1 ;; esac
  case "/$FP_GL/" in *"/../"*|*"/./"*) echo "Invalid Flatpak GL path" >&2; exit 1 ;; esac
  case "$FP_GL_MOUNT" in /usr/*) ;; *) echo "Invalid Flatpak GL mount path" >&2; exit 1 ;; esac
  case "/$FP_GL_MOUNT/" in *"/../"*|*"/./"*) echo "Invalid Flatpak GL mount path" >&2; exit 1 ;; esac
fi
[ -d "$FP_RUNTIME" ] || { echo "Flatpak runtime not found: $FP_RUNTIME" >&2; exit 1; }
[ -d "$FP_APP" ] || { echo "Flatpak app not found: $FP_APP" >&2; exit 1; }

# Shares $1 at $2 (recursively), read-only when $3 is ro. Do not leave a writable mount behind
# if this kernel can't enforce the requested read-only grant.
share() {
  mount -o rbind "$1" "$2" || return 1
  [ "$3" = ro ] || return 0
  if ! mount -o remount,bind,ro=recursive "$2" 2>/dev/null; then
    if ! mount -o remount,bind,ro "$2"; then
      if ! umount -R "$2" 2>/dev/null && ! umount "$2" 2>/dev/null; then
        echo "tessera: fatal: couldn't revoke a writable bind at $2; aborting Flatpak launch" >&2
        exit 1
      fi
      echo "tessera: couldn't enforce read-only access for $2; grant not mounted" >&2
      return 1
    fi
    echo "tessera: recursive read-only unavailable for $2; nested mounts may remain writable" >&2
  fi
}

F=/tmp/.tessera-flatpak-root
mkdir -p "$F"
mount -t tmpfs -o mode=755,nosuid,nodev tmpfs "$F"
mkdir -p "$F/usr" "$F/app" "$F/proc" "$F/sys" "$F/dev" "$F/tmp" \
  "$F/home/user" "$F/opt" "$F/run/host" "$F/etc" "$F/var/tmp" "$F/media" "$F/mnt"
for l in bin:usr/bin lib:usr/lib sbin:usr/sbin lib64:usr/lib; do
  ln -s "${l#*:}" "$F/${l%%:*}"
done

share "$FP_RUNTIME" "$F/usr" ro || { echo "Couldn't mount Flatpak runtime read-only" >&2; exit 1; }
share "$FP_APP" "$F/app" ro || { echo "Couldn't mount Flatpak app read-only" >&2; exit 1; }

# Zypak depends on Flatpak's own sandbox services, which this launcher replaces. Keep the same
# stand-in as the other backends and disable Electron's nested sandbox (not the app's permissions).
ZYPAK=
for z in zypak-wrapper.sh zypak-wrapper; do
  [ -e "$F/app/bin/$z" ] || continue
  if [ -z "$ZYPAK" ]; then
    ZYPAK=/run/host/tessera-zypak
    mkdir -p "$F/run/host"
    printf '#!/bin/sh\nexec "$@"\n' > "$F$ZYPAK"
    chmod 755 "$F$ZYPAK"
  fi
  mount --bind "$F$ZYPAK" "$F/app/bin/$z"
done

if [ -n "$FP_GL" ] && [ -n "$FP_GL_MOUNT" ] && [ -d "$FP_GL" ]; then
  # Runtime GL extensions share a well-known directory with the Mesa supplied by the runtime.
  mount -t tmpfs -o mode=755,nosuid,nodev tmpfs "$F$(dirname "$FP_GL_MOUNT")"
  mkdir -p "$F$FP_GL_MOUNT"
  share "$FP_GL" "$F$FP_GL_MOUNT" ro || { echo "Couldn't mount Flatpak GL extension read-only" >&2; exit 1; }
fi

# Runtime-provided /etc, with passwd/group names for the launcher uid and the container's network
# configuration. Nothing here edits the container's actual /etc.
for e in "$F"/usr/etc/*; do
  [ -e "$e" ] || [ -L "$e" ] || continue
  rel=${e#"$F"/usr/etc/}
  [ -e "$F/etc/$rel" ] || [ -L "$F/etc/$rel" ] || ln -s "/usr/etc/$rel" "$F/etc/$rel"
done
rm -f "$F/etc/passwd" "$F/etc/group" "$F/etc/hosts" "$F/etc/resolv.conf"
U=${APP_UID:?APP_UID not set}
printf 'root:x:0:0:root:/root:/bin/sh\nuser:x:%s:%s:Tessera user:/home/user:/bin/sh\n' "$U" "$U" > "$F/etc/passwd"
printf 'root:x:0:\nuser:x:%s:\n' "$U" > "$F/etc/group"
printf '127.0.0.1 localhost\n::1 localhost\n' > "$F/etc/hosts"
if [ -f /etc/resolv.conf ]; then
  cat /etc/resolv.conf > "$F/etc/resolv.conf"
else
  printf 'nameserver 1.1.1.1\n' > "$F/etc/resolv.conf"
fi

mount -t proc proc "$F/proc"
if ! grep '^Features' /proc/cpuinfo | grep -qw sve; then
  sed 's/^\(CPU part[[:space:]]*:[[:space:]]*\).*/\10xd41/' /proc/cpuinfo > /tmp/.tessera-flatpak-cpuinfo
  mount --bind /tmp/.tessera-flatpak-cpuinfo "$F/proc/cpuinfo" 2>/dev/null || true
fi
mount -t sysfs -o ro sys "$F/sys"

# Minimal /dev. Only ordinary character devices and the app's explicit device grants are exposed.
mount -t tmpfs -o mode=755,nosuid tmpfs "$F/dev"
for n in null zero full random urandom tty; do
  : > "$F/dev/$n"
  mount --bind "/dev/$n" "$F/dev/$n"
done
mkdir -p "$F/dev/pts" "$F/dev/shm"
mount -t devpts -o newinstance,ptmxmode=0666,mode=620 devpts "$F/dev/pts"
ln -sf pts/ptmx "$F/dev/ptmx"
mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$F/dev/shm"
ln -sf /proc/self/fd "$F/dev/fd"

bind_device() {
  d=$1
  case "$d" in /dev/*) ;; *) return 0 ;; esac
  [ -e "$d" ] || return 0
  case "$d" in
    /dev/null|/dev/zero|/dev/full|/dev/random|/dev/urandom|/dev/tty|/dev/pts|/dev/shm|/dev/ptmx|/dev/fd) return 0 ;;
  esac
  dst="$F$d"
  if [ -d "$d" ]; then mkdir -p "$dst"; else mkdir -p "$(dirname "$dst")"; [ -e "$dst" ] || : > "$dst"; fi
  mount --bind "$d" "$dst"
}
printf '%s\n' "$DEVICES" | while IFS= read -r d; do [ -n "$d" ] && bind_device "$d"; done
if [ -n "$GPU_DEVICE" ] && ! printf '%s\n' "$DEVICES" | grep -Fxq "$GPU_DEVICE"; then
  bind_device "$GPU_DEVICE"
fi
if [ "$USB_ALL" = 1 ]; then
  if [ -d /dev/bus/usb ]; then
    mkdir -p "$F/dev/bus/usb"
    mount --rbind /dev/bus/usb "$F/dev/bus/usb"
  fi
  for d in /dev/ttyACM*; do [ -e "$d" ] && bind_device "$d"; done
fi

mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$F/tmp"
if [ "$X11" = 1 ]; then
  mkdir -p "$F/tmp/.X11-unix"
  mount --bind /tmp/.X11-unix "$F/tmp/.X11-unix"
fi

# Flatpak stores per-app data under the same home as Debian apps.
mkdir -p /home/user
mount --bind /home/user "$F/home/user"
[ -d /usr/share/fonts ] && { mkdir -p "$F/run/host/fonts"; mount --bind /usr/share/fonts "$F/run/host/fonts"; }
printf '<?xml version="1.0"?>\n<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">\n<fontconfig>\n  <dir>/run/host/fonts</dir>\n</fontconfig>\n' > "$F/run/host/font-dirs.xml"
for o in tessera-gpu tessera-theme; do
  [ -d "/opt/$o" ] && { mkdir -p "$F/opt/$o"; mount --bind "/opt/$o" "$F/opt/$o"; }
done
if ! grep '^Features' /proc/cpuinfo | grep -qw sve; then
  for c in /home/user/.cache; do
    if [ -d "$c" ] && [ ! -e "$c/.tessera-no-sve" ]; then
      rm -rf "$c/mesa_shader_cache" "$c/mesa_shader_cache_db"
      : > "$c/.tessera-no-sve"
    fi
  done
fi

# The manifest's granted host paths are shared into this root as well as into the app's regular
# container namespace. Missing phone paths were already filtered by DroidspacesBackend.
printf '%s\n' "$BINDS" | while IFS=: read -r src dst mode; do
  [ -n "$src" ] && [ -e "$src" ] || continue
  # Destinations come from Tessera's filesystem-grant manifest. Never let a malformed destination
  # escape this app's root when it is prefixed with $F.
  case "$dst" in /*) ;; *) echo "Invalid filesystem grant destination: $dst" >&2; exit 1 ;; esac
  case "/$dst/" in *"/../"*|*"/./"*|*"//"*) echo "Invalid filesystem grant destination: $dst" >&2; exit 1 ;; esac
  [ "$dst" != / ] || { echo "Invalid filesystem grant destination: /" >&2; exit 1; }
  case "$mode" in ro|rw) ;; *) echo "Invalid filesystem grant mode: $mode" >&2; exit 1 ;; esac
  if [ -d "$src" ]; then mkdir -p "$F$dst"; else mkdir -p "$F$(dirname "$dst")"; [ -e "$F$dst" ] || : > "$F$dst"; fi
  share "$src" "$F$dst" "$mode" || echo "tessera: couldn't share $src at $dst into Flatpak root" >&2
done

set --
while IFS= read -r kv; do [ -n "$kv" ] && set -- "$@" "$kv"; done <<ENVEOF
$GUEST_ENV
ENVEOF
[ -n "$ZYPAK" ] && set -- "$@" ELECTRON_DISABLE_SANDBOX=1

U=${APP_UID:?APP_UID not set}
[ "$U" -gt 0 ] 2>/dev/null || { echo "Invalid Flatpak app uid" >&2; exit 1; }
cd /
# Apply no-new-privs before switching uid, and explicitly clear supplementary groups unless the
# manifest granted network/device groups. chroot otherwise inherits the root process's groups.
CHROOT=$(command -v chroot)
if [ -n "$GROUPS_LIST" ]; then
  exec setpriv --no-new-privs --inh-caps=-all \
    "$CHROOT" --userspec="$U:$U" --groups="$GROUPS_LIST" "$F" \
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
else
  exec setpriv --no-new-privs --inh-caps=-all --clear-groups \
    "$CHROOT" --userspec="$U:$U" "$F" \
    /usr/bin/env -i "$@" /bin/sh -c "cd \"\$HOME\" 2>/dev/null; $CMD; s=\$?
    while :; do
      left=0
      for p in /proc/[0-9]*; do
        [ \"\${p#/proc/}\" = \"\$\$\" ] && continue
        [ \$p/root/. -ef / ] 2>/dev/null && { left=1; break; }
      done
      [ \$left = 0 ] && exit \$s
      sleep 1
    done"
fi
