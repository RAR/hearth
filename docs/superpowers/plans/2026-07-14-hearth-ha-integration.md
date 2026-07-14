# Hearth HA Integration (VACA replacement, sub-project A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a slim first-party Home Assistant custom integration (`custom_components/hearth/`, domain `hearth`) that reaches VACA parity over the existing app-side Wyoming TCP protocol, plus the single app change that advertises `_hearth._tcp`.

**Architecture:** One config entry per device (unique_id `host:port`). A per-entry `HearthClient` owns the TCP connection and fans events out to entities. All protocol code (`codec.py`, `client.py`) is HA-import-free and unit-tested with plain pytest against a fake asyncio server that speaks the app's exact contract. The HA entity layer is thin and compile-checked only (HA is not installed locally); it is verified live on the user's HAOS.

**Tech Stack:** Python 3.13-compatible (runs on HAOS; authored/tested on 3.14), stdlib `asyncio` framing codec (no `wyoming` lib), pytest 9 (stdlib only, NO pytest-asyncio), Home Assistant 2024.x stable APIs, HA's bundled ffmpeg for announce transcoding. One Kotlin change (`NsdAdvertiser`) gated by gradle.

## Global Constraints

- Integration domain: `hearth`. NO pip requirements (manifest `"requirements": []`). No HA imports in `codec.py`/`client.py`. Python 3.13-compatible: `from __future__ import annotations` at the top of every module; no 3.14-only syntax.
- `iot_class` `local_push`; config entry `unique_id = "host:port"`; one entry per device.
- Protocol framing/vocabulary must match the app's `WyomingEvent.kt`/`VacaServer.kt`/`VacaMessages.kt` EXACTLY (they are the contract; the VACA Python source is NOT a reference — clean room).
- Kotlin: `compileSdk 34` never bump, no new Android deps, gate `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` (exit 0).
- Python gate: `python3 -m pytest tests/integration -q` (stdlib + pytest only).
- Work directly on `master`. Every commit message ends with the trailer line:
  ```
  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```
- User-facing strings say "Hearth", never "Echo Dashboard".

## File Structure

```
hacs.json                                 # {"name":"Hearth"} at repo ROOT (Task 3)
custom_components/hearth/
  __init__.py         # entry setup/teardown, HearthClient lifecycle, hearth.toast service (Task 3)
  manifest.json       # domain hearth, config_flow, zeroconf _hearth._tcp, iot_class local_push, requirements [] (Task 3)
  const.py            # DOMAIN, setting keys, action names, tuning constants (Task 3)
  codec.py            # Wyoming JSONL read/write (HA-free) (Task 1)
  client.py           # HearthClient: session, reconnect, dispatch (HA-free) (Task 2)
  config_flow.py      # zeroconf + manual host/port, describe-probe (Task 3)
  media_player.py     # play/pause/stop/volume/play-media/announce (Task 3)
  switch.py           # 5 switches (Task 3)
  number.py           # 3 numbers (Task 3)
  button.py           # refresh button (Task 3)
  strings.json        # config-flow + entity copy (Task 3)
  translations/en.json
  services.yaml       # hearth.toast (Task 3)
tests/integration/
  conftest.py         # loads codec.py + client.py by file path as package `hearth_proto`,
                      #   bypassing the HA-importing custom_components/hearth/__init__.py (Task 1)
  test_codec.py       # codec round-trips + limits + EOF (Task 1)
  test_client.py      # HearthClient vs FakeAppServer (Task 2)
```

