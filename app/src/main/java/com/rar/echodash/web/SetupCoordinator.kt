package com.rar.echodash.web

import com.rar.echodash.ha.AuthManager
import java.net.URLEncoder
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking

/** Result of `begin`: an authorize URL to navigate the browser to, or a validation error. */
sealed interface BeginResult {
    data class Ok(val authorizeUrl: String) : BeginResult
    data class Invalid(val message: String) : BeginResult
}

/** Result of `complete`: success (302→400→502 mapping is the caller's job). */
sealed interface CompleteResult {
    data object Ok : CompleteResult
    data class BadState(val message: String) : CompleteResult
    data class ExchangeFailed(val message: String) : CompleteResult
}

/**
 * Drives the browser HA-setup OAuth round-trip. Holds ONE in-memory pending record: a new `begin`
 * overwrites it, it expires after [EXPIRY_MS], and it is cleared only on a successful exchange (kept
 * on failure so the user can retry Connect). The `state` token binds the callback to this record so a
 * forged callback can't inject a code. Thread-safe (synchronized — called from NanoHTTPD request
 * threads). Android-free: unit-tested with an injected clock and a fake token endpoint.
 */
class SetupCoordinator(
    private val auth: AuthManager,
    private val onConfigured: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        const val EXPIRY_MS: Long = 10 * 60_000L
    }

    private data class Pending(
        val haUrl: String,
        val clientId: String,
        val state: String,
        val createdAt: Long,
    )

    private val lock = Any()
    private var pending: Pending? = null

    fun begin(haUrl: String, clientId: String): BeginResult {
        val base = normalizeBaseUrl(haUrl) ?: return BeginResult.Invalid("Enter a valid http(s) URL")
        val state = randomState()
        synchronized(lock) { pending = Pending(base, clientId, state, clock()) }
        val authorizeUrl =
            "$base/auth/authorize?client_id=${enc(clientId)}&redirect_uri=${enc(clientId)}&state=${enc(state)}"
        return BeginResult.Ok(authorizeUrl)
    }

    fun complete(code: String, state: String): CompleteResult {
        val p = synchronized(lock) { pending }
        if (p == null || state != p.state || clock() - p.createdAt > EXPIRY_MS) {
            return CompleteResult.BadState("setup session expired — try again")
        }
        return try {
            runBlocking { auth.exchangeSetupCode(p.haUrl, p.clientId, code) }
            synchronized(lock) { pending = null }
            onConfigured()
            CompleteResult.Ok
        } catch (e: Exception) {
            // pending is intentionally kept so the user can retry Connect.
            CompleteResult.ExchangeFailed("Home Assistant rejected the login: ${e.message}")
        }
    }

    private fun randomState(): String {
        val bytes = ByteArray(16) // 128-bit
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
