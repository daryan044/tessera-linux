# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
# Runs as root INSIDE a Droidspaces container, started by DroidspacesBackend via:
#   su -c 'exec droidspaces --name=NAME run -- /bin/sh /mnt/tessera/bin/enter.sh SESSION'
#
# The app writes the session's settings to /mnt/tessera/sessions/SESSION/launch.env just before:
#   BINDS, DEVICES, USB_ALL, GPU_DEVICE, X11, RUN_AS_ROOT, APP_UID, NO_NET, NET_GROUPS, GUEST_ENV, CMD
# /mnt/tessera/sessions and /mnt/tessera/bin are Tessera's folders, bound into the container at
# its boot. Everything mounted here lives in a mount namespace this script makes for itself and
# vanishes with it; the container's own mounts are never touched.
set -e

SID=${1:?session id missing}
SHARE=/mnt/tessera
[ -f "$SHARE/sessions/$SID/launch.env" ] || { echo "Tessera's folders aren't in this container; prepare it in Tessera's Setup" >&2; exit 1; }
. "$SHARE/sessions/$SID/launch.env"

# A mount namespace of its own, and a network namespace with nothing in it when the app has no
# network permission; then this script again, inside it.
if [ -z "$TESSERA_NS" ]; then
  NS=-m
  if [ "$NO_NET" = 1 ]; then NS="$NS -n"; fi
  command -v unshare >/dev/null 2>&1 || { echo "unshare (util-linux) isn't in this container" >&2; exit 127; }
  export TESSERA_NS=1
  exec unshare $NS /bin/sh "$0" "$SID"
fi

# The container's mounts may be shared with the rest of it: keep ours to this namespace.
mount --make-rprivate / 2>/dev/null || true

# Shares $1 at $2 (rbind), read-only when $3 is ro. ro=recursive needs a recent kernel and
# util-linux; without it the folder alone is read-only.
share() {
  mount -o rbind "$1" "$2" || return 1
  if [ "$3" = ro ]; then
    mount -o remount,bind,ro=recursive "$2" 2>/dev/null || mount -o remount,bind,ro "$2"
  fi
}

# Every app has its own /tmp and /dev/shm, so apps can't see each other's sockets.
mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs /tmp
mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs /dev/shm 2>/dev/null || true

# LLVM (llvmpipe's shader JIT) picks instructions by core model, not by what the kernel enables:
# Cortex-X3 and A715 have SVE, Android kernels leave it off, and OpenGL apps drawing in software
# died of SIGILL. Every core is named a Cortex-A78 unless SVE is on.
if ! grep '^Features' /proc/cpuinfo | grep -qw sve; then
  if sed 's/^\(CPU part[[:space:]]*:[[:space:]]*\).*/\10xd41/' /proc/cpuinfo > /dev/shm/.cpuinfo 2>/dev/null; then
    mount --bind /dev/shm/.cpuinfo /proc/cpuinfo 2>/dev/null || true
  fi
  # Shaders compiled before that are in Mesa's disk cache, which doesn't key them by core.
  for c in /root/.cache /home/user/.cache; do
    if [ -d "$c" ] && [ ! -e "$c/.tessera-no-sve" ]; then
      rm -rf "$c/mesa_shader_cache" "$c/mesa_shader_cache_db"; touch "$c/.tessera-no-sve"
    fi
  done
fi

# The folders the app was granted, as "src:dst:ro|rw" (src as this container sees it). One that
# can't be shared is reported and skipped: the app still starts, without it.
printf '%s\n' "$BINDS" | while IFS=: read -r src dst mode; do
  [ -n "$src" ] && [ -e "$src" ] || continue
  if [ -d "$src" ]; then mkdir -p "$dst"; else mkdir -p "$(dirname "$dst")"; touch "$dst"; fi
  share "$src" "$dst" "$mode" || echo "tessera: couldn't share $src at $dst" >&2
done

