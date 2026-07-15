package com.rar.echodash.sendspin.shared.log

/**
 * Logging facade for the vendored SendSpin engine.
 *
 * Inlined from the upstream Kotlin-Multiplatform `expect object Log` +
 * Android `actual`; this Hearth vendor is Android-only, so the concrete
 * `android.util.Log`-backed implementation is the single definition.
 */
object Log {
    fun v(tag: String, msg: String): Int = android.util.Log.v(tag, msg)
    fun d(tag: String, msg: String): Int = android.util.Log.d(tag, msg)
    fun i(tag: String, msg: String): Int = android.util.Log.i(tag, msg)
    fun w(tag: String, msg: String): Int = android.util.Log.w(tag, msg)
    fun w(tag: String, msg: String, tr: Throwable): Int = android.util.Log.w(tag, msg, tr)
    fun e(tag: String, msg: String): Int = android.util.Log.e(tag, msg)
    fun e(tag: String, msg: String, tr: Throwable): Int = android.util.Log.e(tag, msg, tr)
}