**Why `conftest.py` / `hearth_proto`:** the integration tests must NOT import `custom_components.hearth.codec` directly — Python would execute `custom_components/hearth/__init__.py`, which imports `homeassistant` (not installed in the test env), aborting collection. `codec.py` and `client.py` are HA-free, so `conftest.py` loads them by file path under a synthetic package `hearth_proto` (giving `client.py`'s `from .codec import ...` a package to resolve against). Tests import `from hearth_proto.codec import ...` / `from hearth_proto.client import ...`. (Verified locally: 22 tests pass on Python 3.14 / pytest 9.)

The one app change (`_hearth._tcp` advertisement) is Task 4.

---

### Task 1: `codec.py` — Wyoming JSONL framing (HA-free)

**Files:**
- Create: `custom_components/hearth/codec.py`
- Test: `tests/integration/test_codec.py`

**Interfaces:**
- Consumes: nothing (stdlib only).
- Produces (relied on by Tasks 2 & 3):
  - `class ProtocolError(Exception)` — raised on malformed header, out-of-range length, or mid-frame EOF.
  - `@dataclass class WyomingEvent` with fields `type: str`, `data: dict[str, Any] = {}` (default via `field(default_factory=dict)`), `payload: bytes = b""`. Default dataclass `__eq__` (compares all three fields).
  - `async def read_event(reader: asyncio.StreamReader) -> WyomingEvent | None` — returns `None` on clean EOF before a header; raises `ProtocolError` on mid-frame EOF or malformed framing. Merges an inline header `"data"` object with the data block (block keys win). Enforces limits.
  - `async def write_event(writer: asyncio.StreamWriter, event: WyomingEvent) -> None` — emits `version` `"1.7.1"`; omits `data_length` when `data` empty and `payload_length` when `payload` empty; `await writer.drain()` at the end.
  - Constants `WYOMING_VERSION = "1.7.1"`, `MAX_HEADER_BYTES = 1 << 20`, `MAX_DATA_LENGTH = 1 << 20`, `MAX_PAYLOAD_LENGTH = 10 << 20`.

- [ ] **Step 1: Create the test loader `conftest.py`**

Create `tests/integration/conftest.py` with exactly this content. It imports the HA-free `codec.py` (and later `client.py`) by file path under a synthetic package `hearth_proto`, so the tests never execute the HA-importing `custom_components/hearth/__init__.py`. It tolerates `client.py` not existing yet (it is created in Task 2):

```python
from __future__ import annotations

import importlib.util
import sys
import types
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2] / "custom_components" / "hearth"
_PKG = "hearth_proto"


def _ensure_loaded() -> None:
    if _PKG in sys.modules:
        return
    pkg = types.ModuleType(_PKG)
    pkg.__path__ = [str(_ROOT)]  # make it a package so `from .codec import` resolves
    sys.modules[_PKG] = pkg
    for name in ("codec", "client"):  # codec first: client does `from .codec import`
        path = _ROOT / f"{name}.py"
        if not path.exists():
            continue
        spec = importlib.util.spec_from_file_location(f"{_PKG}.{name}", path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[f"{_PKG}.{name}"] = module
        spec.loader.exec_module(module)


_ensure_loaded()
```

- [ ] **Step 2: Write the failing codec tests**

Create `tests/integration/test_codec.py` with the complete content below. No pytest-asyncio is available, so every test drives a single coroutine via `asyncio.run`. `FakeWriter` duck-types a `StreamWriter` (`write` + async `drain`); `make_reader` builds a real `StreamReader` primed with bytes and EOF. **Note:** `asyncio.StreamReader()` must be constructed inside a running loop (Python 3.13+/3.14 removed the implicit main-thread loop), so every test that calls `make_reader` does so INSIDE the coroutine passed to `asyncio.run` — never as an argument evaluated before `asyncio.run`.

```python
from __future__ import annotations

import asyncio
import json

import pytest

from hearth_proto.codec import (
    MAX_DATA_LENGTH,
    MAX_PAYLOAD_LENGTH,
    WYOMING_VERSION,
    ProtocolError,
    WyomingEvent,
    read_event,
    write_event,
)


class FakeWriter:
    """Minimal StreamWriter stand-in that just accumulates bytes."""

    def __init__(self) -> None:
        self.buf = bytearray()

    def write(self, data: bytes) -> None:
        self.buf.extend(data)

    async def drain(self) -> None:
        return None


def make_reader(data: bytes) -> asyncio.StreamReader:
    reader = asyncio.StreamReader(limit=MAX_DATA_LENGTH + MAX_PAYLOAD_LENGTH + 4096)
    reader.feed_data(data)
    reader.feed_eof()
    return reader


async def _roundtrip(event: WyomingEvent) -> WyomingEvent | None:
    writer = FakeWriter()
    await write_event(writer, event)
    return await read_event(make_reader(bytes(writer.buf)))


def test_roundtrip_no_data_no_payload():
    ev = WyomingEvent("describe")
    assert asyncio.run(_roundtrip(ev)) == ev


def test_roundtrip_with_data():
    ev = WyomingEvent("ping", {"text": "hello"})
    assert asyncio.run(_roundtrip(ev)) == ev


def test_roundtrip_with_payload():
    ev = WyomingEvent("audio-chunk", {}, b"\x01\x02\x03\x04")
    assert asyncio.run(_roundtrip(ev)) == ev


def test_roundtrip_with_data_and_payload():
    ev = WyomingEvent("audio-start", {"rate": 22050, "width": 2, "channels": 1}, b"\xaa\xbb")
    assert asyncio.run(_roundtrip(ev)) == ev


def test_write_emits_version_and_omits_lengths_when_empty():
    async def scenario() -> dict:
        writer = FakeWriter()
        await write_event(writer, WyomingEvent("describe"))
        line = bytes(writer.buf).split(b"\n", 1)[0]
        return json.loads(line)

    header = asyncio.run(scenario())
    assert header["type"] == "describe"
    assert header["version"] == WYOMING_VERSION
    assert "data_length" not in header
    assert "payload_length" not in header


def test_write_sets_lengths_to_byte_counts():
    async def scenario() -> dict:
        writer = FakeWriter()
        await write_event(writer, WyomingEvent("audio-start", {"rate": 22050}, b"1234567890"))
        raw = bytes(writer.buf)
        line, rest = raw.split(b"\n", 1)
        header = json.loads(line)
        # the data block bytes follow the newline, then the payload
        data_bytes = rest[: header["data_length"]]
        return {"header": header, "data_len_matches": len(data_bytes) == header["data_length"]}

    out = asyncio.run(scenario())
    assert out["header"]["payload_length"] == 10
    assert out["data_len_matches"]


def test_inline_header_data_merges_with_block_block_wins():
    # Header carries an inline "data" object; a separate data block overrides overlapping keys.
    async def scenario() -> WyomingEvent | None:
        block = json.dumps({"b": 3}).encode("utf-8")
        header = json.dumps(
            {"type": "custom-event", "data": {"a": 1, "b": 2}, "data_length": len(block)}
        ).encode("utf-8")
        return await read_event(make_reader(header + b"\n" + block))

    ev = asyncio.run(scenario())
    assert ev is not None
    assert ev.type == "custom-event"
    assert ev.data == {"a": 1, "b": 3}


def test_clean_eof_before_header_returns_none():
    async def scenario():
        return await read_event(make_reader(b""))

    assert asyncio.run(scenario()) is None


def test_eof_inside_header_raises():
    async def scenario():
        await read_event(make_reader(b'{"type":"x"'))  # no newline, then EOF

    with pytest.raises(ProtocolError):
        asyncio.run(scenario())


def test_mid_frame_eof_in_body_raises():
    async def scenario() -> None:
        header = json.dumps({"type": "audio-chunk", "payload_length": 10}).encode("utf-8")
        await read_event(make_reader(header + b"\n" + b"abc"))  # only 3 of 10 bytes

    with pytest.raises(ProtocolError):
        asyncio.run(scenario())


def test_malformed_header_raises():
    async def scenario():
        await read_event(make_reader(b"{ not json\n"))

    with pytest.raises(ProtocolError):
        asyncio.run(scenario())


def test_missing_type_raises():
    async def scenario():
        await read_event(make_reader(b'{"version":"1.7.1"}\n'))

    with pytest.raises(ProtocolError):
        asyncio.run(scenario())


def test_data_length_over_limit_raises():
    async def scenario() -> None:
        header = json.dumps({"type": "x", "data_length": MAX_DATA_LENGTH + 1}).encode("utf-8")
        await read_event(make_reader(header + b"\n"))

    with pytest.raises(ProtocolError):
        asyncio.run(scenario())


def test_payload_length_over_limit_raises():
    async def scenario() -> None:
        header = json.dumps({"type": "x", "payload_length": MAX_PAYLOAD_LENGTH + 1}).encode("utf-8")
        await read_event(make_reader(header + b"\n"))

    with pytest.raises(ProtocolError):
        asyncio.run(scenario())


def test_negative_length_raises():
    async def scenario() -> None:
        header = json.dumps({"type": "x", "data_length": -1}).encode("utf-8")
        await read_event(make_reader(header + b"\n"))

    with pytest.raises(ProtocolError):
        asyncio.run(scenario())
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `python3 -m pytest tests/integration/test_codec.py -q`
Expected: collection/import error — `ModuleNotFoundError: No module named 'hearth_proto.codec'` (the module does not exist yet, so `conftest.py` skips it and the import fails). This is the intended RED.

- [ ] **Step 4: Implement `codec.py`**

Create `custom_components/hearth/codec.py` with exactly this content:

```python
"""Wyoming JSONL framing, byte-compatible with the app's WyomingCodec.kt (HA-free)."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, field
from typing import Any

WYOMING_VERSION = "1.7.1"
MAX_HEADER_BYTES = 1 << 20  # 1 MiB
MAX_DATA_LENGTH = 1 << 20  # 1 MiB
MAX_PAYLOAD_LENGTH = 10 << 20  # 10 MiB


class ProtocolError(Exception):
    """Framing is unrecoverable: malformed header, bad length, or mid-frame EOF."""


@dataclass
class WyomingEvent:
    """One Wyoming event: header line + optional JSON data block + optional binary payload."""

    type: str
    data: dict[str, Any] = field(default_factory=dict)
    payload: bytes = b""


async def write_event(writer: asyncio.StreamWriter, event: WyomingEvent) -> None:
    """Serialise one event. Mirrors WyomingCodec.write: version 1.7.1, lengths omitted when empty."""
    data_bytes: bytes | None = None
    if event.data:
        data_bytes = json.dumps(event.data, separators=(",", ":")).encode("utf-8")

    header: dict[str, Any] = {"type": event.type, "version": WYOMING_VERSION}
    if data_bytes is not None:
        header["data_length"] = len(data_bytes)
    if event.payload:
        header["payload_length"] = len(event.payload)

    writer.write(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    writer.write(b"\n")
    if data_bytes is not None:
        writer.write(data_bytes)
    if event.payload:
        writer.write(event.payload)
    await writer.drain()


def _coerce_length(value: Any, name: str, maximum: int) -> int:
    if value is None:
        return 0
    if isinstance(value, bool) or not isinstance(value, int):
        raise ProtocolError(f"wyoming {name} is not an integer: {value!r}")
    if value < 0 or value > maximum:
        raise ProtocolError(f"wyoming {name} out of range: {value}")
    return value


async def _read_exactly(reader: asyncio.StreamReader, n: int) -> bytes:
    try:
        return await reader.readexactly(n)
    except asyncio.IncompleteReadError as err:
        raise ProtocolError("EOF inside wyoming event body") from err


async def read_event(reader: asyncio.StreamReader) -> WyomingEvent | None:
    """Read one event. None on clean EOF before a header; ProtocolError on bad framing."""
    try:
        line = await reader.readuntil(b"\n")
    except asyncio.IncompleteReadError as err:
        if not err.partial:
            return None  # clean end-of-stream
        raise ProtocolError("EOF inside wyoming header") from err
    except asyncio.LimitOverrunError as err:
        raise ProtocolError("wyoming header too long") from err

    try:
        header = json.loads(line[:-1])  # strip trailing newline
    except ValueError as err:
        raise ProtocolError("malformed wyoming header") from err
    if not isinstance(header, dict) or "type" not in header:
        raise ProtocolError("wyoming header missing type")

    event_type = str(header["type"])
    inline = header.get("data")
    data: dict[str, Any] = dict(inline) if isinstance(inline, dict) else {}
    data_length = _coerce_length(header.get("data_length"), "data_length", MAX_DATA_LENGTH)
    payload_length = _coerce_length(header.get("payload_length"), "payload_length", MAX_PAYLOAD_LENGTH)

    if data_length:
        block_bytes = await _read_exactly(reader, data_length)
        try:
            block = json.loads(block_bytes)
        except ValueError as err:
            raise ProtocolError("malformed wyoming data block") from err
        if not isinstance(block, dict):
            raise ProtocolError("wyoming data block is not an object")
        data = {**data, **block}  # block keys win

    payload = await _read_exactly(reader, payload_length) if payload_length else b""
    return WyomingEvent(event_type, data, payload)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python3 -m pytest tests/integration/test_codec.py -q`
Expected: all tests PASS (15 passed). If any fail, fix `codec.py` — do NOT weaken a test.

- [ ] **Step 6: Commit**

```bash
git add custom_components/hearth/codec.py tests/integration/conftest.py tests/integration/test_codec.py
git commit -m "feat(hearth): Wyoming JSONL framing codec (HA-free)

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

### Task 2: `client.py` — HearthClient (HA-free)

**Files:**
- Create: `custom_components/hearth/client.py`
- Test: `tests/integration/test_client.py`

**Interfaces:**
- Consumes (from Task 1): `codec.WyomingEvent`, `codec.read_event`, `codec.write_event`, `codec.ProtocolError`, `codec.MAX_HEADER_BYTES`.
- Produces (relied on by Task 3 entities and `__init__.py`):
  - `class HearthClient(host: str, port: int, *, ping_interval: float = 4.0, backoff_base: float = 1.0, backoff_max: float = 60.0)`
  - `async def async_start(self) -> None` — starts the supervisor (connect → handshake → event loop, with reconnect). Idempotent.
  - `async def async_stop(self) -> None` — stops the supervisor and closes the socket.
  - `async def async_wait_connected(self, timeout: float) -> None` — resolves when connected; raises `asyncio.TimeoutError` if not connected within `timeout`.
  - `async def async_send_settings(self, settings: dict) -> None` — sends FLAT `custom-event {event_type:"settings", settings:{...}}`.
  - `async def async_send_action(self, name: str, payload: Any | None = None) -> None` — sends FLAT `custom-event {event_type:"action", action:name, payload:...}` (payload key omitted when `None`).
  - `async def async_announce(self, pcm_stream: AsyncIterator[bytes], rate: int, width: int, channels: int) -> None` — sends `audio-start` / `audio-chunk`* / `audio-stop`; resolves when `played` arrives OR the connection drops.
  - `def add_listener(self, cb: Callable[[str, dict], None]) -> Callable[[], None]` — `cb(kind, data)` with `kind` in `"settings"` | `"status"` | `"connection"`; returns an unsubscribe callable. `"connection"` payload is `{"connected": bool}`; `"settings"` payload is the unwrapped kiosk settings dict; `"status"` payload is the unwrapped status dict.
  - Properties `device_name: str | None`, `app_version: str | None`, `connected: bool`.

**Design decisions (spec left the mechanism open):**
- The client uses ONE TCP connection: `describe`→await `info`, `capabilities`→await `capabilities`, then `run-satellite`, then the read loop (so the app marks THIS connection as the active newest-wins session and immediately streams settings-feedback + status, which the read loop dispatches).
- `add_listener` also emits a `"connection"` kind so entities can update `available` — a documented superset of the spec's "settings/status dicts".
- `ping_interval`/`backoff_base`/`backoff_max` are constructor seams (defaults match the spec: 4 s ping, 1 s→60 s backoff) so tests run fast.
- Keepalive: each tick, if the previous ping is still unanswered, the connection is considered dead and the writer is closed (which ends the read loop and triggers reconnect).

- [ ] **Step 1: Write the failing client tests**

Create `tests/integration/test_client.py` with the complete content below. `FakeAppServer` mirrors `VacaServer.kt`'s contract exactly, including the immediate post-`run-satellite` feedback+status and the device→HA nested-`data` quirk. No pytest-asyncio: each test drives one coroutine with `asyncio.run`.

```python
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 -m pytest tests/integration/test_client.py -q`
Expected: `ModuleNotFoundError: No module named 'hearth_proto.client'` (the module does not exist yet, so `conftest.py` skips it). Intended RED.

- [ ] **Step 3: Implement `client.py`**

Create `custom_components/hearth/client.py` with exactly this content:

```python
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
```

- [ ] **Step 4: Run the full integration suite to verify it passes**

Run: `python3 -m pytest tests/integration -q`
Expected: all tests PASS (Task 1 codec tests + the 7 client tests). If a client test hangs, it means `async_wait_connected` never resolves — check the handshake order and that `_set_connected(True)` runs before `_read_loop`. Do NOT add sleeps to the implementation to paper over a race.

- [ ] **Step 5: Commit**

```bash
git add custom_components/hearth/client.py tests/integration/test_client.py
git commit -m "feat(hearth): HearthClient session/reconnect/dispatch (HA-free)

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

### Task 3: HA integration layer (entities, config flow, service)

**Files:**
- Create: `hacs.json` (repo root)
- Create: `custom_components/hearth/manifest.json`
- Create: `custom_components/hearth/const.py`
- Create: `custom_components/hearth/__init__.py`
- Create: `custom_components/hearth/config_flow.py`
- Create: `custom_components/hearth/media_player.py`
- Create: `custom_components/hearth/switch.py`
- Create: `custom_components/hearth/number.py`
- Create: `custom_components/hearth/button.py`
- Create: `custom_components/hearth/strings.json`
- Create: `custom_components/hearth/translations/en.json`
- Create: `custom_components/hearth/services.yaml`

**Interfaces:**
- Consumes (from Task 2): the full `HearthClient` public surface — `HearthClient(host, port)`, `async_start`, `async_stop`, `async_wait_connected(timeout)`, `async_send_settings(dict)`, `async_send_action(name, payload)`, `async_announce(pcm_stream, rate, width, channels)`, `add_listener(cb) -> unsub` with `cb(kind, data)` (`kind` in `"settings"`/`"status"`/`"connection"`), and properties `device_name`, `app_version`, `connected`.
- Consumes (from Task 1): `codec.WyomingEvent`, `codec.read_event`, `codec.write_event`, `codec.MAX_HEADER_BYTES` (config-flow probe only).
- Produces: HA entities + `hearth.toast` service. No public Python surface for later tasks.

**Design decisions (spec left the mechanism open):**
- Client stored in `hass.data[DOMAIN][entry.entry_id]` (long-stable; chosen over `entry.runtime_data`).
- `async_setup_entry` blocks on `client.async_wait_connected(15)` and raises `ConfigEntryNotReady` if the device is offline, so `device_info` carries the real `device_name`/`app_version` and HA retries setup automatically.
- `hearth.toast` is a global service registered in `async_setup`; it resolves the call's target to entity ids via `homeassistant.helpers.service.async_extract_referenced_entity_ids`, maps each to its config entry via the entity registry, and calls `async_send_action("toast-message", {"message": ...})` once per matched client.
- Config-flow probe reuses the HA-free codec directly (a `describe`→`info` exchange) rather than spinning up a full `HearthClient`.

- [ ] **Step 1: Create `hacs.json` (repo root)**

Create `hacs.json` at the repository root:

```json
{
  "name": "Hearth",
  "homeassistant": "2024.6.0",
  "render_readme": true
}
```

- [ ] **Step 2: Create `manifest.json`**

Create `custom_components/hearth/manifest.json`. Keys are ordered as HA's manifest linter expects (domain & name first, the rest alphabetical):

```json
{
  "domain": "hearth",
  "name": "Hearth",
  "codeowners": ["@RAR"],
  "config_flow": true,
  "dependencies": ["ffmpeg"],
  "documentation": "https://github.com/RAR/vaca-ha-native",
  "integration_type": "device",
  "iot_class": "local_push",
  "issue_tracker": "https://github.com/RAR/vaca-ha-native/issues",
  "requirements": [],
  "version": "0.1.0",
  "zeroconf": ["_hearth._tcp.local."]
}
```

- [ ] **Step 3: Create `const.py`**

Create `custom_components/hearth/const.py`:

```python
"""Constants for the Hearth integration."""

from __future__ import annotations

DOMAIN = "hearth"
DEFAULT_PORT = 10700
MANUFACTURER = "Hearth"

PLATFORMS = ["media_player", "switch", "number", "button"]

# Kiosk settings keys (bool/int device state, echoed in settings feedback).
SETTING_SCREEN_ON = "screen_on"
SETTING_SCREEN_BRIGHTNESS = "screen_brightness"
SETTING_SCREEN_AUTO_BRIGHTNESS = "screen_auto_brightness"
SETTING_SCREEN_ALWAYS_ON = "screen_always_on"
SETTING_SCREEN_SAVER = "screen_saver"
SETTING_DARK_MODE = "dark_mode"
SETTING_SCREEN_TIMEOUT = "screen_timeout"
SETTING_MUSIC_VOLUME = "music_volume"
SETTING_DUCKING_VOLUME = "ducking_volume"

# Action names.
ACTION_REFRESH = "refresh"
ACTION_PLAY = "play"
ACTION_PAUSE = "pause"
ACTION_STOP = "stop"
ACTION_PLAY_MEDIA = "play-media"
ACTION_SET_VOLUME = "set-volume"
ACTION_TOAST = "toast-message"

# Scales / announce tuning.
MAX_MUSIC_VOLUME = 10  # the music_volume SETTING is 0-10; action volumes are percent 0-100
ANNOUNCE_RATE = 22050
ANNOUNCE_WIDTH = 2
ANNOUNCE_CHANNELS = 1
ANNOUNCE_CHUNK = 4096

SERVICE_TOAST = "toast"
ATTR_MESSAGE = "message"
```

- [ ] **Step 4: Create `__init__.py`**

Create `custom_components/hearth/__init__.py`:

```python
"""The Hearth integration."""

from __future__ import annotations

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import ConfigEntryNotReady
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.service import async_extract_referenced_entity_ids
from homeassistant.helpers.typing import ConfigType

from .client import HearthClient
from .const import ACTION_TOAST, ATTR_MESSAGE, DOMAIN, PLATFORMS, SERVICE_TOAST

SERVICE_TOAST_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_MESSAGE): cv.string,
        vol.Optional("entity_id"): cv.comp_entity_ids,
        vol.Optional("device_id"): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("area_id"): vol.All(cv.ensure_list, [cv.string]),
    }
)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Register the (global) hearth.toast service once."""

    async def _handle_toast(call: ServiceCall) -> None:
        message = call.data[ATTR_MESSAGE]
        ent_reg = er.async_get(hass)
        selected = async_extract_referenced_entity_ids(hass, call)
        entity_ids = selected.referenced | selected.indirectly_referenced
        entry_ids: set[str] = set()
        for entity_id in entity_ids:
            entry = ent_reg.async_get(entity_id)
            if entry is not None and entry.config_entry_id:
                entry_ids.add(entry.config_entry_id)
        clients = hass.data.get(DOMAIN, {})
        for entry_id in entry_ids:
            client = clients.get(entry_id)
            if client is not None:
                await client.async_send_action(ACTION_TOAST, {"message": message})

    hass.services.async_register(DOMAIN, SERVICE_TOAST, _handle_toast, schema=SERVICE_TOAST_SCHEMA)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Hearth from a config entry."""
    client = HearthClient(entry.data[CONF_HOST], entry.data[CONF_PORT])
    await client.async_start()
    try:
        await client.async_wait_connected(15)
    except TimeoutError as err:
        await client.async_stop()
        raise ConfigEntryNotReady(f"Could not connect to {entry.title}") from err

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = client
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        client = hass.data[DOMAIN].pop(entry.entry_id)
        await client.async_stop()
    return unloaded
```

Note: `asyncio.TimeoutError` is aliased to the builtin `TimeoutError` on Python 3.11+; catching `TimeoutError` covers `async_wait_connected`'s `asyncio.wait_for` timeout on HAOS (3.13).

- [ ] **Step 5: Create `config_flow.py`**

Create `custom_components/hearth/config_flow.py`:

```python
"""Config flow for Hearth: zeroconf discovery + manual host/port, with a describe probe."""

from __future__ import annotations

import asyncio
from contextlib import suppress
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .codec import MAX_HEADER_BYTES, WyomingEvent, read_event, write_event
from .const import DEFAULT_PORT, DOMAIN


class CannotConnect(Exception):
    """Probe failed."""


async def _async_probe_name(host: str, port: int) -> str:
    """Open a short-lived connection, send describe, return the satellite name from info."""
    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(host, port, limit=MAX_HEADER_BYTES), timeout=10
        )
    except (OSError, asyncio.TimeoutError) as err:
        raise CannotConnect from err
    try:
        await write_event(writer, WyomingEvent("describe"))
        while True:
            event = await asyncio.wait_for(read_event(reader), timeout=10)
            if event is None:
                raise CannotConnect
            if event.type == "info":
                satellite = event.data.get("satellite") or {}
                return satellite.get("name") or f"Hearth ({host})"
    except (OSError, asyncio.TimeoutError) as err:
        raise CannotConnect from err
    finally:
        writer.close()
        with suppress(Exception):
            await writer.wait_closed()


class HearthConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle a config flow for Hearth."""

    VERSION = 1

    def __init__(self) -> None:
        self._host: str | None = None
        self._port: int | None = None
        self._name: str | None = None

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Manual host/port entry."""
        errors: dict[str, str] = {}
        if user_input is not None:
            host = user_input[CONF_HOST]
            port = user_input[CONF_PORT]
            await self.async_set_unique_id(f"{host}:{port}")
            self._abort_if_unique_id_configured()
            try:
                name = await _async_probe_name(host, port)
            except CannotConnect:
                errors["base"] = "cannot_connect"
            else:
                return self.async_create_entry(
                    title=name, data={CONF_HOST: host, CONF_PORT: port}
                )
        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_HOST): str,
                    vol.Required(CONF_PORT, default=DEFAULT_PORT): int,
                }
            ),
            errors=errors,
        )

    async def async_step_zeroconf(
        self, discovery_info: ZeroconfServiceInfo
    ) -> ConfigFlowResult:
        """Handle a device discovered over _hearth._tcp."""
        host = discovery_info.host
        port = discovery_info.port or DEFAULT_PORT
        await self.async_set_unique_id(f"{host}:{port}")
        self._abort_if_unique_id_configured()

        name = discovery_info.name.split("._hearth._tcp")[0] or f"Hearth ({host})"
        self._host = host
        self._port = port
        self._name = name
        self.context["title_placeholders"] = {"name": name}
        return await self.async_step_zeroconf_confirm()

    async def async_step_zeroconf_confirm(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Confirm adding a discovered device."""
        assert self._host is not None and self._port is not None and self._name is not None
        if user_input is not None:
            return self.async_create_entry(
                title=self._name, data={CONF_HOST: self._host, CONF_PORT: self._port}
            )
        return self.async_show_form(
            step_id="zeroconf_confirm",
            description_placeholders={"name": self._name},
        )
```

- [ ] **Step 6: Create `media_player.py`**

Create `custom_components/hearth/media_player.py`:

```python
"""Media player entity for a Hearth device."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from typing import Any

from homeassistant.components import media_source
from homeassistant.components.media_player import (
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    async_process_play_media_url,
)
from homeassistant.components.media_player.const import ATTR_MEDIA_ANNOUNCE
from homeassistant.components.ffmpeg import get_ffmpeg_manager
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .client import HearthClient
from .const import (
    ACTION_PAUSE,
    ACTION_PLAY,
    ACTION_PLAY_MEDIA,
    ACTION_SET_VOLUME,
    ACTION_STOP,
    ANNOUNCE_CHANNELS,
    ANNOUNCE_CHUNK,
    ANNOUNCE_RATE,
    ANNOUNCE_WIDTH,
    DOMAIN,
    MANUFACTURER,
    MAX_MUSIC_VOLUME,
    SETTING_MUSIC_VOLUME,
)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    client: HearthClient = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([HearthMediaPlayer(client, entry)])


class HearthMediaPlayer(MediaPlayerEntity):
    """Represents the Hearth device's audio playback."""

    _attr_has_entity_name = True
    _attr_name = None  # main feature -> takes the device name
    _attr_supported_features = (
        MediaPlayerEntityFeature.PLAY
        | MediaPlayerEntityFeature.PAUSE
        | MediaPlayerEntityFeature.STOP
        | MediaPlayerEntityFeature.PLAY_MEDIA
        | MediaPlayerEntityFeature.VOLUME_SET
        | MediaPlayerEntityFeature.MEDIA_ANNOUNCE
    )

    def __init__(self, client: HearthClient, entry: ConfigEntry) -> None:
        self._client = client
        self._playing = False
        self._volume: float | None = None
        self._unsub = None
        self._attr_unique_id = f"{entry.unique_id}_media_player"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            manufacturer=MANUFACTURER,
            name=client.device_name or entry.title,
            sw_version=client.app_version,
        )

    @property
    def available(self) -> bool:
        return self._client.connected

    @property
    def state(self) -> MediaPlayerState:
        return MediaPlayerState.PLAYING if self._playing else MediaPlayerState.IDLE

    @property
    def volume_level(self) -> float | None:
        return self._volume

    async def async_added_to_hass(self) -> None:
        self._unsub = self._client.add_listener(self._on_event)

    async def async_will_remove_from_hass(self) -> None:
        if self._unsub is not None:
            self._unsub()

    @callback
    def _on_event(self, kind: str, data: dict) -> None:
        if kind == "status":
            media = data.get("media_player")
            if isinstance(media, dict) and "playing" in media:
                self._playing = bool(media["playing"])
                self.async_write_ha_state()
        elif kind == "settings" and SETTING_MUSIC_VOLUME in data:
            self._volume = float(data[SETTING_MUSIC_VOLUME]) / MAX_MUSIC_VOLUME
            self.async_write_ha_state()
        elif kind == "connection":
            self.async_write_ha_state()

    async def async_media_play(self) -> None:
        await self._client.async_send_action(ACTION_PLAY)

    async def async_media_pause(self) -> None:
        await self._client.async_send_action(ACTION_PAUSE)

    async def async_media_stop(self) -> None:
        await self._client.async_send_action(ACTION_STOP)

    async def async_set_volume_level(self, volume: float) -> None:
        # action volume is percent 0-100; the music_volume SETTING is 0-10.
        await self._client.async_send_action(ACTION_SET_VOLUME, {"volume": round(volume * 100)})

    async def async_play_media(
        self, media_type: str, media_id: str, **kwargs: Any
    ) -> None:
        announce = bool(kwargs.get(ATTR_MEDIA_ANNOUNCE))
        if media_source.is_media_source_id(media_id):
            play_item = await media_source.async_resolve_media(
                self.hass, media_id, self.entity_id
            )
            media_id = play_item.url
        media_id = async_process_play_media_url(self.hass, media_id)

        if announce:
            await self._announce(media_id)
        else:
            await self._client.async_send_action(ACTION_PLAY_MEDIA, {"url": media_id})

    async def _announce(self, url: str) -> None:
        """Transcode url -> s16le 22050 Hz mono PCM via HA's ffmpeg, stream over announce."""
        binary = get_ffmpeg_manager(self.hass).binary
        proc = await asyncio.create_subprocess_exec(
            binary,
            "-i",
            url,
            "-f",
            "s16le",
            "-ac",
            str(ANNOUNCE_CHANNELS),
            "-ar",
            str(ANNOUNCE_RATE),
            "pipe:1",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )

        async def pcm_stream() -> AsyncIterator[bytes]:
            assert proc.stdout is not None
            while True:
                chunk = await proc.stdout.read(ANNOUNCE_CHUNK)
                if not chunk:
                    break
                yield chunk

        try:
            await self._client.async_announce(
                pcm_stream(), ANNOUNCE_RATE, ANNOUNCE_WIDTH, ANNOUNCE_CHANNELS
            )
        finally:
            if proc.returncode is None:
                proc.kill()
            await proc.wait()
