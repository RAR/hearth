#!/usr/bin/env bash
#
# Provision a Hearth kiosk device over adb.
#
# Everything here is idempotent: each step checks the current state first and
# says "already set" rather than reapplying. Safe to re-run after a flash, after
# a factory reset, or just to audit a device (see --verify).
#
# What it does NOT do, because it cannot:
#   - Home Assistant OAuth. Tokens live in EncryptedSharedPreferences keyed to the
#     app's install; they cannot be transplanted. A human must sign in through the
#     config page once per device.
#   - Restore the device name / PIN / config. Those go through the config server's
#     HTTP API and need the PIN, which is generated at first run. See the
#     "next steps" the script prints when it finishes.
#
# Usage:
#   tools/setup-device.sh <adb-serial-or-host:port>          # provision
#   tools/setup-device.sh --verify <serial>                  # report only, change nothing
#   tools/setup-device.sh --dry-run <serial>                 # print the commands it would run
#   tools/setup-device.sh --apk path/to/app-debug.apk <serial>
#   tools/setup-device.sh --no-install <serial>              # skip the APK, do the rest
#
set -uo pipefail

APK=""
DRY=0
VERIFY=0
FORCE=0
DO_INSTALL=1
DEV=""

# Devices that must not be touched without --force, and why. The Kitchen Echo is
# mid wake-capture run: reinstalling wipes filesDir and destroys the captures.
PROTECTED_DEFAULT="10.75.1.98"
PROTECTED="${HEARTH_PROTECTED:-$PROTECTED_DEFAULT}"

die() { echo "error: $*" >&2; exit 1; }
note() { echo "  $*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --apk)        APK="${2:-}"; shift 2 ;;
    --dry-run)    DRY=1; shift ;;
    --verify)     VERIFY=1; shift ;;
    --force)      FORCE=1; shift ;;
    --no-install) DO_INSTALL=0; shift ;;
    -h|--help)    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)           die "unknown option: $1" ;;
    *)            DEV="$1"; shift ;;
  esac
done

[ -n "$DEV" ] || die "no device given. Try: $0 --help"

# --- device guard -------------------------------------------------------------

for p in $PROTECTED; do
  if [ "$DEV" = "$p" ] || [ "${DEV%%:*}" = "$p" ]; then
    if [ "$FORCE" = 0 ] && [ "$VERIFY" = 0 ]; then
      die "$DEV is protected (wake-capture run in progress -- a reinstall destroys its captures).
       Re-run with --verify to inspect it, or --force if you genuinely mean it."
    fi
    echo "!! $DEV is a protected device; proceeding because you asked."
  fi
done

sh_() { adb -s "$DEV" shell "$@" 2>/dev/null | tr -d '\r'; }

adb -s "$DEV" get-state >/dev/null 2>&1 || die "device $DEV is not reachable over adb"

# --- detect -------------------------------------------------------------------

MODEL=$(sh_ getprop ro.product.model)
DEVNAME=$(sh_ getprop ro.product.device)
SDK=$(sh_ getprop ro.build.version.sdk)
IS_ROOT=$([ "$(sh_ id -u)" = "0" ] && echo 1 || echo 0)

# The fleet is mid-migration: devices flashed before 2026-07-27 still carry the
# old applicationId. Detect rather than assume, so this script works on both.
PKG=""
for candidate in com.rar.hearth com.rar.echodash; do
  if [ -n "$(sh_ pm path "$candidate")" ]; then PKG="$candidate"; break; fi
done
[ -n "$PKG" ] || PKG="com.rar.hearth"   # not installed yet; we are about to

VERSION=$(adb -s "$DEV" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d '\r' | sed 's/.*versionName=//')

echo "device:  $DEV"
echo "model:   $MODEL ($DEVNAME), API $SDK"
echo "package: $PKG ${VERSION:+($VERSION)}"
echo "adb uid: $([ "$IS_ROOT" = 1 ] && echo root || echo shell)"
[ "$PKG" = "com.rar.echodash" ] && echo "note:    still on the OLD applicationId -- see AGENTS.md before migrating"

