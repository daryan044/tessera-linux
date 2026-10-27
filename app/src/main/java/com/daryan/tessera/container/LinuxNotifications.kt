// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.daryan.tessera.LauncherApplication
import com.daryan.tessera.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where [NOTIFY_DAEMON] reports notifications, one JSON object per line, in the X11 directory. */
const val NOTIFY_FILE_NAME = "launcher-notify"
/** Its command FIFO there: "action <id> <key>", "dismissed <id>". */
const val NOTIFY_COMMANDS_NAME = "launcher-notifycmd"

/** A Linux app's notification ([op] "show") or its withdrawal ("close"), as [NOTIFY_DAEMON] reports it. */
@Serializable
data class LinuxNotification(
    val op: String,
    val id: Int,
    val app: String = "",
    val summary: String = "",
    val body: String = "",
    val actions: List<Action> = emptyList(),
    /** 0 low, 1 normal, 2 critical, as the spec has it. */
    val urgency: Int = 1,
) {
    @Serializable
    data class Action(val key: String, val label: String)
}

/** Reads what [NOTIFY_DAEMON] reported since last asked. */
class NotifyReports(private val dir: File) {
    private var read = 0L

    fun poll(): List<LinuxNotification> {
        val file = File(dir, NOTIFY_FILE_NAME)
        if (!file.isFile || file.length() <= read) return emptyList()
        val text = runCatching {
            java.io.RandomAccessFile(file, "r").use { f ->
                f.seek(read)
                ByteArray((f.length() - read).toInt()).also { f.readFully(it); read = f.length() }.decodeToString()
            }
        }.getOrNull() ?: return emptyList()
        return text.lines().filter { it.isNotBlank() }.mapNotNull { parse(it) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(line: String): LinuxNotification? =
            runCatching { json.decodeFromString(LinuxNotification.serializer(), line) }.getOrNull()
    }
}

/**
 * Shows Linux apps' notifications as Android's, under the app's session: tapping one opens its
 * window and tells the app (its "default" action), its buttons are the app's other actions, and
 * swiping it away tells the app it was dismissed. They go when the session ends.
 */
class LinuxNotifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val system = context.getSystemService(NotificationManager::class.java)
            system.createNotificationChannel(NotificationChannel(CHANNEL, "Linux apps", NotificationManager.IMPORTANCE_DEFAULT))
            system.createNotificationChannel(NotificationChannel(CHANNEL_URGENT, "Linux apps, urgent", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun handle(running: RunningSession, n: LinuxNotification) {
        val tag = running.session.id
        if (n.op == "close") return nm.cancel(tag, n.id)
        if (n.op != "show" || !nm.areNotificationsEnabled()) return
        val appName = n.app.ifBlank { running.app.name }
        val builder = NotificationCompat.Builder(context, if (n.urgency >= 2) CHANNEL_URGENT else CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(n.summary.ifBlank { appName })
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setSubText(appName)
            .setAutoCancel(true)
            .setContentIntent(open(running.session, n.id, "default"))
            .setDeleteIntent(dismissed(running.session, n.id))
        n.actions.filter { it.key != "default" && it.label.isNotBlank() }.take(3).forEach { a ->
            builder.addAction(0, a.label, open(running.session, n.id, a.key))
        }
        runCatching { nm.notify(tag, n.id, builder.build()) }
    }

    /** Takes [sessionId]'s notifications away: the app that sent them is gone. */
    fun clear(sessionId: String) {
        if (Build.VERSION.SDK_INT < 23) return
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.tag == sessionId }
            .forEach { nm.cancel(it.tag, it.id) }
    }

    /** Opens the session's window, which passes [action] on (see [ACTION_EXTRA]). */
    private fun open(session: Session, id: Int, action: String): PendingIntent {
        val intent = Intent().setClassName(context, X11Server.TAGGED_WINDOW)
            .setData(Uri.parse("termux-x11-window://${X11Server.windowTag(session)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ID_EXTRA, id)
            .putExtra(ACTION_EXTRA, action)
        return PendingIntent.getActivity(
            context, "${session.id}/$id/$action".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissed(session: Session, id: Int): PendingIntent {
        val intent = Intent(context, Dismissed::class.java).putExtra(SESSION_EXTRA, session.id).putExtra(ID_EXTRA, id)
        return PendingIntent.getBroadcast(
            context, "${session.id}/$id".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** A Linux notification swiped away: the app hears it was dismissed. */
    class Dismissed : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getStringExtra(SESSION_EXTRA) ?: return
            val id = intent.getIntExtra(ID_EXTRA, -1).takeIf { it >= 0 } ?: return
            (context.applicationContext as? LauncherApplication)?.container?.sessions?.notificationDismissed(session, id)
        }
    }

    companion object {
        private const val CHANNEL = "linux_apps"
        private const val CHANNEL_URGENT = "linux_apps_urgent"
        const val ID_EXTRA = "com.daryan.tessera.notify_id"
        /** The notification action a window's intent carries: "default" for a tap, else a button's key. */
        const val ACTION_EXTRA = "com.daryan.tessera.notify_action"
        private const val SESSION_EXTRA = "com.daryan.tessera.notify_session"
    }
}

/**
 * Serves org.freedesktop.Notifications on a GUI session's bus (with Python's GLib, as
 * [THEME_DAEMON]), with the X11 directory as its argument: each notification is appended to
 * [NOTIFY_FILE_NAME] for the launcher to show as Android's, and the launcher's word on it
 * (tapped, a button, swiped away) comes back through [NOTIFY_COMMANDS_NAME] as the spec's
 * ActionInvoked and NotificationClosed signals.
 */
internal const val NOTIFY_DAEMON = """
import itertools, json, os, signal, sys
from gi.repository import Gio, GLib

xdir = sys.argv[1]
out_path = os.path.join(xdir, "launcher-notify")
cmd_path = os.path.join(xdir, "launcher-notifycmd")
PATH = "/org/freedesktop/Notifications"
IFACE = "org.freedesktop.Notifications"
XML = ('<node><interface name="org.freedesktop.Notifications">'
       '<method name="Notify"><arg type="s" direction="in"/><arg type="u" direction="in"/><arg type="s" direction="in"/>'
       '<arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="as" direction="in"/>'
       '<arg type="a{sv}" direction="in"/><arg type="i" direction="in"/><arg type="u" direction="out"/></method>'
       '<method name="CloseNotification"><arg type="u" direction="in"/></method>'
       '<method name="GetCapabilities"><arg type="as" direction="out"/></method>'
       '<method name="GetServerInformation"><arg type="s" direction="out"/><arg type="s" direction="out"/>'
       '<arg type="s" direction="out"/><arg type="s" direction="out"/></method>'
       '<signal name="NotificationClosed"><arg type="u"/><arg type="u"/></signal>'
       '<signal name="ActionInvoked"><arg type="u"/><arg type="s"/></signal>'
       '</interface></node>')
ids = itertools.count(1)
conn = None

def report(o):
    with open(out_path, "a") as f:
        f.write(json.dumps(o) + "\n")
    try:
        os.chmod(out_path, 0o644)
    except OSError:
        pass

def closed(nid, reason):
    # Reasons: 1 expired, 2 dismissed by the user, 3 closed by a call.
    if conn:
        conn.emit_signal(None, PATH, IFACE, "NotificationClosed", GLib.Variant("(uu)", (nid, reason)))

def call(c, sender, path, iface, method, params, inv):
    if method == "Notify":
        app, replaces, icon, summary, body, actions, hints, timeout = params.unpack()
        nid = replaces or next(ids)
        pairs = [{"key": actions[i], "label": actions[i + 1]} for i in range(0, len(actions) - 1, 2)]
        urgency = hints.get("urgency", 1)
        report({"op": "show", "id": nid, "app": app, "summary": summary, "body": body, "actions": pairs,
                "urgency": urgency if isinstance(urgency, int) else 1})
        inv.return_value(GLib.Variant("(u)", (nid,)))
    elif method == "CloseNotification":
        nid = params.unpack()[0]
        report({"op": "close", "id": nid})
        closed(nid, 3)
        inv.return_value(None)
    elif method == "GetCapabilities":
        inv.return_value(GLib.Variant("(as)", (["body", "actions"],)))
    else:
        inv.return_value(GLib.Variant("(ssss)", ("Tessera", "Tessera", "1.0", "1.2")))

def on_bus(c, name):
    global conn
    conn = c
    c.register_object(PATH, Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0], call, None, None)

pending = b""
def on_command(fd, condition):
    global pending
    try:
        pending += os.read(fd, 4096)
    except BlockingIOError:
        return True
    while b"\n" in pending:
        line, pending = pending.split(b"\n", 1)
        parts = line.decode(errors="replace").split()
        if len(parts) == 3 and parts[0] == "action" and parts[1].isdigit():
            if conn:
                conn.emit_signal(None, PATH, IFACE, "ActionInvoked", GLib.Variant("(us)", (int(parts[1]), parts[2])))
            closed(int(parts[1]), 2)
        elif len(parts) == 2 and parts[0] == "dismissed" and parts[1].isdigit():
            closed(int(parts[1]), 2)
    return True

Gio.bus_own_name(Gio.BusType.SESSION, IFACE, Gio.BusNameOwnerFlags.NONE, on_bus, None, None)
if os.path.exists(cmd_path):
    # Read-write so it never sees end-of-file between writers.
    GLib.io_add_watch(os.open(cmd_path, os.O_RDWR | os.O_NONBLOCK), GLib.PRIORITY_DEFAULT, GLib.IO_IN, on_command)
loop = GLib.MainLoop()
GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signal.SIGTERM, loop.quit)
loop.run()
"""
