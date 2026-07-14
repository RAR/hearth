"""HearthClient: owns one Wyoming TCP session to the device and dispatches events (HA-free)."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator, Callable
from contextlib import suppress
from typing import Any

from .codec import (
    MAX_HEADER_BYTES,
    ProtocolError,
    WyomingEvent,
    read_event,
    write_event,
)

_LOGGER = logging.getLogger(__name__)

Listener = Callable[[str, dict], None]


class HearthClient:
    """Connect, handshake (describe/capabilities/run-satellite), keepalive, reconnect, dispatch."""

    def __init__(
        self,
        host: str,
        port: int,
        *,
        ping_interval: float = 4.0,
        backoff_base: float = 1.0,
        backoff_max: float = 60.0,
    ) -> None:
        self._host = host
        self._port = port
        self._ping_interval = ping_interval
        self._backoff_base = backoff_base
        self._backoff_max = backoff_max
        self._backoff = backoff_base

        self._running = False
        self._task: asyncio.Task | None = None
        self._writer: asyncio.StreamWriter | None = None
        self._write_lock = asyncio.Lock()

        self._connected = False
        self._device_name: str | None = None
        self._app_version: str | None = None
        self._awaiting_pong = False
        self._announce_future: asyncio.Future | None = None

        self._listeners: list[Listener] = []
        self._connected_waiters: list[asyncio.Future] = []

    # ---- public properties --------------------------------------------------
    @property
    def connected(self) -> bool:
        return self._connected

    @property
    def device_name(self) -> str | None:
        return self._device_name

    @property
    def app_version(self) -> str | None:
        return self._app_version

    # ---- listeners ----------------------------------------------------------
    def add_listener(self, cb: Listener) -> Callable[[], None]:
        self._listeners.append(cb)

        def remove() -> None:
            if cb in self._listeners:
                self._listeners.remove(cb)

        return remove

    def _emit(self, kind: str, data: dict) -> None:
        for cb in list(self._listeners):
            try:
                cb(kind, data)
            except Exception:  # noqa: BLE001 - a listener must never break the loop
                _LOGGER.exception("hearth listener raised for %s", kind)

    # ---- lifecycle ----------------------------------------------------------
    async def async_start(self) -> None:
        if self._task is not None:
            return
        self._running = True
        self._backoff = self._backoff_base
        self._task = asyncio.create_task(self._run())

    async def async_stop(self) -> None:
        self._running = False
        if self._task is not None:
            self._task.cancel()
            with suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        self._close_writer()

    async def async_wait_connected(self, timeout: float) -> None:
        if self._connected:
            return
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        self._connected_waiters.append(fut)
        try:
            await asyncio.wait_for(fut, timeout)
        finally:
            if fut in self._connected_waiters:
                self._connected_waiters.remove(fut)

    # ---- outbound API -------------------------------------------------------
    async def async_send_settings(self, settings: dict) -> None:
        await self._send(WyomingEvent("custom-event", {"event_type": "settings", "settings": settings}))

    async def async_send_action(self, name: str, payload: Any | None = None) -> None:
        data: dict[str, Any] = {"event_type": "action", "action": name}
        if payload is not None:
            data["payload"] = payload
        await self._send(WyomingEvent("custom-event", data))

    async def async_announce(
        self, pcm_stream: AsyncIterator[bytes], rate: int, width: int, channels: int
    ) -> None:
        if not self._connected:
            return
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        self._announce_future = fut
        try:
            await self._send(WyomingEvent("audio-start", {"rate": rate, "width": width, "channels": channels}))
            async for chunk in pcm_stream:
                if chunk:
                    await self._send(WyomingEvent("audio-chunk", {}, chunk))
            await self._send(WyomingEvent("audio-stop"))
            await fut  # resolved by a 'played' event or by disconnect
        finally:
            if self._announce_future is fut:
                self._announce_future = None

    # ---- internals ----------------------------------------------------------
    async def _send(self, event: WyomingEvent) -> None:
        writer = self._writer
        if writer is None:
            return
        async with self._write_lock:
            await write_event(writer, event)

    def _close_writer(self) -> None:
        writer = self._writer
        if writer is not None:
            with suppress(Exception):
                writer.close()

    def _set_connected(self, value: bool) -> None:
        if self._connected == value:
            return
        self._connected = value
        if value:
            for fut in self._connected_waiters:
                if not fut.done():
                    fut.set_result(None)
            self._connected_waiters = []
        self._emit("connection", {"connected": value})

    def _resolve_announce(self) -> None:
        fut = self._announce_future
        if fut is not None and not fut.done():
            fut.set_result(None)

    async def _run(self) -> None:
        while self._running:
            try:
                await self._connect_and_run()
            except asyncio.CancelledError:
                raise
            except Exception as err:  # noqa: BLE001
                _LOGGER.debug("hearth connection ended: %s", err)
            if not self._running:
                break
            await asyncio.sleep(self._backoff)
            self._backoff = min(self._backoff * 2, self._backoff_max)

    async def _connect_and_run(self) -> None:
        reader, writer = await asyncio.open_connection(self._host, self._port, limit=MAX_HEADER_BYTES)
        self._writer = writer
        ping_task: asyncio.Task | None = None
        try:
            await self._handshake(reader)
            self._backoff = self._backoff_base  # reset only after a successful handshake
            self._set_connected(True)
            ping_task = asyncio.create_task(self._ping_loop())
            await self._read_loop(reader)
        finally:
            if ping_task is not None:
                ping_task.cancel()
                with suppress(asyncio.CancelledError):
                    await ping_task
            self._awaiting_pong = False
            self._set_connected(False)
            self._resolve_announce()
            self._writer = None
            with suppress(Exception):
                writer.close()
                await writer.wait_closed()

    async def _handshake(self, reader: asyncio.StreamReader) -> None:
        await self._send(WyomingEvent("describe"))
        info = await self._read_until(reader, "info")
        satellite = info.data.get("satellite") or {}
        self._device_name = satellite.get("name")
        self._app_version = satellite.get("version")

        await self._send(WyomingEvent("capabilities"))
        caps = await self._read_until(reader, "capabilities")
        if caps.data.get("app_version"):
            self._app_version = caps.data["app_version"]

        await self._send(WyomingEvent("run-satellite"))

    async def _read_until(self, reader: asyncio.StreamReader, want: str) -> WyomingEvent:
        while True:
            event = await read_event(reader)
            if event is None:
                raise ProtocolError("connection closed during handshake")
            if event.type == want:
                return event
            # ignore anything else during handshake (e.g. a stray pong)

    async def _read_loop(self, reader: asyncio.StreamReader) -> None:
        while True:
            event = await read_event(reader)
            if event is None:
                break
            self._dispatch(event)

    def _dispatch(self, event: WyomingEvent) -> None:
        if event.type == "pong":
            self._awaiting_pong = False
        elif event.type == "played":
            self._resolve_announce()
        elif event.type == "custom-event":
            event_type = event.data.get("event_type")
            body = event.data.get("data")
            body = body if isinstance(body, dict) else {}
            if event_type == "settings":
                settings = body.get("settings")
                self._emit("settings", settings if isinstance(settings, dict) else {})
            elif event_type == "status":
                self._emit("status", body)

    async def _ping_loop(self) -> None:
        counter = 0
        while True:
            await asyncio.sleep(self._ping_interval)
            if self._awaiting_pong:
                self._close_writer()  # previous ping unanswered -> dead; ends the read loop
                return
            counter += 1
            self._awaiting_pong = True
            try:
                await self._send(WyomingEvent("ping", {"text": f"hearth-{counter}"}))
            except Exception:  # noqa: BLE001
                self._close_writer()
                return
