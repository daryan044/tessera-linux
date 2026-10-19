// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File

/** The desktop helper's command FIFO in the session's X11 directory; see [DESKTOP_HELPER]. */
const val DESKTOP_COMMANDS_NAME = "launcher-desktop"
/** What is on the desktop (~/Desktop), as [DESKTOP_HELPER] lists it. */
const val DESKTOP_ITEMS_NAME = "launcher-desktop-items"
/** Taps and menus on the desktop's icons, one per line, as [DESKTOP_HELPER] reports them. */
const val DESKTOP_EVENTS_NAME = "launcher-desktop-events"

/** A tap ([menu] false) or a long press or right click on the desktop icon at [path]; [x], [y] in X pixels. */
data class DesktopEvent(val path: String, val menu: Boolean, val x: Int = 0, val y: Int = 0)

internal fun parseEvent(line: String): DesktopEvent? = runCatching {
    val (kind, arg) = line.split(" ", limit = 2).takeIf { it.size == 2 } ?: return null
    when (kind) {
        "open" -> DesktopEvent(Json.decodeFromString(JsonPrimitive.serializer(), arg).content, menu = false)
        "menu" -> {
            val o = Json.decodeFromString(JsonObject.serializer(), arg)
            DesktopEvent(
                (o["path"] as JsonPrimitive).content, menu = true,
                x = (o["x"] as JsonPrimitive).content.toInt(), y = (o["y"] as JsonPrimitive).content.toInt(),
            )
        }
        else -> null
    }
}.getOrNull()

/** A file, folder or app shortcut on the desktop; [app] is a shortcut's launcher app id. */
@Serializable
data class DesktopItem(val name: String, val path: String, val kind: String, val app: String = "") {
    val isShortcut get() = kind == "app"
    val isLink get() = kind == "link"
    val isFolder get() = kind == "dir"
}
private const val WALLPAPER_NAME = "desktop-wallpaper.raw"

/**
 * A desktop session's side of things, in its X11 directory [dir]: what the taskbar asks of
 * [DESKTOP_HELPER] (open an app, keep room for the dock, show a wallpaper), and its window
 * manager's theme ([writeTheme]).
 */
class DesktopFiles(private val dir: File) {
    /**
     * Runs [argv] inside the desktop, beside what is already open there. Each of these returns
     * false when the helper isn't listening (yet: it starts with the desktop).
     */
    fun launch(argv: List<String>): Boolean =
        send("launch " + Json.encodeToString(JsonArray.serializer(), JsonArray(argv.map(::JsonPrimitive))))

    /** What is on the desktop now; empty until the helper has looked. */
    fun items(): List<DesktopItem> = runCatching {
        itemsJson.decodeFromString(ListSerializer(DesktopItem.serializer()), File(dir, DESKTOP_ITEMS_NAME).readText())
    }.getOrDefault(emptyList())

