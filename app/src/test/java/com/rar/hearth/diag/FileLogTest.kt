package com.rar.hearth.diag

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileLogTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = File.createTempFile("hearth-log-test", "").let { f ->
            f.delete(); f.mkdirs(); f
        }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun log(maxBytes: Long = 128L * 1024, nowMs: Long = 0L) =
        FileLog(dir, "log.txt", maxBytes, clock = { nowMs })

    @Test fun `appended lines come back from tail in order`() {
        val log = log()
        log.append("first")
        log.append("second")
        assertEquals("first\nsecond\n", log.tail())
    }

    @Test fun `tail is empty before anything is written`() {
        assertEquals("", log().tail())
    }

    @Test fun `rolling keeps the previous file and starts a new one`() {
        val log = log(maxBytes = 40)
        repeat(20) { log.append("line-$it") }
        assertTrue("expected a rolled file", File(dir, "log.txt.1").exists())
        assertTrue(File(dir, "log.txt").exists())
    }

    @Test fun `rolling never retains more than two files`() {
        val log = log(maxBytes = 20)
        repeat(200) { log.append("line-$it") }
        val logs = dir.list()!!.filter { it.startsWith("log.txt") }
        assertEquals(setOf("log.txt", "log.txt.1"), logs.toSet())
    }

    @Test fun `rolling keeps total size bounded by twice the cap`() {
        val cap = 200L
        val log = log(maxBytes = cap)
        repeat(500) { log.append("some reasonably long line number $it") }
        // Each file may overshoot by at most the final line that triggered the roll.
        assertTrue("retained ${log.sizeBytes()} bytes", log.sizeBytes() < 2 * cap + 200)
    }

    @Test fun `tail spans the previous roll and the current file, oldest first`() {
        val log = log(maxBytes = 30)
        log.append("oldest")
        repeat(5) { log.append("filler-$it") }
        log.append("newest")
        val tail = log.tail()
        assertTrue(tail.endsWith("newest\n"))
        assertTrue("expected content from both files", tail.indexOf("filler-") < tail.indexOf("newest"))
    }

    @Test fun `tail truncates from the front and keeps whole lines`() {
        val log = log()
        repeat(100) { log.append("line-$it") }
        val tail = log.tail(limitBytes = 50)
        assertTrue("got ${tail.length} bytes", tail.length <= 50)
        assertTrue("must end with the newest line", tail.endsWith("line-99\n"))
        // Front-truncation must land on a line boundary, never mid-line.
        assertTrue("first line was cut mid-way: ${tail.lineSequence().first()}",
            tail.lineSequence().first().startsWith("line-"))
    }

    @Test fun `tail returns everything when under the limit`() {
        val log = log()
        log.append("short")
        assertEquals("short\n", log.tail(limitBytes = 4096))
    }

    @Test fun `a new instance appends to the existing file rather than truncating it`() {
        log().append("before-restart")
        // Simulates the process restarting: same dir, brand-new FileLog.
        val reopened = log()
        reopened.append("after-restart")
        assertEquals("before-restart\nafter-restart\n", reopened.tail())
    }

    @Test fun `banner marks the session boundary with a timestamp`() {
        val log = log(nowMs = 0L)
        log.append("pre")
        log.banner("Hearth 0.2.484 started")
        // The stamp renders in the device's local zone, so assert its shape, not a fixed date.
        val banner = log.tail().lines().single { it.contains("Hearth 0.2.484 started") }
        assertTrue(
            banner,
            Regex("""^=+ Hearth 0\.2\.484 started @ \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} =+$""")
                .matches(banner),
        )
    }

    @Test fun `clear removes both files`() {
        val log = log(maxBytes = 20)
        repeat(50) { log.append("line-$it") }
        log.clear()
        assertEquals("", log.tail())
        assertEquals(0L, log.sizeBytes())
        assertFalse(File(dir, "log.txt").exists())
        assertFalse(File(dir, "log.txt.1").exists())
    }

    @Test fun `logging continues after a clear`() {
        val log = log()
        log.append("before")
        log.clear()
        log.append("after")
        assertEquals("after\n", log.tail())
    }

    @Test fun `append does not throw when the directory cannot be created`() {
        // A file where the directory should be: opening the log must fail silently.
        val blocked = File(dir, "blocked").apply { writeText("not a dir") }
        val log = FileLog(File(blocked, "sub"), "log.txt")
        log.append("swallowed")   // must not throw
        assertEquals("", log.tail())
    }

    @Test fun `concurrent appends do not lose or interleave lines`() {
        val log = log()
        val threads = (0 until 8).map { t ->
            Thread { repeat(100) { i -> log.append("t$t-$i") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val lines = log.tail().trim().lines()
        assertEquals(800, lines.size)
        // Every line must be intact — a torn write would produce a malformed entry.
        assertTrue(lines.all { Regex("""^t\d-\d+$""").matches(it) })
    }
}
