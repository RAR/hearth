package com.rar.echodash.vaca

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Advertises the VACA server via mDNS so HA auto-discovers the device (retries every 30 s on failure). */
class NsdAdvertiser(context: Context, private val port: Int) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.RegistrationListener? = null
    private var stopped = false

    @Synchronized
    fun register() {
        if (listener != null) return
        stopped = false
        val info = NsdServiceInfo().apply {
            serviceName = "Echo Dashboard"
            serviceType = "_vaca._tcp."
            setPort(this@NsdAdvertiser.port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(TAG, "registered as ${i.serviceName}")
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, err: Int) {
                Log.w(TAG, "registration failed: $err (HA manual host:port setup still works)")
                onFailed()
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, err: Int) {}
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    private fun onFailed() {
        listener = null
        if (!stopped) handler.postDelayed({ register() }, RETRY_MS)
    }

    @Synchronized
    fun unregister() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    private companion object {
        const val TAG = "NsdAdvertiser"
        const val RETRY_MS = 30_000L
    }
}
