package com.rar.echodash.notify

import com.rar.echodash.ui.model.NotifSeverity
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One HA-pushed notification held in memory. [expiresAtMs] null = persistent (no auto-expiry). */
data class PushedNotification(
    val id: String,
    val severity: NotifSeverity,
    val title: String,
    val message: String?,
    val expiresAtMs: Long?,
)

/**
 * In-memory store of Home-Assistant-pushed notifications, newest-first. Thread-safe (synchronized,
 * like ConfigStore); no Android imports so it runs in plain-JVM tests. Not persisted: pushes do not
 * survive an app restart (HA automations can re-send).
 */
class PushNotificationStore {
    private val _items = MutableStateFlow<List<PushedNotification>>(emptyList())
    val items: StateFlow<List<PushedNotification>> = _items

    // Newest-first working list guarded by the instance lock; _items mirrors it after each mutation.
    private val list = ArrayList<PushedNotification>()
    // Process-lifetime monotonic counter for auto-generated ids ("auto-1", "auto-2", ...).
    private var autoCounter = 0L

    /**
     * Add or replace a notification. Returns the effective id. All inputs are clamped so the store
     * can never hold invalid state. [timeoutSeconds] null/absent or <= 0 -> persistent; otherwise
     * clamped to 5..86400 and turned into [PushedNotification.expiresAtMs] = nowMs + seconds*1000.
     */
    @Synchronized
    fun post(
        id: String?,
        title: String,
        message: String?,
        severity: String?,
        timeoutSeconds: Int?,
        nowMs: Long,
    ): String {
        val effectiveId = id?.trim()?.takeIf { it.isNotBlank() } ?: "auto-${++autoCounter}"
        val cleanTitle = title.trim().take(TITLE_MAX)
        val cleanMessage = message?.trim()?.takeIf { it.isNotBlank() }?.take(MESSAGE_MAX)
        val expiresAtMs = timeoutSeconds
            ?.takeIf { it > 0 }
            ?.coerceIn(TIMEOUT_MIN, TIMEOUT_MAX)
            ?.let { nowMs + it * 1000L }
        val item = PushedNotification(effectiveId, severityOf(severity), cleanTitle, cleanMessage, expiresAtMs)

        list.removeAll { it.id == effectiveId }      // re-posting an id replaces the old row...
        list.add(0, item)                            // ...and moves it to the front (newest-first).
        while (list.size > MAX_ITEMS) list.removeAt(list.size - 1) // cap: drop the oldest (tail).
        publish()
        return effectiveId
    }

    @Synchronized
    fun dismiss(id: String) { if (list.removeAll { it.id == id }) publish() }

    @Synchronized
    fun clear(id: String) { if (list.removeAll { it.id == id }) publish() }

    @Synchronized
    fun clearAll() { if (list.isNotEmpty()) { list.clear(); publish() } }

    @Synchronized
    fun prune(nowMs: Long) {
        if (list.removeAll { it.expiresAtMs != null && it.expiresAtMs <= nowMs }) publish()
    }

    private fun publish() { _items.value = ArrayList(list) }

    private fun severityOf(raw: String?): NotifSeverity =
        when (raw?.trim()?.lowercase(Locale.US)) {
            "critical" -> NotifSeverity.CRITICAL
            "warning" -> NotifSeverity.WARNING
            else -> NotifSeverity.INFO
        }

    private companion object {
        const val MAX_ITEMS = 20
        const val TITLE_MAX = 120
        const val MESSAGE_MAX = 2000
        const val TIMEOUT_MIN = 5
        const val TIMEOUT_MAX = 86_400
    }
}
