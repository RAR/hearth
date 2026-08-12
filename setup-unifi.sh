#!/usr/bin/env bash
# Configure the unifi-network MCP server for Claude Code, read-only.
#
# Prompts for the local-admin credentials rather than taking them as arguments,
# so the password never lands in shell history, in a process list, or in the
# Claude transcript. Delete this file once setup is done.
#
# Read-only is expressed by ABSENCE: no UNIFI_POLICY_NETWORK_* vars are written,
# and the plugin denies every write without an explicit policy opt-in.
# Port 443, site "default", SSL-verify off and lazy tool loading are the
# plugin's own defaults, so they are deliberately not set here.
set -euo pipefail

PROJECT_DIR="/home/rar/android_simpla_ha_dash"
SET_ENV="/home/rar/.claude/plugins/cache/unifi-plugins/unifi-network/0.25.1/scripts/set-env.sh"
HOST="10.75.0.1"

[ -f "$SET_ENV" ] || { echo "ERROR: set-env.sh not found at $SET_ENV" >&2; exit 1; }

# settings.local.json is written relative to the working directory.
cd "$PROJECT_DIR"

echo "UniFi controller: $HOST  (read-only)"
read -rp  "Local admin username: " UNIFI_USER
read -rsp "Local admin password: " UNIFI_PASS; echo
echo

[ -n "$UNIFI_USER" ] || { echo "ERROR: username cannot be empty" >&2; exit 1; }
[ -n "$UNIFI_PASS" ] || { echo "ERROR: password cannot be empty" >&2; exit 1; }

# Values are passed as distinct argv entries, so no quoting/expansion issues
# regardless of what characters the password contains.
bash "$SET_ENV" --target claude \
  "UNIFI_NETWORK_HOST=$HOST" \
  "UNIFI_NETWORK_USERNAME=$UNIFI_USER" \
  "UNIFI_NETWORK_PASSWORD=$UNIFI_PASS"

unset UNIFI_PASS
echo
echo "Done. Next: run /reload-plugins in Claude Code, then /plugin to confirm it is enabled."
