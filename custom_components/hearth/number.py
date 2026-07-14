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
