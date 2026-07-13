package com.rar.echodash.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun melspecRunsOncePerCompleteChunkCarryingRemainder() {
        val mel = zeros256()
        val d = WakeDetector(mel, zeros96(), score(0f), 50) { 0L }
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
        val d = WakeDetector(mel, zeros96(), score(0f), 50) { 0L }
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
        val d = WakeDetector(mel, zeros96(), score(0f), 50) { 0L }
        d.process(pcm(1280, -5))
        for (i in 480 until 1760) assertEquals(-5f, mel.inputs[0][i], 1e-6f)
    }

    @Test
    fun melTransformXOver10Plus2AppliedOnce() {
        val mel = RecordingGraph { FloatArray(256) { 30f } }
        val emb = zeros96()
        val d = WakeDetector(mel, emb, score(0f), 50) { 0L }
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
        val d = WakeDetector(mel, emb, score(0f), 50) { 0L }
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
        val d = WakeDetector(zeros256(), emb, head, 50) { 0L }
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
        val d = WakeDetector(zeros256(), zeros96(), score(0.9f), 50) { 0L }
        assertFalse(d.process(pcm(1280 * 16, 1)))   // first 16 chunks suppressed
        assertTrue(d.process(pcm(1280, 1)))         // 17th chunk fires
        assertEquals(0.9f, d.lastScore, 1e-6f)
    }

    @Test
    fun detectionIsStrictlyAboveThreshold() {
        val d = WakeDetector(zeros256(), zeros96(), score(0.5f), 50) { 0L }
        assertFalse(d.process(pcm(1280 * 17, 1)))   // 0.5 is not > 0.5
    }

    @Test
    fun refractorySuppressesWithinTwoSecondsThenReleases() {
        var clock = 0L
        val d = WakeDetector(zeros256(), zeros96(), score(0.9f), 50) { clock }
        d.process(pcm(1280 * 16, 1))         // warm up (clock 0)
        clock = 1000
        assertTrue(d.process(pcm(1280, 1)))  // chunk 17 fires at t=1000
        clock = 2000                          // 1000 ms later, < 2000 refractory
        assertFalse(d.process(pcm(1280, 1)))
        clock = 3001                          // 2001 ms after the fire, past refractory
        assertTrue(d.process(pcm(1280, 1)))
    }

    @Test
    fun resetReArmsWarmupAndClearsRefractory() {
        val d = WakeDetector(zeros256(), zeros96(), score(0.9f), 50) { 0L }
        assertTrue(d.process(pcm(1280 * 17, 1)))    // fires once
        d.reset()
        assertFalse(d.process(pcm(1280 * 16, 1)))   // warm-up suppresses 16 again
        assertTrue(d.process(pcm(1280, 1)))         // 17th fires (no refractory carried over)
    }
}
