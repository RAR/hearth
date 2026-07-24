package com.rar.hearth.diag

import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Wires the app's existing logging into a [FileLog] that survives a reboot.
 *
 * Rather than route ~400 `Log.*` call sites through a new sink, this tails logcat
 * filtered to our own PID. An app may always read its own logs without
 * `READ_LOGS`, and doing it this way also captures what the call sites can't —
 * native and framework messages logged against this process, and the ART crash
 * dump — which is exactly the material a device-side fault leaves behind.
 *
 * Nothing here is on a hot path: one reader thread, one line at a time, appended
 * to a size-capped file.
 */
object DiagLog {

    /**
     * Start tailing this process's logcat into [log]. Idempotent — a second call is
     * ignored, so re-entering from a restarted Activity can't spawn a second pump.
     *
     * `-T 1` starts from the newest line instead of replaying the whole ring buffer,
     * which would otherwise re-copy pre-restart output into the file on every launch.
     */
    @Volatile private var pump: Thread? = null

    @Synchronized
    fun startLogcatPump(log: FileLog) {
        if (pump != null) return
        val pid = Process.myPid()
        pump = Thread({
            runCatching {
                val proc = ProcessBuilder("logcat", "-v", "time", "-T", "1", "--pid=$pid")
                    .redirectErrorStream(true)
                    .start()
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        log.append(line)
                    }
                }
            }.onFailure {
                // No logcat binary, or the ROM refuses it. The file log still works for
                // the crash handler and the banner; just note why it looks sparse.
                log.append("!! logcat pump unavailable: ${it.javaClass.simpleName}: ${it.message}")
            }
        }, "hearth-logcat").apply {
            isDaemon = true
            // Below normal: diagnostics must never compete with audio or UI work.
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * Record uncaught exceptions to [log] before the process dies, then hand off to
     * whatever handler was already installed so the crash still behaves normally.
     *
     * The logcat pump would usually catch the ART dump too, but not reliably: the
     * process can be gone before the pump thread is scheduled. Writing it here
     * synchronously on the crashing thread is what makes a crash-restart readable.
     */
    fun installCrashHandler(log: FileLog) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                log.append("!! FATAL on thread '${thread.name}'")
                log.append(stackTraceOf(error))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stackTraceOf(error: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        return sw.toString().trimEnd()
    }
}
