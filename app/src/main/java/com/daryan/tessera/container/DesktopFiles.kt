// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File

/** The desktop helper's command FIFO in the session's X11 directory; see [DESKTOP_HELPER]. */
const val DESKTOP_COMMANDS_NAME = "launcher-desktop"
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

    /** Keeps [px] X pixels along the bottom, or the [left] edge, clear of maximized windows. */
    fun reserve(left: Boolean, px: Int): Boolean = send("strut ${if (left) "left" else "bottom"} $px")

    /**
     * Shows [picture] behind the windows, cropped to fill a [width] × [height] X screen. Run off
     * the main thread: it writes the whole screen's pixels.
     */
    fun wallpaper(picture: Bitmap, width: Int, height: Int): Boolean {
        // Not the whole screen's pixels for nobody: the helper may not be up yet.
        if (width <= 0 || height <= 0 || !File(dir, DESKTOP_COMMANDS_NAME).exists()) return false
        val scale = maxOf(width.toFloat() / picture.width, height.toFloat() / picture.height)
        val fitted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(fitted).apply {
            translate((width - picture.width * scale) / 2, (height - picture.height * scale) / 2)
            scale(scale, scale)
            drawBitmap(picture, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
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

    companion object {
        private const val THEME_DIR = "desktop"
        /** Names the theme in use; [DESKTOP_HELPER] sets xfwm4's theme to the path in it. */
        private const val CURRENT = "current"

        /**
         * Writes xfwm4's theme for [palette] under the session's theme directory [themeDir], a
         * new generation each time (the window manager only reloads a theme whose path
         * changes), and points [CURRENT] at it. [titlePx]: the title bar's height in X pixels.
         *
         * It is Circela's look (addy-dclxvi's xfwm4 theme) in the phone's colors: a title bar
         * the color of the apps' own header bars, no title text (the taskbar has it), ring
         * buttons, and close as a filled accent circle on the focused window.
         */
        fun writeTheme(themeDir: File, palette: Palette, titlePx: Int) {
            val root = File(themeDir, THEME_DIR)
            val existing = root.list().orEmpty().mapNotNull { it.toIntOrNull() }
            val generation = (existing.maxOrNull() ?: 0) + 1
            val out = File(root, "$generation").apply { mkdirs() }
            themeFiles(palette, titlePx.coerceIn(24, 160)).forEach { (name, bytes) -> File(out, name).writeBytes(bytes) }
            val current = File(root, CURRENT)
            val tmp = File(root, "$CURRENT.tmp")
            tmp.writeText("${ThemeFiles.GUEST_DIR}/$THEME_DIR/$generation\n")
            tmp.renameTo(current)
            // The one before stays: xfwm4 may still be reading it.
            existing.filter { it < generation - 1 }.forEach { File(root, "$it").deleteRecursively() }
        }

        internal fun themeFiles(p: Palette, title: Int): Map<String, ByteArray> {
            val bar = opaque(p.headerbar)
            val edge = 2
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
            // The title text the color of the bar: there, so xfwm4 lays the bar out, but unseen.
            val hidden = "#%06x".format(p.headerbar and 0xFFFFFF)
            return """
                |button_offset=${title / 6}
                |button_spacing=0
                |full_width_title=true
                |title_shadow_active=false
                |title_shadow_inactive=false
                |title_horizontal_offset=0
                |title_vertical_offset_active=0
                |title_vertical_offset_inactive=0
                |active_text_color=$hidden
                |active_text_shadow_color=$hidden
                |inactive_text_color=$hidden
                |inactive_text_shadow_color=$hidden
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
 * as the window manager runs.
 */
internal const val DESKTOP_SESSION = """
helper=${'$'}1; shift
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
 * - "wallpaper <file> <width> <height>" shows the file's B, G, R, x pixels behind the windows.
 * It also sets xfwm4 up for touch the first time (snap to edges, center new windows, shadows;
 * the user's own changes are kept after that) and sets its theme to the one [DesktopFiles.writeTheme]
 * names, again whenever that changes.
 */
internal const val DESKTOP_HELPER = """
import json, os, select, shutil, subprocess, sys, time

xdir = sys.argv[1]
cmd_path = os.path.join(xdir, "launcher-desktop")
theme = os.environ.get("TESSERA_THEME")
current = os.path.join(theme, "desktop", "current") if theme else None
null = subprocess.DEVNULL

try:
    from Xlib import X, Xatom, display
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
    marker = os.path.expanduser("~/.config/tessera/desktop-configured")
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
        path = open(current).read().strip()
    except OSError:
        return
    if path and path != theme_seen and xfconf("theme", "string", path) == 0:
        theme_seen = path

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
    if paper is not None:
        paper.free()
    paper = pm
    d.flush()

def launch(argv):
    try:
        subprocess.Popen(argv, start_new_session=True, stdin=null, cwd=os.path.expanduser("~"))
    except OSError as e:
        print("desktop: couldn't start %s: %s" % (argv[0] if argv else "?", e), file=sys.stderr)

def run(line):
    parts = line.split(" ", 1)
    if parts[0] == "launch" and len(parts) == 2:
        argv = json.loads(parts[1])
        if isinstance(argv, list) and argv:
            launch([str(a) for a in argv])
    elif parts[0] == "strut" and len(parts) == 2:
        side, px = parts[1].split()
        strut(side, max(0, int(px)))
    elif parts[0] == "wallpaper" and len(parts) == 2:
        path, w, h = parts[1].split()
        wallpaper(path, int(w), int(h))

if not os.path.exists(cmd_path):
    os.mkfifo(cmd_path)
os.chmod(cmd_path, 0o666)
cmd = os.open(cmd_path, os.O_RDWR | os.O_NONBLOCK)
pending = b""
first_run()
while True:
    follow_theme()
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
        d.next_event()
"""
