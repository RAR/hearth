package com.rar.hearth.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads a release APK and hands it to Android's package installer. Follows
 * AndroidPhotoDownloader: OkHttp on Dispatchers.IO, temp file renamed on success so an
 * interrupted download never leaves a corrupt file under the final name.
 *
 * The install confirmation appears on the DEVICE's own screen -- this is Tier 1 in the spec.
 * Silent install would need root or device-owner and is deliberately out of scope.
 */
class ApkUpdater(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val currentVersionCode: Int,
) {
    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status

    /** Staged in app-private storage: no external-storage permission, and cleaned up on uninstall. */
    private val stagingDir: File get() = File(context.filesDir, "update").apply { mkdirs() }

    /**
     * Begins an update. Returns false and changes nothing when the URL is not allowlisted or an
     * update is already in flight.
     *
     * `ConfigServer` (NanoHTTPD) serves each request on its own thread, so two near-simultaneous
     * calls can race here. The busy-check and the state transition are done as one atomic
     * compareAndSet loop so at most one of two racing callers wins the slot.
     */
    fun start(url: String): Boolean {
        if (!isAllowedApkUrl(url)) return false
        while (true) {
            val current = _status.value
            if (current.isBusy()) return false
            if (_status.compareAndSet(current, UpdateStatus(stage = UpdateStage.DOWNLOADING))) break
        }
        scope.launch { run(url) }
        return true
    }

    private suspend fun run(url: String) {
        val apk = download(url)
        if (apk == null) {
            fail("download failed")
            return
        }
        _status.value = _status.value.copy(stage = UpdateStage.VERIFYING, progressPct = 100)
        // getPackageArchiveInfo parses the whole archive; keep it off whatever dispatcher the
        // caller's scope happens to carry (AppDeps' scope is Dispatchers.Main.immediate).
        val info = withContext(Dispatchers.IO) {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }
        if (info == null) {
            apk.delete(); fail("downloaded file is not a valid APK"); return
        }
        if (info.packageName != context.packageName) {
            apk.delete(); fail("APK is ${info.packageName}, expected ${context.packageName}"); return
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
        // Dirty is not knowable from the archive, so require strictly newer here. The button in
        // the web UI is what applies the dirty allowance; this is the last-line sanity check.
        if (code <= currentVersionCode) {
            apk.delete(); fail("APK is version $code, not newer than $currentVersionCode"); return
        }
        _status.value = _status.value.copy(
            stage = UpdateStage.AWAITING_CONFIRMATION,
            versionName = info.versionName,
        )
        runCatching { installViaSession(apk) }.onFailure { fail("install failed: ${it.message}") }
    }

    private suspend fun download(url: String): File? = withContext(Dispatchers.IO) {
        val tmp = File(stagingDir, "update.apk.tmp")
        val out = File(stagingDir, "update.apk")
        tmp.delete(); out.delete()
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                var read = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                _status.value = _status.value.copy(
                                    progressPct = ((read * 100) / total).toInt().coerceIn(0, 100)
                                )
                            }
                        }
                    }
                }
            }
        }.getOrElse { tmp.delete(); return@withContext null }
        if (!tmp.renameTo(out)) { tmp.delete(); return@withContext null }
        out
    }

    /**
     * Copies the APK into a PackageInstaller session and commits it. Runs on Dispatchers.IO: this
     * does a full-file copy plus fsync and must never land on the caller's (possibly main)
     * dispatcher.
     *
     * `createSession` is deliberately outside the try's protection scope only in the sense that a
     * throw from `createSession` itself has nothing to abandon yet; everything from
     * `openSession` onward is wrapped so any failure abandons the session instead of leaving a
     * half-written one staged against this app's per-uid session cap forever.
     */
    private suspend fun installViaSession(apk: File): Unit = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("hearth", 0, apk.length()).use { dest ->
                    apk.inputStream().use { it.copyTo(dest) }
                    session.fsync(dest)
                }
                // The commit target MUST be a broadcast we handle: the system replies with
                // STATUS_PENDING_USER_ACTION and hands back an Intent that somebody has to
                // start. Nothing shows the dialog on its own -- point this at an Activity and
                // the install stalls forever with no error.
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pending = android.app.PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, com.rar.hearth.InstallStatusReceiver::class.java),
                    flags,
                )
                // Registered just before commit: InstallStatusReceiver's callback is delivered
                // asynchronously and in-process, so a companion-held reference is how it finds
                // its way back to this instance to resolve the terminal state.
                registerPendingInstall(this@ApkUpdater)
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    private fun fail(reason: String) {
        _status.value = UpdateStatus(stage = UpdateStage.FAILED, error = reason)
    }

    companion object {
        /**
         * The ApkUpdater instance with a session currently committed and awaiting the system's
         * terminal callback. InstallStatusReceiver runs in this same process (it is not exported
         * and is only ever targeted by our own explicit PendingIntent), so a plain static
         * reference is sufficient -- no IPC needed. Only one install can be in flight at a time
         * (enforced by start()'s busy check), so a single slot is enough.
         */
        @Volatile
        private var pendingInstall: ApkUpdater? = null

        private fun registerPendingInstall(updater: ApkUpdater) {
            pendingInstall = updater
        }

        /**
         * Called by [com.rar.hearth.InstallStatusReceiver] with the PackageInstaller callback's
         * terminal status (anything other than STATUS_PENDING_USER_ACTION, which the receiver
         * handles itself by starting the confirmation dialog). Without this, a cancelled dialog
         * or an ungranted REQUEST_INSTALL_PACKAGES appop leaves the state machine wedged in
         * AWAITING_CONFIRMATION / DOWNLOADING forever, and start() rejects every future update
         * until the process restarts.
         */
        fun onInstallStatus(status: Int, message: String?) {
            val updater = pendingInstall ?: return
            pendingInstall = null
            if (status == PackageInstaller.STATUS_SUCCESS) {
                updater._status.value = UpdateStatus(stage = UpdateStage.IDLE)
            } else {
                updater.fail(message ?: "install failed (status $status)")
            }
        }
    }
}
