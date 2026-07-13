package com.rar.echodash.web

import java.security.SecureRandom
import kotlin.random.Random

/** A 6-digit PIN, zero-padded (so "000123" is valid). Generated once and persisted in app prefs. */
fun generatePin(random: Random = Random.Default): String =
    "%06d".format(random.nextInt(0, 1_000_000))

/**
 * A 32-char lowercase-hex token for the token-gated /api/notify endpoint. Generated once and
 * persisted alongside the PIN. 16 random bytes -> 32 hex chars.
 */
fun generateNotifyToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
