package com.rar.echodash.vaca

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** AudioTrack-backed PCM sink for announce streams (s16le only). */
class AndroidPcmSink : PcmSink {
    private var track: AudioTrack? = null

    override fun start(rateHz: Int, widthBytes: Int, channels: Int) {
        abort()
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
        track?.write(pcm, 0, pcm.size) // blocking write paces the stream
    }

    override fun finish() {
        track?.let {
            runCatching { it.stop() } // MODE_STREAM: plays out buffered audio, then stops
            it.release()
        }
        track = null
    }

    override fun abort() {
        track?.let {
            runCatching { it.pause(); it.flush() }
            it.release()
        }
        track = null
    }
}
