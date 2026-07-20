package com.rar.hearth.sendspin.sendspin

import com.rar.hearth.sendspin.sendspin.protocol.SendSpinProtocol

/**
 * Endpoint a SendSpin connection targets. Hearth vendors the LOCAL path only,
 * so [Local] is the sole variant (the upstream Proxy/Remote endpoints were
 * dropped along with the WebRTC/proxy transports).
 */
sealed class SendSpinEndpoint {
    /**
     * Direct WebSocket to a server on the local network.
     * @param address host[:port], e.g. "10.0.1.5:8927"
     * @param path WebSocket path, defaults to SendSpin's standard endpoint.
     */
    data class Local(
        val address: String,
        val path: String = SendSpinProtocol.ENDPOINT_PATH,
    ) : SendSpinEndpoint()
}
