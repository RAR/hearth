package com.rar.hearth.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeDetectorTest {

    /** Fake TfGraph that records every input (copied) and returns a scripted output. */
    private class RecordingGraph(val transform: (FloatArray) -> FloatArray) : WakeDetector.TfGraph {
        val inputs = mutableListOf<FloatArray>()
        override fun run(input: FloatArray): FloatArray {
            inputs.add(input.copyOf())
            return transform(input)
        }
    }

    /** n samples all equal to [value], little-endian 16-bit. */
    private fun pcm(n: Int, value: Int): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            b[2 * i] = (value and 0xFF).toByte()
            b[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun zeros256() = RecordingGraph { FloatArray(256) }
    private fun zeros96() = RecordingGraph { FloatArray(96) }
    private fun score(v: Float) = RecordingGraph { floatArrayOf(v) }

    /** Single-head detector, the shape every backbone test uses. */
    private fun detector(
        mel: WakeDetector.TfGraph,
        emb: WakeDetector.TfGraph,
        head: WakeDetector.TfGraph,
        thresholdPct: Int = 50,
        nowMs: () -> Long = { 0L },
    ) = WakeDetector(mel, emb, listOf(WakeDetector.Head("wake", head, thresholdPct)), nowMs)

    @Test
    fun melspecRunsOncePerCompleteChunkCarryingRemainder() {
        val mel = zeros256()
        val d = detector(mel, zeros96(), score(0f), 50) { 0L }
        d.process(pcm(640, 1))            // half a chunk -> nothing yet
        assertEquals(0, mel.inputs.size)
        d.process(pcm(640, 1))            // completes 1280 -> one melspec call
        assertEquals(1, mel.inputs.size)
        d.process(pcm(1280, 1))           // exactly one more chunk
        assertEquals(2, mel.inputs.size)
    }

    @Test
    fun audioWindowIs1760With480Context() {
        val mel = zeros256()
        val d = detector(mel, zeros96(), score(0f), 50) { 0L }
        d.process(pcm(1280, 100))
        d.process(pcm(1280, 200))
        assertEquals(1760, mel.inputs[0].size)
        // First chunk: 480 zero context, then 1280 of value 100.
        for (i in 0 until 480) assertEquals(0f, mel.inputs[0][i], 1e-6f)
        for (i in 480 until 1760) assertEquals(100f, mel.inputs[0][i], 1e-6f)
        // Second chunk: context = trailing 480 of the previous chunk (100), then 1280 of 200.
        for (i in 0 until 480) assertEquals(100f, mel.inputs[1][i], 1e-6f)
        for (i in 480 until 1760) assertEquals(200f, mel.inputs[1][i], 1e-6f)
    }

    @Test
    fun pcmDecodedAsSignedLittleEndian() {
        val mel = zeros256()
        val d = detector(mel, zeros96(), score(0f), 50) { 0L }
        d.process(pcm(1280, -5))
        for (i in 480 until 1760) assertEquals(-5f, mel.inputs[0][i], 1e-6f)
    }

    @Test
    fun melTransformXOver10Plus2AppliedOnce() {
        val mel = RecordingGraph { FloatArray(256) { 30f } }
        val emb = zeros96()
        val d = detector(mel, emb, score(0f), 50) { 0L }
        d.process(pcm(1280, 1))
        val melRing = emb.inputs[0]          // 76*32 = 2432, last 8 frames are the new ones
        assertEquals(2432, melRing.size)
        assertEquals(0f, melRing[0], 1e-6f)              // oldest frame still zero
        assertEquals(5f, melRing[68 * 32], 1e-6f)        // 30/10 + 2 = 5, first new frame
        assertEquals(5f, melRing[2431], 1e-6f)           // last element
    }

    @Test
    fun melRingShiftsByEight() {
        var call = 0
        val mel = RecordingGraph { call += 1; val v = if (call == 1) 30f else 70f; FloatArray(256) { v } }
        val emb = zeros96()
        val d = detector(mel, emb, score(0f), 50) { 0L }
        d.process(pcm(2560, 1))              // two chunks in one call
        val ring2 = emb.inputs[1]            // mel ring at the second chunk
        assertEquals(5f, ring2[60 * 32], 1e-6f)   // chunk1 frames (30->5) shifted left by 8 to 60..67
        assertEquals(9f, ring2[68 * 32], 1e-6f)   // chunk2 frames (70->9) are the newest 8
        assertEquals(0f, ring2[59 * 32], 1e-6f)   // still zero before the shifted block
    }

    @Test
    fun embeddingRingShiftsByOneAndHeadSeesLast16() {
        var c = 0
        val emb = RecordingGraph { c += 1; FloatArray(96) { c.toFloat() } }  // call1->1, call2->2, ...
        val head = score(0f)
        val d = detector(zeros256(), emb, head, 50) { 0L }
        d.process(pcm(1280 * 3, 1))          // three chunks -> three embeddings
        val hin = head.inputs[2]             // head input at the third chunk
        assertEquals(1536, hin.size)         // 16 * 96
        assertEquals(3f, hin[15 * 96], 1e-6f)   // newest embedding at row 15
        assertEquals(2f, hin[14 * 96], 1e-6f)
        assertEquals(1f, hin[13 * 96], 1e-6f)
        assertEquals(0f, hin[12 * 96], 1e-6f)   // rows before that still zero
    }

    @Test
    fun warmupSuppressesFirst16ChunksThenFires() {
        val d = detector(zeros256(), zeros96(), score(0.9f), 50) { 0L }
        assertTrue(d.process(pcm(1280 * 16, 1)).isEmpty())   // first 16 chunks suppressed
        assertEquals(listOf("wake"), d.process(pcm(1280, 1)))  // 17th chunk fires
        assertEquals(0.9f, d.lastScore, 1e-6f)
    }

    @Test
    fun detectionIsStrictlyAboveThreshold() {
        val d = detector(zeros256(), zeros96(), score(0.5f), 50) { 0L }
        assertTrue(d.process(pcm(1280 * 17, 1)).isEmpty())   // 0.5 is not > 0.5
    }

    @Test
    fun refractorySuppressesWithinTwoSecondsThenReleases() {
        var clock = 0L
        val d = detector(zeros256(), zeros96(), score(0.9f), 50) { clock }
        d.process(pcm(1280 * 16, 1))                          // warm up (clock 0)
        clock = 1000
        assertEquals(listOf("wake"), d.process(pcm(1280, 1))) // chunk 17 fires at t=1000
        clock = 2000                                          // 1000 ms later, < 2000 refractory
        assertTrue(d.process(pcm(1280, 1)).isEmpty())
        clock = 3001                                          // 2001 ms after the fire, past refractory
        assertEquals(listOf("wake"), d.process(pcm(1280, 1)))
    }

    @Test
    fun resetReArmsWarmupAndClearsRefractory() {
        val d = detector(zeros256(), zeros96(), score(0.9f), 50) { 0L }
        assertEquals(listOf("wake"), d.process(pcm(1280 * 17, 1)))  // fires once
        d.reset()
        assertTrue(d.process(pcm(1280 * 16, 1)).isEmpty())         // warm-up suppresses 16 again
        assertEquals(listOf("wake"), d.process(pcm(1280, 1)))      // 17th fires (no refractory carried over)
    }

    // ---- multiple heads over the shared backbone ----

    @Test
    fun secondHeadFiresIndependentlyWithItsOwnThreshold() {
        // Primary stays below its 50% threshold; the "stop" head crosses its lower 30% threshold.
        val d = WakeDetector(
            melspec = zeros256(),
            embedding = zeros96(),
            heads = listOf(
                WakeDetector.Head("okay_nabu", score(0.20f), thresholdPct = 50),
                WakeDetector.Head("stop", score(0.40f), thresholdPct = 30),
            ),
            nowMs = { 0L },
        )
        assertEquals(listOf("stop"), d.process(pcm(1280 * 17, 1)))
        // Per-head scores are tracked; lastScore mirrors the primary head.
        assertEquals(0.20f, d.lastScore, 1e-6f)
        assertEquals(0.20f, d.lastScoreOf("okay_nabu"), 1e-6f)
        assertEquals(0.40f, d.lastScoreOf("stop"), 1e-6f)
    }

    @Test
    fun bothHeadsCanFireOnTheSameChunk() {
        // Both cross their thresholds on chunk 17; names come back in head order.
        val d = WakeDetector(
            melspec = zeros256(),
            embedding = zeros96(),
            heads = listOf(
                WakeDetector.Head("okay_nabu", score(0.9f), thresholdPct = 50),
                WakeDetector.Head("stop", score(0.9f), thresholdPct = 30),
            ),
            nowMs = { 0L },
        )
        assertEquals(listOf("okay_nabu", "stop"), d.process(pcm(1280 * 17, 1)))
    }

    @Test
    fun refractoryAfterOneHeadBlocksAnother() {
        // Head A fires as soon as warm-up ends; head B only crosses threshold one chunk later,
        // by which time the GLOBAL refractory (set by A's fire) is still holding everything off.
        var clock = 0L
        var bCalls = 0
        val headB = WakeDetector.TfGraph { bCalls++; floatArrayOf(if (bCalls >= 18) 0.9f else 0f) }
        val d = WakeDetector(
            melspec = zeros256(),
            embedding = zeros96(),
            heads = listOf(
                WakeDetector.Head("A", score(0.9f), thresholdPct = 50),
                WakeDetector.Head("B", headB, thresholdPct = 50),
            ),
            nowMs = { clock },
        )
        d.process(pcm(1280 * 16, 1))                          // warm up (clock 0)
        clock = 1000
        assertEquals(listOf("A"), d.process(pcm(1280, 1)))    // chunk 17: only A is hot -> A fires
        clock = 2000                                          // chunk 18 is within A's 2 s refractory
        assertTrue(d.process(pcm(1280, 1)).isEmpty())         // B is hot now but the cooldown blocks it
        clock = 3001                                          // past the refractory
        assertEquals(listOf("A", "B"), d.process(pcm(1280, 1))) // both fire once the cooldown clears
    }
}
