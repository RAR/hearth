package com.rar.echodash.camera

import com.rar.echodash.config.DoorbellConfig
import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoorbellCoordinatorTest {

    private fun st(id: String, state: String) = EntityState(id, state, JsonObject(emptyMap()), 0L)
    private val doorbells = listOf(DoorbellConfig(trigger = "binary_sensor.front_visitor", camera = "Front Door"))

    @Test
    fun firstStateSeenNeverFires() {
        val c = DoorbellCoordinator()
        assertNull(c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 1_000L))
    }

    @Test
    fun offToOnFires() {
        val c = DoorbellCoordinator()
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 0L)
        val cmd = c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 1_000L)
        assertEquals(PopupCommand.Show("Front Door", 1_000L + 30_000L), cmd)
    }

    @Test
    fun onToOnAndOffToOffDoNotFire() {
        val c = DoorbellCoordinator()
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 0L)
        assertNull(c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 1_000L))
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 2_000L)
        assertNull(c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 3_000L))
    }

    @Test
    fun reTriggerFiresAgainWithExtendedUntil() {
        val c = DoorbellCoordinator()
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 0L)
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 1_000L)
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 2_000L)
        val cmd = c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 5_000L)
        assertEquals(PopupCommand.Show("Front Door", 5_000L + 30_000L), cmd)
    }

    @Test
    fun secondDoorbellSwitchesCamera() {
        val two = listOf(
            DoorbellConfig(trigger = "binary_sensor.front_visitor", camera = "Front Door"),
            DoorbellConfig(trigger = "binary_sensor.back_visitor", camera = "Back Door"),
        )
        val c = DoorbellCoordinator()
        val states0 = mapOf(
            "binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off"),
            "binary_sensor.back_visitor" to st("binary_sensor.back_visitor", "off"),
        )
        c.onStates(two, states0, 30, 0L)
        val back = c.onStates(two, mapOf(
            "binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off"),
            "binary_sensor.back_visitor" to st("binary_sensor.back_visitor", "on"),
        ), 30, 4_000L)
        assertEquals(PopupCommand.Show("Back Door", 4_000L + 30_000L), back)
    }

    @Test
    fun eventEntityFiresOnAnyStateChangeAfterFirstSeen() {
        val ev = listOf(DoorbellConfig(trigger = "event.doorbell_press", camera = "Front Door"))
        val c = DoorbellCoordinator()
        c.onStates(ev, mapOf("event.doorbell_press" to st("event.doorbell_press", "2026-07-12T10:00:00Z")), 30, 0L)
        val cmd = c.onStates(ev, mapOf("event.doorbell_press" to st("event.doorbell_press", "2026-07-12T10:05:00Z")), 30, 1_000L)
        assertEquals(PopupCommand.Show("Front Door", 1_000L + 30_000L), cmd)
    }

    @Test
    fun clearingStatesResetsFirstSeenSoReconnectDoesNotFire() {
        val c = DoorbellCoordinator()
        c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "off")), 30, 0L)
        c.onStates(doorbells, emptyMap(), 30, 1_000L) // reconnect clears the map
        // First state after resubscribe is "on" but must be recorded, not fired.
        assertNull(c.onStates(doorbells, mapOf("binary_sensor.front_visitor" to st("binary_sensor.front_visitor", "on")), 30, 2_000L))
    }
}
