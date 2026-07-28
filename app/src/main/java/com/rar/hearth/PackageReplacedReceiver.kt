package com.rar.hearth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the dashboard after the app is replaced.
 *
 * Android kills the app to install the new APK and does not start it again. On a wall-mounted
 * display that turns an update into an outage: the screen stays dark until someone walks over.
 * Same job as [BootReceiver], different trigger.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
