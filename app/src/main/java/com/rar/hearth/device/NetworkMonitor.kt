package com.rar.hearth.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * Watches for the device regaining a default network and invokes [onAvailable] each time one
 * becomes available. Used to trigger an immediate reconnect of the HA WebSocket and a SendSpin
 * re-arm the instant wifi/router connectivity returns, instead of waiting out reconnect backoff.
 *
 * [onAvailable] fires on a ConnectivityManager binder thread, so it must marshal any real work onto
 * the right dispatcher itself (the wiring in AppDeps hops to mainScope). It also fires for the first
 * network at startup and on every network change, so the callback must be idempotent / cheap when
 * already connected. Registered once for the process lifetime (kiosk); [stop] exists for tests.
 *
 * Requires the ACCESS_NETWORK_STATE permission (declared in the manifest).
 */
class NetworkMonitor(context: Context, private val onAvailable: () -> Unit) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }
    @Volatile private var registered = false

    /** Register for default-network availability. Idempotent; no-ops if ConnectivityManager is absent. */
    fun start() {
        if (registered) return
        val manager = cm ?: return
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
            .onFailure { Log.e(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    /** Unregister. Idempotent. */
    fun stop() {
        if (!registered) return
        runCatching { cm?.unregisterNetworkCallback(callback) }
        registered = false
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