```

- [ ] **Step 7: Create `switch.py`**

Create `custom_components/hearth/switch.py`:

```python
"""Switch entities for a Hearth device (5 kiosk toggles)."""

from __future__ import annotations

from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .client import HearthClient
from .const import (
    DOMAIN,
    MANUFACTURER,
    SETTING_DARK_MODE,
    SETTING_SCREEN_ALWAYS_ON,
    SETTING_SCREEN_AUTO_BRIGHTNESS,
    SETTING_SCREEN_ON,
    SETTING_SCREEN_SAVER,
)

# (settings key, translation_key)
SWITCHES = [
    (SETTING_SCREEN_ON, "screen"),
    (SETTING_SCREEN_AUTO_BRIGHTNESS, "auto_brightness"),
    (SETTING_SCREEN_ALWAYS_ON, "always_on"),
    (SETTING_SCREEN_SAVER, "screensaver"),
    (SETTING_DARK_MODE, "dark_mode"),
]


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    client: HearthClient = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(HearthSwitch(client, entry, key, tkey) for key, tkey in SWITCHES)


class HearthSwitch(SwitchEntity):
    """A single kiosk boolean setting."""

    _attr_has_entity_name = True

    def __init__(
        self, client: HearthClient, entry: ConfigEntry, key: str, translation_key: str
    ) -> None:
        self._client = client
        self._key = key
        self._is_on: bool | None = None
        self._unsub = None
        self._attr_translation_key = translation_key
        self._attr_unique_id = f"{entry.unique_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            manufacturer=MANUFACTURER,
            name=client.device_name or entry.title,
            sw_version=client.app_version,
        )

    @property
    def available(self) -> bool:
        return self._client.connected

    @property
    def is_on(self) -> bool | None:
        return self._is_on

    async def async_added_to_hass(self) -> None:
        self._unsub = self._client.add_listener(self._on_event)

    async def async_will_remove_from_hass(self) -> None:
        if self._unsub is not None:
            self._unsub()

    @callback
    def _on_event(self, kind: str, data: dict) -> None:
        if kind == "settings" and self._key in data:
            self._is_on = bool(data[self._key])
            self.async_write_ha_state()
        elif kind == "connection":
            self.async_write_ha_state()

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._client.async_send_settings({self._key: True})

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._client.async_send_settings({self._key: False})
```

- [ ] **Step 8: Create `number.py`**

Create `custom_components/hearth/number.py`:

```python
"""Number entities for a Hearth device (brightness, screen timeout, ducking volume)."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import UnitOfTime
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .client import HearthClient
from .const import (
    DOMAIN,
    MANUFACTURER,
    SETTING_DUCKING_VOLUME,
    SETTING_SCREEN_BRIGHTNESS,
    SETTING_SCREEN_TIMEOUT,
)

# (settings key, translation_key, min, max, step, unit)
NUMBERS = [
    (SETTING_SCREEN_BRIGHTNESS, "brightness", 0, 100, 1, None),
    (SETTING_SCREEN_TIMEOUT, "screen_timeout", 0, 3600, 1, UnitOfTime.SECONDS),
    (SETTING_DUCKING_VOLUME, "ducking_volume", 0, 10, 1, None),
]


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    client: HearthClient = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(
        HearthNumber(client, entry, key, tkey, lo, hi, step, unit)
        for key, tkey, lo, hi, step, unit in NUMBERS
    )


class HearthNumber(NumberEntity):
    """A single kiosk integer setting."""

    _attr_has_entity_name = True

    def __init__(
        self,
        client: HearthClient,
        entry: ConfigEntry,
        key: str,
        translation_key: str,
        native_min: float,
        native_max: float,
        step: float,
        unit: str | None,
    ) -> None:
        self._client = client
        self._key = key
        self._value: float | None = None
        self._unsub = None
        self._attr_translation_key = translation_key
        self._attr_unique_id = f"{entry.unique_id}_{key}"
        self._attr_native_min_value = native_min
        self._attr_native_max_value = native_max
        self._attr_native_step = step
        self._attr_native_unit_of_measurement = unit
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            manufacturer=MANUFACTURER,
            name=client.device_name or entry.title,
            sw_version=client.app_version,
        )

    @property
    def available(self) -> bool:
        return self._client.connected

    @property
    def native_value(self) -> float | None:
        return self._value

    async def async_added_to_hass(self) -> None:
        self._unsub = self._client.add_listener(self._on_event)

    async def async_will_remove_from_hass(self) -> None:
        if self._unsub is not None:
            self._unsub()

    @callback
    def _on_event(self, kind: str, data: dict) -> None:
        if kind == "settings" and self._key in data:
            self._value = float(data[self._key])
            self.async_write_ha_state()
        elif kind == "connection":
            self.async_write_ha_state()

    async def async_set_native_value(self, value: float) -> None:
        await self._client.async_send_settings({self._key: int(value)})
```

- [ ] **Step 9: Create `button.py`**

Create `custom_components/hearth/button.py`:

```python
"""Button entity for a Hearth device (refresh)."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .client import HearthClient
from .const import ACTION_REFRESH, DOMAIN, MANUFACTURER


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    client: HearthClient = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([HearthRefreshButton(client, entry)])


