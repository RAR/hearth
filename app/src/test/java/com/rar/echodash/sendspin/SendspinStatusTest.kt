package com.rar.echodash.sendspin

import com.rar.echodash.sendspin.coordinator.FailureReason
import com.rar.echodash.sendspin.coordinator.TransportState
import org.junit.Assert.assertEquals
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
}
