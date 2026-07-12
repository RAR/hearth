package com.rar.echodash.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Captures mic audio (16 kHz / 16-bit / mono, VOICE_RECOGNITION) in ~30 ms chunks and
 * pushes each to [onChunk]. Runs on its own thread while [start] is active. Any init or
 * read failure (including a missing RECORD_AUDIO grant surfacing as a failed init) calls
 * [onError] once and stops. Never throws to the caller.
 */
class MicStreamer(
    private val onChunk: (ByteArray) -> Unit,
    private val onError: () -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var generation = 0

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO is granted; failure -> onError
    @Synchronized
    fun start() {
        if (running) return
        running = true
        generation++
        val gen = generation
        thread(name = "MicStreamer", isDaemon = true) {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                if (running && gen == generation) { running = false; onError() }
                return@thread
            }
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, CHUNK_BYTES * 4),
                )
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed", e)
                if (running && gen == generation) { running = false; onError() }
                return@thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized (permission?)")
                runCatching { record.release() }
                if (running && gen == generation) { running = false; onError() }
                return@thread
            }
            if (!running || gen != generation) {
                runCatching { record.release() }
                return@thread
            }
            try {
                record.startRecording()
                val buf = ByteArray(CHUNK_BYTES)
                while (running && gen == generation) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) {
                        if (running && gen == generation) { running = false; onError() }
                        break
                    }
                    if (n == 0) continue
                    onChunk(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                }
            } catch (e: Exception) {
                Log.w(TAG, "recording failed", e)
                if (running && gen == generation) { running = false; onError() }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
    }

    private companion object {
        const val TAG = "MicStreamer"
        const val RATE = 16000
        const val CHUNK_BYTES = 960 // 30 ms of 16 kHz s16le mono
    }
}
