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
