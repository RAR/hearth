package com.rar.hearth.voice

/**
 * Pure-JVM streaming openWakeWord inference: melspectrogram -> embedding -> wake-word head(s).
 * A port of wyoming-openwakeword / pyopen-wakeword (constants from research-openwakeword.md).
 * The TFLite graphs hide behind [TfGraph] so the whole pipeline — buffer shifts, the
 * x/10+2 transform, chunk accumulation, threshold, refractory, and warm-up — is testable with
 * fake graphs and no Android/TFLite imports. All threading lives in SatelliteServer; this class
 * is not thread-safe and must be driven from a single thread.
 *
 * PCM in is little-endian 16-bit mono @ 16 kHz, converted to RAW int16-valued floats (the
 * melspec model was trained on un-normalized sample values, not ±1).
 *
 * Multiple [Head]s share the one expensive backbone (melspec + embedding runs ONCE per chunk);
 * each head is a trivial dense pass over the same 16x96 embedding ring, so running several costs
 * almost nothing. [heads]\[0] is the primary wake word; extra heads (e.g. an always-on "stop"
 * that only means something while a timer alarm rings) let the caller decide per-head what a
 * detection means. Warm-up and the post-fire refractory are GLOBAL across heads (see [processChunk]).
 */
class WakeDetector(
    private val melspec: TfGraph,
    private val embedding: TfGraph,
    private val heads: List<Head>,
    private val nowMs: () -> Long,
) {
    init { require(heads.isNotEmpty()) { "WakeDetector needs at least one head" } }

    /** One TFLite graph: flat float input -> flat float output. */
    fun interface TfGraph {
        fun run(input: FloatArray): FloatArray
    }

    /** A wake-word classifier over the shared embedding ring, with its own firing threshold. */
    class Head(val name: String, val graph: TfGraph, val thresholdPct: Int)

    // Ring buffers, all zero-initialized (warm-up state).
    private val audioRing = FloatArray(MEL_SAMPLES)                 // 1760 = 480 context + 1280 new
    private val melRing = FloatArray(EMB_FEATURES * NUM_MELS)       // 76 x 32
    private val embRing = FloatArray(EMB_WINDOW * WW_FEATURES)      // 16 x 96
    private var pending = FloatArray(0)                             // leftover samples (< 1280)
    private var chunksProcessed = 0
    private var lastDetectMs: Long? = null

    // Most recent score per head name (for logging/tuning); the primary head is also mirrored
    // into [lastScore] so existing callers/log lines keep working unchanged.
    private val lastScores = HashMap<String, Float>()

    /** Score of the most recently processed chunk for the PRIMARY head (for logging/tuning). */
    var lastScore: Float = 0f
        private set

    /** Most recent score for head [name] (0 if that head never ran / no such head). */
    fun lastScoreOf(name: String): Float = lastScores[name] ?: 0f

    /**
     * False until the warm-up window has passed, i.e. while [lastScore] is still meaningless.
     *
     * The rings start zero-filled and produce garbage embeddings, which score arbitrarily high —
     * [processChunk] already refuses to fire on them. Anything else that reads a score (audio
     * capture, logging, tuning) has to make the same check, or it will treat startup noise as a
     * detection.
     */
    val isWarm: Boolean get() = chunksProcessed > WARMUP_CHUNKS

    /**
     * Feed a mic PCM chunk. Returns the names of the heads that fired during this call, in head
     * order (usually empty; occasionally one). All heads run on every chunk against the same
     * embedding ring, so more than one can fire on the same chunk.
     */
    fun process(pcm: ByteArray): List<String> {
        if (pcm.size < 2) return emptyList()
        val n = pcm.size / 2
        val incoming = FloatArray(n) { i ->
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()          // sign-extends -> correct signed 16-bit
            ((hi shl 8) or lo).toFloat()
        }
        val combined = if (pending.isEmpty()) incoming else pending + incoming
        var offset = 0
        val fired = mutableListOf<String>()
        while (combined.size - offset >= SAMPLES_PER_CHUNK) {
            fired += processChunk(combined, offset)
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
        lastScores.clear()
    }

    private fun processChunk(src: FloatArray, offset: Int): List<String> {
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

        // Heads: the backbone above ran ONCE; every head is a trivial 16 x 96 -> 1 dense pass over
        // the SAME embedding ring. Score them all first (cheap) so logging/tuning sees every head.
        for (h in heads) lastScores[h.name] = h.graph.run(embRing)[0]
        lastScore = lastScores[heads[0].name] ?: 0f
        chunksProcessed++

        // Warm-up: never trust the first 16 chunks (~1.3 s) after construction/reset.
        if (chunksProcessed <= WARMUP_CHUNKS) return emptyList()
        // Refractory: 2 s wall-clock cooldown after ANY head's detection. Global on purpose —
        // after a wake fire the session leaves DETECTING anyway, and after a stop fire a 2 s gap
        // before the next fire is harmless. Checked once, before scoring the heads, so two heads
        // that cross their thresholds on the SAME chunk both fire (they share this one cooldown).
        val now = nowMs()
        val last = lastDetectMs
        if (last != null && now - last < REFRACTORY_MS) return emptyList()
        // trigger_level 1: fire on the first frame strictly above a head's own threshold.
        var fired: MutableList<String>? = null
        for (h in heads) {
            if ((lastScores[h.name] ?: 0f) > h.thresholdPct / 100f) {
                lastDetectMs = now
                (fired ?: mutableListOf<String>().also { fired = it }) += h.name
            }
        }
        return fired ?: emptyList()
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
