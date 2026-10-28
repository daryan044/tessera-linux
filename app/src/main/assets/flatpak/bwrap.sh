#!/bin/sh
# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Tessera's stand-in for bubblewrap, which needs user namespaces Android kernels don't have.
# flatpak runs it (FLATPAK_BWRAP) for an app's apply_extra script, which unpacks what its maker
# ships separately (VS Code's .deb, 1Password's tarball), and for its triggers (menu, icon and
# MIME caches). It builds the root bubblewrap was asked for: with bind mounts in a mount
# namespace of its own in chroot mode, where installs run as root, or as a PRoot inside PRoot's
# own in PRoot mode, where Tessera puts its proot at /opt/tessera-proot. It isolates nothing:
# what it runs is what flatpak itself installed.

PROOT=/opt/tessera-proot/libproot.so

# Chroot mode, second half: in a mount namespace of its own (so the mounts go when it ends), the
# root the first half wrote down in $2, then the command in it.
if [ "$1" = --tessera-mount ]; then
  W=$2
  NR=$W/root
  mount -t tmpfs tmpfs "$NR"
  while IFS='	' read -r kind src dest ro; do
    t=$NR$dest
    case $kind in
      bind)
        if [ -d "$src" ]; then mkdir -p "$t" 2>/dev/null
        else mkdir -p "${t%/*}" 2>/dev/null; [ -e "$t" ] || : > "$t" 2>/dev/null
        fi
        case $ro in
          *try) mount --rbind "$src" "$t" 2>/dev/null || continue ;;
          *) mount --rbind "$src" "$t" || continue ;;
        esac
        case $ro in ro*) mount -o remount,bind,ro "$t" 2>/dev/null ;; esac
        ;;
      dir) mkdir -p "$t" 2>/dev/null ;;
      tmpfs) mkdir -p "$t" 2>/dev/null && mount -t tmpfs tmpfs "$t" ;;
      link) mkdir -p "${t%/*}" 2>/dev/null; ln -sfn "$src" "$t" 2>/dev/null ;;
    esac
  done < "$W/ops"
  set --
  while IFS= read -r l; do set -- "$@" "$l"; done < "$W/run"
  exec chroot "$NR" "$@"
fi

W=$(mktemp -d /tmp/.bwrap.XXXXXX) || exit 1
NR=$W/root
mkdir -p "$NR" "$W/data"
: > "$W/args"; : > "$W/ops"; : > "$W/env"; : > "$W/cmd"

# Every argument, one per line, with those passed through --args FD (NUL-separated) in place.
prev=
for a in "$@"; do
  if [ "$prev" = --args ]; then tr '\0' '\n' < "/proc/$$/fd/$a" >> "$W/args"
  elif [ "$a" != --args ]; then printf '%s\n' "$a" >> "$W/args"
  fi
  prev=$a
done

# An operation on the new root, in order: kind, source, destination, read-only. Never an empty
# source ("-" instead): read joins runs of tabs, which would shift the destination into it.
op() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$W/ops"; }

