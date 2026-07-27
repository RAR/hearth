# In-App Update from GitHub Releases — Design

**Date:** 2026-07-27
**Status:** Draft for review

## Goal

Show the running build and the latest published build side by side on the device's
web config page, and offer a button that updates the device to that build — so
flashing a display no longer requires a laptop, a USB cable, and `adb`.

## Motivation

Every device is currently updated by hand: `./gradlew assembleDebug` on the dev box,
then `adb install -r` per device. That is fine while the dev box is in the room and
each device is adb-reachable, and it is exactly the two assumptions that keep
failing — the M9 has been unreachable for days at a time, and `persist.adb.tcp.port`
is unset on every device, so adb-over-TCP dies at each reboot.

The config page already knows the running version. Adding the published version
beside it, plus a button, turns a laptop errand into a phone tap.

## Scope

**In scope:** a `Latest version` row and an `Update` button on the Device page; a
GitHub release published by CI; a device endpoint that downloads a release APK and
hands it to Android's package installer; stable signing so those APKs are installable.

Two tiers were considered for the install itself, and this spec builds **Tier 1** only:

- **Tier 1 — confirmed install.** The device stages the APK and Android shows its
  install confirmation on the device's own screen; a human taps it. Works on every
  device today with no root, no device owner, and no provisioning.
- **Tier 2 — silent install.** Requires either LineageOS root granted per app or
  making Hearth a device owner. Possible on the two Echos only (the Shelly has an
  Android account, which blocks device owner), and device owner is generally
  undoable only by factory reset. Deferred; see Open decisions.

**Out of scope:**

- **Silent/unattended install** (Tier 2 above).
- **Automatic updating.** No polling, no scheduled install, no "update all devices".
  Every update is a deliberate per-device act. This is the property that keeps a bad
  commit from sweeping all four kiosks, and it is why the Kitchen Echo — mid
  wake-capture run, and not to be reinstalled — needs no special handling: nobody
  presses its button.
- **Downgrade / rollback.** `versionCode` is the commit count and Android refuses to
  install backwards in place. Recovery stays `adb install -r`.
- **Uploading an APK from the browser.** Considered for displays with no WAN access;
  deferred until one actually exists.

## Verified findings

These were measured on the devices and against GitHub on 2026-07-27, not assumed.
They are recorded because two of them invalidate the obvious design.

| Finding | Evidence | Consequence |
|---|---|---|
| CI signs every build with a different throwaway key | run 30294037681 → `29694df0…`; run 30278583533 → `37b800e5…`; local → `41b71cc0…` | **No CI artifact can install over any other, or over a flashed build.** Fixing this is a prerequisite, not a nicety. |
| No Magisk on any device; `/system/xbin/su` is LineageOS's built-in root, ADB-only by default | `pm list packages` finds no Magisk; `su -c id` on freshy hung until killed, while crown's adb is already uid 0 | App-initiated root is not available without per-device setup. Rules out root install for Tier 1. |
| No device owner set anywhere; Echos have 0 Android accounts, the Shelly has 1 | `dumpsys device_policy`, `dumpsys account` | Silent `PackageInstaller` is unavailable today. Device owner is possible on the Echos only, and is Tier 2. |
| `api.github.com` sends `access-control-allow-origin: *` | `curl -I` with an `Origin` header | The **browser** can read release metadata directly — no device endpoint, and no requirement that the display can reach GitHub to answer "am I behind?". |
| Release **asset** CORS is unconfirmed | the redirect target returned no `access-control-allow-origin` | The browser must **not** be relied on to fetch the APK. The device downloads it. This reverses an earlier assumption of mine. |
| No releases exist yet | `/releases` returns `[]` | CI currently uploads Actions artifacts, which need a token even on a public repo and expire after 90 days. Releases are needed regardless. |

## Decisions

### Sign CI builds with the **existing** debug keystore

CI gets the current local debug keystore (`41b71cc0…`) as a base64 GitHub Actions
secret and uses it for the debug signing config.

Rejected: minting a fresh proper release key. It is better practice in the abstract,
but Android requires a matching signature to update in place, so a new key means
**uninstall and reinstall on every device** — losing each one's config and its
Home Assistant OAuth token, and requiring a re-auth per display. Preserving the
existing key makes the very first release install cleanly over what is already
flashed, on all four devices, with no migration.

Accepted risk, stated plainly: this is a debug keystore with the publicly-known
Android password, committed to nothing but readable by anyone who can read the
secret. It is not a secret in the cryptographic sense. The protection that matters
here is that installing over the app requires either LAN access to a PIN-protected
config server or physical adb access, and the threat model is a home network. If
that ever stops being true, the fix is a real release key plus a one-time reinstall
sweep — not a change to this design.

### Publish on a tag, not on every push

The release workflow triggers on `v*` tags (plus `workflow_dispatch`), not on every
master push. Master keeps producing artifacts for CI's own sake.

This is what makes "the button" safe: it can only ever offer a build that was
deliberately tagged. Publishing every green master build would mean the button
offers whatever landed most recently, which is the auto-update failure mode this
design exists to avoid.

