"""Constants for the Hearth integration."""

from __future__ import annotations

DOMAIN = "hearth"
DEFAULT_PORT = 10700
MANUFACTURER = "Hearth"

PLATFORMS = ["media_player", "switch", "number", "button", "select", "notify"]

# Kiosk settings keys (bool/int device state, echoed in settings feedback).
SETTING_SCREEN_ON = "screen_on"
SETTING_SCREEN_BRIGHTNESS = "screen_brightness"
SETTING_SCREEN_AUTO_BRIGHTNESS = "screen_auto_brightness"
SETTING_SCREEN_ALWAYS_ON = "screen_always_on"
SETTING_SCREEN_SAVER = "screen_saver"
SETTING_DARK_MODE = "dark_mode"
SETTING_SCREEN_TIMEOUT = "screen_timeout"
SETTING_MUSIC_VOLUME = "music_volume"
SETTING_DUCKING_VOLUME = "ducking_volume"

# Action names.
ACTION_REFRESH = "refresh"
ACTION_PLAY = "play"
ACTION_PAUSE = "pause"
ACTION_STOP = "stop"
ACTION_PLAY_MEDIA = "play-media"
ACTION_SET_VOLUME = "set-volume"
ACTION_TOAST = "toast-message"
ACTION_SET_VIEW = "set-view"

# Dashboard views (lowercase DashView names, in enum declaration order) — the select options.
VIEW_OPTIONS = ["home", "lights", "climate", "media", "calendar", "weather", "solar", "cameras"]

ACTION_NOTIFY = "notify"
ACTION_NOTIFY_CLEAR = "notify-clear"

# Scales / announce tuning.
MAX_MUSIC_VOLUME = 10  # the music_volume SETTING is 0-10; action volumes are percent 0-100
ANNOUNCE_RATE = 22050
ANNOUNCE_WIDTH = 2
ANNOUNCE_CHANNELS = 1
ANNOUNCE_CHUNK = 4096

SERVICE_TOAST = "toast"
ATTR_MESSAGE = "message"

SERVICE_NOTIFY = "notify"
SERVICE_NOTIFY_CLEAR = "notify_clear"
ATTR_TITLE = "title"
ATTR_SEVERITY = "severity"
ATTR_TIMEOUT = "timeout"
ATTR_ID = "id"
ATTR_ALL = "all"
