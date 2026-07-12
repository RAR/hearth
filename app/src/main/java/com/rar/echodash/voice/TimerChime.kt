package com.rar.echodash.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread

/**
 * Plays the "timer done" alarm through AudioTrack on the alarm stream. The waveform is synthesized
 * by [ToneGenerator] as one cycle (sound + trailing gap); [start] loops that single buffer until
 * [stop]. Both are idempotent. [playOnce] auditions a single cycle for the config-page preview.
 * No bundled audio asset.
 */
class TimerChime {
    @Volatile private var playing = false
    private var worker: Thread? = null

    /** Loop [tone] at [volume] until [stop]. Idempotent: a second call while playing is a no-op. */
    @Synchronized
    fun start(tone: String, volume: Int) {
        if (playing) return
        playing = true
        worker = thread(name = "TimerChime", isDaemon = true) {
            val rate = 22050
            val cycle = ToneGenerator.render(tone, volume, rate)
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_ALARM, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, cycle.size * 2), AudioTrack.MODE_STREAM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "chime init failed", e); playing = false; return@thread
            }
            try {
                track.play()
                // The gap is baked into the rendered cycle, so each loop iteration is one write.
                while (playing) {
                    var off = 0
                    while (playing && off < cycle.size) off += track.write(cycle, off, cycle.size - off)
                }
            } catch (e: Exception) {
                Log.w(TAG, "chime playback failed", e)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    /** Stop any running loop. Idempotent. */
    @Synchronized
    fun stop() {
        playing = false
        worker = null
    }

    /**
     * Play exactly ONE cycle of [tone] at [volume], then stop and release. Best-effort: swallows
     * all failures and never throws. Runs on its own daemon thread with its own AudioTrack and does
     * NOT touch [playing]/[worker], so it is safe to call while a [start] loop is running (the OS
     * mixes both on STREAM_ALARM) and can never leave a loop running. Used by the config preview.
     */
    fun playOnce(tone: String, volume: Int) {
        thread(name = "TimerChimePreview", isDaemon = true) {
            val rate = 22050
            val cycle = ToneGenerator.render(tone, volume, rate)
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_ALARM, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, cycle.size * 2), AudioTrack.MODE_STREAM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "preview init failed", e); return@thread
            }
            try {
                track.play()
                var off = 0
                while (off < cycle.size) off += track.write(cycle, off, cycle.size - off)
                // MODE_STREAM: write() returns as soon as data is queued, not once it has
                // rendered, so we must wait for the hardware playback head to reach the frames
                // we wrote before releasing — otherwise the native track is destroyed with the
                // whole cycle still unplayed and nothing is heard. Same fix as
                // AndroidPcmSink.finish().
                val target = cycle.size.toLong()
                val cycleMs = cycle.size * 1000L / rate
                val bufferMs = track.bufferSizeInFrames * 1000L / rate
                val deadline = System.currentTimeMillis() + cycleMs + bufferMs + 500L
                while (System.currentTimeMillis() < deadline) {
                    // getPlaybackHeadPosition() is a 32-bit frame counter (unsigned).
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (head >= target) break
                    Thread.sleep(20)
                }
                runCatching { track.stop() }
            } catch (e: Exception) {
                Log.w(TAG, "preview playback failed", e)
            } finally {
                runCatching { track.release() }
            }
        }
    }

    private companion object { const val TAG = "TimerChime" }
}
