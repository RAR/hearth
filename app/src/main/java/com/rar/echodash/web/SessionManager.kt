package com.rar.echodash.web

import kotlin.random.Random

/** Outcome of a login attempt. */
sealed interface LoginResult {
    data class Ok(val token: String) : LoginResult
    data object Invalid : LoginResult
    data class LockedOut(val retryAfterSeconds: Long) : LoginResult
}

/**
 * PIN check + browser sessions for the config server. Tokens are valid until app restart (held in
 * memory). Five consecutive wrong PINs lock the login route for 60 s. Clock and RNG are injected so
 * the logic unit-tests deterministically; no Android APIs.
 */
class SessionManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val tokens = HashSet<String>()
    private var consecutiveFailures = 0
    private var lockoutUntilMs = 0L

    fun login(pin: String, correctPin: String): LoginResult {
        val now = clock()
        if (now < lockoutUntilMs) {
            return LoginResult.LockedOut(((lockoutUntilMs - now + 999) / 1000))
        }
        return if (pin == correctPin) {
            consecutiveFailures = 0
            val token = newToken()
            tokens += token
            LoginResult.Ok(token)
        } else {
            consecutiveFailures++
            if (consecutiveFailures >= 5) {
                consecutiveFailures = 0
                lockoutUntilMs = now + 60_000L
                LoginResult.LockedOut(60L)
            } else {
                LoginResult.Invalid
            }
        }
    }

    fun isValidSession(token: String?): Boolean = token != null && token in tokens

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