# An appop can only be granted for a permission the INSTALLED apk declares, so a
# build older than the one that added SYSTEM_ALERT_WINDOW silently refuses the
# grant. Say so up front rather than letting it look like a mystery failure.
SAW_MIN_CODE=533
VCODE=$(echo "${VERSION:-}" | sed -n 's/^[0-9]*\.[0-9]*\.\([0-9]*\).*/\1/p')
if [ -n "$VCODE" ] && [ "$VCODE" -lt "$SAW_MIN_CODE" ] 2>/dev/null; then
  echo "warn:    installed build ($VERSION) predates the SYSTEM_ALERT_WINDOW declaration"
  echo "         (added in versionCode $SAW_MIN_CODE). That appop cannot be granted until this"
  echo "         device is updated -- install a current APK first, then re-run."
fi
echo

# run <description> <check-command> <expected-substring> <action-command...>
# Prints one line per step. In --verify mode it reports and never acts.
run() {
  local desc="$1" check="$2" want="$3"; shift 3
  local have
  have=$(eval "$check")
  if [ -n "$want" ] && echo "$have" | grep -q -- "$want"; then
    note "[ok]      $desc"
    return 0
  fi
  if [ "$VERIFY" = 1 ]; then
    note "[MISSING] $desc"
    return 1
  fi
  if [ "$DRY" = 1 ]; then
    note "[would]   $desc  ->  $*"
    return 0
  fi
  if ! "$@" >/dev/null 2>&1; then
    note "[FAILED]  $desc  (tried: $*)"
    return 1
  fi
  # `appops set` exits 0 even when the grant does not stick -- the op only exists
  # if the INSTALLED apk declares the permission. Re-read rather than trust it.
  if [ -n "$want" ]; then
    have=$(eval "$check")
    if ! echo "$have" | grep -q -- "$want"; then
      note "[FAILED]  $desc  -- command succeeded but the setting did not take."
      note "          got: $(echo "$have" | head -1)"
      return 1
    fi
  fi
  note "[set]     $desc"
}

FAILED=0

# --- 1. the app ---------------------------------------------------------------

if [ "$DO_INSTALL" = 1 ] && [ "$VERIFY" = 0 ]; then
  if [ -z "$APK" ]; then
    APK="$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/debug/app-debug.apk"
  fi
  if [ -f "$APK" ]; then
    if [ "$DRY" = 1 ]; then
      note "[would]   install $(basename "$APK")"
    else
      # -r keeps data. This only works when the signature and applicationId match
      # what is already installed; if it fails, that is the message you want to see.
      if adb -s "$DEV" install -r "$APK" 2>&1 | grep -q Success; then
        note "[set]     installed $(basename "$APK")"
      else
        note "[FAILED]  install -- signature or applicationId mismatch? (see AGENTS.md)"
        FAILED=1
      fi
    fi
  else
    note "[skip]    no APK at $APK (build one, or pass --apk, or --no-install)"
  fi
fi

# --- 2. permissions the manifest cannot grant itself --------------------------
# Both are appops, not runtime permissions: declaring them in the manifest is
# necessary but NOT sufficient on API 26+.

run "appop REQUEST_INSTALL_PACKAGES (in-app updater can stage an APK)" \
    "sh_ appops get $PKG REQUEST_INSTALL_PACKAGES" "allow" \
    adb -s "$DEV" shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow || FAILED=1

# Without this the app updates fine but never comes back on screen: Android 10+
# refuses to let a RECEIVER-state process start an Activity, so the display sits
# on the launcher until somebody touches it. The app draws no overlays.
run "appop SYSTEM_ALERT_WINDOW (restart itself after an update)" \
    "sh_ appops get $PKG SYSTEM_ALERT_WINDOW" "allow" \
    adb -s "$DEV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || FAILED=1

# --- 3. kiosk behaviour -------------------------------------------------------

