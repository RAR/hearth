package com.rar.echodash.voice

import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import kotlin.concurrent.thread

/**
 * Plays the "timer done" alarm. Bundled system alarms (see [TimerSounds]) play through MediaPlayer
 * on the alarm stream; the four synthesized tones play through AudioTrack, whose waveform is
 * rendered by [ToneGenerator] as one cycle (sound + trailing gap) that [start] loops until [stop].
 * Both are idempotent. Any failure on the asset path falls back to the synthesized "twotone" loop --
 * an alarm must never fail silent. [playOnce] auditions a single file/cycle for the config preview.
 *
 * [assetFd] opens a bundled asset (App.kt passes assets.openFd; tests pass { null }).
 */
class TimerChime(private val assetFd: (String) -> AssetFileDescriptor? = { null }) {
    @Volatile private var playing = false
    private var worker: Thread? = null
    private var player: MediaPlayer? = null

    /**
     * Loop [tone] at [volume] until [stop]. Idempotent: a second call while playing is a no-op.
     * System-alarm tones loop via MediaPlayer; on any asset failure we fall back to the synthesized
     * "twotone" loop at the same volume. Synthesized tones take the AudioTrack path directly.
     */
    @Synchronized
    fun start(tone: String, volume: Int) {
        if (playing) return
        playing = true
        val asset = TimerSounds.assetPath(tone)
        if (asset != null) {
            if (volume <= 0) return                 // muted alarm -> nothing to play
            if (startAsset(asset, volume)) return   // MediaPlayer loop running
            startSynthLoop("twotone", volume)       // asset failed -> synthesized fallback
        } else {
            startSynthLoop(tone, volume)            // synthesized tone
        }
    }

    /** Start bundled [asset] looping via MediaPlayer at [volume]. Returns true on success; on any
     *  failure releases the player/fd and returns false so [start] can fall back to synthesis. */
    private fun startAsset(asset: String, volume: Int): Boolean {
        val fd = assetFd(asset) ?: return false
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.isLooping = true
            mp.setVolume(volume / 100f, volume / 100f)
            mp.prepare()                            // synchronous; the bundled files are small
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            Log.w(TAG, "alarm asset '$asset' failed; falling back to synth", e)
            runCatching { fd.close() }
            runCatching { mp.release() }
            false
        }
    }

    /** Synthesized AudioTrack loop -- the prime-before-play HAL recipe. Unchanged from the
     *  no-asset implementation; used for synthesized tones and as the asset-failure fallback. */
    private fun startSynthLoop(tone: String, volume: Int) {
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
                // Prime one full cycle BEFORE play(): this device's HAL renders a track that
                // was started on an empty buffer silently until further writes arrive, which
                // swallowed the first alarm cycle. The buffer holds exactly one cycle, so this
                // write fills it while stopped; subsequent loop writes block-and-pace as before.
                var primed = 0
                while (playing && primed < cycle.size) {
                    val n = track.write(cycle, primed, cycle.size - primed)
                    if (n <= 0) break
                    primed += n
                }
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

    /** Stop any running loop and release the MediaPlayer if active. Idempotent: a second call
     *  finds player == null and does nothing, so an active MediaPlayer is released exactly once. */
    @Synchronized
    fun stop() {
        playing = false
        worker = null
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
    }

    /**
     * Play exactly ONE file/cycle of [tone] at [volume], then stop and release. Best-effort: swallows
     * all failures and never throws. System-alarm tones play the whole file once through MediaPlayer
     * (released on completion); synthesized tones render one AudioTrack cycle on a daemon thread.
     * Does NOT touch [playing]/[worker]/[player], so it is safe to call while a [start] loop runs
     * (the OS mixes both on the alarm output) and can never leave a loop running. Used by the preview.
     */
    fun playOnce(tone: String, volume: Int) {
        val asset = TimerSounds.assetPath(tone)
        if (asset == null) { playSynthOnce(tone, volume); return }   // synthesized preview
        if (volume <= 0) return
        val fd = assetFd(asset)
        if (fd == null) { playSynthOnce("twotone", volume); return } // missing asset -> synth fallback
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.isLooping = false
            mp.setVolume(volume / 100f, volume / 100f)
            // MediaPlayer binds its completion callback to the creating thread's Looper, falling back
            // to the main Looper when the caller (a web-server worker) has none -- so this fires.
            mp.setOnCompletionListener { it.release() }
            mp.prepare()                            // synchronous; the bundled files are small
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "alarm preview '$asset' failed; falling back to synth", e)
            runCatching { fd.close() }
            runCatching { mp.release() }
            playSynthOnce("twotone", volume)
        }
    }

    /** One synthesized AudioTrack cycle on a daemon thread. Unchanged from the no-asset playOnce. */
    private fun playSynthOnce(tone: String, volume: Int) {
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
                // Prime the full cycle into the buffer BEFORE play(): starting a MODE_STREAM
                // track on an empty buffer leaves this device's HAL rendering silently (the
                // mixer consumes frames in real time but no sound reaches the speaker unless
                // writes keep arriving, as the looping alarm path does). Write-then-play is the
                // canonical one-shot recipe and never starts in underrun.
                var off = 0
                while (off < cycle.size) {
                    val n = track.write(cycle, off, cycle.size - off)
                    if (n <= 0) break
                    off += n
                }
                track.play()
                // MODE_STREAM: write() returns as soon as data is queued, not once it has
                // rendered, so we must wait for the hardware playback head to reach the frames
                // we wrote before releasing -- otherwise the native track is destroyed with the
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
