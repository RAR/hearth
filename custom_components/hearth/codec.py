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