# The session's X server socket and the files the launcher and the app exchange.
if [ -n "$X11" ]; then
  mkdir -p /tmp/.X11-unix
  mount --bind "$SHARE/sessions/$SID/tmp/.X11-unix" /tmp/.X11-unix
fi

# GUEST_ENV is one KEY=VALUE per line; turn it into positional args for env -i.
set --
while IFS= read -r kv; do [ -n "$kv" ] && set -- "$@" "$kv"; done <<ENVEOF
$GUEST_ENV
ENVEOF

ENVBIN=$(command -v env || echo /usr/bin/env)

# A session that owns the terminal, so bash has job control (Ctrl+C, Ctrl+Z). --wait: when this
# process already leads a group setsid forks, and without it the parent would return at once and
# the launcher would take the app for finished. Without a terminal (the Files tab's shell talks
# over pipes) there's nothing to own, and --ctty would fail.
SETSID=
CTTY=
[ -t 0 ] && CTTY=--ctty
SETSIDBIN=$(command -v setsid || true)
[ -n "$SETSIDBIN" ] && SETSID="$SETSIDBIN $CTTY --wait"
# Start in $HOME; expanded by the guest shell, not this one.
START='cd "$HOME" 2>/dev/null; exec '

if [ "$RUN_AS_ROOT" = 1 ]; then
  exec $SETSID "$ENVBIN" -i "$@" /bin/sh -c "$START$CMD"
fi

# Apps run as the launcher app's own uid (APP_UID), not Debian's usual 1000: on Android, 1000 is
# "system", which system services trust. The app's uid carries no rights beyond the app's, and
# is what the other engines run apps as too.
U=${APP_UID:?APP_UID not set}
SETPRIV=$(command -v setpriv || true)
[ -n "$SETPRIV" ] || { echo "setpriv (util-linux) isn't in this container" >&2; exit 127; }
mkdir -p /home/user
[ "$(stat -c %u /home/user)" = "$U" ] || chown -R "$U:$U" /home/user
# Give the uid a name. Without one, D-Bus won't start ("Could not get password database
# information for UID of current process") and other programs complain.
if grep -q '^user:' /etc/passwd; then
  sed -i "s#^user:[^:]*:[0-9]*:[0-9]*:#user:x:$U:$U:#" /etc/passwd
else
  echo "user:x:$U:$U:Tessera user:/home/user:/bin/bash" >> /etc/passwd
fi
if grep -q '^user:' /etc/group; then
  sed -i "s#^user:[^:]*:[0-9]*:#user:x:$U:#" /etc/group
else
  echo "user:x:$U:" >> /etc/group
fi
[ ! -f /etc/shadow ] || grep -q '^user:' /etc/shadow || echo 'user:!:19000::::::' >> /etc/shadow

# Supplementary groups: Android's network groups when network is granted, and the group of each
# granted device, so the app can open just those (not "system", 1000, which system services trust).
GROUPS_LIST=$NET_GROUPS
DEV_GROUPS=$({
  printf '%s\n' "$DEVICES"
  if [ "$USB_ALL" = 1 ]; then find /dev/bus/usb -type c 2>/dev/null; ls /dev/ttyACM* 2>/dev/null; fi
} | while IFS= read -r d; do
  [ -n "$d" ] && [ -e "$d" ] && stat -c %g "$d"
done | grep -vx 1000 | sort -u | tr '\n' ',' | sed 's/,$//')
[ -n "$DEV_GROUPS" ] && GROUPS_LIST=${GROUPS_LIST:+$GROUPS_LIST,}$DEV_GROUPS
if [ -n "$GROUPS_LIST" ]; then GROUP_ARG="--groups=$GROUPS_LIST"; else GROUP_ARG=--clear-groups; fi
# --no-new-privs keeps setuid binaries inert.
exec $SETSID "$SETPRIV" --reuid="$U" --regid="$U" "$GROUP_ARG" --no-new-privs --inh-caps=-all \
  "$ENVBIN" -i "$@" /bin/sh -c "$START$CMD"
