package com.rar.echodash.vaca

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** ExoPlayer-backed engine; must be constructed on the main thread. */
class ExoPlayerEngine(context: Context) : MediaEngine {
    private val main = Handler(Looper.getMainLooper())
    override var onPlayingChanged: ((Boolean) -> Unit)? = null

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged?.invoke(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlayingChanged?.invoke(false)
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
    override fun setVolume(fraction: Float) = onMain { player.volume = fraction }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
