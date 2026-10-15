#!/usr/bin/env bash
# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the theme pack for Linux apps (docs/LOOK-AND-FEEL.md): what Debian doesn't package,
# from pinned, checksummed releases, staged as /opt/tessera-theme:
#  - adw-gtk3 (LGPL-2.1), libadwaita's look for GTK 3 apps, light and dark. Debian trixie
#    doesn't package it. Tessera recolors it with the phone's palette at launch (ThemeFiles).
#  - Bibata Modern Ice (GPL-3.0), a white cursor with rounded corners, in place of X's sharp
#    black one.
#
# Packs the result as build/theme/assets/theme/tessera-theme.tgz (bundled into the APK by
# app/build.gradle.kts; builds without it leave GTK 3 apps on Debian's stock Adwaita and the
# pointer on X's own), unpacked into a container's /opt/tessera-theme. Nothing is compiled: any
# host with curl and Python 3 (the archives are repacked by Python, as their symlinks can't be
# unpacked on Windows).
#   scripts/theme/build-theme.sh
set -euo pipefail

ADW_GTK3_VERSION=6.5
ADW_GTK3_SHA256=a81780fadfc432be0fc3d89c4ebb41aa28e4f032d42c36f9789c57dd10cfa41c
BIBATA_VERSION=2.0.7
BIBATA_SHA256=a68cae60c4dc706350e194ebc91c5fe48bc7bc9d59e119555834a2a7ee5078ef
# Bumped when the pack changes without a new release of either.
PACK_REVISION=3
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../build/theme/assets/theme"
WORK="$HERE/../../build/theme/work"

rm -rf "$WORK" && mkdir -p "$WORK" "$OUT"
cd "$WORK"
fetch() { # url sha256 file
  curl -fsSL -o "$3" "$1"
  echo "$2  $3" | sha256sum -c -
}
fetch "https://github.com/lassekongo83/adw-gtk3/releases/download/v$ADW_GTK3_VERSION/adw-gtk3v$ADW_GTK3_VERSION.tar.xz" \
  "$ADW_GTK3_SHA256" adw-gtk3.tar.xz
fetch "https://github.com/ful1e5/Bibata_Cursor/releases/download/v$BIBATA_VERSION/Bibata-Modern-Ice.tar.xz" \
  "$BIBATA_SHA256" bibata.tar.xz
curl -fsSL -o adw-gtk3.LICENSE "https://raw.githubusercontent.com/lassekongo83/adw-gtk3/v$ADW_GTK3_VERSION/LICENSE"
curl -fsSL -o bibata.LICENSE "https://raw.githubusercontent.com/ful1e5/Bibata_Cursor/v$BIBATA_VERSION/LICENSE"

python=$(command -v python3 || command -v python)
"$python" - "$OUT/tessera-theme.tgz" <<'PY'
import io, struct, sys, tarfile

PREFIX = "opt/tessera-theme/share/"
# Cursor sizes kept: X screens are already scaled up 1.5 to 3 times, and the 14 sizes up to 96
# Bibata ships would make the pack 30 MB unpacked.
CURSOR_SIZES = {16, 20, 24, 28, 32}
IMAGE = 0xFFFD0002

def trim_cursor(data):
    """An Xcursor file with only the images of CURSOR_SIZES (and anything not an image)."""
    if data[:4] != b"Xcur":
        return data
    header, version, ntoc = struct.unpack("<III", data[4:16])
    toc = [struct.unpack("<III", data[16 + 12 * i:28 + 12 * i]) for i in range(ntoc)]
    def chunk(pos):
        size, = struct.unpack("<I", data[pos:pos + 4])
        typ, = struct.unpack("<I", data[pos + 4:pos + 8])
        if typ == IMAGE:
            w, h = struct.unpack("<II", data[pos + 16:pos + 24])
            return data[pos:pos + size + w * h * 4]
        length, = struct.unpack("<I", data[pos + 16:pos + 20])  # a comment: its text follows
        return data[pos:pos + size + length]
    kept = [(t, sub, chunk(pos)) for t, sub, pos in toc if t != IMAGE or sub in CURSOR_SIZES]
    if not any(t == IMAGE for t, _, _ in kept):
        return data
    out = bytearray(b"Xcur" + struct.pack("<III", header, version, len(kept)))
    pos = 16 + 12 * len(kept)
    for t, sub, c in kept:
        out += struct.pack("<III", t, sub, pos)
        pos += len(c)
    for _, _, c in kept:
        out += c
    return bytes(out)

def add_dir(out, name):
    info = tarfile.TarInfo(name)
    info.type, info.mode = tarfile.DIRTYPE, 0o755
    out.addfile(info)

def add_file(out, name, path):
    data = open(path, "rb").read()
    info = tarfile.TarInfo(name)
    info.size, info.mode = len(data), 0o644
    out.addfile(info, io.BytesIO(data))

with tarfile.open(sys.argv[1], "w:gz", format=tarfile.GNU_FORMAT) as out:
    for d in ["opt", "opt/tessera-theme", PREFIX.rstrip("/"), PREFIX + "themes", PREFIX + "icons", PREFIX + "licenses"]:
        add_dir(out, d)
    for archive, into in [("adw-gtk3.tar.xz", "themes/"), ("bibata.tar.xz", "icons/")]:
        with tarfile.open(archive, "r:xz") as src:
            for m in src.getmembers():
                f = src.extractfile(m) if m.isfile() else None
                if f is not None and "/cursors/" in m.name:
                    data = trim_cursor(f.read())
                    m.size, f = len(data), io.BytesIO(data)
                m.name = PREFIX + into + m.name.lstrip("./")
                m.uid = m.gid = 0
                m.uname = m.gname = "root"
                out.addfile(m, f)
    # Apps ask for "default" (Debian points it at Adwaita) or "Adwaita" by name, whatever
    # XCURSOR_THEME says. Cursor-only themes of those names, first on XCURSOR_PATH, hand them
    # Bibata instead. Icons aren't looked up on that path, so Adwaita's icons stay.
    for name in ["default", "Adwaita"]:
        add_dir(out, PREFIX + "icons/" + name)
        lines = ["[Icon Theme]", "Name=" + name, "Inherits=Bibata-Modern-Ice"]
        data = "".join(line + chr(10) for line in lines).encode()
        info = tarfile.TarInfo(PREFIX + "icons/" + name + "/index.theme")
        info.size, info.mode = len(data), 0o644
        out.addfile(info, io.BytesIO(data))
    for name in ["adw-gtk3", "bibata"]:
        add_dir(out, PREFIX + "licenses/" + name)
        add_file(out, PREFIX + "licenses/" + name + "/LICENSE", name + ".LICENSE")
PY
echo "adw-gtk3-$ADW_GTK3_VERSION+bibata-$BIBATA_VERSION-t$PACK_REVISION" > "$OUT/VERSION"
cd "$HERE" && rm -rf "$WORK"
echo "$OUT/tessera-theme.tgz ($(cat "$OUT/VERSION"))"