class HearthRefreshButton(ButtonEntity):
    """Reloads the dashboard WebView."""

    _attr_has_entity_name = True
    _attr_translation_key = "refresh"

    def __init__(self, client: HearthClient, entry: ConfigEntry) -> None:
        self._client = client
        self._unsub = None
        self._attr_unique_id = f"{entry.unique_id}_refresh"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            manufacturer=MANUFACTURER,
            name=client.device_name or entry.title,
            sw_version=client.app_version,
        )

    @property
    def available(self) -> bool:
        return self._client.connected

    async def async_added_to_hass(self) -> None:
        self._unsub = self._client.add_listener(self._on_event)

    async def async_will_remove_from_hass(self) -> None:
        if self._unsub is not None:
            self._unsub()

    @callback
    def _on_event(self, kind: str, data: dict) -> None:
        if kind == "connection":
            self.async_write_ha_state()

    async def async_press(self) -> None:
        await self._client.async_send_action(ACTION_REFRESH)
```

- [ ] **Step 10: Create `strings.json`**

Create `custom_components/hearth/strings.json`:

```json
{
  "config": {
    "step": {
      "user": {
        "title": "Add a Hearth device",
        "description": "Enter the device's host and port. The app serves one integration session at a time (newest wins): migrate from VACA per device by deleting its VACA entry first.",
        "data": {
          "host": "Host",
          "port": "Port"
        }
      },
      "zeroconf_confirm": {
        "title": "Add a Hearth device",
        "description": "Add {name} to Home Assistant? The app serves one integration session at a time (newest wins); if this device still has a VACA entry, delete it first."
      }
    },
    "error": {
      "cannot_connect": "Failed to connect"
    },
    "abort": {
      "already_configured": "Device is already configured"
    }
  },
  "entity": {
    "switch": {
      "screen": { "name": "Screen" },
      "auto_brightness": { "name": "Auto brightness" },
      "always_on": { "name": "Always on" },
      "screensaver": { "name": "Screen saver" },
      "dark_mode": { "name": "Dark mode" }
    },
    "number": {
      "brightness": { "name": "Brightness" },
      "screen_timeout": { "name": "Screen timeout" },
      "ducking_volume": { "name": "Ducking volume" }
    },
    "button": {
      "refresh": { "name": "Refresh" }
    }
  },
  "services": {
    "toast": {
      "name": "Show toast",
      "description": "Shows a short on-screen message on the Hearth device.",
      "fields": {
        "message": {
          "name": "Message",
          "description": "The text to show."
        }
      }
    }
  }
}
```

- [ ] **Step 11: Create `translations/en.json`**

Create `custom_components/hearth/translations/en.json` with the SAME content as `strings.json` (Step 10). Transcribe it identically (HA loads `translations/en.json` at runtime; `strings.json` is the developer source).

- [ ] **Step 12: Create `services.yaml`**

Create `custom_components/hearth/services.yaml`:

```yaml
toast:
  name: Show toast
  description: Shows a short on-screen message on the Hearth device.
  target:
    entity:
      integration: hearth
    device:
      integration: hearth
  fields:
    message:
      name: Message
      description: The text to show.
      required: true
      example: Dinner is ready
      selector:
        text:
