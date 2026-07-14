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