# Set HOME before disabling any stock launcher, so the device is never home-less.
run "HOME launcher = Hearth" \
    "adb -s $DEV shell dumpsys package preferred-activities 2>/dev/null | grep -q '$PKG/.MainActivity' && echo yes || echo no" \
    "yes" \
    adb -s "$DEV" shell cmd package set-home-activity "$PKG/com.rar.hearth.MainActivity" || FAILED=1

# Keeps the voice satellite and config server alive when the screen idles.
run "doze whitelist" \
    "sh_ dumpsys deviceidle whitelist | grep -c $PKG" "1" \
    adb -s "$DEV" shell dumpsys deviceidle whitelist "+$PKG" || FAILED=1

# --- 4. stay reachable --------------------------------------------------------
# adb-over-TCP does not survive a reboot unless this is persisted. Unset is the
# likely reason a device "goes missing" for days at a time.
if [ "$IS_ROOT" = 1 ]; then
  run "persist.adb.tcp.port=5555 (adb survives reboot)" \
      "sh_ getprop persist.adb.tcp.port" "5555" \
      adb -s "$DEV" shell setprop persist.adb.tcp.port 5555 || FAILED=1
else
  CUR=$(sh_ getprop persist.adb.tcp.port)
  if [ "$CUR" = "5555" ]; then
    note "[ok]      persist.adb.tcp.port=5555 (adb survives reboot)"
  else
    note "[skip]    persist.adb.tcp.port needs root -- run 'adb -s $DEV root' first, then re-run"
  fi
fi

# --- 5. per-device quirks -----------------------------------------------------

case "$DEVNAME$MODEL" in
  *Pegasus*|*pegasus*|*Shelly*|*shelly*)
    # The stock Shelly launcher also draws a "return to Shelly" corner button.
    # `pm disable` throws SecurityException for shell; disable-user works.
    run "stock Shelly launcher disabled" \
        "sh_ pm list packages -d | grep -c cloud.shelly.stargate" "1" \
        adb -s "$DEV" shell pm disable-user --user 0 cloud.shelly.stargate || FAILED=1
    ;;
esac

# Mic gain on the Echos (MICPGA 40 -> 64) is NOT done here on purpose: the app's
# MixerGuard reapplies it at runtime and after reboot, which a setup script cannot.

# --- report -------------------------------------------------------------------

echo
if [ "$VERIFY" = 1 ]; then
  echo "verify only -- nothing was changed."
elif [ "$FAILED" = 0 ]; then
  echo "provisioning complete."
else
  echo "provisioning finished WITH FAILURES (see [FAILED] above)."
fi

if [ "$VERIFY" = 0 ] && [ "$DRY" = 0 ]; then
  IP=$(sh_ ip -f inet addr show wlan0 | grep -o 'inet [0-9.]*' | awk '{print $2}')
  cat <<EOF

Still needs a human:
  1. Open http://${IP:-<device-ip>}:8080 and sign in with the PIN shown on the
     device's own setup screen (it is generated at first run).
  2. Connect to Home Assistant. OAuth tokens cannot be scripted or transplanted.
  3. Restore the device's name, PIN and config if this is a rebuild:
       curl -c /tmp/j -H 'Content-Type: application/json' -d '{"pin":"<setup-pin>"}' \\
            http://${IP:-<device-ip>}:8080/api/login
       curl -b /tmp/j -X PUT -H 'Content-Type: application/json' \\
            -d '{"name":"<device name>"}' http://${IP:-<device-ip>}:8080/api/name
       curl -b /tmp/j -X PUT -H 'Content-Type: application/json' \\
            --data-binary @<device>-config.json http://${IP:-<device-ip>}:8080/api/config
       curl -b /tmp/j -X PUT -H 'Content-Type: application/json' \\
            -d '{"pin":"<your pin>"}' http://${IP:-<device-ip>}:8080/api/pin
     Note the Content-Type header: without it the login returns a misleading 401.
EOF
fi

exit "$FAILED"
