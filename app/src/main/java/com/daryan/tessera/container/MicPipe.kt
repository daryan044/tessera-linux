// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileDescriptor

/** The FIFO a session's PulseAudio records from, in the X11 directory; see [MicPipe]. */
const val MIC_FIFO_NAME = "launcher-mic"
/** The name of that source in PulseAudio, which apps record from by default (PULSE_SOURCE). */
const val MIC_SOURCE_NAME = "android_mic"

/**
 * A GUI session's sound input (#15), the other way round from [AudioPipe]: its PulseAudio has a
 * pipe source reading 16-bit stereo PCM from [fifo], and this writes what Android records into
 * it. What Android records from is Android's choice: the phone's microphone, a headset's, or a
 * USB audio interface while one is plugged in, whose two inputs come as left and right.
 *
 * The microphone is only open while a Linux app records. PulseAudio stops reading the pipe when
 * nothing listens to the source (module-suspend-on-idle); the pipe then stays full, and this
 * lets go of the microphone until it drains again.
 */
class MicPipe(private val context: Context, private val fifo: File) {
    @Volatile private var stopped = false

    /** Blocks until [stop] is called. Call off the main thread. */
    fun run() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no microphone permission; ${fifo.name} stays silent")
            return
        }
        var fd: FileDescriptor? = null
        try {
            // Read-write, so opening doesn't wait for PulseAudio; only PulseAudio reads it.
            fd = Os.open(fifo.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            // Through a copy of the descriptor, whose number Android's own type doesn't give out;
            // the size is the pipe's, whichever descriptor asks.
            runCatching { android.os.ParcelFileDescriptor.dup(fd).use { Pty.fcntlInt(it.fd, F_SETPIPE_SZ, PIPE_BYTES) } }
            // Full of silence from the start: until someone reads, nobody is recording.
            fill(fd)
            while (!stopped) {
                if (!writable(fd, 500)) continue
                record(fd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "microphone pipe ${fifo.path} failed", e)
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    fun stop() {
        stopped = true
    }

    /** Records into [fd] for as long as it's being read. */
    private fun record(fd: FileDescriptor) {
        val channels = listOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
            .firstOrNull { AudioRecord.getMinBufferSize(AUDIO_RATE, it, AudioFormat.ENCODING_PCM_16BIT) > 0 } ?: return pause()
        val stereo = channels == AudioFormat.CHANNEL_IN_STEREO
        val min = AudioRecord.getMinBufferSize(AUDIO_RATE, channels, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_RATE, channels, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open the microphone", e)
            return pause()
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return pause()
            recorder.startRecording()
            val buf = ByteArray(CHUNK)
            val out = ByteArray(CHUNK * 2)
            var stalledSince = 0L
            while (!stopped) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) break
                // PulseAudio's source is stereo either way; one channel goes to both sides.
                val (bytes, length) = if (stereo) buf to n else {
                    var o = 0
                    var i = 0
                    while (i + 1 < n) {
                        out[o] = buf[i]; out[o + 1] = buf[i + 1]; out[o + 2] = buf[i]; out[o + 3] = buf[i + 1]
                        o += 4
                        i += 2
                    }
                    out to o
                }
                if (write(fd, bytes, length)) {
                    stalledSince = 0
                } else {
                    // Full: what was just recorded is dropped. Full for a while: nobody listens.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (stalledSince == 0L) stalledSince = now else if (now - stalledSince > IDLE_MS) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "recording stopped", e)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /** Whether all of [bytes] went into the pipe; false when it's full. */
    private fun write(fd: FileDescriptor, bytes: ByteArray, length: Int): Boolean {
        var off = 0
        while (off < length) {
            val n = try {
                Os.write(fd, bytes, off, length - off)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) return false
                throw e
            }
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun fill(fd: FileDescriptor) {
        val silence = ByteArray(4096)
        while (write(fd, silence, silence.size)) Unit
    }

    /** Whether [fd] takes more within [timeoutMs]: someone is reading it. */
    private fun writable(fd: FileDescriptor, timeoutMs: Int): Boolean {
        val p = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLOUT.toShort()
        }
        return try {
            Os.poll(arrayOf(p), timeoutMs) > 0 && (p.revents.toInt() and OsConstants.POLLOUT) != 0
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EINTR) pause()
            false
        }
    }

    private fun pause() {
        runCatching { Thread.sleep(1000) }
    }

    private companion object {
        const val TAG = "MicPipe"
        /** 20 ms of 16-bit mono, or 10 ms of stereo. */
        const val CHUNK = 1920
        const val PIPE_BYTES = 16 * 1024 // ~85 ms of stereo
        /** How long the pipe may stay full before the microphone is let go. */
        const val IDLE_MS = 1500L
        const val F_SETPIPE_SZ = 1031 // Linux fcntl; not in OsConstants
    }
}
