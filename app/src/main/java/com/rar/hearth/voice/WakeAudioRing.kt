package com.rar.hearth.voice

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A rolling in-memory buffer of the most recent mic audio, dumped to a WAV when a score crosses
 * the capture floor (voice.captureThreshold — deliberately below the wake threshold, so
 * near-misses are collected without triggering a wake session for each one).
 *
 * Chasing a false positive is otherwise blind: the detector scores audio and discards it, so all
 * that survives is a number in the log. Reading the mic from outside the app is not an option on
 * these devices — the capture PCM is only reachable through the HAL, and a userspace ALSA open
 * returns digital silence. Keeping the last [seconds] here and writing it out on a fire captures
 * the offending audio exactly as the model heard it: same rate, same gain, same preprocessing.
 * Those WAVs feed [tools/score-wake-model.py] for offline reproduction and become the negative
 * examples a retrain needs.
 *
 * Off by default (voice.captureOnWake) — this writes room audio to flash, so it is opt-in and
 * meant to be switched on only while hunting a specific false positive.
 *
 * Retention is bounded two ways: the ring itself is a fixed allocation, and [maxFiles] caps what
 * accumulates on disk (oldest deleted first). All IO failures are swallowed — a capture problem
 * must never take down the mic path. Not thread-safe by design: [add] and [dump] are both called
 * from the single detector thread.
 */
class WakeAudioRing(
    private val dir: File,
    seconds: Int = 10,
    private val rate: Int = 16000,
    private val maxFiles: Int = 60,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Even by construction (2 bytes per sample), and every chunk written is an even length, so the
    // write position stays sample-aligned and a wrap can never split a sample in half.
    private val buf = ByteArray(seconds * rate * BYTES_PER_SAMPLE)
    private var pos = 0
    private var filled = false

    /** Append one PCM chunk (16-bit LE mono at [rate]), overwriting the oldest audio. */
    fun add(pcm: ByteArray) {
        if (pcm.isEmpty() || buf.isEmpty()) return
        // A chunk larger than the whole ring would wrap onto itself; keep only its tail.
        val src = if (pcm.size >= buf.size) pcm.size - buf.size else 0
        val n = pcm.size - src
        val head = minOf(n, buf.size - pos)
        System.arraycopy(pcm, src, buf, pos, head)
        if (head < n) System.arraycopy(pcm, src + head, buf, 0, n - head)
        pos += n
        if (pos >= buf.size) { pos -= buf.size; filled = true }
    }

    /** The retained audio, oldest sample first. */
    fun snapshot(): ByteArray =
        if (!filled) buf.copyOf(pos)
        else ByteArray(buf.size).also {
            System.arraycopy(buf, pos, it, 0, buf.size - pos)
            System.arraycopy(buf, 0, it, buf.size - pos, pos)
        }

    /**
     * Write the retained audio to `<dir>/wake-<stamp>-<head>-<score>.wav` and prune old captures.
     * Returns the file, or null if there was nothing buffered or the write failed.
     */
    fun dump(head: String, score: Float): File? {
        val pcm = snapshot()
        if (pcm.isEmpty()) return null
        return runCatching {
            dir.mkdirs()
            val name = "wake-%s-%s-%02d.wav".format(
                STAMP_FMT.format(Date(clock())),
                head.replace(Regex("[^A-Za-z0-9_-]"), "_"),
                (score * 100).toInt().coerceIn(0, 99),
            )
            val file = File(dir, name)
            file.writeBytes(wavHeader(pcm.size) + pcm)
            prune()
            file
        }.getOrNull()
    }

    /** Captures on disk, newest first. */
    fun captures(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith("wake-") && f.name.endsWith(".wav") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    /** Delete every capture; the in-memory ring is untouched. */
    fun clear() {
        captures().forEach { runCatching { it.delete() } }
    }

    private fun prune() {
        captures().drop(maxFiles).forEach { runCatching { it.delete() } }
    }

    private fun wavHeader(dataBytes: Int): ByteArray {
        val byteRate = rate * BYTES_PER_SAMPLE
        val h = ByteArray(44)
        var i = 0
        fun ascii(s: String) { for (c in s) h[i++] = c.code.toByte() }
        fun le32(v: Int) { h[i++] = v.toByte(); h[i++] = (v shr 8).toByte(); h[i++] = (v shr 16).toByte(); h[i++] = (v shr 24).toByte() }
        fun le16(v: Int) { h[i++] = v.toByte(); h[i++] = (v shr 8).toByte() }
        ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1)          // PCM, mono
        le32(rate); le32(byteRate); le16(BYTES_PER_SAMPLE); le16(16)
        ascii("data"); le32(dataBytes)
        return h
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        val STAMP_FMT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
