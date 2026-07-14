from __future__ import annotations

import asyncio
from contextlib import suppress

import pytest

from hearth_proto.client import HearthClient
from hearth_proto.codec import WyomingEvent, read_event, write_event

DEFAULT_SETTINGS = {
    "screen_on": True,
    "screen_brightness": 80,
    "screen_auto_brightness": False,
    "screen_always_on": True,
    "screen_saver": False,
    "dark_mode": False,
    "screen_timeout": 60,
    "music_volume": 5,
    "ducking_volume": 2,
}


class FakeAppServer:
    """Speaks the app's VacaServer contract: probe replies, session, feedback, announce ack."""

    def __init__(self, *, device_name="Test Hearth", app_version="9.9", has_light=True) -> None:
        self.device_name = device_name
        self.app_version = app_version
        self.has_light = has_light
        self.settings_state = dict(DEFAULT_SETTINGS)
        self.received: list[WyomingEvent] = []
        self.audio_payload = bytearray()
        self._server: asyncio.AbstractServer | None = None
        self.session_writer: asyncio.StreamWriter | None = None
        self.host = "127.0.0.1"
        self.port = 0

    async def start(self) -> None:
        self._server = await asyncio.start_server(
            self._handle, "127.0.0.1", 0, limit=1 << 20
        )
        self.host, self.port = self._server.sockets[0].getsockname()[:2]

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            with suppress(Exception):
                await self._server.wait_closed()

    async def drop_session(self) -> None:
        if self.session_writer is not None:
            self.session_writer.close()
            with suppress(Exception):
                await self.session_writer.wait_closed()
            self.session_writer = None

    def _info(self) -> WyomingEvent:
        return WyomingEvent(
            "info",
            {
                "satellite": {
                    "name": self.device_name,
                    "version": self.app_version,
                    "attribution": {"name": self.device_name, "url": "https://example.invalid"},
                }
            },
        )

    def _capabilities(self) -> WyomingEvent:
        return WyomingEvent(
            "capabilities",
            {
                "app_version": self.app_version,
                "has_battery": False,
                "sensors": [{"type": 5}] if self.has_light else [],
                "audio": {"max_music_volume": 10},
            },
        )

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while True:
                event = await read_event(reader)
                if event is None:
                    break
                self.received.append(event)
                if event.type == "describe":
                    await write_event(writer, self._info())
                elif event.type == "capabilities":
                    await write_event(writer, self._capabilities())
                elif event.type == "ping":
                    await write_event(writer, WyomingEvent("pong", {"text": event.data.get("text")}))
                elif event.type == "run-satellite":
                    self.session_writer = writer
                    await write_event(
                        writer,
                        WyomingEvent(
                            "custom-event",
                            {"event_type": "settings", "data": {"settings": self.settings_state}},
                        ),
                    )
                    await write_event(
                        writer,
                        WyomingEvent(
                            "custom-event",
                            {"event_type": "status", "data": {"media_player": {"playing": False}}},
                        ),
                    )
                elif event.type == "audio-chunk":
                    self.audio_payload.extend(event.payload)
                elif event.type == "audio-stop":
                    await write_event(writer, WyomingEvent("played"))
                # audio-start and custom-event (settings/action) just get recorded
        except Exception:
            pass
        finally:
            with suppress(Exception):
                writer.close()


def _custom_events(server: FakeAppServer) -> list[WyomingEvent]:
    return [e for e in server.received if e.type == "custom-event"]


def test_handshake_captures_name_and_version():
    async def scenario() -> FakeAppServer:
        server = FakeAppServer()
        await server.start()
        client = HearthClient(server.host, server.port, ping_interval=5)
        await client.async_start()
        await client.async_wait_connected(2)
        assert client.device_name == "Test Hearth"
        assert client.app_version == "9.9"
        assert client.connected is True
        await client.async_stop()
        await server.stop()
        return server

    server = asyncio.run(scenario())
    assert [e.type for e in server.received[:3]] == ["describe", "capabilities", "run-satellite"]


