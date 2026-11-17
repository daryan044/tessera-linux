// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.daryan.tessera.LauncherApplication
import com.daryan.tessera.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where [MEDIA_DAEMON] reports what's playing, one JSON object per line, in the X11 directory. */
const val MEDIA_FILE_NAME = "launcher-media"
/** Its command FIFO there: "play", "pause", "playpause", "next", "previous", "stop". */
const val MEDIA_COMMANDS_NAME = "launcher-mediacmd"

/** What a Linux media player says it's doing, over MPRIS, as [MEDIA_DAEMON] reports it. */
@Serializable
data class LinuxMedia(
    /** "Playing", "Paused", "Stopped", or empty once no player is left. */
    val status: String = "",
    /** The player's own name ("VLC media player"). */
    val player: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** The track's length in microseconds, MPRIS's unit; 0 when unknown. */
    val length: Long = 0,
    val next: Boolean = false,
    val previous: Boolean = false,
) {
    val playing get() = status == "Playing"
    /** Something to show controls for: a player that plays or waits, not one that stopped or left. */
    val active get() = status == "Playing" || status == "Paused"
}

/** Reads what [MEDIA_DAEMON] reported since last asked: only the latest counts. */
class MediaReports(private val dir: File) {
    private var read = 0L

    fun poll(): LinuxMedia? {
        val file = File(dir, MEDIA_FILE_NAME)
        if (!file.isFile || file.length() <= read) return null
        val text = runCatching {
            java.io.RandomAccessFile(file, "r").use { f ->
                f.seek(read)
                ByteArray((f.length() - read).coerceAtMost(1 shl 20).toInt()).also { f.readFully(it); read = f.length() }.decodeToString()
            }
        }.getOrNull() ?: return null
        return text.lines().lastOrNull { it.isNotBlank() }?.let(::parse)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(line: String): LinuxMedia? = runCatching { json.decodeFromString(LinuxMedia.serializer(), line) }.getOrNull()
    }
}

/**
 * Linux players on the phone's media controls (#26): each session with a player gets an Android
 * media session, so what it plays shows where Android's own players do (the notification shade,
 * the lock screen, a headset's buttons, a watch), and their play, pause and skip reach the
 * player. [command] sends one to a session's [MEDIA_DAEMON].
 */
