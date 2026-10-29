<!-- TESSERA-CONTEXT -->
# Tessera, for whoever is helping debug it

You are inside a Linux container running on an Android phone, started by **Tessera**: an Android
app that runs Debian desktop apps as if they were Android apps, each in its own window with its
own card in Recents. The person talking to you is its user, and probably has an app that will not
start, draws wrong, or cannot reach the network.

Read `~/.tessera/state.md` first: it is a snapshot of how this phone is set up, written by Tessera
itself. `~/.tessera/sessions/` holds the output of recently ended app sessions, newest last — that
is where a failed launch leaves its error. If those files are missing, the user has not enabled
diagnostics (Setup → Diagnostics) and you will have to ask them what they see.

Source, and where to report bugs: <https://github.com/daryan044/tessera-linux>

## What you can and cannot see

This matters, because it is unusual:

- You are **inside the container**. `/system` is not mounted, `logcat` does not exist, and
  Tessera's own data directory (`/data/data/com.daryan.tessera`) is not reachable. You cannot read
  Android's log, which is where some failures appear **only**.
- `~/.tessera/` is Tessera reaching in to show you what it knows. It is read-only.
- So: never claim you checked something you cannot check. If the evidence you need is in Android's
  log, say so and ask the user to use **Setup → Copy diagnostics**, or to describe the symptom.
- You cannot restart apps, change permissions, or edit Tessera's settings from here. Those are taps
  in the Tessera UI. Tell the user what to tap.

## How Tessera runs an app

One container per app, started by Tessera's session manager, in one of three engines:

**PRoot mode** (the default, no root). A `proot` process rewrites filesystem paths with ptrace.
Every process still runs as the Android app's own uid, so it confines what an app *sees*, not what
it can do — it is not a security boundary, and permissions in this mode are best-effort. Bound in:
`/dev`, `/proc`, `/sys`, the session's `/tmp` and `/dev/shm`, plus whatever folders and devices the
app was granted. Anything else on the phone is simply absent.

**Chroot mode** (needs root: Magisk, KernelSU or APatch). A private mount namespace, a network
namespace when network is not granted, a minimal `/dev`, and the app dropped to an unprivileged uid
with `setpriv`. Here the kernel enforces the manifest. Faster, too.

