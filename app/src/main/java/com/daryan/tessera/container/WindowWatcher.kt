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
 * "main", "class", "hidden", "maximized", "desktop"}], "screen": [width, height], "workspace":
 * n, "workspaces": n}, replaced atomically on every change, where "main" means a window of its
 * own rather than a dialog and "desktop" is its workspace (-1: on all of them). Commands come
 * one per line through the FIFO next to it: "activate <id>" brings a window to the front (and
 * back from minimized), "close <id>" asks it to close; for a desktop's taskbar also "minimize
 * <id>", "maximize <id>" (toggles), "snap <id> left|right" (half of the work area), "workspace
 * <n>" (switches to it) and "move <id> <n>" (sends a window to workspace n).
 *
 * What it knows of each window is kept until that window's properties change, so a title that
 * keeps changing (a terminal's, a download's) costs one window's lookups, not every window's.
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
STATE, HIDDEN = A("_NET_WM_STATE"), A("_NET_WM_STATE_HIDDEN")
MAX_V, MAX_H = A("_NET_WM_STATE_MAXIMIZED_VERT"), A("_NET_WM_STATE_MAXIMIZED_HORZ")
CHANGE_STATE, WORKAREA = A("WM_CHANGE_STATE"), A("_NET_WORKAREA")
MOVERESIZE, EXTENTS = A("_NET_MOVERESIZE_WINDOW"), A("_NET_FRAME_EXTENTS")
WM_DESKTOP, CURRENT, COUNT = A("_NET_WM_DESKTOP"), A("_NET_CURRENT_DESKTOP"), A("_NET_NUMBER_OF_DESKTOPS")
# Structure too: the screen changes size when the phone turns.
root.change_attributes(event_mask=X.PropertyChangeMask | X.StructureNotifyMask)
watched = set()
# What describe() found, by window, until a property of it changes.
known = {}

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
    try:
        cls = (w.get_wm_class() or ("", ""))[1] or ""
    except error.XError:
        cls = ""
    states = list(prop(w, STATE) or [])
    desk = (prop(w, WM_DESKTOP) or [0])[0]
    return {"id": wid, "title": name or "", "main": transient is None and (not types or NORMAL in types),
            "class": cls, "hidden": HIDDEN in states, "maximized": MAX_V in states and MAX_H in states,
            "desktop": -1 if desk >= 0xFFFFFFFF else desk}

def cached(wid):
    if wid not in known:
        known[wid] = describe(wid)
    return known[wid]

last = None
def publish():
    global last
    ids = list(prop(root, CLIENTS) or [])
    active = (prop(root, ACTIVE) or [0])[0]
    try:
        g = root.get_geometry()
        screen = [g.width, g.height]
    except error.XError:
        screen = []
    for gone in set(known) - set(ids):
        known.pop(gone, None)
        watched.discard(gone)
    text = json.dumps({"active": active, "windows": [cached(i) for i in ids], "screen": screen,
                       "workspace": (prop(root, CURRENT) or [0])[0], "workspaces": (prop(root, COUNT) or [1])[0]})
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

def snap(wid, side):
    area = list(prop(root, WORKAREA) or [])[:4]
    if len(area) < 4:
        return
    x, y, w, h = area
    l, r, t, b = (list(prop(d.create_resource_object("window", wid), EXTENTS) or []) + [0, 0, 0, 0])[:4]
    half = w // 2
    left = x if side == "left" else x + half
    message(wid, STATE, [0, MAX_V, MAX_H, 1, 0])
    # Static gravity, with x, y, width and height given, from a pager (source 2): the client
    # goes inside its frame's share of the area.
    message(wid, MOVERESIZE, [10 | 0xF00 | 0x2000, left + l, y + t, half - l - r, h - t - b])

if not os.path.exists(cmd_path):
    os.mkfifo(cmd_path)
try:
    # Made by the launcher, as a rule (see SessionManager.makeFifo), which may not let us.
    os.chmod(cmd_path, 0o666)
except OSError:
    pass
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
        if len(parts) not in (2, 3):
            continue
        try:
            wid = int(parts[1], 0)
        except ValueError:
            continue
        if parts[0] == "workspace":
            # Here the number is the workspace's, not a window's.
            message(root.id, CURRENT, [wid, X.CurrentTime, 0, 0, 0])
            continue
        if parts[0] == "activate":
            message(wid, ACTIVE, [2, X.CurrentTime, 0, 0, 0])
        elif parts[0] == "close":
            message(wid, CLOSE, [X.CurrentTime, 2, 0, 0, 0])
        elif parts[0] == "minimize":
            message(wid, CHANGE_STATE, [3, 0, 0, 0, 0])
        elif parts[0] == "maximize":
            message(wid, STATE, [2, MAX_V, MAX_H, 1, 0])
        elif parts[0] == "snap" and len(parts) == 3:
            snap(wid, parts[2])
        elif parts[0] == "move" and len(parts) == 3 and parts[2].isdigit():
            message(wid, WM_DESKTOP, [int(parts[2]), 2, 0, 0, 0])
    while d.pending_events():
        e = d.next_event()
        # A window's own property changed: look at it again. The root's (the window list, the
        # active window, the workspace) are read on every publish anyway.
        if e.type == X.PropertyNotify and e.window.id != root.id:
            known.pop(e.window.id, None)
    publish()
"""