def test_feedback_and_status_dispatched_unwrapped():
    events: list[tuple[str, dict]] = []

    async def scenario() -> None:
        server = FakeAppServer()
        await server.start()
        client = HearthClient(server.host, server.port, ping_interval=5)
        client.add_listener(lambda kind, data: events.append((kind, data)))
        await client.async_start()
        await client.async_wait_connected(2)
        await asyncio.sleep(0.2)  # let the immediate feedback + status arrive
        await client.async_stop()
        await server.stop()

    asyncio.run(scenario())
    settings = [d for k, d in events if k == "settings"]
    status = [d for k, d in events if k == "status"]
    connection = [d for k, d in events if k == "connection"]
    assert settings and settings[0] == DEFAULT_SETTINGS  # unwrapped from data.settings
    assert status and status[0] == {"media_player": {"playing": False}}  # unwrapped from data
    assert {"connected": True} in connection


def test_send_settings_is_flat():
    async def scenario() -> FakeAppServer:
        server = FakeAppServer()
        await server.start()
        client = HearthClient(server.host, server.port, ping_interval=5)
        await client.async_start()
        await client.async_wait_connected(2)
        await client.async_send_settings({"screen_on": False})
        await asyncio.sleep(0.1)
        await client.async_stop()
        await server.stop()
        return server

    server = asyncio.run(scenario())
    sent = [e for e in _custom_events(server) if e.data.get("event_type") == "settings"]
    assert sent and sent[-1].data == {"event_type": "settings", "settings": {"screen_on": False}}


def test_send_action_is_flat_with_payload():
    async def scenario() -> FakeAppServer:
        server = FakeAppServer()
        await server.start()
        client = HearthClient(server.host, server.port, ping_interval=5)
        await client.async_start()
        await client.async_wait_connected(2)
        await client.async_send_action("set-volume", {"volume": 50})
        await client.async_send_action("refresh")
        await asyncio.sleep(0.1)
        await client.async_stop()
        await server.stop()
        return server

    server = asyncio.run(scenario())
    actions = [e for e in _custom_events(server) if e.data.get("event_type") == "action"]
    by_action = {e.data["action"]: e.data for e in actions}
    assert by_action["set-volume"] == {"event_type": "action", "action": "set-volume", "payload": {"volume": 50}}
    assert by_action["refresh"] == {"event_type": "action", "action": "refresh"}  # no payload key


def test_announce_streams_and_resolves_on_played():
    async def scenario() -> FakeAppServer:
        server = FakeAppServer()
        await server.start()
        client = HearthClient(server.host, server.port, ping_interval=5)
        await client.async_start()
        await client.async_wait_connected(2)

        async def gen():
            yield b"\x01\x02"
            yield b"\x03\x04"

        await asyncio.wait_for(client.async_announce(gen(), 22050, 2, 1), timeout=2)
        await client.async_stop()
        await server.stop()
        return server

    server = asyncio.run(scenario())
    types = [e.type for e in server.received]
    assert "audio-start" in types
    assert "audio-stop" in types
    assert bytes(server.audio_payload) == b"\x01\x02\x03\x04"


def test_ping_keepalive_keeps_connection_up():
    async def scenario() -> tuple[FakeAppServer, bool]:
        server = FakeAppServer()
        await server.start()
        client = HearthClient(server.host, server.port, ping_interval=0.05)
        await client.async_start()
        await client.async_wait_connected(2)
        await asyncio.sleep(0.25)  # several ping intervals
        still_up = client.connected
        await client.async_stop()
        await server.stop()
        return server, still_up

    server, still_up = asyncio.run(scenario())
    assert any(e.type == "ping" for e in server.received)
    assert still_up is True


def test_reconnect_after_connection_drop():
    conn: list[bool] = []

    async def scenario() -> bool:
        server = FakeAppServer()
        await server.start()
        client = HearthClient(
            server.host, server.port, ping_interval=0.05, backoff_base=0.05, backoff_max=0.2
        )
        client.add_listener(
            lambda kind, data: conn.append(data["connected"]) if kind == "connection" else None
        )
        await client.async_start()
        await client.async_wait_connected(2)
        await server.drop_session()
        await asyncio.sleep(0.05)
        await client.async_wait_connected(2)  # supervisor reconnects
        reconnected = client.connected
        await client.async_stop()
        await server.stop()
        return reconnected

    reconnected = asyncio.run(scenario())
    assert reconnected is True
    assert True in conn and False in conn  # saw at least one up and one down transition
