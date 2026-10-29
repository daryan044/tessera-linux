# Droidspaces mode

Tessera can run its apps inside a container you made with
[Droidspaces](https://github.com/ravindu644/Droidspaces-OSS): a whole Linux system with its own
init, which keeps running beside Tessera. Pick the **Droidspaces** engine in the side menu or in
Setup, pick which container to use, and apps start in it.

This is the third engine, next to PRoot and chroot. Like chroot it needs root, and like chroot the
kernel enforces each app's permissions. Unlike chroot, the Linux system is yours: its distro, its
network setup, its other services (systemd, sshd, a web server) are the container's own.

## What you need

- Root (Magisk, KernelSU or APatch), and Droidspaces installed. Tessera looks for the binary at
  `/data/local/Droidspaces/bin/droidspaces` (where the Droidspaces app puts it), then on `PATH`.
- A **Debian or Ubuntu** container. Tessera installs apps with `apt`, and its launch script uses
  `unshare`, `mount` and `setpriv` from util-linux, which those have.
- Droidspaces v7.0.0 or later. Tessera only uses `start`, `stop`, `restart`, `run` and `pid`, so it
  doesn't depend on the `--format` flag that v7.1.0 renamed to `--json`.

## Setting up

1. Make a container in the Droidspaces app (or on the command line).
2. In Tessera: **Setup → Droidspaces**, pick the container under **Container**.
3. **Set up for Tessera**. This is once per container, and asks first. Droidspaces binds folders
   when a container boots and can't add one to a running container, so Tessera adds two entries to
   the container's saved `bind_mounts` (a copy of the original config is kept as
   `container.config.bak-tessera`) and restarts it if it was running.
4. **Start** the container if it's stopped. Apps now open in it.

Turn on **Android storage** for the container in Droidspaces if you want apps to see the phone's
folders; and **Hardware access** or **GPU** if you want GPU acceleration or USB devices.

## What Tessera shares into the container

| Where in the container | What | Why |
|---|---|---|
| `/mnt/tessera/sessions` | the app's session folders (`cache/sessions`) | each app's X server socket and the files it and Tessera exchange |
| `/mnt/tessera/bin` (read-only) | `enter.sh` and `flatpak.sh` | the ordinary app entry point and the Flatpak root builder |

Nothing else of Tessera's is visible to the container, and the apps themselves don't see
`/mnt/tessera` at all: `enter.sh` hides it before the app starts, once the app's own X socket is
bound (it holds every session's socket).

## How an app starts

`su -c 'droidspaces --name=<container> run -- /bin/sh /mnt/tessera/bin/enter.sh <session> <settings>'`

`<settings>` is the app's settings as shell assignments, one per line, so nothing has to survive
`droidspaces run`'s environment. They come on root's command line rather than in a file in the
session's folder, because apps run as the uid that owns those folders and could rewrite it.
`enter.sh`, running as root inside the container:

1. makes a private mount namespace, and a private network namespace when the app has no network
   permission, with `unshare`;
2. gives the app its own `/tmp` and `/dev/shm`, binds the app's session folder at `/tmp/.X11-unix`,
   shares the folders it was granted, and hides `/mnt/tessera`;
3. gives the app's uid a name (`user`), and drops to that uid with `setpriv`, with no new
   privileges and no capabilities.

The container's own mounts are never touched: everything `enter.sh` mounts disappears with its
namespace.

## Limits

- **Folders.** A grant is shared only if the container can see the folder: the phone's storage (when
  the container has Android storage on) or a path in the container's own `bind_mounts`. Others are
  skipped, and Tessera says so when the app starts.
- **Flathub apps** use Tessera's custom Flatpak launcher, which builds a root with the installed
  runtime at `/usr` and app at `/app` inside each launch's private mount namespace. It does not run
  `flatpak run` or enable Droidspaces' broader `--allow-sandboxing` option. As in PRoot/chroot,
  this is not Flatpak's own bubblewrap sandbox; Tessera's per-app permissions apply instead. The
  Droidspaces path still needs broader device-level testing.
- **Controlling this phone with adb** (the adb permission) isn't available yet.
- **DNS** is the container's own (Droidspaces' `dns_servers`); Tessera doesn't rewrite it.
- **USB and GPU** need the device nodes inside the container: turn on hardware access or GPU in
  Droidspaces. Without them the permission has nothing to hand over.
- Restarting or stopping the container ends every app running in it.
- **The container's `user` account becomes Tessera's.** Apps run as Tessera's own uid, so the
  first launch sets `user`'s uid and group in `/etc/passwd` and `/etc/group` to it (adding `user`
  if there's none) and hands `/home/user` over to it. Tessera's GPU driver and theme pack are
  installed into the container too. Use a container made for Tessera rather than one you work in.
- A running container's saved config is read from the copy it booted with, so "set up" shows as
  needed until the container has been restarted after being prepared.

## Troubleshooting

- *"Droidspaces isn't installed"*: Tessera found no binary and root may have been refused. Check
  that Tessera was granted root, then **Check again**.
- *"Set the container up for Tessera in Setup"*: the container booted without the two bind mounts.
  **Set up for Tessera** (restart it if you edited the config by hand).
- *App exits at once with `Tessera's folders aren't in this container`*: the same, seen from inside.
- *`unshare`, `setpriv` or `mount` missing*: install `util-linux` in the container.
- Use **Copy diagnostics** in the side menu: it lists the container, whether it runs, and whether
  Tessera's folders are bound.