    /** Opens [path] with its app inside the desktop; a .desktop file runs its command. */
    fun open(path: String): Boolean = send("open " + Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(path)))

    /** Puts a shortcut that runs [argv] on the desktop, named [name], for the launcher's app [id]. */
    fun addShortcut(id: String, name: String, argv: List<String>): Boolean = send(
        "shortcut " + Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "argv" to JsonArray(argv.map(::JsonPrimitive)))),
        ),
    )

    /**
     * Puts a copy of [content] on the desktop as [name]: written to the X11 directory, which
     * both sides reach, then moved into ~/Desktop by the helper. Off the main thread.
     */
    fun import(name: String, content: java.io.InputStream): Boolean {
        val incoming = File(dir, INCOMING).apply { mkdirs() }
        val file = File.createTempFile("drop", null, incoming)
        file.outputStream().use { content.copyTo(it) }
        file.setReadable(true, false)
        val spec = JsonObject(mapOf("from" to JsonPrimitive("/tmp/.X11-unix/$INCOMING/${file.name}"), "name" to JsonPrimitive(name)))
        return send("import " + Json.encodeToString(JsonObject.serializer(), spec)).also { if (!it) file.delete() }
    }

    /** Takes a shortcut off the desktop; a file or folder goes to the trash. */
    fun remove(path: String): Boolean = send("remove " + Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(path)))

    /** Keeps [px] X pixels along the bottom, or the [left] edge, clear of maximized windows. */
    fun reserve(left: Boolean, px: Int): Boolean = send("strut ${if (left) "left" else "bottom"} $px")

    /**
     * Shows [picture] behind the windows, cropped to fill a [width] × [height] X screen, with
     * [icons] (the desktop's icons, drawn at the screen's proportions) over it, and tells the
     * helper where each icon is, in X pixels, by its path. Run off the main thread: it writes
     * the whole screen's pixels.
     */
    fun wallpaper(
        picture: Bitmap,
        width: Int,
        height: Int,
        icons: Bitmap? = null,
        places: Map<String, android.graphics.Rect> = emptyMap(),
    ): Boolean {
        // Not the whole screen's pixels for nobody: the helper may not be up yet.
        if (width <= 0 || height <= 0 || !File(dir, DESKTOP_COMMANDS_NAME).exists()) return false
        val scale = maxOf(width.toFloat() / picture.width, height.toFloat() / picture.height)
        val fitted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(fitted).apply {
            save()
            translate((width - picture.width * scale) / 2, (height - picture.height * scale) / 2)
            scale(scale, scale)
            drawBitmap(picture, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            restore()
            icons?.let { drawBitmap(it, null, android.graphics.Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG)) }
        }
        val spots = JsonArray(
            places.map { (path, r) ->
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(path), "x" to JsonPrimitive(r.left), "y" to JsonPrimitive(r.top),
                        "w" to JsonPrimitive(r.width()), "h" to JsonPrimitive(r.height()),
                    ),
                )
            },
        )
        send("icons " + Json.encodeToString(JsonArray.serializer(), spots))
        // The X server's 24-bit pixels, as XPutImage takes them: B, G, R, unused.
        val pixels = IntArray(width * height)
        fitted.getPixels(pixels, 0, width, 0, 0, width, height)
        fitted.recycle()
        val bytes = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            bytes[i * 4] = p.toByte()
            bytes[i * 4 + 1] = (p shr 8).toByte()
            bytes[i * 4 + 2] = (p shr 16).toByte()
        }
        val file = File(dir, WALLPAPER_NAME)
        val tmp = File(dir, "$WALLPAPER_NAME.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
        return send("wallpaper /tmp/.X11-unix/$WALLPAPER_NAME $width $height")
    }

    private fun send(line: String) = sendLine(File(dir, DESKTOP_COMMANDS_NAME), line)

    /** Reads what the helper reported since last asked: taps and menu requests on the icons. */
    class Events(private val dir: File) {
        private var read = 0L

        fun poll(): List<DesktopEvent> {
            val file = File(dir, DESKTOP_EVENTS_NAME)
            if (!file.isFile || file.length() <= read) return emptyList()
            val text = runCatching {
                java.io.RandomAccessFile(file, "r").use { f ->
                    f.seek(read)
                    ByteArray((f.length() - read).toInt()).also { f.readFully(it); read = f.length() }.decodeToString()
                }
            }.getOrNull() ?: return emptyList()
            return text.lines().mapNotNull(::parseEvent)
        }
    }

    companion object {
        private val itemsJson = Json { ignoreUnknownKeys = true }
        private const val INCOMING = "incoming"
        private const val THEME_DIR = "desktop"
        /** Names the theme in use; [DESKTOP_HELPER] sets xfwm4's theme to the path in it. */
        private const val CURRENT = "current"

        /**
         * Writes xfwm4's theme for [palette] under the session's theme directory [themeDir], a
         * new generation each time (the window manager only reloads a theme whose path
         * changes), and points [CURRENT] at it. [titlePx]: the title bar's height in X pixels.
         *
         * It is Circela's look (addy-dclxvi's xfwm4 theme) in the phone's colors: a title bar
         * the color of the apps' own header bars with the window's name in it, ring buttons, and
         * close as a filled accent circle on the focused window. The frame is wider than
         * Circela's, in the bar's color, so a finger can catch an edge to resize.
         */
        fun writeTheme(themeDir: File, palette: Palette, titlePx: Int) {
            val root = File(themeDir, THEME_DIR)
            val existing = root.list().orEmpty().mapNotNull { it.toIntOrNull() }
            val generation = (existing.maxOrNull() ?: 0) + 1
            val out = File(root, "$generation").apply { mkdirs() }
            themeFiles(palette, titlePx.coerceIn(24, 160)).forEach { (name, bytes) -> File(out, name).writeBytes(bytes) }
            val current = File(root, CURRENT)
            val tmp = File(root, "$CURRENT.tmp")
            // The theme, then its title font: sized to the bar, which xfwm4 takes from settings.
            tmp.writeText("${ThemeFiles.GUEST_DIR}/$THEME_DIR/$generation\n${titleFont(titlePx.coerceIn(24, 160))}\n")
            tmp.renameTo(current)
            // The one before stays: xfwm4 may still be reading it.
            existing.filter { it < generation - 1 }.forEach { File(root, "$it").deleteRecursively() }
        }

        /** The title's font for a [title]-pixel bar, in points at X's 96 dpi. */
        internal fun titleFont(title: Int) = "Sans Bold ${(title * 0.30f * 72 / 96).toInt().coerceAtLeast(6)}"

        /** How wide the frame's sides and bottom are, for a [title]-pixel bar: easy to catch. */
        internal fun edgeFor(title: Int) = (title * 0.2f).toInt().coerceAtLeast(4)

        internal fun themeFiles(p: Palette, title: Int): Map<String, ByteArray> {
            val bar = opaque(p.headerbar)
            val edge = edgeFor(title)
            val button = (title * 1.15f).toInt()
            val files = linkedMapOf<String, ByteArray>()
            files["themerc"] = themerc(p, title).toByteArray()
            for (active in listOf(true, false)) {
                val state = if (active) "active" else "inactive"
                for (i in 1..5) files["title-$i-$state.png"] = png(1, title) { drawColor(bar) }
                files["top-left-$state.png"] = png(edge, title) { drawColor(bar) }
                files["top-right-$state.png"] = png(edge, title) { drawColor(bar) }
                listOf("left", "right").forEach { files["$it-$state.png"] = png(edge, 1) { drawColor(bar) } }
                listOf("bottom", "bottom-left", "bottom-right").forEach { files["$it-$state.png"] = png(edge, edge) { drawColor(bar) } }
            }
            // Each button in each of xfwm4's states: active and inactive window, hovered, pressed.
            for (state in listOf("active", "inactive", "prelight", "pressed")) {
                val focused = state != "inactive"
                files["hide-$state.png"] = png(button, title) { buttonFace(p, bar, state); ring(p, title, focused, filled = false) }
                files["maximize-$state.png"] = png(button, title) { buttonFace(p, bar, state); ring(p, title, focused, filled = false) }
                files["maximize-toggled-$state.png"] = png(button, title) { buttonFace(p, bar, state); ring(p, title, focused, filled = true) }
                files["close-$state.png"] = png(button, title) { buttonFace(p, bar, state); close(p, title, focused) }
            }
            return files
        }

        private fun themerc(p: Palette, title: Int): String {
            val active = "#%06x".format(p.onWindow and 0xFFFFFF)
            val inactive = "#%06x".format(blend(p.onWindow, p.headerbar, 0.55f))
            return """
                |button_offset=${title / 6}
                |button_spacing=0
                |full_width_title=true
                |title_shadow_active=false
                |title_shadow_inactive=false
                |title_horizontal_offset=0
                |title_vertical_offset_active=0
                |title_vertical_offset_inactive=0
                |active_text_color=$active
                |inactive_text_color=$inactive
                |show_app_icon=false
                |maximized_offset=0
                |shadow_delta_height=${title / 4}
                |shadow_delta_width=0
                |shadow_delta_x=0
                |shadow_delta_y=-${title / 8}
                |shadow_opacity=${if (p.dark) 80 else 45}
                |""".trimMargin()
        }

        /** The round highlight behind a hovered or pressed button, as Android's ripples leave. */
        private fun Canvas.buttonFace(p: Palette, bar: Int, state: String) {
            drawColor(bar)
            val alpha = when (state) {
                "prelight" -> 0.10f
                "pressed" -> 0.18f
                else -> return
            }
            val r = minOf(width, height) * 0.40f
            drawCircle(width / 2f, height / 2f, r, fill(p.onWindow, alpha))
        }

        /** Minimize and maximize: Circela's rings; a maximized window's has a dot in it. */
        private fun Canvas.ring(p: Palette, title: Int, focused: Boolean, filled: Boolean) {
            val r = title * 0.17f
            val stroke = title * 0.065f
            val color = stroke(p.onWindow, if (focused) 0.75f else 0.38f, stroke)
            drawCircle(width / 2f, height / 2f, r, color)
            if (filled) drawCircle(width / 2f, height / 2f, r * 0.45f, fill(p.onWindow, if (focused) 0.75f else 0.38f))
        }

        /** Close: a filled circle in the accent with a ring in it while focused; a ring otherwise. */
        private fun Canvas.close(p: Palette, title: Int, focused: Boolean) {
            if (!focused) return ring(p, title, focused = false, filled = false)
            drawCircle(width / 2f, height / 2f, title * 0.30f, fill(p.accent, 1f))
            drawCircle(width / 2f, height / 2f, title * 0.12f, stroke(p.onAccent, 1f, title * 0.06f))
        }

        private fun fill(rgb: Int, alpha: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(rgb, alpha)
            style = Paint.Style.FILL
        }

        private fun stroke(rgb: Int, alpha: Float, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(rgb, alpha)
            style = Paint.Style.STROKE
            strokeWidth = width
        }

        private fun opaque(rgb: Int) = withAlpha(rgb, 1f)

        /** [a] over [b] at [amount] of [a], as 0xRRGGBB. */
        private fun blend(a: Int, b: Int, amount: Float): Int {
            fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
            return listOf(16, 8, 0).fold(0) { acc, sh ->
                acc or ((ch(a, sh) * amount + ch(b, sh) * (1 - amount)).toInt() shl sh)
            }
        }

        private fun withAlpha(rgb: Int, alpha: Float) = ((alpha * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

        private fun png(width: Int, height: Int, draw: Canvas.() -> Unit): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).draw()
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }
}