Tag format is strict: **`v<baseVersion>.<versionCode>`**, e.g. `v0.2.514`. The web
UI parses the `versionCode` back out of `tag_name`.

Rejected: shipping a `version.json` asset for the page to read. Asset CORS is
unconfirmed (see findings), and the release JSON from `api.github.com` already
carries everything needed.

### The browser asks GitHub; the device fetches the APK

Split by what each side can actually do:

- **"What's the latest?"** — the browser calls `api.github.com` directly. CORS-clean
  and verified. A display with no internet still reports its own version correctly
  and simply shows the check as unavailable.
- **"Install it."** — the device downloads the asset itself, server-side, where CORS
  does not apply.

### `versionCode` is the comparison authority

`versionName` (`0.2.514+19e1b93`) is for humans; the ordering lives in `versionCode`,
which is the commit count and monotonic on a linear master. `/api/status` gains
`appVersionCode` beside the existing `appVersion`.

Rejected: string-comparing `versionName`. The `+<sha>` suffix makes it unordered, and
a `.dirty` build must be treated as "not a release" rather than as newer or older.

### The update endpoint takes a URL, but only GitHub's

`POST /api/update` accepts a download URL and rejects anything not prefixed
`https://github.com/RAR/hearth/releases/download/`. Without that check the endpoint
is an arbitrary-APK installer reachable from the LAN. The config server's PIN is a
real gate, but a PIN is not a reason to accept an unvalidated URL.

Rejected: hardcoding the repo path in the device and passing only a tag. Marginally
safer, but it would force an app update to ever move the repo — and the allowlist
already gives the property that matters.

## Architecture

### Signing and release (CI)

`app/build.gradle.kts` gains a debug signing config that reads a keystore path and
password from Gradle properties or environment, **falling back to the default debug
keystore when absent** — so a fresh clone and PR builds still build.

A new `.github/workflows/release.yml`, triggered on `v*`:

1. Full-depth checkout (`versionCode` is the commit count — a shallow clone pins it to 1).
2. Decode the keystore secret, then run the same gate `build.yml` runs —
   `./gradlew test assembleDebug` followed by `lintDebug`.
3. Verify the built `versionCode` matches the tag, and **fail the release if it does not** —
   a mistyped tag would otherwise publish a build the page can never recognise as newer.
4. Publish a release with the APK attached, named `hearth-<tag>.apk`.

### Device — `update/` package

Two pure-Kotlin pieces, no Android imports, unit-tested like `ui/model/`:

```kotlin
// update/UpdateVersions.kt
fun parseTagVersionCode(tag: String): Int?      // "v0.2.514" -> 514; malformed -> null

/**
 * Newer, or the same code from a dirty local build — a `.dirty` build at the release's
 * own versionCode is a different build than the release, and offering it is the way
 * back onto a clean one.
 */
fun updateAvailable(currentCode: Int, latestCode: Int, currentIsDirty: Boolean): Boolean =
    latestCode > currentCode || (latestCode == currentCode && currentIsDirty)

// update/UpdateUrl.kt
fun isAllowedApkUrl(url: String): Boolean       // the GitHub releases-download allowlist
```

And an Android-side downloader-installer following `AndroidPhotoDownloader`'s existing
pattern — OkHttp is already a dependency, so **no new dependency is added**. It
downloads to app-private storage, then hands the file to `PackageInstaller`.

`REQUEST_INSTALL_PACKAGES` goes in the manifest. On API 26+ it also needs a per-device
grant, which is scriptable once per device rather than a UI errand:

```
adb shell appops set com.rar.echodash REQUEST_INSTALL_PACKAGES allow
```

### Device — endpoints

| Endpoint | Behaviour |
|---|---|
| `GET /api/status` | gains `appVersionCode` beside the existing `appVersion` |
| `POST /api/update` | body `{"url": "..."}`; validates against the allowlist, starts the download, returns immediately |
| `GET /api/update` | `{state, versionName, progressPct, error}` — `idle`/`downloading`/`verifying`/`awaiting_confirmation`/`failed` |

`verifying` is a real step, not a spinner: before invoking the installer the device
reads the staged file with `PackageManager.getPackageArchiveInfo` and refuses it
unless the package name is `com.rar.echodash` and its `versionCode` satisfies
`updateAvailable` against the running build. This catches a truncated download, a
mis-attached asset, and a tag pointing at the wrong artifact — all of which would
otherwise reach the user as an opaque Android install failure.

The install is asynchronous and outlives the request: the page polls `GET /api/update`.

### Web UI — Device page

Below the existing read-only `App version` row:

- **`Latest version`** — from `api.github.com`, with the release date. Shows
  `unavailable` (not an error) when the browser cannot reach GitHub.
- **`Update` button** — enabled only when `latestCode > appVersionCode`. Otherwise it
  reads `Up to date` and is disabled.
- **A status line** driven by `GET /api/update`, ending on the sentence that matters:
  **"Confirm the install on the device's screen."** A user who taps Update from a phone
  in another room and gets no feedback will assume it failed.

