package com.rar.echodash.ui.model

import com.rar.echodash.config.EvConfig
import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, unit: String? = null): EntityState {
        val a = if (unit != null) attrs("""{"unit_of_measurement":"$unit"}""") else attrs("{}")
        return EntityState(id, state, a, 0L)
    }

    @Test
    fun noCardWhenChargingEntityMissingOrFalsy() {
        // charging not configured
        assertEquals(emptyList<EvCard>(),
            evCards(listOf(EvConfig(name = "A", soc = "sensor.soc")),
                mapOf("sensor.soc" to st("sensor.soc", "50")), 0L))
        // charging off
        assertEquals(emptyList<EvCard>(),
            evCards(listOf(EvConfig(charging = "binary_sensor.c")),
                mapOf("binary_sensor.c" to st("binary_sensor.c", "off")), 0L))
        // charging unavailable
        assertEquals(emptyList<EvCard>(),
            evCards(listOf(EvConfig(charging = "binary_sensor.c")),
                mapOf("binary_sensor.c" to st("binary_sensor.c", "unavailable")), 0L))
        // charging entity missing from the map
        assertEquals(emptyList<EvCard>(),
            evCards(listOf(EvConfig(charging = "binary_sensor.c")), emptyMap(), 0L))
    }

    @Test
    fun truthyVariantsProduceCard() {
        listOf("on", "true", "Charging").forEach { s ->
            val cards = evCards(listOf(EvConfig(name = "Car", charging = "binary_sensor.c")),
                mapOf("binary_sensor.c" to st("binary_sensor.c", s)), 0L)
            assertEquals(1, cards.size)
            assertEquals("Car", cards[0].name)
        }
    }

    @Test
    fun socClampedAndRounded() {
        fun soc(v: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", soc = "sensor.soc")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.soc" to st("sensor.soc", v, "%")),
            0L,
        ).single().socPct
        assertEquals(64, soc("63.6"))
        assertEquals(100, soc("104"))
        assertNull(soc("n/a"))
    }

    @Test
    fun powerUnitAwareFormatting() {
        fun status(state: String, unit: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.p" to st("sensor.p", state, unit)),
            0L,
        ).single().statusLine
        assertEquals("7.2 kW", status("7240", "W"))
        assertEquals("7.2 kW", status("7.24", "kW"))
        assertEquals("11 kW", status("11000", "W"))
    }

    @Test
    fun etaMinutesNumber() {
        fun status(v: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.eta")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.eta" to st("sensor.eta", v)),
            0L,
        ).single().statusLine
        assertEquals("1h05 left", status("65"))
        assertEquals("45m left", status("45"))
    }

    @Test
    fun etaDurationString() {
        val status = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.eta")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.eta" to st("sensor.eta", "1:05:00")),
            0L,
        ).single().statusLine
        assertEquals("1h05 left", status)
    }

    @Test
    fun etaTimestamp() {
        val now = 1_700_000_000_000L
        val future = java.time.Instant.ofEpochMilli(now + 65 * 60_000L).toString()
        val past = java.time.Instant.ofEpochMilli(now - 5 * 60_000L).toString()
        fun status(v: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.eta")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.eta" to st("sensor.eta", v)),
            now,
        ).single().statusLine
        assertEquals("1h05 left", status(future))
        assertNull(status(past)) // finishing/past -> eta omitted; card still shown with null statusLine
    }

    @Test
    fun statusLineJoinsAndOmits() {
        val base = mapOf("binary_sensor.c" to st("binary_sensor.c", "on"))
        val both = evCards(listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p", eta = "sensor.e")),
            base + mapOf("sensor.p" to st("sensor.p", "7240", "W"), "sensor.e" to st("sensor.e", "65")), 0L).single()
        assertEquals("7.2 kW · 1h05 left", both.statusLine)

        val p = evCards(listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p")),
            base + mapOf("sensor.p" to st("sensor.p", "7240", "W")), 0L).single()
        assertEquals("7.2 kW", p.statusLine)

        val e = evCards(listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.e")),
            base + mapOf("sensor.e" to st("sensor.e", "65")), 0L).single()
        assertEquals("1h05 left", e.statusLine)

        val none = evCards(listOf(EvConfig(name = "X", charging = "binary_sensor.c")), base, 0L).single()
        assertNull(none.statusLine)
        assertEquals("X", none.name)
    }

    @Test
    fun blankNameFallsBackToEV() {
        val card = evCards(listOf(EvConfig(name = "  ", charging = "binary_sensor.c")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on")), 0L).single()
        assertEquals("EV", card.name)
    }

    @Test
    fun twoChargingKeepConfigOrderAndSkipIdle() {
        val oneOn = mapOf(
            "binary_sensor.a" to st("binary_sensor.a", "off"),
            "binary_sensor.b" to st("binary_sensor.b", "on"),
        )
        val one = evCards(listOf(
            EvConfig(name = "A", charging = "binary_sensor.a"),
            EvConfig(name = "B", charging = "binary_sensor.b"),
        ), oneOn, 0L)
        assertEquals(listOf("B"), one.map { it.name })

        val bothOn = mapOf(
            "binary_sensor.a" to st("binary_sensor.a", "on"),
            "binary_sensor.b" to st("binary_sensor.b", "charging"),
        )
        val two = evCards(listOf(
            EvConfig(name = "A", charging = "binary_sensor.a"),
            EvConfig(name = "B", charging = "binary_sensor.b"),
        ), bothOn, 0L)
        assertEquals(listOf("A", "B"), two.map { it.name })
    }
}
