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
