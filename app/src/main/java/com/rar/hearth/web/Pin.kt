package com.rar.hearth.web

import java.security.SecureRandom
import kotlin.random.Random

/** A 6-digit PIN, zero-padded (so "000123" is valid). Generated once and persisted in app prefs. */
fun generatePin(random: Random = Random.Default): String =
    "%06d".format(random.nextInt(0, 1_000_000))

/** Matches a user-chosen config PIN: 4–8 ASCII digits, nothing else. `\d` here is `[0-9]` only. */
private val CUSTOM_PIN_REGEX = Regex("^\\d{4,8}$")

/** True when [s] is a valid custom PIN (exactly 4–8 ASCII digits). Used to validate PUT /api/pin. */
fun isValidCustomPin(s: String): Boolean = CUSTOM_PIN_REGEX.matches(s)

/**
 * A 32-char lowercase-hex token for the token-gated /api/notify endpoint. Generated once and
 * persisted alongside the PIN. 16 random bytes -> 32 hex chars.
 */
fun generateNotifyToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
