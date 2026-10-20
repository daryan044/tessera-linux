# Native binaries

The app runs Linux through PRoot, which ships inside the APK. Android 10+ refuses to `exec()`
files from app storage, so every executable is packaged as a fake shared library in
`app/src/main/jniLibs/<abi>/` and lands in `nativeLibraryDir`, the one place an app may execute
from. `useLegacyPackaging = true` in `app/build.gradle.kts` makes Android extract them to files.

| File | What it is | Passed as |
|---|---|---|
| `libproot.so` | the `proot` executable | — |
| `libproot-loader.so` | loader for guests of the device's own architecture | `PROOT_LOADER` |
| `libproot-loader32.so` | loader for 32-bit guests (arm64 and x86_64 only) | `PROOT_LOADER_32` |

They are generated, not committed (`.gitignore`). Without them the app still builds and runs,
Setup just shows "PRoot engine" as missing.

## Building

```
scripts/proot/build.sh                  # arm64-v8a, armeabi-v7a, x86_64
ABIS=arm64-v8a scripts/proot/build.sh   # one ABI
```

Needs bash (Git Bash is fine on Windows), curl, tar and the NDK (`ANDROID_NDK_HOME`, or
`ANDROID_HOME` with NDK 29.0.14206865 installed). Output goes to `jniLibs/`, intermediates to
`build/proot/`.

What it does, mirroring `termux/proot`'s `src/GNUmakefile` and Termux's package recipe:

- Downloads [termux/proot](https://github.com/termux/proot) `v5.1.107.96` and
  talloc `2.5.0`, both pinned by SHA-256.
- Builds talloc from `talloc.c` alone, using `scripts/proot/talloc-config.h` in place of waf's
  configure step and `talloc-compat.c` for the one libc function bionic lacks at API 24.
- Builds PRoot and both loaders statically (no runtime dependencies, no `libtalloc.so` to ship),
  with `PROOT_UNBUNDLE_LOADER` so the loaders are separate files.
- On arm64, generates `loader-info.c` (the pokedata workaround offset) from the loader's symbols.

Not included yet: `libandroid-shmem`, which Termux links for System V shared memory emulation.
PRoot's own sysvipc extension still works; add it if an app needs `shmget` across processes.

## Verified

On a Xiaomi 13 Ultra (Android 16), as the app's own uid (`run-as`), targetSdk 28:
extracting Alpine with `--link2symlink -0`, then `apk update` and `apk add htop python3`
inside the container, and running both.

Found along the way: Android cuts network access for apps in the background. Container sessions
must run under a foreground service (step 1.4) or downloads stall with DNS timeouts.

## Licensing

PRoot is GPL-2.0-or-later and talloc is LGPL-3.0-or-later. Together with Termux:X11's server
(GPL-3.0) later, the app is distributed under GPL-3.0-or-later.
