"""The Hearth integration."""

from __future__ import annotations

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import ConfigEntryNotReady, ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.service import async_extract_referenced_entity_ids
from homeassistant.helpers.typing import ConfigType

from .client import HearthClient
from .const import (
    ACTION_NOTIFY,
    ACTION_NOTIFY_CLEAR,
    ACTION_TOAST,
    ATTR_ALL,
    ATTR_ID,
    ATTR_MESSAGE,
    ATTR_SEVERITY,
    ATTR_TIMEOUT,
    ATTR_TITLE,
    DOMAIN,
    PLATFORMS,
    SERVICE_NOTIFY,
    SERVICE_NOTIFY_CLEAR,
    SERVICE_TOAST,
)

# Shared target selector fields for all hearth.* services.
_TARGET_FIELDS = {
    vol.Optional("entity_id"): cv.comp_entity_ids,
    vol.Optional("device_id"): vol.All(cv.ensure_list, [cv.string]),
    vol.Optional("area_id"): vol.All(cv.ensure_list, [cv.string]),
}

SERVICE_TOAST_SCHEMA = vol.Schema({vol.Required(ATTR_MESSAGE): cv.string, **_TARGET_FIELDS})

SERVICE_NOTIFY_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_TITLE): cv.string,
        vol.Optional(ATTR_MESSAGE): cv.string,
        vol.Optional(ATTR_SEVERITY): vol.In(["info", "warning", "critical"]),
        vol.Optional(ATTR_TIMEOUT): vol.Coerce(int),
        vol.Optional(ATTR_ID): cv.string,
        **_TARGET_FIELDS,
    }
)

SERVICE_NOTIFY_CLEAR_SCHEMA = vol.Schema(
    {
        vol.Optional(ATTR_ID): cv.string,
        vol.Optional(ATTR_ALL): cv.boolean,
        **_TARGET_FIELDS,
    }
)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Register the global hearth.* services once."""

    def _clients_for(call: ServiceCall) -> list[HearthClient]:
        ent_reg = er.async_get(hass)
        selected = async_extract_referenced_entity_ids(hass, call)
        entity_ids = selected.referenced | selected.indirectly_referenced
        entry_ids: set[str] = set()
        for entity_id in entity_ids:
            entry = ent_reg.async_get(entity_id)
            if entry is not None and entry.config_entry_id:
                entry_ids.add(entry.config_entry_id)
        clients = hass.data.get(DOMAIN, {})
        return [clients[e] for e in entry_ids if e in clients]

    async def _handle_toast(call: ServiceCall) -> None:
        message = call.data[ATTR_MESSAGE]
        for client in _clients_for(call):
            await client.async_send_action(ACTION_TOAST, {"message": message})

    async def _handle_notify(call: ServiceCall) -> None:
        payload: dict = {"title": call.data[ATTR_TITLE]}
        if ATTR_MESSAGE in call.data:
            payload["message"] = call.data[ATTR_MESSAGE]
        if ATTR_SEVERITY in call.data:
            payload["severity"] = call.data[ATTR_SEVERITY]
        if ATTR_TIMEOUT in call.data:
            payload["timeout"] = call.data[ATTR_TIMEOUT]
        if ATTR_ID in call.data:
            payload["id"] = call.data[ATTR_ID]
        for client in _clients_for(call):
            await client.async_send_action(ACTION_NOTIFY, payload)

    async def _handle_notify_clear(call: ServiceCall) -> None:
        notify_id = call.data.get(ATTR_ID)
        clear_all = call.data.get(ATTR_ALL, False)
        # Exactly one of id / all — validated here (the schema can't express XOR).
        if bool(notify_id) == bool(clear_all):
            raise ServiceValidationError("Provide exactly one of 'id' or 'all'.")
        payload = {"all": True} if clear_all else {"id": notify_id}
        for client in _clients_for(call):
            await client.async_send_action(ACTION_NOTIFY_CLEAR, payload)

    hass.services.async_register(DOMAIN, SERVICE_TOAST, _handle_toast, schema=SERVICE_TOAST_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_NOTIFY, _handle_notify, schema=SERVICE_NOTIFY_SCHEMA)
    hass.services.async_register(
        DOMAIN, SERVICE_NOTIFY_CLEAR, _handle_notify_clear, schema=SERVICE_NOTIFY_CLEAR_SCHEMA
    )
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

    def _sync_sw_version() -> None:
        """Push a changed app version into the device registry.

        DeviceInfo.sw_version is only read when entities are constructed, so
        without this the registry keeps whatever version was current at setup —
        a device flashed with a new build would keep reporting the old one until
        HA restarted or the entry was reloaded. The client re-learns the version
        on every handshake, so refresh it whenever the device reconnects.
        """
        version = client.app_version
        if not version:
            return
        dev_reg = dr.async_get(hass)
        device = dev_reg.async_get_device(identifiers={(DOMAIN, entry.entry_id)})
        if device is not None and device.sw_version != version:
            dev_reg.async_update_device(device.id, sw_version=version)

    def _on_client_event(kind: str, data: dict) -> None:
        if kind == "connection" and data.get("connected"):
            _sync_sw_version()

    entry.async_on_unload(client.add_listener(_on_client_event))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    # The device row only exists once the platforms have registered their entities,
    # so the initial reconcile has to happen after the forward, not before.
    _sync_sw_version()
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        client = hass.data[DOMAIN].pop(entry.entry_id)
        await client.async_stop()
    return unloaded
