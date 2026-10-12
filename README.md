# Linux Launcher

A Material 3 Expressive launcher for Linux apps on Android. Each app runs in a container that
gets only what its manifest grants: specific folders, network, its own X display, audio, devices.

- **PRoot mode** works without root. Restrictions are best-effort; network can't be blocked.
- **Chroot mode** (Magisk / KernelSU / APatch) enforces every permission with mount and network
  namespaces and an unprivileged uid.

The UI says which of the two applies to each permission, per app.

## Status

Done: app skeleton, NDK pty spawner, permission model, both backends, launcher UI (1.1), and
PRoot built from source for Android (1.1b, [docs/native-binaries.md](docs/native-binaries.md)).
Also done: Debian install (1.2), terminal and foreground service (1.4), GUI apps in their own
fullscreen X11 windows (phase 2). Full status: [docs/ROADMAP.md](docs/ROADMAP.md).

## Layout

```
app/src/main/
  cpp/pty.c                     forkpty-style spawner (JNI)
  java/.../container/
    PermissionManifest.kt       manifest format + validation
    ProotBackend.kt             manifest -> proot argv
    ChrootBackend.kt            manifest -> su + unshare + enter.sh
    SessionManager.kt           launches and tracks processes
  assets/apps/*.json            app manifests (one per launcher entry)
  assets/chroot/enter.sh        root-side namespace setup
  java/.../ui/                  Compose UI (Material 3 Expressive, Haze blur)
```

## App manifests

```json
{
  "id": "gimp",
  "name": "GIMP",
  "category": "Graphics",
  "gui": true,
  "exec": ["/usr/bin/gimp"],
  "permissions": {
    "display": true,
    "network": false,
    "devices": ["/dev/dri/renderD128"],
    "filesystem": [
      { "path": "/storage/emulated/0/Pictures", "mountAt": "/home/user/Pictures", "readOnly": false }
    ]
  }
}
```

## Building

JDK 17 and the Android SDK with NDK 28.2.13676358 and CMake 3.31.6.

```
git submodule update --init           # Termux:X11 fork (third_party/termux-x11)
scripts/proot/build.sh                # once: builds PRoot into app/src/main/jniLibs
scripts/x11/build-native.sh           # once, on Linux or WSL: X server + xkb data into build/x11
./gradlew assembleDebug testDebugUnitTest
```

The X server's native build needs a Linux host (it compiles helper tools with the host gcc);
everything else, including the app itself, builds on Windows, macOS or Linux.

`targetSdk` is 28 on purpose: newer targets forbid executing files from app storage, which is
where the Linux system lives. This rules out Google Play; distribute through GitHub and F-Droid.

## License

Copyright (C) 2026 Daryan

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).

It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

### Third-party components

| Component | License | Used for |
|---|---|---|
| [PRoot](https://github.com/termux/proot) (Termux fork) | GPL-2.0-or-later | container engine, built by `scripts/proot/build.sh` |
| [talloc](https://talloc.samba.org) | LGPL-3.0-or-later | PRoot dependency (`scripts/proot/talloc-compat.c` is adapted from Samba) |
| [Termux:X11](https://github.com/termux/termux-x11) ([our fork](https://github.com/daryan044/termux-x11)) | GPL-3.0 | X server and GUI windows |
| [Termux terminal-emulator / terminal-view](https://github.com/termux/termux-app) | Apache-2.0 | terminal |
| [Haze](https://github.com/chrisbanes/haze) | Apache-2.0 | background blur |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 | UI |
| kotlinx.serialization | Apache-2.0 | manifests |
