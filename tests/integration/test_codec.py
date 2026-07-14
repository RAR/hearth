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
