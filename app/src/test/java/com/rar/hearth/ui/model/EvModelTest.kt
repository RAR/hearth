package com.rar.hearth.ui.model

import com.rar.hearth.config.EvConfig
import com.rar.hearth.ha.EntityState
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
    fun limitParsedClampedAndRounded() {
        fun limit(v: String?) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", limit = v?.let { "sensor.limit" })),
            buildMap {
                put("binary_sensor.c", st("binary_sensor.c", "on"))
                if (v != null) put("sensor.limit", st("sensor.limit", v, "%"))
            },
            0L,
        ).single().limitPct
        assertEquals(80, limit("79.6"))
        assertEquals(100, limit("150"))
        assertNull(limit("n/a"))
        assertNull(limit(null)) // unconfigured
    }

    @Test
    fun limitShownWhileIdleToo() {
        val card = evCards(
            listOf(EvConfig(name = "Car", plugged = "binary_sensor.plug", limit = "sensor.limit")),
            mapOf(
                "binary_sensor.plug" to st("binary_sensor.plug", "on"),
                "sensor.limit" to st("sensor.limit", "80", "%"),
            ),
            0L,
        ).single()
        assertEquals(false, card.charging)
        assertEquals(80, card.limitPct)
    }

    @Test
    fun powerUnitAwareFormatting() {
        fun chargeLine(state: String, unit: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.p" to st("sensor.p", state, unit)),
            0L,
        ).single().chargeLine
        assertEquals("7.2 kW", chargeLine("7240", "W"))
        assertEquals("7.2 kW", chargeLine("7.24", "kW"))
        assertEquals("11 kW", chargeLine("11000", "W"))
    }

    @Test
    fun etaMinutesNumber() {
        fun card(v: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.eta")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.eta" to st("sensor.eta", v)),
            0L,
        ).single()
        val sixtyFive = card("65")
        assertNull(sixtyFive.chargeLine)
        assertEquals("1h05", sixtyFive.etaText)
        val fortyFive = card("45")
        assertNull(fortyFive.chargeLine)
        assertEquals("45m", fortyFive.etaText)
    }

    @Test
    fun etaDurationString() {
        val card = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.eta")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.eta" to st("sensor.eta", "1:05:00")),
            0L,
        ).single()
        assertNull(card.chargeLine)
        assertEquals("1h05", card.etaText)
    }

    @Test
    fun etaTimestamp() {
        val now = 1_700_000_000_000L
        val future = java.time.Instant.ofEpochMilli(now + 65 * 60_000L).toString()
        val past = java.time.Instant.ofEpochMilli(now - 5 * 60_000L).toString()
        fun card(v: String) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.eta")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.eta" to st("sensor.eta", v)),
            now,
        ).single()
        assertEquals("1h05", card(future).etaText)
        val pastCard = card(past)
        assertNull(pastCard.etaText) // finishing/past -> eta omitted; card still shown with null etaText
        assertNull(pastCard.chargeLine)
    }

    @Test
    fun statusLineJoinsAndOmits() {
        val base = mapOf("binary_sensor.c" to st("binary_sensor.c", "on"))
        val both = evCards(listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p", eta = "sensor.e")),
            base + mapOf("sensor.p" to st("sensor.p", "7240", "W"), "sensor.e" to st("sensor.e", "65")), 0L).single()
        assertEquals("7.2 kW", both.chargeLine)
        assertEquals("1h05", both.etaText)

        val p = evCards(listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p")),
            base + mapOf("sensor.p" to st("sensor.p", "7240", "W")), 0L).single()
        assertEquals("7.2 kW", p.chargeLine)
        assertNull(p.etaText)

        val e = evCards(listOf(EvConfig(charging = "binary_sensor.c", eta = "sensor.e")),
            base + mapOf("sensor.e" to st("sensor.e", "65")), 0L).single()
        assertNull(e.chargeLine)
        assertEquals("1h05", e.etaText)

        val none = evCards(listOf(EvConfig(name = "X", charging = "binary_sensor.c")), base, 0L).single()
        assertNull(none.chargeLine)
        assertNull(none.etaText)
        assertEquals("X", none.name)
    }

    @Test
    fun blankNameFallsBackToEV() {
        val card = evCards(listOf(EvConfig(name = "  ", charging = "binary_sensor.c")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on")), 0L).single()
        assertEquals("EV", card.name)
    }

    @Test
    fun pluggedShowsCardWithoutChargingAndHidesStatus() {
        val cards = evCards(
            listOf(EvConfig(name = "Car", plugged = "binary_sensor.plug", power = "sensor.p", soc = "sensor.soc")),
            mapOf(
                "binary_sensor.plug" to st("binary_sensor.plug", "on"),
                "sensor.p" to st("sensor.p", "7240", "W"),
                "sensor.soc" to st("sensor.soc", "50", "%"),
            ),
            0L,
        )
        assertEquals(1, cards.size)
        assertEquals(false, cards[0].charging)
        assertNull(cards[0].chargeLine) // plugged-idle: power reads 0 W noise -> no charge line
        assertNull(cards[0].etaText)
        assertEquals(50, cards[0].socPct)
    }

    @Test
    fun statusLetterEntityDrivesBothStates() {
        fun cards(state: String) = evCards(
            listOf(EvConfig(name = "Car", plugged = "sensor.status", charging = "sensor.status")),
            mapOf("sensor.status" to st("sensor.status", state)),
            0L,
        )
        val b = cards("B")
        assertEquals(1, b.size)
        assertEquals(false, b[0].charging)
        val c = cards("C")
        assertEquals(1, c.size)
        assertEquals(true, c[0].charging)
        assertEquals(emptyList<EvCard>(), cards("A"))
    }

    @Test
    fun energyUnitAwareInStatusLine() {
        fun card(energyState: String, unit: String?) = evCards(
            listOf(EvConfig(charging = "binary_sensor.c", power = "sensor.p", energy = "sensor.e", eta = "sensor.eta")),
            mapOf(
                "binary_sensor.c" to st("binary_sensor.c", "on"),
                "sensor.p" to st("sensor.p", "7240", "W"),
                "sensor.e" to st("sensor.e", energyState, unit),
                "sensor.eta" to st("sensor.eta", "65"),
            ),
            0L,
        ).single()
        val whToKwh = card("4300", null) // Wh -> kWh
        assertEquals("7.2 kW · 4.3 kWh", whToKwh.chargeLine)
        assertEquals("1h05", whToKwh.etaText)
        val kwh = card("4.3", "kWh")
        assertEquals("7.2 kW · 4.3 kWh", kwh.chargeLine)
        assertEquals("1h05", kwh.etaText)
        val integerAtTen = card("12.4", "kWh") // integer at >= 10
        assertEquals("7.2 kW · 12 kWh", integerAtTen.chargeLine)
        assertEquals("1h05", integerAtTen.etaText)
    }

    @Test
    fun chargingFlagSetWhileCharging() {
        val card = evCards(
            listOf(EvConfig(charging = "binary_sensor.c")),
            mapOf("binary_sensor.c" to st("binary_sensor.c", "on")),
            0L,
        ).single()
        assertEquals(true, card.charging)
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

    @Test
    fun cardCarriesItsConfigSlotThroughCompaction() {
        // Slot 0 is unplugged, slot 1 is charging: the single card returned must still report
        // slot 1, not the index 0 it happens to occupy in the compacted list.
        val cards = evCards(
            listOf(
                EvConfig(name = "First", charging = "binary_sensor.a"),
                EvConfig(name = "Second", charging = "binary_sensor.b"),
            ),
            mapOf(
                "binary_sensor.a" to st("binary_sensor.a", "off"),
                "binary_sensor.b" to st("binary_sensor.b", "on"),
            ),
            0L,
        )
        assertEquals(1, cards.size)
        assertEquals("Second", cards[0].name)
        assertEquals(1, cards[0].slot)
    }

    @Test
    fun slotsAreAssignedFromConfigPositionNotOutputPosition() {
        val cards = evCards(
            listOf(
                EvConfig(name = "First", charging = "binary_sensor.a"),
                EvConfig(name = "Second", charging = "binary_sensor.b"),
            ),
            mapOf(
                "binary_sensor.a" to st("binary_sensor.a", "on"),
                "binary_sensor.b" to st("binary_sensor.b", "on"),
            ),
            0L,
        )
        assertEquals(listOf(0, 1), cards.map { it.slot })
    }
}
