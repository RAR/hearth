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
 *
 * This is fragile in a way [BootReceiver] is not, and depends on a manifest permission to work:
 * Android 10+ blocks background activity starts from a process in RECEIVER state, so on its own
 * this `startActivity()` call is silently dropped by ActivityTaskManager (confirmed on a real
 * Android 11 device: the install succeeded, this receiver ran, the process started, and the
 * screen stayed on the system launcher). The `SYSTEM_ALERT_WINDOW` permission declared in the
 * manifest -- plus its per-device `appops set ... allow` grant -- is what exempts this app from
 * that block by flipping `isBgStartWhitelisted`. If that permission or its appop grant is ever
 * removed, this receiver keeps running and the process keeps starting, but the screen goes dark
 * after every update until someone physically touches the display.
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
