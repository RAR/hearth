package com.rar.echodash.vaca

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToInt

/** ExoPlayer-backed engine; must be constructed on the main thread. */
class ExoPlayerEngine(context: Context) : MediaEngine {
    private val main = Handler(Looper.getMainLooper())
    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onMeta: ((String?, ByteArray?) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null
    override var onVolumeChanged: ((Int) -> Unit)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged?.invoke(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlayingChanged?.invoke(false)
                onEnded?.invoke()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // media3 surfaces ICY StreamTitle as title; embedded tag art as artworkData.
                onMeta?.invoke(mediaMetadata.title?.toString(), mediaMetadata.artworkData)
            }
        })
    }

    override fun play(url: String) = onMain {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    override fun resume() = onMain { player.play() }
    override fun pause() = onMain { player.pause() }
    override fun stop() = onMain {
        player.stop()
        player.clearMediaItems()
    }
    override fun setVolume(fraction: Float) {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (fraction.coerceIn(0f, 1f) * maxVolume).roundToInt(),
            0,
        )
    }

    override fun setDucking(fraction: Float) = onMain { player.volume = fraction }

    override fun currentVolumePercent(): Int =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume

    init {
        // "android.media.VOLUME_CHANGED_ACTION" is not in the public SDK but is the long-stable
        // broadcast for system volume changes (hardware buttons, other apps).
        // The engine lives for the app's whole life (the app is the device launcher), so the
        // receiver is never unregistered — there is no teardown path to unregister it from.
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) {
                    onVolumeChanged?.invoke(currentVolumePercent())
                }
            }
        }, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
