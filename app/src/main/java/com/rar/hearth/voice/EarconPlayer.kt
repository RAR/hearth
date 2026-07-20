package com.rar.hearth.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread

/**
 * One-shot voice acknowledgment chirps on STREAM_MUSIC (they track media volume like the TTS
 * responses; the timer alarm keeps STREAM_ALARM). Same HAL recipe as TimerChime.playOnce:
 * prime the full buffer BEFORE play(), then wait for the playback head before releasing.
 * Best-effort: swallows all failures, never throws. Each call runs on its own daemon thread.
 */
class EarconPlayer {
    /** Play one [ToneGenerator.earcon] cycle of [kind] at [volume]; no-op when volume <= 0. */
    fun play(kind: String, volume: Int) {
        if (volume <= 0) return
        thread(name = "Earcon", isDaemon = true) {
            val rate = 22050
            val tone = ToneGenerator.earcon(kind, volume, rate)
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // Trailing-silence pad, ≥300 ms and never below the track's own minimum buffer.
            // Unlike the timer tones (whose rendered cycle ends in ~1 s of baked-in gap) a bare
            // chirp has no tail: this HAL's deep buffer swallows a 160 ms chirp whole, the
            // playback head hits the target immediately, and stop()+release() destroyed the
            // native track before any audio left the speaker. The pad keeps the head-wait alive
            // until the audible part has actually drained, and guarantees the primed buffer is
            // never under-filled at play() (the other known silent-start mode on this HAL).
            val pad = maxOf(rate * 300 / 1000, minBuf / 2 - tone.size)
            val pcm = tone + ShortArray(pad)
            val track = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, pcm.size * 2), AudioTrack.MODE_STREAM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "earcon init failed", e); return@thread
            }
            try {
                // Prime BEFORE play(): this HAL renders a track started on an empty buffer
                // silently (see TimerChime for the full story).
                var off = 0
                while (off < pcm.size) {
                    val n = track.write(pcm, off, pcm.size - off)
                    if (n <= 0) break
                    off += n
                }
                track.play()
                // Wait for the hardware head to consume what we wrote before releasing,
                // else the native track dies with the chirp still unplayed.
                val target = pcm.size.toLong()
                val deadline = System.currentTimeMillis() +
                    pcm.size * 1000L / rate + track.bufferSizeInFrames * 1000L / rate + 500L
                while (System.currentTimeMillis() < deadline) {
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (head >= target) break
                    Thread.sleep(20)
                }
                runCatching { track.stop() }
            } catch (e: Exception) {
                Log.w(TAG, "earcon playback failed", e)
            } finally {
                runCatching { track.release() }
            }
        }
    }

    private companion object { const val TAG = "EarconPlayer" }
}
