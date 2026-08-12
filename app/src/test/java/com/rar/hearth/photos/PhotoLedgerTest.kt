package com.rar.hearth.photos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoLedgerTest {

    private fun tempDir(): File =
        File.createTempFile("ledger", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun roundTripsKeys() {
        val ledger = PhotoLedger(File(tempDir(), "seen.txt"))

        ledger.add(listOf("a", "b"))

        assertEquals(setOf("a", "b"), ledger.read())
    }

    @Test
    fun addAccumulatesAcrossCalls() {
        val ledger = PhotoLedger(File(tempDir(), "seen.txt"))

        ledger.add(listOf("a"))
        ledger.add(listOf("b"))
        ledger.add(listOf("a"))

        assertEquals(setOf("a", "b"), ledger.read())
    }

    @Test
    fun missingFileReadsEmpty() {
        val ledger = PhotoLedger(File(tempDir(), "never-written.txt"))

        assertTrue(ledger.read().isEmpty())
    }

    @Test
    fun blankLinesAreIgnored() {
        val file = File(tempDir(), "seen.txt")
        file.writeText("a\n\n  \nb\n")

        assertEquals(setOf("a", "b"), PhotoLedger(file).read())
    }

    @Test
    fun clearStartsAFreshEpoch() {
        val ledger = PhotoLedger(File(tempDir(), "seen.txt"))
        ledger.add(listOf("a", "b"))

        ledger.clear()

        assertTrue(ledger.read().isEmpty())
    }

    @Test
    fun clearOnAMissingFileIsHarmless() {
        val ledger = PhotoLedger(File(tempDir(), "seen.txt"))

        ledger.clear()

        assertTrue(ledger.read().isEmpty())
    }

    @Test
    fun addingNothingIsANoOp() {
        val file = File(tempDir(), "seen.txt")
        val ledger = PhotoLedger(file)

        ledger.add(emptyList())

        assertTrue(ledger.read().isEmpty())
        assertTrue(!file.exists())
    }

    @Test
    fun unreadableLedgerDegradesToEmptyRatherThanThrowing() {
        // A directory where the ledger file is expected: readLines() throws, read() must not.
        val dir = tempDir()
        val asDirectory = File(dir, "seen.txt").also { it.mkdirs() }

        assertTrue(PhotoLedger(asDirectory).read().isEmpty())
    }

    @Test
    fun writesParentDirectoryIfAbsent() {
        val nested = File(tempDir(), "nested/seen.txt")

        PhotoLedger(nested).add(listOf("a"))

        assertEquals(setOf("a"), PhotoLedger(nested).read())
    }
}
