// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.input

import android.content.Context
import android.util.Log
import com.daryan.tessera.MouseButton
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
        mouse.send(HidReports.mouse(HidReports.buttonBits(held), 0, 0))
    }

    override fun click(button: MouseButton) {
        press(button, true)
        press(button, false)
    }

    override fun keys(vararg keyCodes: Int) {
        val stroke = HidKeymap.stroke(keyCodes) ?: return
        tap(stroke)
    }

    override fun type(text: String) {
        HidKeymap.strokes(text).forEach(::tap)
    }

    /** Down, then up: a host takes a key as held until a report says otherwise. */
    private fun tap(stroke: HidKeymap.Stroke) {
        keyboard.send(HidReports.keyboard(stroke.modifiers, listOf(stroke.usage)))
        keyboard.send(HidReports.KEYS_UP)
    }

    /** Lets everything go, stops the writers, and gives Android its gadget back. */
    override fun close() {
        runCatching { keyboard.send(HidReports.KEYS_UP) }
        runCatching { mouse.send(HidReports.BUTTONS_UP) }
        mouse.close()
        keyboard.close()
        runCatching { Log.i(TAG, "gadget.sh down: ${run(su, "sh ${script.absolutePath} down")}") }
    }

    /**
     * One endpoint, written to by a thread of its own so a stalled host (the cable pulled out
     * mid-report) never blocks a gesture. Reports are dropped rather than queued without end:
     * a pointer update that arrives late is worse than one that never arrives.
     */
    class Writer(private val process: Process, private val stream: OutputStream) : AutoCloseable {
        private val queue = ArrayBlockingQueue<ByteArray>(64)
        @Volatile private var running = true
        @Volatile private var failed = false

        val alive: Boolean get() = running && !failed && process.isAlive

        private val thread = Thread {
            while (running) {
                val report = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    stream.write(report)
                    stream.flush()
                } catch (e: Exception) {
                    // The host went away, or the gadget was taken from under us.
                    Log.w(TAG, "HID endpoint closed, reports stop here: ${e.message}")
                    failed = true
                    running = false
                }
            }
        }.apply { isDaemon = true; name = "hid-writer"; start() }

        fun send(report: ByteArray) {
            if (!alive) return
            queue.offer(report)
        }

        override fun close() {
            running = false
            thread.interrupt()
            runCatching { stream.close() }
            runCatching { process.destroy() }
        }
    }

    companion object {
        private const val TAG = "UsbHid"
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

            val setup = run(su, "sh ${script.absolutePath} up ${mouseDesc.absolutePath} ${kbdDesc.absolutePath}")
            Log.i(TAG, "gadget.sh up: $setup")
            if (!setup.contains("ready")) error(setup.lines().lastOrNull { it.isNotBlank() } ?: "the gadget did not come up")

            val mouse = writer(su, "/dev/hidg0")
            val keyboard = writer(su, "/dev/hidg1")
            UsbHidSink(su, script, mouse, keyboard)
        }

        /**
         * A root process holding an endpoint open, fed reports through its stdin. `cat` would
         * exit the moment the node refused a write, so this checks it is still there and says so
         * rather than leaving a sink that silently swallows every gesture.
         */
        private fun writer(su: String, node: String): Writer {
            val process = ProcessBuilder(su, "-c", "cat > $node")
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
            val process = ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(20, TimeUnit.SECONDS)
            return out
        }
    }
}
