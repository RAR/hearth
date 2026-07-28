package com.rar.hearth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the PackageInstaller session's status callbacks.
 *
 * This is not optional plumbing. After `session.commit()` the system replies with
 * STATUS_PENDING_USER_ACTION and an Intent in EXTRA_INTENT; the confirmation dialog appears
 * only when somebody starts that Intent. Without this receiver the install stalls silently:
 * no dialog, no error, nothing on screen.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        // We are a background receiver, so the dialog needs its own task.
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
