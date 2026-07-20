package com.rar.hearth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Best-effort: on API 29+ background activity starts are blocked unless this app
// is the default HOME launcher — which is the supported kiosk configuration.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