/**
 * A desktop session ([PermissionManifest.desktop]), run by [GUI_WRAPPER] in place of an app:
 * [DESKTOP_HELPER] beside the desktop's window manager (the rest of the arguments), for as long
 * as the window manager runs. Its environment is published for Flathub apps, which run beside
 * it ([SessionManager.launchFlatpakInDesktop]).
 */
internal const val DESKTOP_SESSION = """
helper=${'$'}1; shift
# What a Flathub app opened in the desktop takes from it (its bus, its theme), as FLATPAK_HOLD
# publishes it for an app's own session.
env > /tmp/.X11-unix/$FLATPAK_ENV_FILE.tmp && mv /tmp/.X11-unix/$FLATPAK_ENV_FILE.tmp /tmp/.X11-unix/$FLATPAK_ENV_FILE
python3 -c "${'$'}helper" /tmp/.X11-unix & dh=${'$'}!
"${'$'}@"; status=${'$'}?
kill ${'$'}dh 2>/dev/null
exit ${'$'}status
"""

/**
 * Runs in a desktop session next to xfwm4, taking the taskbar's commands one per line from
 * [DESKTOP_COMMANDS_NAME] in the X11 directory (its argument):
 * - "launch <JSON argv>" starts an app in the desktop, in a session of its own;
 * - "strut left|bottom <px>" keeps that much of the edge for the dock, as a dock window's strut,
 *   so maximized windows stop at it;
 * - "wallpaper <file> <width> <height>" shows the file's B, G, R, x pixels behind the windows,
 *   on a window of the desktop type under every other (the launcher draws the icons into it);
 * - "icons <JSON [{path, x, y, w, h}]>" says where those icons are: a tap on one is reported as
 *   "open <JSON path>", a right click or a long press as "menu <JSON {path, x, y}>", one per
 *   line appended to [DESKTOP_EVENTS_NAME];
 * - "open <JSON path>" opens a file or folder on the desktop with its app (a .desktop file runs);
 * - "shortcut <JSON {id, name, argv}>" puts an app on the desktop as a .desktop file;
 * - "remove <JSON path>" takes a shortcut off the desktop, or moves a file there to the trash;
 * Files and web links dragged from Linux apps onto the desktop window (XDND) are copied there.
 * - "import <JSON {from, name}>" moves a file dropped from Android (copied into the X11
 *   directory's incoming folder first) onto the desktop, under a name not taken yet.
 * It keeps [DESKTOP_ITEMS_NAME] to what is in ~/Desktop (the XDG desktop folder), as JSON.
 * It also sets xfwm4 up for touch the first time (snap to edges, center new windows, shadows;
 * the user's own changes are kept after that) and sets its theme to the one [DesktopFiles.writeTheme]
 * names, again whenever that changes.
 */
