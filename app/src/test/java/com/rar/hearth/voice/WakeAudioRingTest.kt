package com.rar.hearth.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakeAudioRingTest {

    @get:Rule val tmp = TemporaryFolder()

    /** [n] bytes of a recognizable ramp, so wrap-order is verifiable. */
    private fun chunk(start: Int, n: Int) = ByteArray(n) { (start + it).toByte() }

    /** 1 s at 16 kHz = 32000 bytes; a 1-second ring is the smallest useful size. */
    private fun ring(seconds: Int = 1, maxFiles: Int = 20, clock: () -> Long = { 0L }) =
        WakeAudioRing(tmp.root, seconds = seconds, rate = 16000, maxFiles = maxFiles, clock = clock)

    @Test
    fun snapshotIsEmptyBeforeAnyAudio() {
        assertEquals(0, ring().snapshot().size)
    }

    @Test
    fun partialFillReturnsOnlyWhatWasWritten() {
        val r = ring()
        r.add(chunk(0, 960))
        val snap = r.snapshot()
        assertEquals(960, snap.size)
        assertEquals(chunk(0, 960).toList(), snap.toList())
    }

    @Test
    fun ringOverwritesOldestAndKeepsChronologicalOrder() {
        val r = ring()                       // capacity 32000
        r.add(chunk(0, 30000))
        r.add(chunk(0, 4000))                // 2000 bytes wrap, evicting the first 2000
        val snap = r.snapshot()
        assertEquals(32000, snap.size)
        // Oldest surviving byte is index 2000 of the first chunk; newest is the tail of the second.
        assertEquals(chunk(0, 30000)[2000], snap[0])
        assertEquals(chunk(0, 4000)[3999], snap[snap.size - 1])
    }

    @Test
    fun chunkLargerThanRingKeepsOnlyItsTail() {
        val r = ring()
        r.add(chunk(0, 40000))
        val snap = r.snapshot()
        assertEquals(32000, snap.size)
        assertEquals(chunk(0, 40000)[39999], snap[snap.size - 1])
    }

    @Test
    fun emptyChunkIsIgnored() {
        val r = ring()
        r.add(ByteArray(0))
        assertEquals(0, r.snapshot().size)
    }

    @Test
    fun dumpWritesAWavWhoseHeaderDescribesThePayload() {
        val r = ring()
        r.add(chunk(0, 3200))
        val file = r.dump("ok_ember", 0.86f)
        assertNotNull(file)
        val bytes = file!!.readBytes()
        assertEquals(44 + 3200, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(1, le16(bytes, 20))          // PCM
        assertEquals(1, le16(bytes, 22))          // mono
        assertEquals(16000, le32(bytes, 24))      // sample rate
        assertEquals(32000, le32(bytes, 28))      // byte rate
        assertEquals(16, le16(bytes, 34))         // bits per sample
        assertEquals(3200, le32(bytes, 40))       // data size
        assertEquals(36 + 3200, le32(bytes, 4))   // RIFF size
    }

    @Test
    fun dumpPayloadIsTheSnapshotVerbatim() {
        val r = ring()
        r.add(chunk(7, 1600))
        val bytes = r.dump("ok_ember", 0.5f)!!.readBytes()
        assertEquals(chunk(7, 1600).toList(), bytes.drop(44))
    }

    @Test
    fun dumpNameCarriesTheHeadAndScore() {
        val r = ring(clock = { 0L })
        r.add(chunk(0, 320))
        val name = r.dump("ok_ember", 0.86f)!!.name
        assertTrue(name, name.startsWith("wake-"))
        assertTrue(name, name.endsWith("-ok_ember-86.wav"))
    }

    @Test
    fun dumpScoreIsClampedIntoTwoDigits() {
        val r = ring()
        r.add(chunk(0, 320))
        assertTrue(r.dump("h", 1.0f)!!.name.endsWith("-h-99.wav"))
        assertTrue(r.dump("h", -1f)!!.name.endsWith("-h-00.wav"))
    }

    @Test
    fun dumpSanitizesHeadNamesIntoTheFilename() {
        val r = ring()
        r.add(chunk(0, 320))
        val name = r.dump("../evil name", 0.5f)!!.name
        // Dots go too, so a head name can never walk out of the capture directory.
        assertTrue(name, name.endsWith("-___evil_name-50.wav"))
        assertEquals(tmp.root, r.dump("../evil name", 0.5f)!!.parentFile)
    }

    @Test
    fun dumpReturnsNullWhenNothingIsBuffered() {
        assertNull(ring().dump("ok_ember", 0.9f))
    }

    @Test
    fun capturesAreListedNewestFirst() {
        var t = 1_000_000L
        val r = ring(clock = { t })
        val names = (1..3).map {
            r.add(chunk(0, 320))
            val f = r.dump("h$it", 0.5f)!!
            f.setLastModified(t)
            t += 60_000
            f.name
        }
        assertEquals(names.reversed(), r.captures().map { it.name })
    }

    @Test
    fun pruningKeepsTheNewestMaxFilesOnly() {
        var t = 1_000_000L
        val r = ring(maxFiles = 2, clock = { t })
        repeat(4) {
            r.add(chunk(0, 320))
            r.dump("h$it", 0.5f)!!.setLastModified(t)
            t += 60_000
        }
        val kept = r.captures().map { it.name }
        assertEquals(2, kept.size)
        assertTrue(kept.toString(), kept.all { it.contains("-h2-") || it.contains("-h3-") })
    }

    @Test
    fun clearDeletesEveryCaptureButLeavesTheRingIntact() {
        val r = ring()
        r.add(chunk(0, 320))
        r.dump("h", 0.5f)
        r.clear()
        assertEquals(0, r.captures().size)
        assertEquals(320, r.snapshot().size)
    }

    @Test
    fun unrelatedFilesInTheDirectoryAreNeverListedOrPruned() {
        val stray = File(tmp.root, "notes.txt").apply { writeText("keep me") }
        val r = ring(maxFiles = 1)
        r.add(chunk(0, 320))
        r.dump("h", 0.5f)
        assertEquals(0, r.captures().count { it.name == "notes.txt" })
        assertTrue(stray.exists())
    }

    @Test
    fun dumpFailureIsSwallowed() {
        // A regular file where the directory should be: mkdirs and the write both fail.
        val blocked = File(tmp.root, "blocked").apply { writeText("x") }
        val r = WakeAudioRing(blocked, seconds = 1, rate = 16000, clock = { 0L })
        r.add(chunk(0, 320))
        assertNull(r.dump("h", 0.5f))
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
}
