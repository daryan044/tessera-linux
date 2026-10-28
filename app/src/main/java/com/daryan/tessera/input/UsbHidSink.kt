// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.input

import android.content.Context
import android.util.Log
import com.daryan.tessera.MouseButton
import com.daryan.tessera.container.RootShell
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phone as a USB mouse and keyboard to whatever it is plugged into.
 *
 * Needs root, and a kernel with the HID gadget function (`CONFIG_USB_CONFIGFS_F_HID`). The gadget
 * work is [assets/hid/gadget.sh], which has to run as one uninterrupted script; what is left here
 * is two long-lived root processes, one per endpoint, that reports are streamed into. A `su` per
 * report would cost tens of milliseconds and make the pointer unusable.
 *
 * Android's USB HAL owns that gadget and rewrites it when the USB mode changes or the cable is
 * replugged, which silently takes the HID functions away; [connected] goes false when that
 * happens, and the screen offers to set it up again.
 */
class UsbHidSink private constructor(
    private val su: String,
    private val script: File,
    private val mouse: Writer,
    private val keyboard: Writer,
) : InputSink, AutoCloseable {

    private val held = mutableSetOf<MouseButton>()

    /** For remembering that the gadget is back to normal once [close] has put it back. */
    private var context: Context? = null

    override val connected: Boolean
        get() = mouse.alive && keyboard.alive

    override fun move(dx: Float, dy: Float) {
        mouse.send(HidReports.mouse(HidReports.buttonBits(held), dx.toInt(), dy.toInt()))
    }

    override fun scroll(dx: Float, dy: Float) {
        // A wheel has notches, not pixels, and turns the opposite way to a scrollbar.
        val notches = -dy.toInt()
        if (notches != 0) mouse.send(HidReports.mouse(HidReports.buttonBits(held), 0, 0, notches))
    }

    override fun press(button: MouseButton, down: Boolean) {
        if (down) held += button else held -= button
        // A gap after the change too, so a drag's opening press is not lost in the report that
        // follows it: the first movement arrives within a frame of this.
        mouse.send(HidReports.mouse(HidReports.buttonBits(held), 0, 0), pauseAfterMs = CLICK_MS)
        Log.i(TAG, "${if (down) "press" else "release"} $button, held=$held")
    }

    override fun click(button: MouseButton) {
        if (down(button)) return
        // Held for a moment, the way a finger holds a real button. Down and up in consecutive
        // reports is a click of no duration at all, which a host may merge with whatever comes
        // next -- the press that starts a tap-then-hold drag, for one.
        mouse.send(HidReports.mouse(HidReports.buttonBits(held + button), 0, 0), pauseAfterMs = CLICK_MS)
        mouse.send(HidReports.mouse(HidReports.buttonBits(held), 0, 0), pauseAfterMs = CLICK_MS)
        Log.i(TAG, "click $button")
    }

    /** True when [button] is already held: a click would contradict the drag in progress. */
    private fun down(button: MouseButton) = button in held

    override fun zoom(notches: Int) {
        if (notches == 0) return
        keyboard.send(HidReports.keyboard(HidModifier.LEFT_CTRL, emptyList()), pauseAfterMs = CLICK_MS)
        mouse.send(HidReports.mouse(HidReports.buttonBits(held), 0, 0, notches), pauseAfterMs = CLICK_MS)
        keyboard.send(HidReports.KEYS_UP, pauseAfterMs = CLICK_MS)
    }

    override fun keys(vararg keyCodes: Int) {
        val stroke = HidKeymap.stroke(keyCodes) ?: return
        tap(stroke)
    }

    override fun type(text: String) {
        HidKeymap.strokes(text).forEach(::tap)
    }

    /**
     * Down, then up: a host takes a key as held until a report says otherwise. Held briefly, for
     * the same reason a click is: a keypress of no duration can be dropped outright.
     */
    private fun tap(stroke: HidKeymap.Stroke) {
        keyboard.send(HidReports.keyboard(stroke.modifiers, listOf(stroke.usage)), pauseAfterMs = CLICK_MS)
        keyboard.send(HidReports.KEYS_UP, pauseAfterMs = CLICK_MS)
    }

    /**
     * Lets everything go, stops the writers, and gives Android its gadget back. Blocks for as
     * long as root takes; call it off the main thread.
     */
    override fun close() {
        runCatching { keyboard.send(HidReports.KEYS_UP) }
        runCatching { mouse.send(HidReports.BUTTONS_UP) }
        mouse.close()
        keyboard.close()
        down(su, script, context)
    }

    /**
     * One endpoint, written to by a thread of its own so a stalled host (the cable pulled out
     * mid-report) never blocks a gesture. Reports are dropped rather than queued without end:
     * a pointer update that arrives late is worse than one that never arrives.
     */
    class Writer(private val process: Process, private val stream: OutputStream) : AutoCloseable {
        /** A report, and how long to wait after it before sending the next. */
        private class Item(val report: ByteArray, val pauseAfterMs: Long)

        private val queue = ArrayBlockingQueue<Item>(64)
        @Volatile private var running = true
        @Volatile private var failed = false

        val alive: Boolean get() = running && !failed && process.isAlive

        private val thread = Thread {
            // close() ends this thread by interrupting it, and both poll and sleep answer that
            // with InterruptedException. Caught out here, where it means "we are done": left to
            // escape, it took the whole app down the moment a sink was closed.
            try {
                while (running) {
                    val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    try {
                        stream.write(item.report)
                        stream.flush()
                        if (item.pauseAfterMs > 0) Thread.sleep(item.pauseAfterMs)
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        // The host went away, or the gadget was taken from under us.
                        Log.w(TAG, "HID endpoint closed, reports stop here: ${e.message}")
                        failed = true
                        running = false
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "writer stopped")
            }
        }.apply { isDaemon = true; name = "hid-writer"; start() }

        fun send(report: ByteArray, pauseAfterMs: Long = 0) {
            if (!alive) return
            queue.offer(Item(report, pauseAfterMs))
        }

        /** Stops writing and waits for the process to go: the gadget mustn't change under it. */
        override fun close() {
            running = false
            thread.interrupt()
            runCatching { stream.close() }
            runCatching {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
        }
    }

    companion object {
        private const val TAG = "UsbHid"

        /** How long a button stays down for a click, and the gap after any button change. */
        private const val CLICK_MS = 24L
        private const val SCRIPT = "hid/gadget.sh"

        /**
         * Whether it is worth offering: a root shell, which is all we can tell from here.
         *
         * The gadget itself is root's (`/config/usb_gadget/g1` is `drwxrwx--- root:root`, under
         * configfs, which SELinux keeps apps out of), so whether this kernel has the HID function
         * is for [open]'s script to find out and say. Testing it from the app only ever produced
         * a greyed-out button with no explanation.
         */
        fun supported(su: String?): Boolean = su != null

        /**
         * Sets the gadget up and opens the endpoints. Returns null with the reason when it can't,
         * since every step of this depends on the phone rather than on us.
         */
        fun open(context: Context, su: String): Result<UsbHidSink> = runCatching {
            val dir = File(context.filesDir, "hid").apply { mkdirs() }
            val script = File(dir, "gadget.sh")
            context.assets.open(SCRIPT).use { input -> script.outputStream().use { input.copyTo(it) } }
            val mouseDesc = File(dir, "mouse.desc").apply { writeBytes(HidReports.MOUSE_DESCRIPTOR) }
            val kbdDesc = File(dir, "keyboard.desc").apply { writeBytes(HidReports.KEYBOARD_DESCRIPTOR) }
            // Root reads these, so they must not be private to the app's uid; a directory
            // needs execute to be entered, which setReadable alone does not give.
            listOf(dir, script, mouseDesc, kbdDesc).forEach { it.setReadable(true, false) }
            dir.setExecutable(true, false)

            // Remembered before the gadget changes, and forgotten only once it's put back: if the
            // app dies in between, the next start takes the functions off (see cleanUp).
            prefs(context).edit().putBoolean(KEY_UP, true).commit()
            val setup = run(su, "sh ${script.absolutePath} up ${mouseDesc.absolutePath} ${kbdDesc.absolutePath}")
            Log.i(TAG, "gadget.sh up: $setup")
            if (!setup.contains("ready")) error(setup.lines().lastOrNull { it.isNotBlank() } ?: "the gadget did not come up")

            val mouse = writer(su, "/dev/hidg0")
            val keyboard = writer(su, "/dev/hidg1")
            UsbHidSink(su, script, mouse, keyboard).also { it.context = context.applicationContext }
        }

        /**
         * Takes the HID functions off if an earlier run left them on (the app was killed while
         * the phone was a USB mouse). Left on, the phone stays a mouse to anything it's plugged
         * into, a charger included, which is where the kernel copes worst. Blocks; call it off
         * the main thread.
         */
        fun cleanUp(context: Context, su: String) {
            if (!prefs(context).getBoolean(KEY_UP, false)) return
            val script = File(File(context.filesDir, "hid"), "gadget.sh")
            if (!script.isFile) {
                prefs(context).edit().remove(KEY_UP).apply()
                return
            }
            Log.i(TAG, "taking off HID functions an earlier run left on")
            down(su, script, context)
        }

        private fun down(su: String, script: File, context: Context? = null) {
            val out = runCatching { run(su, "sh ${script.absolutePath} down") }.getOrDefault("")
            Log.i(TAG, "gadget.sh down: $out")
            if (out.contains("stopped")) context?.let { prefs(it).edit().remove(KEY_UP).apply() }
        }

        private fun prefs(context: Context) = context.getSharedPreferences("usb_hid", Context.MODE_PRIVATE)
        private const val KEY_UP = "functions_up"

        /**
         * A root process holding an endpoint open, fed reports through its stdin. `cat` would
         * exit the moment the node refused a write, so this checks it is still there and says so
         * rather than leaving a sink that silently swallows every gesture.
         */
        private fun writer(su: String, node: String): Writer {
            val process = ProcessBuilder(su, "-c", RootShell.line("cat > $node"))
                .redirectErrorStream(true)
                .start()
            if (!process.isAlive) {
                val why = runCatching { process.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
                error(why.ifBlank { "could not open $node as root" })
            }
            Log.i(TAG, "holding $node open")
            return Writer(process, process.outputStream)
        }

        private fun run(su: String, command: String): String {
            val process = ProcessBuilder(su, "-c", RootShell.line(command)).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(20, TimeUnit.SECONDS)
            return out
        }
    }
}