Vanilla JS, no dependencies, consistent with the rest of the page.

## Data flow

```
browser ──► api.github.com/releases/latest ──► tag_name ──► latestCode
                                                                │
device ──► GET /api/status ──► appVersionCode ──────────────────┤
                                                                ▼
                                                   button enabled iff newer
                                                                │
                                    POST /api/update {url} ─────┘
                                                                │
device ──► GitHub release asset ──► app-private file ──► PackageInstaller
                                                                │
                                              confirmation on the DEVICE screen
```

The version check needs only the browser. The install needs only the device. Neither
depends on the other's network reachability.

## Error handling and edge cases

| Case | Behaviour |
|---|---|
| Browser cannot reach GitHub | `Latest version: unavailable`, button disabled. Not an error state — a local-only display is a valid configuration. |
| Running build is `.dirty` | Offered even at the release's own `versionCode` (see `updateAvailable`); the status line says the running build is uncommitted. |
| Staged APK is a different package or an older build | Rejected at `verifying` with a specific reason; the installer is never invoked. |
| Release tag malformed | `parseTagVersionCode` returns null → treated as unavailable rather than as version 0. |
| Tag disagrees with the APK's `versionCode` | Caught in CI; the release never publishes. |
| `versionCode` equal | `Up to date`, disabled. |
| Download fails midway | Partial file deleted, state `failed` with the reason; button re-enabled. |
| Signature mismatch on install | Android rejects it; surfaced verbatim rather than as a generic failure — this is the symptom of the signing prerequisite regressing, and it must not be mistaken for a network fault. |
| `REQUEST_INSTALL_PACKAGES` not granted | Install returns immediately; the status line names the `appops` command. |
| Nobody confirms the on-device dialog | State stays `awaiting_confirmation`; no timeout. The APK stays staged. |
| Two update requests at once | Second is rejected while one is in flight. |
| Disk full | Download fails cleanly; app-private storage, so no partial file is left behind. |

## Testing

Plain-JVM JUnit4 only, consistent with the rest of the app.

**`UpdateVersionsTest`** — tag parsing across well-formed, malformed, empty, and
`v`-less inputs; `updateAvailable` across newer, older, equal, and dirty-current.

**`UpdateUrlTest`** — the allowlist accepts a real release-download URL and rejects a
lookalike host, a `http://` downgrade, a path-traversal suffix, and an unrelated host.
This is a security boundary, so its tests are adversarial rather than illustrative.

**`ConfigServerUpdateTest`** — `/api/status` carries `appVersionCode`; `POST /api/update`
rejects a disallowed URL with 4xx and does not start a download; a second concurrent
request is rejected. Uses the existing `ConfigServerTest` harness, with `mockwebserver`
(already a test dependency) standing in for GitHub.

Not unit-tested, consistent with existing practice: the `PackageInstaller` handoff, the
browser's GitHub fetch, and the config page beyond `node --check`.

**Gate before every commit:** `./gradlew testDebugUnitTest assembleDebug` with the
return code checked, plus `node --check app.js` for the JS.

## Verification

The end-to-end path cannot be proven by tests — it needs one real release:

1. Tag `v0.2.<n>`, confirm CI publishes a release with the APK attached.
2. Confirm the published APK's signer is `41b71cc0…` — the same key the devices already
   carry. **If this fails, nothing else in the flow can work.**
3. On crown, confirm the page shows both versions and enables the button.
4. Press it; confirm the download progresses and the install dialog appears on the
   device; confirm the app relaunches on the new version and its config and HA token
   survived.
5. Confirm the button then reads `Up to date`.

Do **not** exercise this on the Kitchen Echo (10.75.1.98) while its wake-capture run is
in progress.

## Prerequisite, tracked separately

`persist.adb.tcp.port` is unset on every device, so adb-over-TCP does not survive a
reboot — the likely cause of the M9's recurring unreachability. That is the fallback
path this feature depends on when an update goes wrong, so it is worth fixing, but it
is a one-line `setprop` per device and not part of this change.

## Global constraints

- `minSdk` 27, `targetSdk` 34, `applicationId` `com.rar.echodash` — all unchanged.
- **No new app dependencies.** OkHttp is already present; the downloader follows
  `AndroidPhotoDownloader`.
- Pure `update/` modules take no Compose or Android imports.
- The config page stays dependency-free vanilla JS.
- The HA integration is untouched — it has no pip dependencies and gains none.
- Existing installs must update in place, retaining config and HA credentials.

## Open decisions

Three calls I made on the user's behalf, each easy to reverse before implementation:

1. **Reusing the debug keystore** rather than minting a release key — chosen to avoid a
   reinstall-and-re-auth sweep across four devices. The tradeoff is written out above.
2. **Tag-triggered releases** rather than every master build — chosen so the button
   cannot offer an untested commit. Costs a deliberate `git tag` per release.
3. **Tier 1 only** — the install is confirmed on the device's screen. Silent install
   (device owner on the Echos) is deliberately deferred until the manual step proves
   annoying enough to justify a change that is hard to undo.
