package com.rar.hearth.media

/**
 * Resolve a raw HA entity_picture string to a fetchable absolute URL. Absolute http(s) URLs pass
 * through; a relative path is joined onto [baseUrl] (leading slash optional, trailing slash on the
 * base trimmed). Returns null when [raw] is blank/null, or when a relative path has no base to join
 * against. Pure JVM.
 */
fun resolveArtUrl(raw: String?, baseUrl: String?): String? {
    val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (s.startsWith("http://") || s.startsWith("https://")) return s
    val base = baseUrl?.trim()?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: return null
    return if (s.startsWith("/")) "$base$s" else "$base/$s"
}
