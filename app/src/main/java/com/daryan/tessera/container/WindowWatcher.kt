// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

/** Where the window watcher reports, and takes commands, as the guest sees them. */
const val WINDOWS_FILE_GUEST = "/tmp/.X11-unix/launcher-windows"
const val WINDOWS_FILE_NAME = "launcher-windows"
const val WINDOW_COMMANDS_NAME = "launcher-wincmd"

/**
 * Runs next to a GUI app (see [guestCommand]) with python3-xlib. It keeps [WINDOWS_FILE_GUEST]
 * up to date with the app's windows as JSON, {"active": id, "windows": [{"id", "title",
 * "main"}]}, replaced atomically on every change, where "main" means a window of its own
 * rather than a dialog. Commands come one per line through the FIFO next to it:
 * "activate <id>" brings a window to the front, "close <id>" asks it to close.
 */
internal const val WINDOW_WATCHER = """
import json, os, select, sys
from Xlib import X, display, error
from Xlib.protocol import event

state_path = sys.argv[1]
cmd_path = os.path.join(os.path.dirname(state_path), "launcher-wincmd")
d = display.Display()
root = d.screen().root
A = d.intern_atom
CLIENTS, ACTIVE, CLOSE = A("_NET_CLIENT_LIST"), A("_NET_ACTIVE_WINDOW"), A("_NET_CLOSE_WINDOW")
NAME, TYPE, NORMAL = A("_NET_WM_NAME"), A("_NET_WM_WINDOW_TYPE"), A("_NET_WM_WINDOW_TYPE_NORMAL")
root.change_attributes(event_mask=X.PropertyChangeMask)
watched = set()

def prop(w, atom):
    try:
        p = w.get_full_property(atom, X.AnyPropertyType)
        return p.value if p else None
    except error.XError:
        return None

def describe(wid):
    w = d.create_resource_object("window", wid)
    if wid not in watched:
        try:
            w.change_attributes(event_mask=X.PropertyChangeMask)  # title changes
            watched.add(wid)
        except error.XError:
            pass
    name = prop(w, NAME)
    if name is None:
        try:
            name = w.get_wm_name()
        except error.XError:
            name = None
    if isinstance(name, bytes):
        name = name.decode("utf-8", "replace")
    types = list(prop(w, TYPE) or [])
    try:
        transient = w.get_wm_transient_for()
    except error.XError:
        transient = None
    return {"id": wid, "title": name or "", "main": transient is None and (not types or NORMAL in types)}

last = None
def publish():
    global last
    ids = list(prop(root, CLIENTS) or [])
    active = (prop(root, ACTIVE) or [0])[0]
    text = json.dumps({"active": active, "windows": [describe(i) for i in ids]})
    if text == last:
        return
    last = text
    tmp = state_path + ".tmp"
    with open(tmp, "w") as f:
        f.write(text)
    os.chmod(tmp, 0o644)
    os.replace(tmp, state_path)

def message(wid, atom, data):
    ev = event.ClientMessage(window=d.create_resource_object("window", wid), client_type=atom, data=(32, data))
    root.send_event(ev, event_mask=X.SubstructureRedirectMask | X.SubstructureNotifyMask)
    d.flush()

if not os.path.exists(cmd_path):
    os.mkfifo(cmd_path)
os.chmod(cmd_path, 0o666)
# Read-write so it never sees end-of-file between writers.
cmd = os.open(cmd_path, os.O_RDWR | os.O_NONBLOCK)
pending = b""
publish()
while True:
    select.select([d.fileno(), cmd], [], [], 5)
    try:
        pending += os.read(cmd, 4096)
    except BlockingIOError:
        pass
    while b"\n" in pending:
        line, pending = pending.split(b"\n", 1)
        parts = line.decode(errors="replace").split()
        if len(parts) != 2:
            continue
        try:
            wid = int(parts[1], 0)
        except ValueError:
            continue
        if parts[0] == "activate":
            message(wid, ACTIVE, [2, X.CurrentTime, 0, 0, 0])
        elif parts[0] == "close":
            message(wid, CLOSE, [X.CurrentTime, 2, 0, 0, 0])
    while d.pending_events():
        d.next_event()
    publish()
"""
