package com.rar.hearth.web

/**
 * Normalize a user-entered Home Assistant base URL: trim, default the scheme to http:// when absent,
 * strip a trailing slash. Returns null for blank input or a non-http(s) scheme. Server-side setup
 * validation for the web OAuth flow (also used by the legacy device SetupScreen until it is retired).
 */
fun normalizeBaseUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/').trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val ok = withScheme.startsWith("http://") || withScheme.startsWith("https://")
    return if (ok) withScheme.trimEnd('/') else null
}
