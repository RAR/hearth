package com.rar.hearth.device

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** AudioTrack-backed PCM sink for announce streams (s16le only). */
class AndroidPcmSink : PcmSink {
    private var track: AudioTrack? = null
    private var channels: Int = 1
    private var framesWritten: Long = 0
    private var sampleRateHz: Int = 0

    // Set by abort() — which may run on a different thread than the one
    // draining finish() when a tap interrupts an in-flight reply. finish()'s
    // poll loop checks this each iteration so it doesn't block until the
    // whole buffer has played out from underneath an abort.
    @Volatile private var abortRequested = false

    override fun start(rateHz: Int, widthBytes: Int, channels: Int) {
        abort()
        abortRequested = false
        this.channels = channels
        this.sampleRateHz = rateHz
        framesWritten = 0
        val channelMask =
            if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(rateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(rateHz)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBuf, 8192) * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).also { it.play() }
    }

    override fun write(pcm: ByteArray) {
        val result = track?.write(pcm, 0, pcm.size) ?: return // blocking write paces the stream
        if (result < 0) throw java.io.IOException("AudioTrack.write failed: $result")
        framesWritten += result.toLong() / (2L * channels) // 2 bytes/sample (s16le)
    }

    override fun finish() {
        track?.let {
            // MODE_STREAM: stop() drains asynchronously, so wait for the hardware
            // playback head to reach the frames we wrote before releasing —
            // otherwise the native track is destroyed with the tail (up to the
            // full buffer, ~370ms+) still unplayed, clipping the announcement.
            val bufferMs =
                if (sampleRateHz > 0) it.bufferSizeInFrames * 1000L / sampleRateHz else 0L
            val deadline = System.currentTimeMillis() + bufferMs + 500L
            val target = framesWritten
            while (System.currentTimeMillis() < deadline && !abortRequested) {
                // getPlaybackHeadPosition() is a 32-bit frame counter (unsigned).
                // Wrapped in runCatching: abort() can release the track out from
                // under this poll on another thread the instant abortRequested
                // flips, so the read itself may hit a torn-down track.
                val head = runCatching { it.playbackHeadPosition.toLong() and 0xFFFFFFFFL }
                    .getOrDefault(target)
                if (head >= target) break
                Thread.sleep(20)
            }
            if (!abortRequested) {
                runCatching { it.stop() }
                it.release()
            }
            // else: abort() already stopped/released this track — don't touch it again.
        }
        track = null
    }

    override fun abort() {
        abortRequested = true
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
        framesWritten = 0
    }
}
