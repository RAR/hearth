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
    // Updated on every attempt from this address (touch), independent of outcome. Used both to
    // decide staleness (a "consecutive" streak more than a lockout-window old is meaningless) and
    // to pick an eviction victim by recency instead of insertion order.
    var lastSeenMs = 0L
}

/** Address bucket used when a client's remote address is null/blank. */
private const val UNKNOWN_CLIENT = "unknown"

/** Hard cap on how many distinct client addresses [SessionManager] will track at once. */
private const val MAX_TRACKED_CLIENTS = 256

/** Consecutive failures, summed across all clients, that trip the global backstop lockout. */
private const val GLOBAL_BACKSTOP_THRESHOLD = 50

/**
 * A client entry not touched for this long has an irrelevant "consecutive" failure streak — it
 * becomes evictable even with a non-zero [ClientState.consecutiveFailures], so a low-and-slow
 * one-failure-per-address sweep can't wedge the map into permanent fail-open. Matches the lockout
 * window so a client can't be forgotten mid-lockout.
 */
private const val CLIENT_STALE_MS = 60_000L

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
 * addresses cannot grow it without bound, and — importantly — cannot *poison* it into permanent
 * fail-open either: a failure streak older than the 60 s lockout window decays (see
 * [CLIENT_STALE_MS]), so an entry sitting at 1-4 failures with no active lockout becomes evictable
 * again shortly after the attacker stops touching it. If the map is still full and truly nothing is
 * evictable (every one of the 256 slots is either actively failing within the last minute or still
 * locked out), the oldest entry by [ClientState.lastSeenMs] is evicted outright rather than falling
 * back to "untracked" — failing closed (evict someone) beats silently disabling the per-client
 * control for all new addresses.
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
    private val clients = HashMap<String, ClientState>()
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
            if (now < client.lockoutUntilMs) {
                return@synchronized LoginResult.LockedOut(((client.lockoutUntilMs - now + 999) / 1000))
            }

            if (pin == correctPin) {
                client.consecutiveFailures = 0
                globalConsecutiveFailures = 0
                val token = newToken()
                tokens += token
                LoginResult.Ok(token)
            } else {
                globalConsecutiveFailures++
                if (globalConsecutiveFailures >= GLOBAL_BACKSTOP_THRESHOLD) {
                    globalConsecutiveFailures = 0
                    globalLockoutUntilMs = now + 60_000L
                    // Leave this client's own consecutiveFailures alone: the global backstop
                    // firing doesn't erase this address's individual progress toward its own
                    // 5-attempt lockout.
                    return@synchronized LoginResult.LockedOut(60L)
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
     * Returns the [ClientState] for [address], creating one if there's room, and always touches
     * [ClientState.lastSeenMs] to `now`. If the map is at [MAX_TRACKED_CLIENTS], first tries to
     * evict an entry that's either clean (no failures, no active lockout) or merely stale (its
     * streak is older than [CLIENT_STALE_MS], so it's meaningless even if non-zero). If literally
     * nothing qualifies — every one of the [MAX_TRACKED_CLIENTS] slots is both fresh and either
     * failing or locked out — evicts the least-recently-seen entry outright. This always returns a
     * real, trackable [ClientState]: the per-client control never silently falls open just because
     * the map is full.
     */
    private fun trackedClient(address: String, now: Long): ClientState {
        clients[address]?.let {
            it.lastSeenMs = now
            return it
        }

        if (clients.size >= MAX_TRACKED_CLIENTS) {
            // "Oldest" is by lastSeenMs, not insertion order — a LinkedHashMap's iteration order
            // never refreshes for entries that keep getting touched, which would misidentify an
            // actively-attacked address as an eviction candidate just because it was created early.
            val evictable = clients.entries
                .filter { (_, s) -> (s.consecutiveFailures == 0 && s.lockoutUntilMs <= now) ||
                    (now - s.lastSeenMs > CLIENT_STALE_MS) }
                .minByOrNull { (_, s) -> s.lastSeenMs }
                ?: clients.entries.minByOrNull { (_, s) -> s.lastSeenMs }
            // The fallback above cannot be null: clients.size >= MAX_TRACKED_CLIENTS > 0 here, so
            // there is always at least one entry to evict. Failing closed by evicting the
            // least-recently-seen client beats returning null and disabling per-client tracking.
            clients.remove(evictable!!.key)
        }

        return ClientState().also { it.lastSeenMs = now; clients[address] = it }
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
