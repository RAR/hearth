package com.rar.hearth

import android.app.Application

class HearthApplication : Application() {
    lateinit var deps: AppDeps
        private set

    override fun onCreate() {
        super.onCreate()
        deps = AppDeps(this)
        // First: the crash handler and file log, so a failure in the startup calls
        // below is still readable after the reboot that clears logcat.
        deps.startDiagnostics()
        deps.startHearth()
        deps.startVoice()
        deps.startSendspin()
    }
}
