package com.rar.hearth.update

import java.net.URI

/**
 * Allowlist for APK download URLs. `POST /api/update` takes a URL off the network, so without
 * this the endpoint is an arbitrary-APK installer reachable from the LAN. The config server's
 * PIN is a real gate, but a PIN is not a reason to accept an unvalidated URL.
 *
 * Parsed with [URI] rather than prefix-matched: `https://github.com.evil.example/...` and
 * `https://github.com@evil.example/...` both survive a naive startsWith().
 */
private const val ALLOWED_HOST = "github.com"
private const val ALLOWED_PATH_PREFIX = "/RAR/hearth/releases/download/"

fun isAllowedApkUrl(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    if (!uri.isAbsolute) return false
    if (uri.scheme?.lowercase() != "https") return false
    // userInfo set means a "user@host" form -- the host is not what a reader expects.
    if (uri.userInfo != null) return false
    if (uri.host?.lowercase() != ALLOWED_HOST) return false
    val path = uri.rawPath ?: return false
    if (!path.startsWith(ALLOWED_PATH_PREFIX)) return false
    // Reject traversal in both raw and percent-decoded form.
    val decoded = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrNull() ?: return false
    if (path.contains("..") || decoded.contains("..")) return false
    return decoded.endsWith(".apk")
}
