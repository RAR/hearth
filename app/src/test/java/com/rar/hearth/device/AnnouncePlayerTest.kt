package com.rar.hearth.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncePlayerTest {

    private class FakeSink(var failOnWrite: Boolean = false) : PcmSink {
        val calls = mutableListOf<String>()
        override fun start(rateHz: Int, widthBytes: Int, channels: Int) {
            calls += "start:$rateHz/$widthBytes/$channels"
        }
        override fun write(pcm: ByteArray) {
            if (failOnWrite) throw IOException("boom")
            calls += "write:${pcm.size}"
        }
        override fun finish() { calls += "finish" }
        override fun abort() { calls += "abort" }
    }

    private class Harness(scope: CoroutineScope, failOnWrite: Boolean = false) {
        val sink = FakeSink(failOnWrite)
        var playedCount = 0
        val ducks = mutableListOf<Boolean>()
        val player = AnnouncePlayer(scope, sink, onPlayed = { playedCount++ }, setDucking = { ducks += it })
    }

    @Test
    fun playsStreamThenSendsPlayedAndUnducks() = runTest {
        val h = Harness(this)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(2048))
        h.player.onAudioChunk(ByteArray(1024))
        h.player.onAudioStop()
        h.player.shutdown()
        advanceUntilIdle()
        assertEquals(listOf("start:22050/2/1", "write:2048", "write:1024", "finish"), h.sink.calls)
        assertEquals(1, h.playedCount)
        assertEquals(listOf(true, false), h.ducks)
    }

    @Test
    fun enqueueCallsReturnWithoutRunningTheWorker() = runTest {
        // The server's reader thread must never be blocked: on* methods only
        // enqueue. Nothing may touch the sink until the worker runs.
        val h = Harness(this)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(10))
        assertEquals(0, h.sink.calls.size)
        assertEquals(0, h.ducks.size)
        h.player.shutdown()
        advanceUntilIdle()
        assertEquals(listOf("start:22050/2/1", "write:10"), h.sink.calls)
    }

    @Test
    fun sinkFailureStillSendsPlayedExactlyOnce() = runTest {
        val h = Harness(this, failOnWrite = true)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(10))   // fails -> abort + played
        h.player.onAudioChunk(ByteArray(10))   // ignored
        h.player.onAudioStop()                 // ignored, no double played
        h.player.shutdown()
        advanceUntilIdle()
        assertEquals(1, h.playedCount)
        assertEquals(listOf(true, false), h.ducks)
        assertEquals(listOf("start:22050/2/1", "abort"), h.sink.calls)
    }

    @Test
    fun disconnectAbortsWithoutPlayed() = runTest {
        val h = Harness(this)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(10))
        h.player.onDisconnected()
        h.player.shutdown()
        advanceUntilIdle()
        assertEquals(0, h.playedCount)
        assertEquals(listOf(true, false), h.ducks)
        assertEquals("abort", h.sink.calls.last())
    }

    @Test
    fun eventsOutsideAStreamAreIgnored() = runTest {
        val h = Harness(this)
        h.player.onAudioChunk(ByteArray(10))
        h.player.onAudioStop()
        h.player.onDisconnected()
        h.player.shutdown()
        advanceUntilIdle()
        assertEquals(0, h.playedCount)
        assertEquals(0, h.sink.calls.size)
        assertEquals(0, h.ducks.size)
    }

    @Test
    fun restartMidStreamAbortsPreviousStream() = runTest {
        val h = Harness(this)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioStop()
        h.player.shutdown()
        advanceUntilIdle()
        assertEquals(listOf("start:22050/2/1", "abort", "start:22050/2/1", "finish"), h.sink.calls)
        assertEquals(1, h.playedCount)
    }

    @Test
    fun abortStopsPlaybackWithoutPlayed() = runTest {
        val h = Harness(this)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(10))
        h.player.abort()
        h.player.shutdown()
        advanceUntilIdle()
        assertEquals(0, h.playedCount)                       // no onPlayed on abort command
        assertEquals(listOf(true, false), h.ducks)           // ducked then un-ducked, exactly once each
        assertEquals("abort", h.sink.calls.last())           // sink aborted (dropped buffer)
    }

    @Test
    fun abortAbortsSinkSynchronouslyBeforeWorkerRuns() = runTest {
        // Cross-thread interrupt: HA has usually already sent audio-stop by the time
        // the user taps, so the worker may be blocked inside handle(Cmd.Stop)'s
        // sink.finish() drain. abort() must not wait behind that — it aborts the
        // sink directly on the caller thread. Pin that here: without ever pumping
        // the worker (no advanceUntilIdle), the sink must already show "abort".
        val h = Harness(this)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(10))
        h.player.abort()
        assertEquals(listOf("abort"), h.sink.calls) // synchronous — worker hasn't run at all yet
        h.player.shutdown()
        advanceUntilIdle()
        assertEquals(0, h.playedCount)
        assertEquals(listOf(true, false), h.ducks)
    }
}