# Everything flatpak passes as a descriptor is read here, in its mount namespace: a folder's path
# (another namespace can't bind it, nor even name it) and a file's contents, kept in $W/data,
# numbered by n.
n=0
CHDIR=/
exec 3< "$W/args"
next() { IFS= read -r v <&3; }
while IFS= read -r a <&3; do
  case $a in
    --) break ;;
    --ro-bind) next; s=$v; next; op bind "$s" "$v" ro ;;
    --bind|--dev-bind) next; s=$v; next; op bind "$s" "$v" ;;
    # -try: no complaint when it can't be had (/proc/self/ns/user, with no user namespaces).
    --ro-bind-try) next; s=$v; next; op bind "$s" "$v" ro-try ;;
    --bind-try|--dev-bind-try) next; s=$v; next; op bind "$s" "$v" try ;;
    --ro-bind-fd) next; s=$(readlink "/proc/$$/fd/$v"); next; op bind "$s" "$v" ro ;;
    --bind-fd) next; s=$(readlink "/proc/$$/fd/$v"); next; op bind "$s" "$v" ;;
    --ro-bind-data|--bind-data|--file)
      next; n=$((n + 1)); s=$W/data/$n; cat "/proc/$$/fd/$v" > "$s"; next; op bind "$s" "$v" ;;
    --dir) next; op dir - "$v" ;;
    --tmpfs) next; op tmpfs - "$v" ;;
    --proc) next; op bind /proc "$v" ;;
    --dev) next; op bind /dev "$v" ;;
    --symlink) next; s=$v; next; op link "$s" "$v" ;;
    --setenv) next; k=$v; next; printf '%s=%s\n' "$k" "$v" >> "$W/env" ;;
    --chdir) next; CHDIR=$v ;;
    --chmod) next; next ;;
    --overlay) next; next; next ;;
    # One value each, nothing to do here: locks, limits, labels, ids, seccomp and status fds.
    --lock-file|--remount-ro|--mqueue|--perms|--size|--seccomp|--add-seccomp-fd|--info-fd|--json-status-fd|\
    --block-fd|--userns-block-fd|--sync-fd|--cap-drop|--cap-add|--uid|--gid|--hostname|--exec-label|\
    --file-label|--argv0|--userns|--userns2|--pidns|--unsetenv|--overlay-src|--tmp-overlay|--ro-overlay|\
    --level-prefix) next ;;
    # Isolation and the like, which this can't give.
    --*) ;;
    *) printf '%s\n' "$a" >> "$W/cmd"; break ;;
  esac
done
cat <&3 >> "$W/cmd"
exec 3<&-
[ -s "$W/cmd" ] || { echo "bwrap (Tessera): no command" >&2; exit 1; }

# What runs in the new root: env with only the variables asked for (as --clearenv), then a shell
# that goes to --chdir and starts the command.
set -- /usr/bin/env -i
while IFS= read -r l; do set -- "$@" "$l"; done < "$W/env"
set -- "$@" /bin/sh -c 'cd "$1" 2>/dev/null || cd /; shift; exec "$@"' sh "$CHDIR"
while IFS= read -r l; do set -- "$@" "$l"; done < "$W/cmd"

# The triggers: / as it is, with paths bound onto themselves. Nothing to build; run it.
same=1
while IFS='	' read -r kind src dest ro; do
  [ "$kind" = bind ] && [ "$src" = "$dest" ] || { same=0; break; }
done < "$W/ops"
if [ "$same" = 1 ] && grep -q "^bind	/	/	" "$W/ops"; then exec "$@"; fi

if [ -x "$PROOT" ]; then
  # PRoot mode: binds are PRoot's options, ahead of the command; the rest is made in the new
  # root's folder.
  set -- "--rootfs=$NR" "--cwd=$CHDIR" "$@"
  while IFS='	' read -r kind src dest ro; do
    case $kind in
      bind) [ -e "$src" ] && set -- "--bind=$src:$dest" "$@" ;;
      dir) mkdir -p "$NR$dest" ;;
      tmpfs) n=$((n + 1)); mkdir -p "$W/tmpfs/$n"; set -- "--bind=$W/tmpfs/$n:$dest" "$@" ;;
      link) mkdir -p "$NR${dest%/*}"; ln -sfn "$src" "$NR$dest" ;;
    esac
  done < "$W/ops"
  mkdir -p "$W/proot"
  PROOT_LOADER=/opt/tessera-proot/libproot-loader.so PROOT_TMP_DIR=$W/proot \
    exec "$PROOT" --kill-on-exit --link2symlink --root-id "$@"
fi

# Chroot mode: the mounts in a namespace of their own. Propagation as it is: the chroot's / isn't
# a mount point to change it on, and enter.sh made every mount private already.
: > "$W/run"
for a in "$@"; do printf '%s\n' "$a" >> "$W/run"; done
exec unshare -m --propagation unchanged "$0" --tessera-mount "$W"