internal const val DESKTOP_HELPER = """
import json, os, re, select, shlex, shutil, subprocess, sys, time

xdir = sys.argv[1]
cmd_path = os.path.join(xdir, "launcher-desktop")
theme = os.environ.get("TESSERA_THEME")
current = os.path.join(theme, "desktop", "current") if theme else None
null = subprocess.DEVNULL

try:
    from Xlib import X, Xatom, display
    from Xlib.protocol import event
    d = display.Display()
    root = d.screen().root
    A = d.intern_atom
except Exception as e:
    print("desktop: no python3-xlib (%s); no wallpaper or dock space" % e, file=sys.stderr)
    d = None

def xfconf(prop, kind, value):
    try:
        return subprocess.call(["xfconf-query", "-c", "xfwm4", "-p", "/general/" + prop, "-n", "-t", kind, "-s", str(value)],
                               stdout=null, stderr=null)
    except OSError:
        return 1

def first_run():
    marker = os.path.expanduser("~/.config/tessera/desktop-configured-2")
    if os.path.exists(marker) or not shutil.which("xfconf-query"):
        return
    # xfconfd comes up with xfwm4; give it a few tries.
    for _ in range(20):
        if xfconf("workspace_count", "int", 1) == 0:
            break
        time.sleep(0.5)
    else:
        return
    for prop, kind, value in [
        ("button_layout", "string", "|HMC"),
        ("title_alignment", "string", "center"),
        ("placement_mode", "string", "center"),
        ("placement_ratio", "int", 100),
        ("tile_on_move", "bool", "true"),
        ("snap_to_border", "bool", "true"),
        ("snap_to_windows", "bool", "true"),
        ("snap_width", "int", 24),
        ("double_click_action", "string", "maximize"),
        ("borderless_maximize", "bool", "true"),
        ("use_compositing", "bool", "true"),
        ("show_frame_shadow", "bool", "true"),
        ("show_popup_shadow", "bool", "true"),
        ("show_dock_shadow", "bool", "false"),
        ("box_move", "bool", "false"),
        ("box_resize", "bool", "false"),
    ]:
        xfconf(prop, kind, value)
    os.makedirs(os.path.dirname(marker), exist_ok=True)
    open(marker, "w").close()

theme_seen = None
def follow_theme():
    global theme_seen
    if not current:
        return
    try:
        text = open(current).read()
    except OSError:
        return
    lines = text.split("\n")
    if lines[0] and text != theme_seen and xfconf("theme", "string", lines[0]) == 0:
        if len(lines) > 1 and lines[1]:
            xfconf("title_font", "string", lines[1])
        theme_seen = text

seen = set()
def give_title_bars():
    # Apps that ask for no frame at all (Electron's and Chrome's own title bars, Qt's frameless
    # windows) get xfwm4's: on a phone there is no other way to move, size or close them. GTK's
    # header bars (they mark their frame extents) and splash screens keep their own.
    if d is None:
        return
    clients = root.get_full_property(A("_NET_CLIENT_LIST"), X.AnyPropertyType)
    ids = list(clients.value) if clients else []
    for wid in ids:
        if wid in seen:
            continue
        seen.add(wid)
        try:
            w = d.create_resource_object("window", wid)
            hints = w.get_full_property(A("_MOTIF_WM_HINTS"), X.AnyPropertyType)
            if not hints or len(hints.value) < 3 or not (hints.value[0] & 2) or hints.value[2] != 0:
                continue
            if w.get_full_property(A("_GTK_FRAME_EXTENTS"), X.AnyPropertyType) is not None:
                continue
            types = w.get_full_property(A("_NET_WM_WINDOW_TYPE"), X.AnyPropertyType)
            if types and A("_NET_WM_WINDOW_TYPE_NORMAL") not in list(types.value):
                continue
            if w.get_wm_transient_for() is not None:
                continue
            w.delete_property(A("_MOTIF_WM_HINTS"))
            d.flush()
        except Exception:
            pass

dock = None
def strut(side, px):
    global dock
    if d is None:
        return
    g = root.get_geometry()
    if dock is None:
        # A pixel in the corner that shows the wallpaper through; its strut is what counts.
        dock = root.create_window(0, 0, 1, 1, 0, d.screen().root_depth, X.InputOutput, X.CopyFromParent,
                                  background_pixmap=X.ParentRelative)
        dock.change_property(A("_NET_WM_WINDOW_TYPE"), Xatom.ATOM, 32, [A("_NET_WM_WINDOW_TYPE_DOCK")])
        dock.change_property(A("_NET_WM_STATE"), Xatom.ATOM, 32,
                             [A("_NET_WM_STATE_SKIP_TASKBAR"), A("_NET_WM_STATE_SKIP_PAGER"), A("_NET_WM_STATE_BELOW")])
        dock.set_wm_name("Tessera dock")
        dock.map()
    if side == "left":
        values = [px, 0, 0, 0, 0, g.height - 1, 0, 0, 0, 0, 0, 0]
        dock.configure(x=0, y=0)
    else:
        values = [0, 0, 0, px, 0, 0, 0, 0, 0, 0, 0, g.width - 1]
        dock.configure(x=0, y=g.height - 1)
    dock.change_property(A("_NET_WM_STRUT_PARTIAL"), Xatom.CARDINAL, 32, values)
    dock.change_property(A("_NET_WM_STRUT"), Xatom.CARDINAL, 32, values[:4])
    d.flush()

paper = None
desk = None
def ensure_desk(w, h):
    # The desktop itself: a window of the desktop type (xfwm4 keeps it under every other one,
    # undecorated), showing the wallpaper with the icons drawn in, and hearing taps on them.
    global desk
    if desk is None:
        desk = root.create_window(0, 0, w, h, 0, d.screen().root_depth, X.InputOutput, X.CopyFromParent,
                                  event_mask=X.ButtonPressMask | X.ButtonReleaseMask)
        desk.change_property(A("_NET_WM_WINDOW_TYPE"), Xatom.ATOM, 32, [A("_NET_WM_WINDOW_TYPE_DESKTOP")])
        desk.change_property(A("_NET_WM_STATE"), Xatom.ATOM, 32,
                             [A("_NET_WM_STATE_BELOW"), A("_NET_WM_STATE_SKIP_TASKBAR"), A("_NET_WM_STATE_SKIP_PAGER"), A("_NET_WM_STATE_STICKY")])
        desk.set_wm_name("Desktop")
        desk.set_wm_class("tessera-desktop", "Tessera-desktop")
        # Takes drops from Linux apps (XDND version 5): files, links.
        desk.change_property(A("XdndAware"), Xatom.ATOM, 32, [5])
        desk.map()
    else:
        desk.configure(x=0, y=0, width=w, height=h)

def wallpaper(path, w, h):
    global paper
    if d is None:
        return
    s = d.screen()
    data = open(path, "rb").read()
    if len(data) < w * h * 4:
        return
    pm = root.create_pixmap(w, h, s.root_depth)
    gc = pm.create_gc()
    # In strips that fit in a request.
    rows = max(1, 200000 // (w * 4))
    for y in range(0, h, rows):
        n = min(rows, h - y)
        pm.put_image(gc, 0, y, w, n, X.ZPixmap, s.root_depth, 0, data[y * w * 4:(y + n) * w * 4])
    gc.free()
    root.change_attributes(background_pixmap=pm)
    # Where compositors (xfwm4's too) look for the background.
    root.change_property(A("_XROOTPMAP_ID"), Xatom.PIXMAP, 32, [pm.id])
    root.change_property(A("ESETROOT_PMAP_ID"), Xatom.PIXMAP, 32, [pm.id])
    root.clear_area()
    ensure_desk(w, h)
    desk.change_attributes(background_pixmap=pm)
    desk.clear_area()
    if paper is not None:
        paper.free()
    paper = pm
    d.flush()

# Where each icon is on the desktop, in X pixels, as the launcher drew them.
icons = []
events_path = os.path.join(xdir, "launcher-desktop-events")

def emit(line):
    # For the launcher, which opens what was tapped (Flathub apps need it) and shows menus.
    with open(events_path, "a") as f:
        f.write(line + "\n")
    os.chmod(events_path, 0o644)

def hit(x, y):
    for i in icons:
        if i["x"] <= x < i["x"] + i["w"] and i["y"] <= y < i["y"] + i["h"]:
            return i["path"]
    return None

from urllib.parse import unquote, urlparse

def unique(name):
    base, ext = os.path.splitext(name)
    dest, n = os.path.join(desktop_dir, name), 2
    while os.path.exists(dest):
        dest = os.path.join(desktop_dir, "%s (%d)%s" % (base, n, ext))
        n += 1
    return dest

def take(uris):
    # What was dropped from a Linux app: files are copied here, web links become links.
    for uri in uris:
        u = urlparse(uri)
        if u.scheme == "file":
            src = unquote(u.path)
            if os.path.dirname(os.path.realpath(src)) == desktop_dir or not os.path.exists(src):
                continue
            dest = unique(os.path.basename(src.rstrip("/")) or "Dropped")
            if os.path.isdir(src):
                shutil.copytree(src, dest, symlinks=True)
            else:
                shutil.copy2(src, dest)
        elif u.scheme in ("http", "https"):
            name = (u.netloc + u.path).strip("/").replace("/", " ")[:60] or "Link"
            path = unique(name + ".desktop")
            with open(path, "w") as f:
                f.write("[Desktop Entry]\nType=Link\nName=%s\nURL=%s\n" % (name, uri))

drag = {}
def xdnd(e):
    kind = e.client_type
    data = e.data[1]
    if kind == A("XdndEnter"):
        drag.clear()
        drag["source"] = data[0]
    elif kind == A("XdndPosition"):
        reply(data[0], "XdndStatus", [desk.id, 1, 0, 0, A("XdndActionCopy")])
    elif kind == A("XdndLeave"):
        drag.clear()
    elif kind == A("XdndDrop"):
        drag["source"] = data[0]
        desk.convert_selection(A("XdndSelection"), A("text/uri-list"), A("TESSERA_DROP"), data[2])

def reply(window, kind, data):
    target = d.create_resource_object("window", window)
    target.send_event(event.ClientMessage(window=target, client_type=A(kind), data=(32, data)))
    d.flush()

def dropped(e):
    source = drag.pop("source", None)
    ok = 0
    if e.property != X.NONE:
        p = desk.get_full_property(A("TESSERA_DROP"), X.AnyPropertyType, sizehint=1 << 20)
        if p is not None:
            text = p.value.decode(errors="replace") if isinstance(p.value, bytes) else str(p.value)
            uris = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
            try:
                take(uris)
                ok = 1
            except OSError as err:
                print("desktop: couldn't take the drop: %s" % err, file=sys.stderr)
    if source:
        reply(source, "XdndFinished", [desk.id, ok, A("XdndActionCopy") if ok else 0, 0, 0])

press = None
def on_event(e):
    global press
    if desk is None or getattr(e, "window", None) is None or e.window.id != desk.id:
        return
    if e.type == X.ClientMessage:
        return xdnd(e)
    if e.type == X.SelectionNotify:
        return dropped(e)
    if e.type == X.ButtonPress and e.detail == 1:
        press = (e.event_x, e.event_y, e.time)
    elif e.type == X.ButtonPress and e.detail == 3:
        # A right click, or Termux:X11's touch and hold: the icon's menu.
        p = hit(e.event_x, e.event_y)
        if p:
            emit("menu " + json.dumps({"path": p, "x": e.event_x, "y": e.event_y}))
    elif e.type == X.ButtonRelease and e.detail == 1 and press:
        x0, y0, t0 = press
        press = None
        if abs(e.event_x - x0) > 24 or abs(e.event_y - y0) > 24:
            return
        p = hit(e.event_x, e.event_y)
        if p and (e.time - t0) % 0x100000000 > 600:
            emit("menu " + json.dumps({"path": p, "x": e.event_x, "y": e.event_y}))
        elif p:
            emit("open " + json.dumps(p))

def launch(argv):
    try:
        subprocess.Popen(argv, start_new_session=True, stdin=null, cwd=os.path.expanduser("~"))
    except OSError as e:
        print("desktop: couldn't start %s: %s" % (argv[0] if argv else "?", e), file=sys.stderr)

def find_desktop():
    try:
        p = subprocess.run(["xdg-user-dir", "DESKTOP"], capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        p = ""
    home = os.path.expanduser("~")
    if not p or os.path.realpath(p) == os.path.realpath(home):
        p = os.path.join(home, "Desktop")
    os.makedirs(p, exist_ok=True)
    return os.path.realpath(p)

desktop_dir = find_desktop()
listing_path = os.path.join(xdir, "launcher-desktop-items")
listed = None

def entry(path):
    fields, section = {}, None
    try:
        with open(path, errors="replace") as f:
            for line in f:
                line = line.strip()
                if line.startswith("["):
                    section = line
                elif section == "[Desktop Entry]" and "=" in line:
                    k, v = line.split("=", 1)
                    fields.setdefault(k.strip(), v.strip())
    except OSError:
        pass
    return fields

def publish_desktop():
    global listed
    items = []
    try:
        names = os.listdir(desktop_dir)
    except OSError:
        names = []
    for n in names:
        if n.startswith("."):
            continue
        p = os.path.join(desktop_dir, n)
        if n.endswith(".desktop"):
            e = entry(p)
            kind = "link" if e.get("Type") == "Link" else "app"
            items.append({"name": e.get("Name", n[:-8]), "path": p, "kind": kind, "app": e.get("X-Tessera-Id", "")})
        else:
            items.append({"name": n, "path": p, "kind": "dir" if os.path.isdir(p) else "file"})
    order = {"app": 0, "link": 1, "dir": 2, "file": 3}
    items.sort(key=lambda i: (order[i["kind"]], i["name"].lower()))
    text = json.dumps(items)
    if text == listed:
        return
    listed = text
    tmp = listing_path + ".tmp"
    with open(tmp, "w") as f:
        f.write(text)
    os.chmod(tmp, 0o644)
    os.replace(tmp, listing_path)

FIELD = re.compile(r"%[fFuUdDnNickvm]")

def open_item(path):
    if path.endswith(".desktop"):
        e = entry(path)
        if e.get("Type") == "Link" and e.get("URL"):
            path = e["URL"]
        elif e.get("Exec"):
            argv = [a for a in shlex.split(FIELD.sub("", e["Exec"])) if a]
            if e.get("Terminal", "false").lower() == "true":
                argv = ["x-terminal-emulator", "-e"] + argv
            return launch(argv)
    for opener in (["xdg-open"], ["gio", "open"]):
        if shutil.which(opener[0]):
            return launch(opener + [path])
    print("desktop: nothing to open %s with (install xdg-utils)" % path, file=sys.stderr)

def on_desktop(path):
    real = os.path.realpath(path)
    return os.path.dirname(real) == desktop_dir

def shortcut(spec):
    safe = re.sub(r"[^A-Za-z0-9._-]", "-", str(spec["id"]))
    path = os.path.join(desktop_dir, "tessera-%s.desktop" % safe)
    name = str(spec["name"]).replace("\n", " ")
    with open(path, "w") as f:
        f.write("[Desktop Entry]\nType=Application\nName=%s\nExec=%s\nX-Tessera-Id=%s\n"
                % (name, " ".join(shlex.quote(str(a)) for a in spec["argv"]), spec["id"]))
    os.chmod(path, 0o755)

def remove(path):
    if not on_desktop(path):
        return
    if path.endswith(".desktop"):
        os.remove(path)
    elif shutil.which("gio"):
        subprocess.call(["gio", "trash", path], stdout=null, stderr=null)

def import_file(spec):
    src = str(spec["from"])
    if os.path.dirname(os.path.realpath(src)) != os.path.realpath(os.path.join(xdir, "incoming")):
        return
    name = os.path.basename(str(spec["name"])) or "Dropped file"
    base, ext = os.path.splitext(name)
    dest, n = os.path.join(desktop_dir, name), 2
    while os.path.exists(dest):
        dest = os.path.join(desktop_dir, "%s (%d)%s" % (base, n, ext))
        n += 1
    shutil.move(src, dest)

def run(line):
    parts = line.split(" ", 1)
    if parts[0] == "import" and len(parts) == 2:
        import_file(json.loads(parts[1]))
    elif parts[0] == "open" and len(parts) == 2:
        open_item(json.loads(parts[1]))
    elif parts[0] == "shortcut" and len(parts) == 2:
        shortcut(json.loads(parts[1]))
    elif parts[0] == "remove" and len(parts) == 2:
        remove(json.loads(parts[1]))
    elif parts[0] == "launch" and len(parts) == 2:
        argv = json.loads(parts[1])
        if isinstance(argv, list) and argv:
            launch([str(a) for a in argv])
    elif parts[0] == "strut" and len(parts) == 2:
        side, px = parts[1].split()
        strut(side, max(0, int(px)))
    elif parts[0] == "wallpaper" and len(parts) == 2:
        path, w, h = parts[1].split()
        wallpaper(path, int(w), int(h))
    elif parts[0] == "icons" and len(parts) == 2:
        icons[:] = [i for i in json.loads(parts[1]) if isinstance(i, dict)]

if not os.path.exists(cmd_path):
    os.mkfifo(cmd_path)
try:
    # Made by the launcher, as a rule (see SessionManager.makeFifo), which may not let us.
    os.chmod(cmd_path, 0o666)
except OSError:
    pass
cmd = os.open(cmd_path, os.O_RDWR | os.O_NONBLOCK)
pending = b""
first_run()
while True:
    follow_theme()
    give_title_bars()
    try:
        publish_desktop()
    except OSError as e:
        print("desktop: %s" % e, file=sys.stderr)
    watch = [cmd] + ([d.fileno()] if d is not None else [])
    select.select(watch, [], [], 1)
    try:
        pending += os.read(cmd, 65536)
    except BlockingIOError:
        pass
    while b"\n" in pending:
        line, pending = pending.split(b"\n", 1)
        try:
            run(line.decode(errors="replace").strip())
        except Exception as e:
            print("desktop: %s" % e, file=sys.stderr)
    while d is not None and d.pending_events():
        on_event(d.next_event())
"""