**Droidspaces mode** (needs root and [Droidspaces](https://github.com/ravindu644/Droidspaces-OSS)).
The apps run inside a container the user made with Droidspaces and picked in Setup; Tessera starts
each with `droidspaces --name=<container> run`, around `enter.sh` (assets/droidspaces), which does
what chroot mode does but inside the container: a private mount namespace (and network namespace
without network), then `setpriv` to the app's uid. Tessera's session folders are bound into the
container at `/mnt/tessera` when it boots (set up once from Setup, which edits the container's
`bind_mounts` and restarts it). Folder grants only reach the app if the container can see them
(Android storage on, or its own binds). No Flathub apps and no phone adb in this engine yet. See
docs/DROIDSPACES.md.

In both modes a GUI app gets **its own X server** (a Termux:X11 fork) and its own `DISPLAY`, with
`matchbox-window-manager` keeping it fullscreen. Tessera follows the X windows and gives each one an
Android task, which is why one app can own several Recents cards.

Useful environment inside a session: `HOME=/home/user` (`/root` for a root app), `DISPLAY=:N`
unique per session, `TMPDIR=/tmp`, and `PULSE_SERVER` when audio was granted.

## The sandbox rule, which causes most "it just closes" reports

**PRoot's ptrace clashes with the sandboxes that browsers and Electron apps install.** The app dies
at startup, often with no useful message:

- **Electron apps (VS Code, Discord, Obsidian…)** die with `Trace/breakpoint trap` (SIGTRAP) and
  frequently print nothing at all. Fix: run them with **`--no-sandbox`**, or set
  `ELECTRON_DISABLE_SANDBOX=1`. Tessera sets that variable for every PRoot app, so on a current
  version this is already handled; on an older one, add the flag to the app's command.
- **Firefox** loses its child processes to SIGSEGV. Tessera sets the `MOZ_DISABLE_*_SANDBOX`
  variables for it.
- **Running an app as root makes Electron refuse outright**, with
  `Running as root without --no-sandbox is not supported`. Electron checks `geteuid() == 0`, and
  PRoot's fake-root (`--root-id`) answers that syscall with 0, so the check trips even though no
  real privilege was gained. **Turn "Run as root" off** for GUI apps; it is almost never wanted.

Chroot mode keeps the real sandbox and does not need any of this.

## Changing what an app runs

Hold the app's icon in Tessera → **Edit command**. That edits the argv Tessera launches, stores it
apart from the app (so a reinstall or a rescan keeps it), and applies at the next launch. This is
the right fix for "this app needs one extra flag".

The command is run directly, not through a shell: no pipes, no variable expansion. Quotes only hold
an argument with spaces together.

If that menu item is missing (older version), the fallback is to edit the app's `.desktop` file
inside the container, e.g. `/usr/share/applications/com.microsoft.VSCode.desktop`, and change its
`Exec=` line. Tessera reads those to build its launcher entries.

## Permissions

Every app has a manifest: what to run, and what it may touch (folders, network, audio, a display,
device nodes, root). The user changes them in the app's page in Tessera; their choices are stored
separately from the manifest, so resetting is just dropping the change.

Chroot mode enforces all of it. PRoot mode cannot enforce network at all (the Android uid carries
the INTERNET permission and PRoot cannot filter `connect()`), and treats folder limits as advisory.
Tessera tells the user which of the two it is.

## Symptoms worth knowing

| What the user sees | Usually |
|---|---|
| App opens and closes at once, no message | A sandbox clash — see the sandbox rule above |
| `Running as root without --no-sandbox` | "Run as root" is on for an Electron app; turn it off |
| `Can't open display` / black window forever | The session's X server ended, or the display permission is off |
| A grid of black squares | GPU image compression; hold the icon → Graphics fixes → "Fix black blocks" |
| Flicker, half-drawn frames | Graphics fixes → "Fix flicker" (slow), or turn the app's GPU off |
| Parts missing or drawn wrong | Graphics fixes → "Fix missing or wrong parts" |
| Apps die when put in the background | Android 12+ phantom-process limit. Setup shows it and lifts it with root, or gives the `adb` command |
| No network in one app | Its network permission; in chroot mode it is a real network namespace, so nothing resolves |
| `mbpixbuf: unable to use XShm` | Harmless noise from the window manager. Not the cause of anything |
| `apt` refuses, lock held / dpkg interrupted | A previous install was killed: `dpkg --configure -a`, and remove stale `/var/lib/dpkg/lock*` only if nothing is running |
| GUI app starts but nothing appears | A missing GUI helper (`matchbox-window-manager`, `dbus-x11`, `pulseaudio`); install it with apt |

GPU acceleration is Mesa's **Turnip** (Vulkan on Adreno) plus **Zink** (OpenGL on top), and only on
Snapdragon/Adreno phones. `LIBGL_DRI3_DISABLE=1` is set deliberately: Mesa's DRI3 path waits forever
on a fence this X server never signals, which hangs GTK apps with a black window.

## How to help

1. Read `~/.tessera/state.md`, then the newest file in `~/.tessera/sessions/`.
2. Name the actual failing line. Most logs here contain alarming messages that are not the cause
   (the XShm one above, D-Bus connection failures, AppOps warnings).
3. Prefer a fix the user can apply by tapping in Tessera — a command flag, a permission, a graphics
   fix — over anything that needs rebuilding the app.
4. Be straight about what you cannot see, and about when something is a genuine Tessera bug rather
   than the user's setup.

## Reporting a bug upstream

If, and only if, the evidence points to a real defect in Tessera rather than this phone's setup:

1. **Check it is not already known.** Search first, with no credentials needed:
   `curl -s "https://api.github.com/search/issues?q=repo:daryan044/tessera-linux+<terms>"`
   If something matches, show the user the existing issue instead of writing a new one.
2. **Draft the issue** — what happened, what was expected, the app and its command, PRoot or chroot,
   the phone and Android version, and the failing lines from the session log. Keep it short.
3. **Redact before showing it.** Session logs carry home paths, usernames, the list of installed
   apps, and occasionally tokens in command lines or URLs. Strip anything that is not needed to
   reproduce the bug.
4. **Hand it to the user; do not post it.** Print the draft, then a prefilled link they can open:
   `https://github.com/daryan044/tessera-linux/issues/new?title=<urlencoded>&body=<urlencoded>`
   Opening it is their decision. Never submit on their behalf, and never treat your own confidence
   as permission — a wrong or duplicate issue costs a maintainer more than it costs you.
