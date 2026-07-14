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
