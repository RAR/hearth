package com.rar.hearth.sendspin.sendspin.transport

import io.ktor.websocket.CloseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A SendSpin session that ends without throwing reports its close code through
 * [BaseWebSocketTransport.resolveCloseCode]. SendSpin's onClosed gate treats 1000 as
 * "the server ended this deliberately" and does NOT reconnect, so anything that is
 * merely a dropped socket must not surface as 1000.
 *
 * Field failure this guards (Kitchen, 2026-08-08): the socket to Music Assistant died
 * with no Close frame, the null reason defaulted to 1000, SendSpin logged "Server
 * closed connection normally - session ended", and the link stayed dead for the life
 * of the process.
 */
class BaseWebSocketTransportCloseCodeTest {

    @Test
    fun nullReason_isAbnormalClosure_notNormal() {
        val code = BaseWebSocketTransport.resolveCloseCode(null)

        assertEquals(BaseWebSocketTransport.CLOSE_CODE_ABNORMAL, code)
        // The whole point: this must not land on the no-reconnect path.
        assertNotEquals(1000, code)
    }

    @Test
    fun abnormalClosureConstant_isRfc6455Value() {
        assertEquals(1006, BaseWebSocketTransport.CLOSE_CODE_ABNORMAL)
    }

    @Test
    fun explicitNormalClosure_isPreserved() {
        val reason = CloseReason(CloseReason.Codes.NORMAL, "bye")

        assertEquals(1000, BaseWebSocketTransport.resolveCloseCode(reason))
    }

    @Test
    fun explicitGoingAway_isPreserved() {
        // 1001 is what the stall watchdog forces so the abnormal path runs.
        val reason = CloseReason(CloseReason.Codes.GOING_AWAY, "restart")

        assertEquals(1001, BaseWebSocketTransport.resolveCloseCode(reason))
    }

    @Test
    fun explicitInternalError_isPreserved() {
        val reason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, "boom")

        assertEquals(1011, BaseWebSocketTransport.resolveCloseCode(reason))
    }
}
