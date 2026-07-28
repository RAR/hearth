package com.rar.hearth.web

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** Outcome of a login attempt. */
sealed interface LoginResult {
    data class Ok(val token: String) : LoginResult
    data object Invalid : LoginResult
    data class LockedOut(val retryAfterSeconds: Long) : LoginResult
}

/** Per-client failure bookkeeping used by the per-address lockout in [SessionManager.login]. */
private class ClientState {
    var consecutiveFailures = 0
    var lockoutUntilMs = 0L
}

/** Address bucket used when a client's remote address is null/blank. */
private const val UNKNOWN_CLIENT = "unknown"

/** Hard cap on how many distinct client addresses [SessionManager] will track at once. */
private const val MAX_TRACKED_CLIENTS = 256

/** Consecutive failures, summed across all clients, that trip the global backstop lockout. */
private const val GLOBAL_BACKSTOP_THRESHOLD = 50

/**
 * PIN check + browser sessions for the config server. Tokens are valid until app restart (held in
 * memory). Clock and RNG are injected so the logic unit-tests deterministically; no Android APIs.
 *
 * Lockout is two-layered:
 *  - Per-client: five consecutive wrong PINs from one client address lock out just that address
 *    for 60 s. This keeps one misbehaving client from denying service to everyone else.
 *  - Global backstop: because per-client tracking is bypassable by rotating source addresses, 50
 *    consecutive wrong PINs spread across *any* clients also locks out everyone for 60 s. This is
 *    intentionally not simplified away — see the security brief for finding 3.
 *
 * The per-client map is bounded (see [MAX_TRACKED_CLIENTS]) so an attacker who churns through
 * addresses cannot grow it without bound: expired, zero-failure entries are evicted opportunistically,
 * and once full, new addresses fall back to being judged by the global backstop alone.
 *
 * Thread-safe: `login` and `isValidSession` are both synchronized on a private lock, so this class
 * can be called concurrently from the NanoHTTPD server's request threads without races on the
 * token set or the lockout/failure-count state.
 */
class SessionManager(
    private val clock: () -> Long = System::currentTimeMillis,
    // Default is a CSPRNG (SecureRandom), not the fast-but-predictable XorWow of Random.Default —
    // this token authorises installing an APK on the device, so it must not be guessable from
    // observed output. Tests inject a seeded `Random(n)` for determinism; do not change that seam,
    // only ever the default. Do not "simplify" this back to Random.Default.
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    private val lock = Any()
    private val tokens = HashSet<String>()
    private val clients = LinkedHashMap<String, ClientState>()
    private var globalConsecutiveFailures = 0
    private var globalLockoutUntilMs = 0L

    fun login(pin: String, correctPin: String, clientAddress: String? = null): LoginResult =
        synchronized(lock) {
            val now = clock()
            val address = clientAddress?.takeIf { it.isNotBlank() } ?: UNKNOWN_CLIENT

            if (now < globalLockoutUntilMs) {
                return@synchronized LoginResult.LockedOut(((globalLockoutUntilMs - now + 999) / 1000))
            }

            val client = trackedClient(address, now)
            if (client != null && now < client.lockoutUntilMs) {
                return@synchronized LoginResult.LockedOut(((client.lockoutUntilMs - now + 999) / 1000))
            }

            if (pin == correctPin) {
                client?.consecutiveFailures = 0
                globalConsecutiveFailures = 0
                val token = newToken()
                tokens += token
                LoginResult.Ok(token)
            } else {
                globalConsecutiveFailures++
                if (globalConsecutiveFailures >= GLOBAL_BACKSTOP_THRESHOLD) {
                    globalConsecutiveFailures = 0
                    globalLockoutUntilMs = now + 60_000L
                    client?.consecutiveFailures = 0
                    return@synchronized LoginResult.LockedOut(60L)
                }

                if (client == null) {
                    // Map is full and this address couldn't be tracked: judged by the global
                    // backstop only.
                    return@synchronized LoginResult.Invalid
                }

                client.consecutiveFailures++
                if (client.consecutiveFailures >= 5) {
                    client.consecutiveFailures = 0
                    client.lockoutUntilMs = now + 60_000L
                    LoginResult.LockedOut(60L)
                } else {
                    LoginResult.Invalid
                }
            }
        }

    /**
     * Returns the [ClientState] for [address], creating one if there's room. If the map is at
     * [MAX_TRACKED_CLIENTS], first tries to evict an expired, zero-failure entry to make room; if
     * none can be evicted, returns null so the caller falls back to the global backstop only.
     */
    private fun trackedClient(address: String, now: Long): ClientState? {
        clients[address]?.let { return it }

        if (clients.size >= MAX_TRACKED_CLIENTS) {
            val evictable = clients.entries.firstOrNull { (_, s) ->
                s.consecutiveFailures == 0 && s.lockoutUntilMs <= now
            }
            if (evictable != null) {
                clients.remove(evictable.key)
            } else {
                return null
            }
        }

        return ClientState().also { clients[address] = it }
    }

    fun isValidSession(token: String?): Boolean = synchronized(lock) {
        token != null && token in tokens
    }

    /** Test-only: current size of the per-client tracking map, to assert the cap holds. */
    internal fun trackedClientCountForTest(): Int = synchronized(lock) { clients.size }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
