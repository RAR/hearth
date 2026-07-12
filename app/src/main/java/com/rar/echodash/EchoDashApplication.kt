package com.rar.echodash

import android.app.Application

class EchoDashApplication : Application() {
    lateinit var deps: AppDeps
        private set

    override fun onCreate() {
        super.onCreate()
        deps = AppDeps(this)
        deps.startVaca()
        deps.startVoice()
    }
}
