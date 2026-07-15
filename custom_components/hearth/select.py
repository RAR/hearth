"""Select entity for a Hearth device (current dashboard view)."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .client import HearthClient
from .const import ACTION_SET_VIEW, DOMAIN, MANUFACTURER, VIEW_OPTIONS


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    client: HearthClient = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([HearthViewSelect(client, entry)])


class HearthViewSelect(SelectEntity):
    """The device's current dashboard view.

    Options are static (all eight views). The app ignores a `set-view` for a panel that is
    disabled in config, and reports its real view on the next status event, so the select
    snaps back on its own.
    """

    _attr_has_entity_name = True
    _attr_translation_key = "view"
    _attr_options = VIEW_OPTIONS

    def __init__(self, client: HearthClient, entry: ConfigEntry) -> None:
        self._client = client
        self._unsub = None
        self._attr_current_option: str | None = None
        self._attr_unique_id = f"{entry.unique_id}_view"
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
        if kind == "status":
            sensors = data.get("sensors")
            if isinstance(sensors, dict) and "current_view" in sensors:
                view = sensors["current_view"]
                self._attr_current_option = view if view in self._attr_options else None
                self.async_write_ha_state()
        elif kind == "connection":
            self.async_write_ha_state()

    async def async_select_option(self, option: str) -> None:
        await self._client.async_send_action(ACTION_SET_VIEW, {"view": option})
