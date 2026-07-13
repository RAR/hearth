package com.rar.echodash.voice

/**
 * Pure-JVM streaming openWakeWord inference: melspectrogram -> embedding -> wake-word head.
 * A port of wyoming-openwakeword / pyopen-wakeword (constants from research-openwakeword.md).
 * The three TFLite graphs hide behind [TfGraph] so the whole pipeline — buffer shifts, the
 * x/10+2 transform, chunk accumulation, threshold, refractory, and warm-up — is testable with
 * fake graphs and no Android/TFLite imports. All threading lives in SatelliteServer; this class
 * is not thread-safe and must be driven from a single thread.
 *
 * PCM in is little-endian 16-bit mono @ 16 kHz, converted to RAW int16-valued floats (the
 * melspec model was trained on un-normalized sample values, not ±1).
 */
class WakeDetector(
    private val melspec: TfGraph,
    private val embedding: TfGraph,
    private val head: TfGraph,
    private val thresholdPct: Int,
    private val nowMs: () -> Long,
) {
    /** One TFLite graph: flat float input -> flat float output. */
    fun interface TfGraph {
        fun run(input: FloatArray): FloatArray
    }

    // Ring buffers, all zero-initialized (warm-up state).
    private val audioRing = FloatArray(MEL_SAMPLES)                 // 1760 = 480 context + 1280 new
    private val melRing = FloatArray(EMB_FEATURES * NUM_MELS)       // 76 x 32
    private val embRing = FloatArray(EMB_WINDOW * WW_FEATURES)      // 16 x 96
    private var pending = FloatArray(0)                             // leftover samples (< 1280)
    private var chunksProcessed = 0
    private var lastDetectMs: Long? = null

    /** Score of the most recently processed chunk (for logging/tuning). */
    var lastScore: Float = 0f
        private set

    /** Feed a mic PCM chunk. Returns true iff a wake detection fired during this call. */
    fun process(pcm: ByteArray): Boolean {
        if (pcm.size < 2) return false
        val n = pcm.size / 2
        val incoming = FloatArray(n) { i ->
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()          // sign-extends -> correct signed 16-bit
            ((hi shl 8) or lo).toFloat()
        }
        val combined = if (pending.isEmpty()) incoming else pending + incoming
        var offset = 0
        var fired = false
        while (combined.size - offset >= SAMPLES_PER_CHUNK) {
            if (processChunk(combined, offset)) fired = true
            offset += SAMPLES_PER_CHUNK
        }
        pending = if (offset < combined.size) combined.copyOfRange(offset, combined.size) else FloatArray(0)
        return fired
    }

    /** Re-arm: clear all buffers and the refractory timer, restart the 16-chunk warm-up. */
    fun reset() {
        audioRing.fill(0f)
        melRing.fill(0f)
        embRing.fill(0f)
        pending = FloatArray(0)
        chunksProcessed = 0
        lastDetectMs = null
        lastScore = 0f
    }

    private fun processChunk(src: FloatArray, offset: Int): Boolean {
        // Audio window: keep the trailing 480 samples of the previous window as left-context,
        // then append the new 1280.
        System.arraycopy(audioRing, SAMPLES_PER_CHUNK, audioRing, 0, CONTEXT)
        System.arraycopy(src, offset, audioRing, CONTEXT, SAMPLES_PER_CHUNK)

        // Melspec: 1760 samples -> 8 x 32 frames; apply x/10 + 2 exactly once, then shift the
        // mel ring left by 8 frames and append.
        val mel = melspec.run(audioRing)                 // 8 * 32 = 256
        val shift = MEL_FRAMES_PER_CHUNK * NUM_MELS       // 256
        System.arraycopy(melRing, shift, melRing, 0, melRing.size - shift)
        val base = melRing.size - shift                  // (76 - 8) * 32 = 2176
        for (i in 0 until shift) melRing[base + i] = mel[i] / 10f + 2f

        // Embedding: 76 x 32 -> 96; shift the embedding ring left by 1 and append.
        val emb = embedding.run(melRing)                 // 96
        System.arraycopy(embRing, WW_FEATURES, embRing, 0, embRing.size - WW_FEATURES)
        System.arraycopy(emb, 0, embRing, embRing.size - WW_FEATURES, WW_FEATURES)

        // Head: 16 x 96 -> 1 score.
        val out = head.run(embRing)
        lastScore = out[0]
        chunksProcessed++

        // Warm-up: never trust the first 16 chunks (~1.3 s) after construction/reset.
        if (chunksProcessed <= WARMUP_CHUNKS) return false
        // Refractory: 2 s wall-clock cooldown after any detection.
        val now = nowMs()
        val last = lastDetectMs
        if (last != null && now - last < REFRACTORY_MS) return false
        // trigger_level 1: fire on the first frame strictly above threshold.
        if (lastScore > thresholdPct / 100f) {
            lastDetectMs = now
            return true
        }
        return false
    }

    private companion object {
        const val SAMPLES_PER_CHUNK = 1280      // 80 ms @ 16 kHz
        const val CONTEXT = 480                 // 3 STFT hops of left-context
        const val MEL_SAMPLES = 1760            // CONTEXT + SAMPLES_PER_CHUNK
        const val NUM_MELS = 32
        const val MEL_FRAMES_PER_CHUNK = 8      // ceil(1760/160 - 3)
        const val EMB_FEATURES = 76             // mel-frame window into the embedding model
        const val WW_FEATURES = 96              // embedding dimension
        const val EMB_WINDOW = 16               // embeddings the head sees
        const val WARMUP_CHUNKS = 16
        const val REFRACTORY_MS = 2000L
    }
}