```

- [ ] **Step 13: Run the compile + JSON validation gate**

The HA entity modules import `homeassistant.*`, which is not installed locally, so they are syntax-checked with `py_compile` (compiles without importing) rather than run. The JSON files are validated by loading them.

Run:
```bash
python3 -m py_compile custom_components/hearth/*.py
python3 -c "import json; [json.load(open(p)) for p in ['hacs.json','custom_components/hearth/manifest.json','custom_components/hearth/strings.json','custom_components/hearth/translations/en.json']]"
python3 -m pytest tests/integration -q
```
Expected: the first two commands print nothing and exit 0 (any `SyntaxError` or JSON error fails the task); the pytest run stays green (Tasks 1 & 2 unaffected).

- [ ] **Step 14: Commit**

```bash
git add hacs.json custom_components/hearth/manifest.json custom_components/hearth/const.py \
        custom_components/hearth/__init__.py custom_components/hearth/config_flow.py \
        custom_components/hearth/media_player.py custom_components/hearth/switch.py \
        custom_components/hearth/number.py custom_components/hearth/button.py \
        custom_components/hearth/strings.json custom_components/hearth/translations/en.json \
        custom_components/hearth/services.yaml
git commit -m "feat(hearth): HA integration layer (entities, config flow, toast service)

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

### Task 4: Kotlin — advertise `_hearth._tcp`

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/App.kt:267` (add `hearthNsd` field next to `nsd`), `:326` (register in `startVaca()`), `:171` (bounce in `applyDeviceName()`).

**Interfaces:**
- Consumes: existing `NsdAdvertiser(context, port, serviceType = "_vaca._tcp.", name: () -> String)` (a positional `serviceType` overload is already used for `_wyoming._tcp`), `VacaServer.DEFAULT_PORT`, `App.deviceName()`, `App.vacaRunning`.
- Produces: a third mDNS advertisement `_hearth._tcp` on `VacaServer.DEFAULT_PORT` carrying the same device name, matched by the new integration's zeroconf discovery (`_hearth._tcp.local.`). No new Kotlin tests (this is Android-side plumbing identical to the existing `_vaca`/`_wyoming` advertisers; `NsdAdvertiser` cannot be unit-run without Android).

**Note on TDD.** `NsdAdvertiser` touches `android.net.nsd` and cannot run under the plain-JVM unit tests, so there is no failing test to write here (the same reason the existing `_vaca` and `_wyoming` advertisers have none). The build gate compiles the change and the full existing suite stays green.

- [ ] **Step 1: Add the `hearthNsd` advertiser field**

In `app/src/main/java/com/rar/echodash/App.kt`, immediately after the existing `nsd` field (line 267):

```kotlin
    private val nsd = NsdAdvertiser(appContext, VacaServer.DEFAULT_PORT, name = { deviceName() })
    private val hearthNsd =
        NsdAdvertiser(appContext, VacaServer.DEFAULT_PORT, "_hearth._tcp.", name = { deviceName() })
```

- [ ] **Step 2: Register `hearthNsd` in `startVaca()`**

In the same file, in `startVaca()` (around line 324), register the new advertiser right after `nsd.register()`:

```kotlin
    fun startVaca() {
        vaca.start()
        nsd.register()
        hearthNsd.register()
        lightSensor.start()
        vacaRunning = true
    }
```

- [ ] **Step 3: Bounce `hearthNsd` on rename in `applyDeviceName()`**

In the same file, in `applyDeviceName()` (the `if (vacaRunning)` block around line 170-173), re-register the hearth advertiser beside the existing `_vaca` bounce:

```kotlin
    private fun applyDeviceName(name: String?) {
        settings.deviceName = name
        if (vacaRunning) {
            nsd.unregister(); nsd.register()   // re-announce _vaca._tcp mDNS with the new name
            hearthNsd.unregister(); hearthNsd.register()   // re-announce _hearth._tcp mDNS with the new name
            vaca.stop(); vaca.start()          // drop HA's VACA session so it re-reads info on reconnect
        }
        voiceRestartTick.value += 1            // reactive voice collect tears down + rebuilds (voiceNsd + satellite)
    }
```

- [ ] **Step 4: Run the Kotlin build gate**

Run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: exit 0. The existing unit suite is unchanged and passes; the debug APK assembles with the new advertiser. If it fails to compile, confirm the `NsdAdvertiser(context, port, serviceType, name = ...)` positional-`serviceType` call shape matches the existing `_wyoming` advertiser in the same file.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat: advertise _hearth._tcp for the Hearth HA integration

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Self-Review

**1. Spec coverage:**
- Repo layout (`hacs.json` root; `custom_components/hearth/` with manifest, `__init__`, const, codec, client, config_flow, media_player, switch, number, button, services.yaml; `tests/integration/`) — Tasks 1-3 File Structure + steps. ✓
- No pip requirements; framing hand-rolled in `codec.py` mirroring `WyomingCodec` (JSONL header + optional data block + optional binary payload; version 1.7.1; 1 MiB data / 10 MiB payload limits; inline-`data` merge with block-wins; clean-EOF None; mid-frame EOF error) — Task 1. ✓
- Integration→app: `describe`→`info` (name/version), `capabilities`→`capabilities`, `ping`→`pong`, `run-satellite` marks the active session, FLAT `custom-event` settings/action, `audio-start`/`audio-chunk`/`audio-stop` announce with `played` completion — Task 2 handshake + `async_send_settings`/`async_send_action`/`async_announce`, verified by `FakeAppServer`. ✓
- App→integration: `custom-event` settings feedback NESTED under `data.settings`, `custom-event` status under `data`, `played` — Task 2 `_dispatch` unwrap + tests. ✓
- Settings keys (`screen_on`, `screen_brightness`, `screen_auto_brightness`, `screen_always_on`, `screen_saver`, `dark_mode`, `screen_timeout`, `music_volume`, `ducking_volume`) — `const.py` + switch/number/media_player mapping (Task 3). ✓
- Actions incl. scale quirk (action `volume` percent 0-100 via `set-volume`/`play-media`; `music_volume` SETTING 0-10 via `volume_level = music_volume/10`) — media_player `async_set_volume_level` (round(volume*100)) and `volume_level` (Task 3). ✓
- HearthClient: connect→describe→capabilities→run-satellite→loop; ping every 4 s; missing pong/read error → disconnect; exponential backoff 1 s→60 s reset on success; entities `available: False` while disconnected; public surface `async_start/async_stop/async_send_settings/async_send_action/async_announce/add_listener/device_name/app_version/connected`; announce resolves on `played` or drop; feedback is source of truth (entities update from feedback, not optimistically) — Task 2. ✓
- Entities: media_player (PLAYING/IDLE, volume, PLAY/PAUSE/STOP/PLAY_MEDIA/VOLUME_SET/ANNOUNCE, `media_source` resolution via `async_process_play_media_url`, ffmpeg 22050/16/mono transcode streamed over announce); 5 switches; 3 numbers; refresh button; `hearth.toast` service; device info manufacturer "Hearth", name from info, sw_version=app version — Task 3. ✓
- Config flow: zeroconf `_hearth._tcp.local.` + manual host/port (default 10700) with `describe` probe → title; `cannot_connect` on failure; unique_id `host:port` aborts duplicates; coexistence note in copy — Task 3 config_flow + strings. ✓
- App change: third `NsdAdvertiser` for `_hearth._tcp` on `DEFAULT_PORT`, registered in `startVaca()`, bounced in `applyDeviceName()` — Task 4. ✓
- Out of scope (view select, notify, occupancy, removing `_vaca`, options flow, reauth, HACS default store, media browsing) — none added. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows complete final content; `translations/en.json` (Step 11) explicitly transcribes the Step 10 JSON verbatim rather than referencing it. ✓

**3. Type/name consistency:**
- Codec surface used by client & config_flow matches Task 1's Produces exactly: `WyomingEvent(type, data={}, payload=b"")`, `async read_event(reader) -> WyomingEvent | None`, `async write_event(writer, event)`, `ProtocolError`, `MAX_HEADER_BYTES`. Client imports `MAX_HEADER_BYTES, ProtocolError, WyomingEvent, read_event, write_event`; config_flow imports `MAX_HEADER_BYTES, WyomingEvent, read_event, write_event`. ✓
- Client surface consumed by Task 3 matches Task 2's Produces exactly: `HearthClient(host, port)`, `async_start`, `async_stop`, `async_wait_connected(timeout)`, `async_send_settings(dict)`, `async_send_action(name, payload=None)`, `async_announce(stream, rate, width, channels)`, `add_listener(cb) -> unsub`, properties `device_name`/`app_version`/`connected`. `__init__` uses `async_start`/`async_wait_connected`/`async_stop`/`async_send_action`; entities use `add_listener`/`connected`/`device_name`/`app_version`/`async_send_settings`/`async_send_action`/`async_announce`. ✓
- Listener contract `cb(kind, data)` with `kind` in `settings`/`status`/`connection` — emitted in client `_dispatch`/`_set_connected`, consumed identically in every entity `_on_event`. ✓
- `const.py` names (`DOMAIN`, `MANUFACTURER`, `PLATFORMS`, `SETTING_*`, `ACTION_*`, `MAX_MUSIC_VOLUME`, `ANNOUNCE_*`, `SERVICE_TOAST`, `ATTR_MESSAGE`) — defined in Step 3, imported consistently across `__init__`/media_player/switch/number/button. ✓
- Kotlin: `NsdAdvertiser(appContext, VacaServer.DEFAULT_PORT, "_hearth._tcp.", name = { deviceName() })` matches the existing positional-`serviceType` call shape used for `_wyoming`; `hearthNsd.register()`/`unregister()` mirror the `nsd` calls. ✓

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-14-hearth-ha-integration.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute the tasks in this session using executing-plans, with checkpoints before each commit.

**Which approach?**
