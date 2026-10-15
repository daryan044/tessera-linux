// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

/**
 * Runs in a themed GUI session (see guestCommand), with [ThemeFiles]' directory as its argument:
 * - serves the desktop's settings portal (org.freedesktop.portal.Settings) on the session bus,
 *   with org.freedesktop.appearance's color-scheme and accent-color from the `portal` file.
 *   libadwaita, Qt, Firefox and Chromium read light or dark (and libadwaita the accent) there,
 *   and follow its SettingChanged signal;
 * - runs xsettingsd with the directory's settings, for GTK's theme, icons and window buttons;
 * - watches `portal` (the launcher writes it last), then signals the changes and has xsettingsd
 *   reload, so apps restyle without a restart.
 * Touches `ready` once it owns the portal's name, which the wrapper waits for before the app.
 * Only the Settings interface: apps outside Flatpak only use the portal for file pickers when
 * told to.
 */
internal const val THEME_DAEMON = """
import os, signal, subprocess, sys, shutil
from gi.repository import Gio, GLib

d = sys.argv[1]
state_file = os.path.join(d, "portal")
conf = os.path.join(d, "xsettingsd.conf")
PATH = "/org/freedesktop/portal/desktop"
IFACE = "org.freedesktop.portal.Settings"

def load():
    v = {}
    try:
        with open(state_file) as f:
            for line in f:
                k, _, val = line.strip().partition("=")
                if k:
                    v[k] = val
    except OSError:
        pass
    out = {"color-scheme": GLib.Variant("u", int(v.get("color-scheme", "0"))), "contrast": GLib.Variant("u", 0)}
    if v.get("accent"):
        out["accent-color"] = GLib.Variant("(ddd)", tuple(float(x) for x in v["accent"].split(",")))
    return {"org.freedesktop.appearance": out}

XML = ('<node><interface name="org.freedesktop.portal.Settings">'
       '<method name="ReadAll"><arg type="as" direction="in"/><arg type="a{sa{sv}}" direction="out"/></method>'
       '<method name="Read"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method>'
       '<method name="ReadOne"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method>'
       '<signal name="SettingChanged"><arg type="s"/><arg type="s"/><arg type="v"/></signal>'
       '<property name="version" type="u" access="read"/>'
       '</interface></node>')

current = load()
conn = None

def matches(ns, patterns):
    return not patterns or any(ns == p or (p.endswith("*") and ns.startswith(p[:-1])) for p in patterns)

def call(c, sender, path, iface, method, params, inv):
    if method == "ReadAll":
        res = {ns: kv for ns, kv in current.items() if matches(ns, params.unpack()[0])}
        inv.return_value(GLib.Variant("(a{sa{sv}})", (res,)))
        return
    ns, key = params.unpack()
    v = current.get(ns, {}).get(key)
    if v is None:
        inv.return_dbus_error("org.freedesktop.portal.Error.NotFound", "Requested setting not found")
    elif method == "Read":
        # The old call wraps the value in one more variant.
        inv.return_value(GLib.Variant("(v)", (GLib.Variant("v", v),)))
    else:
        inv.return_value(GLib.Variant("(v)", (v,)))

def prop(c, sender, path, iface, name):
    return GLib.Variant("u", 2)

xs = None
if shutil.which("xsettingsd") and os.path.exists(conf):
    xs = subprocess.Popen(["xsettingsd", "-c", conf], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def on_bus(c, name):
    global conn
    conn = c
    c.register_object(PATH, Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0], call, prop, None)

def on_name(c, name):
    open(os.path.join(d, "ready"), "w").close()

def stamp():
    try:
        return os.stat(state_file).st_mtime_ns
    except OSError:
        return None

seen = stamp()
def poll():
    global seen, current
    now = stamp()
    if now is None or now == seen:
        return True
    seen = now
    new = load()
    for ns, kv in new.items():
        for k, v in kv.items():
            if conn and current.get(ns, {}).get(k) != v:
                conn.emit_signal(None, PATH, IFACE, "SettingChanged", GLib.Variant("(ssv)", (ns, k, v)))
    current = new
    if xs:
        xs.send_signal(signal.SIGHUP)
    return True

Gio.bus_own_name(Gio.BusType.SESSION, "org.freedesktop.portal.Desktop", Gio.BusNameOwnerFlags.NONE, on_bus, on_name, None)
GLib.timeout_add(500, poll)
loop = GLib.MainLoop()
GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signal.SIGTERM, loop.quit)
try:
    loop.run()
finally:
    if xs:
        xs.terminate()
"""
