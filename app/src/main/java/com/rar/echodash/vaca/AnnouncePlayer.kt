package com.rar.echodash.vaca

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Raw PCM output. [finish] drains buffered audio then releases; [abort] drops everything now. */
interface PcmSink {
    fun start(rateHz: Int, widthBytes: Int, channels: Int)
    fun write(pcm: ByteArray)
    fun finish()
    fun abort()
}

/**
 * Plays an HA announce stream (audio-start/chunk/stop). The on* methods are
 * called from the VACA server's connection-reader thread and must NEVER
 * block — HA keepalive pings share that connection and time out in 5 s. They
 * enqueue onto an unbounded channel; a worker coroutine in [scope] performs
 * the (blocking) sink calls. `onPlayed` fires exactly once per stream, even
 * on failure — otherwise HA's announce service call hangs for the full audio
 * duration.
 */
class AnnouncePlayer(
    scope: CoroutineScope,
    private val sink: PcmSink,
    private val onPlayed: () -> Unit,
    private val setDucking: (Boolean) -> Unit,
) {
    private sealed interface Cmd {
        data class Start(val rate: Int, val width: Int, val channels: Int) : Cmd
        data class Chunk(val pcm: ByteArray) : Cmd
        data object Stop : Cmd
        data object Abort : Cmd
    }

    private val queue = Channel<Cmd>(Channel.UNLIMITED)
    private var streaming = false // worker-confined

    @Suppress("unused")
    private val worker = scope.launch {
        for (cmd in queue) handle(cmd)
    }

    fun onAudioStart(rate: Int, width: Int, channels: Int) {
        queue.trySend(Cmd.Start(rate, width, channels))
    }

    fun onAudioChunk(pcm: ByteArray) {
        queue.trySend(Cmd.Chunk(pcm))
    }

    fun onAudioStop() {
        queue.trySend(Cmd.Stop)
    }

    fun onDisconnected() {
        queue.trySend(Cmd.Abort)
    }

    /** Closes the queue so the worker exits after draining. Tests only. */
    fun shutdown() {
        queue.close()
    }

    private fun handle(cmd: Cmd) {
        when (cmd) {
            is Cmd.Start -> {
                if (streaming) runCatching { sink.abort() }
                streaming = true
                setDucking(true)
                try {
                    sink.start(cmd.rate, cmd.width, cmd.channels)
                } catch (e: Exception) {
                    fail(e)
                }
            }
            is Cmd.Chunk -> {
                if (!streaming) return
                try {
                    sink.write(cmd.pcm)
                } catch (e: Exception) {
                    fail(e)
                }
            }
            Cmd.Stop -> {
                if (!streaming) return
                streaming = false
                try {
                    sink.finish()
                } catch (e: Exception) {
                    Log.w(TAG, "finish failed", e)
                    runCatching { sink.abort() }
                }
                setDucking(false)
                onPlayed()
            }
            Cmd.Abort -> {
                if (!streaming) return
                streaming = false
                runCatching { sink.abort() }
                setDucking(false)
            }
        }
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "announce playback failed", e)
        streaming = false
        runCatching { sink.abort() }
        setDucking(false)
        onPlayed()
    }

    private companion object {
        const val TAG = "AnnouncePlayer"
    }
}
