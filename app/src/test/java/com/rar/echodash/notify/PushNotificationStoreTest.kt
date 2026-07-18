package com.rar.echodash.notify

import com.rar.echodash.ui.model.NotifSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotificationStoreTest {

    @Test
    fun blankOrNullIdGetsUniqueAutoId() {
        val store = PushNotificationStore()
        val id1 = store.post(null, "A", null, null, null, 0L)
        val id2 = store.post("   ", "B", null, null, null, 0L)
        assertEquals("auto-1", id1)
        assertEquals("auto-2", id2)
        assertEquals(2, store.items.value.size)
    }

    @Test
    fun newestFirstAndRepostReplacesAndBumps() {
        val store = PushNotificationStore()
        store.post("a", "A", null, null, null, 0L)
        store.post("b", "B", null, null, null, 0L)
        assertEquals(listOf("b", "a"), store.items.value.map { it.id }) // newest first
        store.post("a", "A2", null, null, null, 0L)                     // re-post moves to front
        assertEquals(listOf("a", "b"), store.items.value.map { it.id })
        assertEquals("A2", store.items.value.first().title)
        assertEquals(2, store.items.value.size)                         // replaced, not duplicated
    }

    @Test
    fun postStampsReceivedAtMsAndRepostRestamps() {
        val store = PushNotificationStore()
        store.post("a", "A", null, null, null, 1_000L)
        assertEquals(1_000L, store.items.value.single().receivedAtMs)
        store.post("a", "A2", null, null, null, 2_000L) // re-post = update, restamps
        assertEquals(2_000L, store.items.value.single().receivedAtMs)
    }

    @Test
    fun capEvictsOldest() {
        val store = PushNotificationStore()
        for (i in 1..25) store.post("id$i", "T$i", null, null, null, 0L)
        val ids = store.items.value.map { it.id }
        assertEquals(20, ids.size)
        assertEquals("id25", ids.first()) // newest kept
        assertEquals("id6", ids.last())   // id1..id5 evicted
        assertFalse(ids.contains("id5"))
    }

    @Test
    fun clampsTitleAndMessageAndBlankMessageToNull() {
        val store = PushNotificationStore()
        store.post("a", "  " + "x".repeat(200) + "  ", "y".repeat(3000), null, null, 0L)
        store.post("b", "B", "   ", null, null, 0L) // blank message -> null
        val items = store.items.value.associateBy { it.id }
        assertEquals(120, items["a"]!!.title.length)
        assertEquals(2000, items["a"]!!.message!!.length)
        assertNull(items["b"]!!.message)
    }

    @Test
    fun severityMapping() {
        val store = PushNotificationStore()
        store.post("a", "A", null, "critical", null, 0L)
        store.post("b", "B", null, "  Warning ", null, 0L)
        store.post("c", "C", null, "info", null, 0L)
        store.post("d", "D", null, "bogus", null, 0L)
        store.post("e", "E", null, null, null, 0L)
        val m = store.items.value.associateBy { it.id }
        assertEquals(NotifSeverity.CRITICAL, m["a"]!!.severity)
        assertEquals(NotifSeverity.WARNING, m["b"]!!.severity)
        assertEquals(NotifSeverity.INFO, m["c"]!!.severity)
        assertEquals(NotifSeverity.INFO, m["d"]!!.severity)
        assertEquals(NotifSeverity.INFO, m["e"]!!.severity)
    }

    @Test
    fun nonPositiveOrNullTimeoutIsPersistent() {
        val store = PushNotificationStore()
        store.post("a", "A", null, null, 0, 0L)
        store.post("b", "B", null, null, -5, 0L)
        store.post("c", "C", null, null, null, 0L)
        assertTrue(store.items.value.all { it.expiresAtMs == null })
    }

    @Test
    fun timeoutClampedToMinAndMax() {
        val store = PushNotificationStore()
        store.post("a", "A", null, null, 1, 0L)       // 1 -> clamp up to 5
        store.post("b", "B", null, null, 999_999, 0L) // -> clamp down to 86400
        val items = store.items.value.associateBy { it.id }
        assertEquals(5_000L, items["a"]!!.expiresAtMs)
        assertEquals(86_400_000L, items["b"]!!.expiresAtMs)
    }

    @Test
    fun prunesItemsAtOrPastExpiry() {
        val store = PushNotificationStore()
        store.post("keep", "K", null, null, null, 1000L)  // persistent
        store.post("soon", "S", null, null, 10, 1000L)    // expires at 1000 + 10_000 = 11_000
        store.prune(10_999L)
        assertEquals(setOf("keep", "soon"), store.items.value.map { it.id }.toSet())
        store.prune(11_000L) // boundary: at-expiry drops
        assertEquals(listOf("keep"), store.items.value.map { it.id })
    }

    @Test
    fun dismissClearClearAllIdempotent() {
        val store = PushNotificationStore()
        store.post("a", "A", null, null, null, 0L)
        store.post("b", "B", null, null, null, 0L)
        store.dismiss("a")
        assertEquals(listOf("b"), store.items.value.map { it.id })
        store.dismiss("a") // already gone -> no-op
        store.clear("nope") // unknown -> no-op
        assertEquals(listOf("b"), store.items.value.map { it.id })
        store.clearAll()
        assertTrue(store.items.value.isEmpty())
        store.clearAll() // idempotent
        assertTrue(store.items.value.isEmpty())
    }
}
