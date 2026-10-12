// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Plays a GUI session's sound. Its PulseAudio (started by [guestCommand]) has a pipe sink
 * writing 16-bit stereo PCM into [fifo]; this reads it and hands it to an [AudioTrack].
 * AudioTrack's blocking write paces the reads, and a full pipe paces PulseAudio, so the guest
 * plays in real time without a clock of its own. No host server, no native code.
 */
class AudioPipe(private val fifo: File) {
    @Volatile private var stopped = false

    /** Blocks until the session's PulseAudio goes away or [stop] is called. Call off the main thread. */
    fun run() {
        // PulseAudio makes the FIFO when it loads the sink, a moment after the app starts.
        while (!stopped && !fifo.exists()) Thread.sleep(200)
        if (stopped) return
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, BYTES_PER_SECOND / 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            // PulseAudio keeps its end open read-write, so this doesn't wait for a writer, and
            // reads end (EOF) once PulseAudio exits with the session.
            FileInputStream(fifo).use { input ->
                // A 64 KiB pipe is a third of a second of lag; a smaller one keeps sound in step.
                runCatching { Os.fcntlInt(input.fd, F_SETPIPE_SZ, PIPE_BYTES) }
                track.play()
                val buf = ByteArray(4096)
                while (!stopped) {
                    val n = input.read(buf)
                    if (n < 0) break
                    track.write(buf, 0, n)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "audio pipe ${fifo.path} failed", e)
        } finally {
            track.release()
        }
    }

    fun stop() {
        stopped = true
    }

    private companion object {
        const val TAG = "AudioPipe"
        const val BYTES_PER_SECOND = AUDIO_RATE * 2 * 2
        const val PIPE_BYTES = 16 * 1024 // ~85 ms
        const val F_SETPIPE_SZ = 1031 // Linux fcntl; not in OsConstants
    }
}
