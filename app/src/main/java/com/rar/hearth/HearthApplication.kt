package com.rar.hearth

import android.app.Application

class HearthApplication : Application() {
    lateinit var deps: AppDeps
        private set

    override fun onCreate() {
        super.onCreate()
        deps = AppDeps(this)
        deps.startHearth()
        deps.startVoice()
        deps.startSendspin()
    }
}
