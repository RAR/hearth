package com.rar.hearth.web

import java.net.Inet4Address
import java.net.NetworkInterface

/** First site-local IPv4 address of an up, non-loopback interface (for the Configure URL). */
fun localIpAddress(): String? =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
