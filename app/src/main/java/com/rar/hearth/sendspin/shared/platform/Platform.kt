package com.rar.hearth.sendspin.shared.platform

import android.os.Build
import android.os.SystemClock

/**
 * Platform primitives for the vendored SendSpin engine.
 *
 * Inlined from the upstream Kotlin-Multiplatform `expect object Platform` +
 * Android `actual`; this Hearth vendor is Android-only, so the concrete
 * Android-backed implementation is the single definition.
 */
object Platform {
    /** Monotonic elapsed time in milliseconds (like SystemClock.elapsedRealtime on Android) */
    fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    /** Wall clock time in milliseconds */
    fun currentTimeMillis(): Long = System.currentTimeMillis()

    /** Base64 decode a string to bytes */
    fun base64Decode(input: String): ByteArray =
        java.util.Base64.getDecoder().decode(input)

    /** Device manufacturer name */
    fun manufacturer(): String = Build.MANUFACTURER

    /** Lowercase hex SHA-256 of the UTF-8 bytes of [input]. */
    fun sha256Hex(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
