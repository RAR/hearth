"""Notify entity for a Hearth device (stock notify platform)."""

from __future__ import annotations

from homeassistant.components.notify import NotifyEntity, NotifyEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .client import HearthClient
from .const import ACTION_NOTIFY, DOMAIN, MANUFACTURER


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    client: HearthClient = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([HearthNotify(client, entry)])


class HearthNotify(NotifyEntity):
    """Posts a notification row to the device's notification area (title + message)."""

    _attr_has_entity_name = True
    _attr_translation_key = "notify"
    # Title support shipped with the notify entity platform's title parameter in HA 2024.6,
    # our floor (hacs.json) — without this flag core drops/rejects titles for this entity.
    _attr_supported_features = NotifyEntityFeature.TITLE

    def __init__(self, client: HearthClient, entry: ConfigEntry) -> None:
        self._client = client
        self._unsub = None
        self._attr_unique_id = f"{entry.unique_id}_notify"
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

    async def async_send_message(self, message: str, title: str | None = None) -> None:
        await self._client.async_send_action(
            ACTION_NOTIFY, {"title": title or "Notification", "message": message}
        )
