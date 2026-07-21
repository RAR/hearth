package com.rar.hearth.sendspin

import com.rar.hearth.sendspin.coordinator.FailureReason
import com.rar.hearth.sendspin.coordinator.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure status-derivation function that maps a transport
 * [TransportState] plus a `streaming` flag onto the user-facing
 * [SendspinStatus]. This is the only JVM-testable pure unit in the endpoint;
 * the SendSpin / SyncAudioPlayer / NsdManager pipeline is device-bound and
 * verified only by the compile gate.
 */
class SendspinStatusTest {

    @Test
    fun ready_while_streaming_is_playing() {
        assertEquals(
            SendspinStatus.Playing,
            sendspinStatus(TransportState.Ready, streaming = true),
        )
    }

    @Test
    fun ready_without_stream_is_connected() {
        assertEquals(
            SendspinStatus.Connected,
            sendspinStatus(TransportState.Ready, streaming = false),
        )
    }

    @Test
    fun connecting_is_connecting() {
        assertEquals(
            SendspinStatus.Connecting,
            sendspinStatus(TransportState.Connecting, streaming = false),
        )
    }

    @Test
    fun idle_is_disconnected() {
        assertEquals(
            SendspinStatus.Disconnected,
            sendspinStatus(TransportState.Idle, streaming = false),
        )
    }

    @Test
    fun failed_is_disconnected() {
        assertEquals(
            SendspinStatus.Disconnected,
            sendspinStatus(
                TransportState.Failed(FailureReason.TransientNetwork),
                streaming = false,
            ),
        )
    }

    @Test
    fun reArm_on_recoverable_terminal_failures() {
        // Exhausted (8-min cap) AND the first-attempt give-ups the engine treats as non-recoverable
        // (refused/DNS/TLS -> HandshakeFailed) all self-heal: the common server-restart case.
        assertTrue(shouldReArmAfter(TransportState.Failed(FailureReason.Exhausted)))
        assertTrue(shouldReArmAfter(TransportState.Failed(FailureReason.HandshakeFailed)))
        assertTrue(shouldReArmAfter(TransportState.Failed(FailureReason.TransientNetwork)))
    }

    @Test
    fun no_reArm_when_retry_cannot_help() {
        // A blind retry won't fix these, so don't churn.
        assertFalse(shouldReArmAfter(TransportState.Failed(FailureReason.AuthRejected)))
        assertFalse(shouldReArmAfter(TransportState.Failed(FailureReason.ProtocolError)))
        // Idle = intentional teardown; Ready/Connecting = healthy or healing.
        assertFalse(shouldReArmAfter(TransportState.Idle))
        assertFalse(shouldReArmAfter(TransportState.Ready))
        assertFalse(shouldReArmAfter(TransportState.Connecting))
    }
}