class LinuxMediaControls(private val context: Context, private val command: (sessionId: String, String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val sessions = HashMap<String, MediaSession>()
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux media players", NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** [running]'s player changed what it does; called from any thread. */
    fun handle(running: RunningSession, media: LinuxMedia) = main.post {
        val id = running.session.id
        if (!media.active) return@post release(id)
        runCatching {
            val session = sessions.getOrPut(id) {
                MediaSession(context, "Tessera $id").apply {
                    setCallback(object : MediaSession.Callback() {
                        override fun onPlay() = command(id, "play")
                        override fun onPause() = command(id, "pause")
                        override fun onSkipToNext() = command(id, "next")
                        override fun onSkipToPrevious() = command(id, "previous")
                        override fun onStop() = command(id, "stop")
                    })
                    isActive = true
                }
            }
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, media.title.ifBlank { running.app.name })
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, media.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, media.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, if (media.length > 0) media.length / 1000 else -1)
                    .build(),
            )
            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
            if (media.next) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
            if (media.previous) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            session.setPlaybackState(
                PlaybackState.Builder().setActions(actions)
                    .setState(if (media.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            nm.notify(id, NOTIFICATION_ID, notification(running, media, session))
        }.onFailure { Log.w(TAG, "media controls for $id", it) }
    }

    /** [sessionId] ended, or its player did. */
    fun clear(sessionId: String) {
        main.post { release(sessionId) }
    }

    private fun release(id: String) {
        sessions.remove(id)?.let { s -> runCatching { s.isActive = false; s.release() } }
        runCatching { nm.cancel(id, NOTIFICATION_ID) }
    }

    private fun notification(running: RunningSession, media: LinuxMedia, session: MediaSession): Notification {
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        val buttons = buildList {
            if (media.previous) add(Triple(android.R.drawable.ic_media_previous, "Previous", "previous"))
            add(if (media.playing) Triple(android.R.drawable.ic_media_pause, "Pause", "pause") else Triple(android.R.drawable.ic_media_play, "Play", "play"))
            if (media.next) add(Triple(android.R.drawable.ic_media_next, "Next", "next"))
        }
        builder.setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(media.title.ifBlank { running.app.name })
            .setContentText(listOf(media.artist, media.album).filter { it.isNotBlank() }.joinToString(" · "))
            .setSubText(media.player.ifBlank { running.app.name })
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(media.playing)
            .setOnlyAlertOnce(true)
            .setContentIntent(window(running.session))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*buttons.indices.toList().toIntArray()))
        buttons.forEach { (icon, label, cmd) ->
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(icon, label, button(running.session.id, cmd)).build())
        }
        return builder.build()
    }

    private fun window(session: Session): PendingIntent {
        val intent = Intent().setClassName(context, X11Server.TAGGED_WINDOW)
            .setData(Uri.parse("termux-x11-window://${X11Server.windowTag(session)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, "media/${session.id}".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun button(sessionId: String, cmd: String): PendingIntent {
        val intent = Intent(context, Button::class.java).putExtra(SESSION_EXTRA, sessionId).putExtra(COMMAND_EXTRA, cmd)
        return PendingIntent.getBroadcast(context, "media/$sessionId/$cmd".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** A button of the media notification. */
    class Button : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getStringExtra(SESSION_EXTRA) ?: return
            val cmd = intent.getStringExtra(COMMAND_EXTRA)?.takeIf { it in COMMANDS } ?: return
            (context.applicationContext as? LauncherApplication)?.container?.sessions?.mediaCommand(session, cmd)
        }
    }

    companion object {
        private const val TAG = "LinuxMedia"
        private const val CHANNEL = "linux_media"
        /** Apart from the ids Linux apps give their own notifications, which count up from 1. */
        const val NOTIFICATION_ID = 0x4D505249
        private const val SESSION_EXTRA = "com.daryan.tessera.media_session"
        private const val COMMAND_EXTRA = "com.daryan.tessera.media_command"
        /** What [MEDIA_DAEMON] takes. */
        val COMMANDS = setOf("play", "pause", "playpause", "next", "previous", "stop")
    }
}

/**
 * Follows the MPRIS players on a GUI session's bus (with Python's GLib, as [NOTIFY_DAEMON]),
 * with the X11 directory as its argument: what the one in use is playing is appended to
 * [MEDIA_FILE_NAME] whenever it changes, and the launcher's commands come through
 * [MEDIA_COMMANDS_NAME] and go to that player. The one in use is one that's playing, else the
 * one it was.
 */
internal const val MEDIA_DAEMON = """
import json, os, signal, sys
from gi.repository import Gio, GLib

xdir = sys.argv[1]
out_path = os.path.join(xdir, "launcher-media")
cmd_path = os.path.join(xdir, "launcher-mediacmd")
PREFIX = "org.mpris.MediaPlayer2."
OBJ = "/org/mpris/MediaPlayer2"
ROOT = "org.mpris.MediaPlayer2"
PLAYER = "org.mpris.MediaPlayer2.Player"
PROPS = "org.freedesktop.DBus.Properties"
DBUS = "org.freedesktop.DBus"
METHODS = {"play": "Play", "pause": "Pause", "playpause": "PlayPause", "next": "Next", "previous": "Previous", "stop": "Stop"}
bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
players = {}
owners = {}
current = None
last = None

def text(v):
    if isinstance(v, (list, tuple)):
        return ", ".join(str(x) for x in v)
    return "" if v is None else str(v)

def report():
    global current, last
    playing = [n for n, p in players.items() if p.get("PlaybackStatus") == "Playing"]
    if playing and current not in playing:
        current = playing[0]
    if current not in players:
        current = next(iter(players), None)
    if current is None:
        o = {"status": ""}
    else:
        p = players[current]
        m = p.get("Metadata") or {}
        try:
            length = int(m.get("mpris:length") or 0)
        except (TypeError, ValueError):
            length = 0
        o = {"status": text(p.get("PlaybackStatus")), "player": text(p.get("Identity")), "title": text(m.get("xesam:title")),
             "artist": text(m.get("xesam:artist")), "album": text(m.get("xesam:album")), "length": length,
             "next": bool(p.get("CanGoNext")), "previous": bool(p.get("CanGoPrevious"))}
    line = json.dumps(o)
    if line == last:
        return
    last = line
    with open(out_path, "a") as f:
        f.write(line + "\n")
    try:
        os.chmod(out_path, 0o644)
    except OSError:
        pass

def get_all(name, iface):
    try:
        r = bus.call_sync(name, OBJ, PROPS, "GetAll", GLib.Variant("(s)", (iface,)), GLib.VariantType("(a{sv})"),
                          Gio.DBusCallFlags.NONE, 2000, None)
        return r.unpack()[0]
    except GLib.Error:
        return {}

def add(name, owner):
    props = get_all(name, PLAYER)
    props["Identity"] = get_all(name, ROOT).get("Identity", name[len(PREFIX):])
    players[name] = props
    owners[owner] = name
    report()

def remove(name):
    players.pop(name, None)
    for o in [o for o, n in owners.items() if n == name]:
        del owners[o]
    report()

def on_owner(c, sender, path, iface, sig, params):
    name, old, new = params.unpack()
    if not name.startswith(PREFIX):
        return
    if new:
        add(name, new)
    else:
        remove(name)

def on_props(c, sender, path, iface, sig, params):
    which, changed, gone = params.unpack()
    name = owners.get(sender)
    if which != PLAYER or name not in players:
        return
    players[name].update(changed)
    report()

pending = b""
def on_command(fd, condition):
    global pending
    try:
        pending += os.read(fd, 4096)
    except BlockingIOError:
        return True
    while b"\n" in pending:
        line, pending = pending.split(b"\n", 1)
        method = METHODS.get(line.decode(errors="replace").strip())
        if method and current in players:
            bus.call(current, OBJ, PLAYER, method, None, None, Gio.DBusCallFlags.NONE, -1, None, None, None)
    return True

bus.signal_subscribe(DBUS, DBUS, "NameOwnerChanged", "/org/freedesktop/DBus", None, Gio.DBusSignalFlags.NONE, on_owner)
bus.signal_subscribe(None, PROPS, "PropertiesChanged", OBJ, None, Gio.DBusSignalFlags.NONE, on_props)
try:
    names = bus.call_sync(DBUS, "/org/freedesktop/DBus", DBUS, "ListNames", None, GLib.VariantType("(as)"),
                          Gio.DBusCallFlags.NONE, 2000, None).unpack()[0]
except GLib.Error:
    names = []
for n in names:
    if n.startswith(PREFIX):
        try:
            owner = bus.call_sync(DBUS, "/org/freedesktop/DBus", DBUS, "GetNameOwner", GLib.Variant("(s)", (n,)),
                                  GLib.VariantType("(s)"), Gio.DBusCallFlags.NONE, 2000, None).unpack()[0]
        except GLib.Error:
            continue
        add(n, owner)
if os.path.exists(cmd_path):
    # Read-write so it never sees end-of-file between writers.
    GLib.io_add_watch(os.open(cmd_path, os.O_RDWR | os.O_NONBLOCK), GLib.PRIORITY_DEFAULT, GLib.IO_IN, on_command)
loop = GLib.MainLoop()
GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signal.SIGTERM, loop.quit)
loop.run()
"""
