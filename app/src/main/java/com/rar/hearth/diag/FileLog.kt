package com.rar.hearth.diag

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling text log on disk, so a fault survives the reboot that fixes it.
 *
 * The kiosk keeps nothing but logcat, which is a volatile ring buffer wiped on
 * restart — exactly when a wedged audio HAL or a crash-loop is most worth reading.
 * This keeps the last [maxBytes] × 2 of log lines in `filesDir` instead, readable
 * afterwards from the config page.
 *
 * Two files are kept: [name] holds the current session, `<name>.1` the previous
 * roll. When the current file passes [maxBytes] it becomes `.1` (discarding the
 * older `.1`) and a fresh file starts, so retention is between [maxBytes] and
 * 2 × [maxBytes] — never unbounded, which matters on devices with ~1 GB of flash
 * that run for months.
 *
 * Every method swallows IO failures: logging must never be the thing that breaks
 * the dashboard. Writes are serialized on [lock] because the callers (logcat pump,
 * crash handler) are on different threads.
 */
class FileLog(
    private val dir: File,
    private val name: String = "hearth-log.txt",
    // 1 MB × 2 files. Sized against the measured post-filter rate of ~7 KB/min (see
    // DiagLog.FILTERS), which retains roughly 2.5–5 hours — long enough that a fault
    // noticed hours after a reboot is still in the file. Trivial against ~1 GB of flash.
    private val maxBytes: Long = 1024L * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val current = File(dir, name)
    private val previous = File(dir, "$name.1")

    private var writer: Writer? = null
    private var written: Long = 0

    /** Append one line (a newline is added). Never throws. */
    fun append(line: String) {
        synchronized(lock) {
            runCatching {
                val w = writer ?: open()
                w.write(line)
                w.write("\n")
                w.flush()
                // +1 for the newline; ASCII-approximate is fine, the cap is a budget not a contract.
                written += line.length + 1
                if (written >= maxBytes) roll()
            }.onFailure { closeQuietly() }
        }
    }

    /**
     * Write a session banner. Called at process start so reboots and crash-restarts
     * are visible as boundaries in the log — the first thing you want when reading
     * back "what happened before it came up again".
     */
    fun banner(text: String) {
        append("")
        append("========== $text @ ${stamp(clock())} ==========")
    }

    /**
     * The retained log, oldest first: the previous roll followed by the current file.
     * Returns at most [limitBytes] of text, truncated from the *front* so the most
     * recent lines always survive. Empty string when nothing has been logged yet.
     */
    fun tail(limitBytes: Int = 512 * 1024): String {
        synchronized(lock) {
            runCatching { writer?.flush() }
            val text = buildString {
                if (previous.exists()) append(runCatching { previous.readText() }.getOrDefault(""))
                if (current.exists()) append(runCatching { current.readText() }.getOrDefault(""))
            }
            if (text.length <= limitBytes) return text
            // Drop the partial first line so the output always starts on a line boundary.
            val cut = text.length - limitBytes
            val nl = text.indexOf('\n', cut)
            return if (nl >= 0) text.substring(nl + 1) else text.substring(cut)
        }
    }

    /** Delete both files and start over. Used by the config page's "clear" action. */
    fun clear() {
        synchronized(lock) {
            closeQuietly()
            runCatching { current.delete() }
            runCatching { previous.delete() }
            written = 0
        }
    }

    /** Bytes currently retained across both files; for the config page's size display. */
    fun sizeBytes(): Long = synchronized(lock) {
        (if (current.exists()) current.length() else 0) + (if (previous.exists()) previous.length() else 0)
    }

    private fun open(): Writer {
        dir.mkdirs()
        // Append: a restart continues the same file rather than discarding the
        // pre-reboot session, which is the whole point of writing to disk.
        written = if (current.exists()) current.length() else 0
        return OutputStreamWriter(FileOutputStream(current, true), Charsets.UTF_8).also { writer = it }
    }

    private fun roll() {
        closeQuietly()
        runCatching { previous.delete() }
        runCatching { current.renameTo(previous) }
        written = 0
    }

    private fun closeQuietly() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun stamp(ms: Long): String = STAMP_FMT.format(Date(ms))

    companion object {
        private val STAMP_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
