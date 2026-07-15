package com.rar.echodash.sendspin

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Minimal settings shim for the vendored SendSpin engine.
 *
 * Upstream SendSpinDroid ships a large `UserSettings` object backed by encrypted
 * SharedPreferences with dozens of UI/remote/proxy knobs. Hearth vendors only the
 * local audio path, so this shim exposes exactly the four accessors the facade and
 * decoder factory call:
 *
 *  - [getPlayerId]        stable per-install id, generated once and persisted
 *  - [getPreferredCodec]  fixed to FLAC (lossless, MediaCodec-decodable)
 *  - [lowMemoryMode]      always false (the Echo/tablet targets have adequate RAM)
 *  - [highPowerMode]      always false (uses the default 30s ping / backoff ceiling)
 *
 * [initialize] is optional: when a [Context] has been supplied the player id is
 * persisted across launches; if [getPlayerId] is called before initialization it
 * still returns a stable in-memory id for the process lifetime.
 */
object UserSettings {

    private const val PREFS_FILE = "hearth_sendspin_prefs"
    private const val KEY_PLAYER_ID = "player_id"

    @Volatile
    private var prefs: SharedPreferences? = null

    // In-memory fallback so getPlayerId() is stable even if called before
    // initialize() runs. Persisted to prefs once a context is available.
    @Volatile
    private var cachedPlayerId: String? = null

    /** Preferred streaming codec. FLAC is lossless and decodes via MediaCodec. */
    fun getPreferredCodec(): String = "flac"

    /** Low-memory profile is not used on Hearth's target devices. */
    val lowMemoryMode: Boolean = false

    /** High-power profile is not used; default reconnect/ping cadence applies. */
    val highPowerMode: Boolean = false

    /**
     * Supply an application [Context] so the player id survives app restarts.
     * Safe to call more than once; the first call wins.
     */
    fun initialize(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            // Persist any id generated before initialize() ran.
            cachedPlayerId?.let { id ->
                if (p.getString(KEY_PLAYER_ID, null).isNullOrBlank()) {
                    p.edit().putString(KEY_PLAYER_ID, id).apply()
                }
            }
            prefs = p
        }
    }

    /**
     * Stable per-install player id. Generated once (persisted in SharedPreferences
     * when a context is available) and returned identically thereafter.
     */
    fun getPlayerId(): String {
        prefs?.getString(KEY_PLAYER_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        cachedPlayerId?.let { return it }
        synchronized(this) {
            cachedPlayerId?.let { return it }
            val p = prefs
            p?.getString(KEY_PLAYER_ID, null)?.takeIf { it.isNotBlank() }?.let {
                cachedPlayerId = it
                return it
            }
            val newId = UUID.randomUUID().toString()
            cachedPlayerId = newId
            p?.edit()?.putString(KEY_PLAYER_ID, newId)?.apply()
            return newId
        }
    }
}
