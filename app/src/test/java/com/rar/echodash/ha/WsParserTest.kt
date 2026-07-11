package com.rar.echodash.ha

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
    fun parsesEventCarriesIdAndInnerEvent() {
        val msg = WsParser.parse(
            """{"id":6,"type":"event","event":{"a":{"light.kitchen":{"s":"on","a":{}}}}}"""
        ) as WsIncoming.Event
        assertEquals(6, msg.id)
        assertTrue(msg.event.containsKey("a"))
    }

    @Test
    fun parsesResultMessage() {
        val msg = WsParser.parse("""{"id":7,"type":"result","success":true,"result":[1,2]}""") as WsIncoming.Result
        assertEquals(7, msg.id)
        assertTrue(msg.success)
    }
}
