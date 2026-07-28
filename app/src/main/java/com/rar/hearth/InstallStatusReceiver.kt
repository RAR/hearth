package com.rar.hearth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.rar.hearth.update.ApkUpdater

/**
 * Receives the PackageInstaller session's status callbacks.
 *
 * This is not optional plumbing. After `session.commit()` the system replies with
 * STATUS_PENDING_USER_ACTION and an Intent in EXTRA_INTENT; the confirmation dialog appears
 * only when somebody starts that Intent. Without this receiver the install stalls silently:
 * no dialog, no error, nothing on screen.
 *
 * Every OTHER status here is terminal (success, cancel, or one of the STATUS_FAILURE_* codes,
 * including the ungranted-REQUEST_INSTALL_PACKAGES-appop case), and is routed back to
 * [ApkUpdater] so its state machine leaves AWAITING_CONFIRMATION instead of wedging there
 * forever -- which would otherwise disable updating until the process restarts.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            // We are a background receiver, so the dialog needs its own task.
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        ApkUpdater.onInstallStatus(status, message)
    }
}
