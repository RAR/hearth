package com.rar.echodash.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Repeating two-tone "timer done" chime synthesized on the fly and played through AudioTrack
 * on the alarm stream. [start] loops until [stop]; both are idempotent. No bundled audio asset.
 */
class TimerChime {
    @Volatile private var playing = false
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (playing) return
        playing = true
        worker = thread(name = "TimerChime", isDaemon = true) {
            val rate = 22050
            val tone = buildTone(rate)
            val gap = ShortArray(rate) // ~1 s silence between repeats
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_ALARM, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, tone.size * 2), AudioTrack.MODE_STREAM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "chime init failed", e); playing = false; return@thread
            }
            try {
                track.play()
                while (playing) {
                    var off = 0
                    while (playing && off < tone.size) off += track.write(tone, off, tone.size - off)
                    off = 0
                    while (playing && off < gap.size) off += track.write(gap, off, gap.size - off)
                }
            } catch (e: Exception) {
                Log.w(TAG, "chime playback failed", e)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    @Synchronized
    fun stop() {
        playing = false
        worker = null
    }

    private fun buildTone(rate: Int): ShortArray {
        val beep = rate * 200 / 1000 // 200 ms per beep
        val out = ShortArray(beep * 2)
        for (i in 0 until beep) {
            out[i] = (sin(2 * PI * 880.0 * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort()
            out[beep + i] = (sin(2 * PI * 1320.0 * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private companion object { const val TAG = "TimerChime" }
}
