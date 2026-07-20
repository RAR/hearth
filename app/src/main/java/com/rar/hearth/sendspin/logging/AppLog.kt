package com.rar.hearth.sendspin.logging

import android.util.Log

/**
 * Minimal logging shim for the vendored SendSpin engine.
 *
 * Upstream SendSpinDroid ships a full `logging/` package (file writers, logcat
 * bridge, redaction, level gating). Hearth does not vendor that infrastructure;
 * this shim provides only the category-scoped `AppLog.<Category>.<level>(...)`
 * facade the vendored audio/protocol code calls, delegating straight to
 * [android.util.Log]. Signatures match the upstream `Logger` API
 * (`w`/`e` accept an optional [Throwable]).
 */
object AppLog {
    val Audio: Logger = Logger("SS.Audio")
    val Sync: Logger = Logger("SS.Sync")
    val Protocol: Logger = Logger("SS.Protocol")
    val Network: Logger = Logger("SS.Network")
    val Playback: Logger = Logger("SS.Playback")
    val App: Logger = Logger("SS.App")

    class Logger internal constructor(private val tag: String) {
        fun v(msg: String) { Log.v(tag, msg) }
        fun d(msg: String) { Log.d(tag, msg) }
        fun i(msg: String) { Log.i(tag, msg) }
        fun w(msg: String, t: Throwable? = null) { Log.w(tag, msg, t) }
        fun e(msg: String, t: Throwable? = null) { Log.e(tag, msg, t) }
    }
}
