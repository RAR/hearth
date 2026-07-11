package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WsParserTest {

    @Test
    fun parsesAuthHandshakeMessages() {
        assertEquals(WsIncoming.AuthRequired, WsParser.parse("""{"type":"auth_required","ha_version":"2025.1.0"}"""))
        assertEquals(WsIncoming.AuthOk, WsParser.parse("""{"type":"auth_ok","ha_version":"2025.1.0"}"""))
        assertEquals(WsIncoming.AuthInvalid("Invalid access token"),
            WsParser.parse("""{"type":"auth_invalid","message":"Invalid access token"}"""))
    }

    @Test
    fun parsesEntityAddEvent() {
        val msg = WsParser.parse(
            """{"id":1,"type":"event","event":{"a":{"sensor.outside_temperature":
               {"s":"15.6","a":{"unit_of_measurement":"°C","friendly_name":"Outside Temperature","device_class":"temperature"}}}}}"""
                .replace("\n", "").replace("               ", "")
        )
        val update = msg as WsIncoming.EntityUpdate
        assertEquals(
            EntityPatch(state = "15.6", unit = "°C", friendlyName = "Outside Temperature"),
            update.states["sensor.outside_temperature"]
        )
    }

    @Test
    fun parsesEntityChangeEvent() {
        val msg = WsParser.parse(
            """{"id":1,"type":"event","event":{"c":{"sensor.outside_temperature":{"+":{"s":"16.0","lc":1720000000}}}}}"""
        )
        val update = msg as WsIncoming.EntityUpdate
        assertEquals(
            EntityPatch(state = "16.0", unit = null, friendlyName = null),
            update.states["sensor.outside_temperature"]
        )
    }

    @Test
    fun parsesResultMessage() {
        val msg = WsParser.parse("""{"id":7,"type":"result","success":true,"result":[1,2]}""")
        val result = msg as WsIncoming.Result
        assertEquals(7, result.id)
        assertTrue(result.success)
    }

    @Test
    fun filtersTemperatureSensorsFromGetStates() {
        val states = Json.parseToJsonElement(
            """[
              {"entity_id":"sensor.outside_temperature","state":"15.6","attributes":{"device_class":"temperature","unit_of_measurement":"°C","friendly_name":"Outside Temperature"}},
              {"entity_id":"sensor.outside_temperature_battery","state":"12","attributes":{"device_class":"battery"}},
              {"entity_id":"light.kitchen","state":"on","attributes":{}},
              {"entity_id":"sensor.no_attrs","state":"x","attributes":{}}
            ]"""
        )
        val sensors = WsParser.temperatureSensors(states)
        assertEquals(1, sensors.size)
        assertEquals(
            EntityState("sensor.outside_temperature", "15.6", "°C", "Outside Temperature"),
            sensors[0]
        )
    }
}
