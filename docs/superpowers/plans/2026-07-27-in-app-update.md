# In-App Update from GitHub Releases — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a `Latest version` row and a working `Update` button on the device's web config page, so a display can be updated from a phone instead of a laptop and a USB cable.

**Architecture:** The browser asks `api.github.com` for the newest release (CORS-clean) and compares its tag against a new `appVersionCode` in `/api/status`. Pressing Update posts a release-asset URL to the device, which downloads it itself, verifies the staged APK is the right package at a newer version, and hands it to Android's `PackageInstaller`; the user confirms on the device's own screen. A `MY_PACKAGE_REPLACED` receiver restarts the app afterwards, because Android does not.

**Tech Stack:** Kotlin/Compose app, NanoHTTPD config server, OkHttp (already a dependency), vanilla JS config page, GitHub Actions.

## Already shipped — do NOT redo

Commits `16f608a`, `46fc284`, `1a0d825`, `6433779`:

- Stable signing keystore, SHA-256 `1179d10d7aeb35970cd677e8711c68cb7b450a72dc9fc199aac6a505157f9a53`. Resolution: `HEARTH_KEYSTORE`/`HEARTH_KEYSTORE_PASSWORD` env → `~/.hearth/` → Gradle's default debug key.
- `build.yml` decodes the keystore secret and **asserts** the resulting APK's fingerprint.
- `applicationId` is `com.rar.hearth`.
- Repo secrets `HEARTH_KEYSTORE` and `HEARTH_KEYSTORE_PASSWORD` exist.
- Verified end to end: a CI APK installed in place over a live device keeping config, PIN, and HA session.

## Global Constraints

- `minSdk` 27, `targetSdk` 34, `applicationId` `com.rar.hearth` — all unchanged.
- **No new app dependencies.** OkHttp 4.12.0 is already present.
- Pure `update/` modules take **no** Compose or Android imports — they must run on plain JVM.
- Tests are **plain-JVM JUnit4 only**. No instrumented tests, no Robolectric.
- The config page stays dependency-free vanilla JS.
- The HA integration (`custom_components/hearth/`) is untouched.
- Existing installs must keep updating in place, retaining config and HA credentials.
- **Gate before every commit:** `./gradlew testDebugUnitTest assembleDebug`, checking the return code (do not pipe to `tail` and lose it), plus `node --check app/src/main/assets/config/app.js` for any JS change.
- Release tag format is exactly `v<baseVersion>.<versionCode>`, e.g. `v0.2.514`.
- The APK download allowlist prefix is exactly `https://github.com/RAR/hearth/releases/download/`.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/rar/hearth/update/UpdateVersions.kt` (new) | Pure: parse a release tag, decide if an update is available |
| `app/src/main/java/com/rar/hearth/update/UpdateUrl.kt` (new) | Pure: the download-URL allowlist (a security boundary) |
| `app/src/main/java/com/rar/hearth/update/UpdateState.kt` (new) | Pure: the state machine's data types, shared by installer and server |
| `app/src/main/java/com/rar/hearth/update/ApkUpdater.kt` (new) | Android: download → verify → `PackageInstaller`. Follows `AndroidPhotoDownloader`. |
| `app/src/main/java/com/rar/hearth/PackageReplacedReceiver.kt` (new) | Android: restart `MainActivity` after the app is replaced |
| `app/src/main/java/com/rar/hearth/web/ConfigServer.kt` | Adds `appVersionCode` to status; `POST`/`GET /api/update` |
| `app/src/main/java/com/rar/hearth/AppDeps.kt` | Wires `BuildConfig.VERSION_CODE` and the updater into `ConfigServer` |
| `app/src/main/AndroidManifest.xml` | `REQUEST_INSTALL_PACKAGES`, the new receiver |
| `app/src/main/assets/config/app.js` | `Latest version` row, `Update` button, progress polling |
| `.github/workflows/release.yml` (new) | Publishes a release with the APK on a `v*` tag |

Tasks 1–3 are pure and independently testable. Task 4 is the Android surface. Tasks 5–6 expose it. Task 7 is CI. Task 8 is the manual verification that only a real device can give.

---

### Task 1: Version comparison (pure)

**Files:**
- Create: `app/src/main/java/com/rar/hearth/update/UpdateVersions.kt`
- Test: `app/src/test/java/com/rar/hearth/update/UpdateVersionsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `parseTagVersionCode(tag: String): Int?` and `updateAvailable(currentCode: Int, latestCode: Int, currentIsDirty: Boolean): Boolean`, both in package `com.rar.hearth.update`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rar/hearth/update/UpdateVersionsTest.kt`:

```kotlin
package com.rar.hearth.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionsTest {

    @Test
    fun parsesTheVersionCodeOutOfAWellFormedTag() {
        assertEquals(514, parseTagVersionCode("v0.2.514"))
        assertEquals(1, parseTagVersionCode("v0.2.1"))
        assertEquals(12345, parseTagVersionCode("v1.10.12345"))
    }

    @Test
    fun rejectsMalformedTagsRatherThanGuessing() {
        // A tag we cannot read must be "unknown", never 0 -- 0 would read as
        // "older than everything" and wrongly enable the button.
        assertNull(parseTagVersionCode(""))
        assertNull(parseTagVersionCode("v0.2"))            // no versionCode component
        assertNull(parseTagVersionCode("0.2.514"))         // missing the v prefix
        assertNull(parseTagVersionCode("v0.2.x"))          // non-numeric
        assertNull(parseTagVersionCode("release-514"))
        assertNull(parseTagVersionCode("v0.2.514-rc1"))    // suffix we do not define
        assertNull(parseTagVersionCode("v0.2.-5"))         // negative
    }

    @Test
    fun offersAnUpdateOnlyWhenTheReleaseIsNewer() {
        assertTrue(updateAvailable(currentCode = 513, latestCode = 514, currentIsDirty = false))
        assertFalse(updateAvailable(currentCode = 514, latestCode = 514, currentIsDirty = false))
        assertFalse(updateAvailable(currentCode = 515, latestCode = 514, currentIsDirty = false))
    }

    @Test
    fun aDirtyBuildIsOfferedTheReleaseAtItsOwnVersionCode() {
        // A .dirty build at 514 is NOT the 514 release -- it is uncommitted local
        // work. Offering it is the way back onto a clean build.
        assertTrue(updateAvailable(currentCode = 514, latestCode = 514, currentIsDirty = true))
        // But dirty never justifies going backwards.
        assertFalse(updateAvailable(currentCode = 515, latestCode = 514, currentIsDirty = true))
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew testDebugUnitTest --tests '*UpdateVersionsTest*'`
Expected: FAIL — compilation error, `parseTagVersionCode` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/rar/hearth/update/UpdateVersions.kt`:

```kotlin
package com.rar.hearth.update

/**
 * Release-tag arithmetic. Pure Kotlin (no Android imports) so it unit-tests on the JVM.
 *
 * Tags are exactly `v<major>.<minor>.<versionCode>` -- the last component is the app's
 * versionCode, which is the commit count and therefore monotonic on a linear master.
 * versionName is deliberately NOT used for ordering: its `+<sha>` suffix makes it unordered.
 */
private val TAG_RE = Regex("""^v\d+\.\d+\.(\d+)$""")

/** The versionCode encoded in [tag], or null if the tag is not one we recognise. */
fun parseTagVersionCode(tag: String): Int? =
    TAG_RE.find(tag)?.groupValues?.get(1)?.toIntOrNull()

/**
 * True when [latestCode] is worth installing over [currentCode].
 *
 * A `.dirty` build carries the versionCode of the commit it was built from but is not that
 * build, so at an equal code the release is still worth offering. Dirty never justifies a
 * downgrade -- Android would refuse the install anyway.
 */
fun updateAvailable(currentCode: Int, latestCode: Int, currentIsDirty: Boolean): Boolean =
    latestCode > currentCode || (latestCode == currentCode && currentIsDirty)
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew testDebugUnitTest --tests '*UpdateVersionsTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the gate and commit**

```bash
./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"
git add app/src/main/java/com/rar/hearth/update/UpdateVersions.kt app/src/test/java/com/rar/hearth/update/UpdateVersionsTest.kt
git commit -m "feat(update): pure release-tag version comparison"
```

RC must be 0 before committing.

---

### Task 2: Download-URL allowlist (pure, security boundary)

**Files:**
- Create: `app/src/main/java/com/rar/hearth/update/UpdateUrl.kt`
- Test: `app/src/test/java/com/rar/hearth/update/UpdateUrlTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `isAllowedApkUrl(url: String): Boolean` in package `com.rar.hearth.update`.

**Why this is its own task:** `POST /api/update` takes a URL from the network. Without this check the endpoint installs arbitrary APKs on the LAN. The tests are adversarial on purpose — they are the specification.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rar/hearth/update/UpdateUrlTest.kt`:

```kotlin
package com.rar.hearth.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlTest {

    @Test
    fun acceptsARealReleaseDownloadUrl() {
        assertTrue(isAllowedApkUrl(
            "https://github.com/RAR/hearth/releases/download/v0.2.514/hearth-v0.2.514.apk"))
    }

    @Test
    fun rejectsLookalikeHosts() {
        // The classic prefix-match bug: these all START with something that looks right.
        assertFalse(isAllowedApkUrl(
            "https://github.com.evil.example/RAR/hearth/releases/download/v1/x.apk"))
        assertFalse(isAllowedApkUrl(
            "https://evil.example/https://github.com/RAR/hearth/releases/download/v1/x.apk"))
        assertFalse(isAllowedApkUrl(
            "https://github.com@evil.example/RAR/hearth/releases/download/v1/x.apk"))
        assertFalse(isAllowedApkUrl(
            "https://notgithub.com/RAR/hearth/releases/download/v1/x.apk"))
    }

    @Test
    fun rejectsTheWrongRepoEvenOnTheRightHost() {
        assertFalse(isAllowedApkUrl(
            "https://github.com/someoneelse/evil/releases/download/v1/x.apk"))
        assertFalse(isAllowedApkUrl(
            "https://github.com/RAR/otherrepo/releases/download/v1/x.apk"))
    }

    @Test
    fun rejectsPlaintextAndOtherSchemes() {
        assertFalse(isAllowedApkUrl(
            "http://github.com/RAR/hearth/releases/download/v0.2.514/hearth.apk"))
        assertFalse(isAllowedApkUrl(
            "file:///data/local/tmp/evil.apk"))
        assertFalse(isAllowedApkUrl(
            "ftp://github.com/RAR/hearth/releases/download/v1/x.apk"))
    }

    @Test
    fun rejectsPathTraversalOutOfTheReleasesArea() {
        assertFalse(isAllowedApkUrl(
            "https://github.com/RAR/hearth/releases/download/../../../evil.apk"))
        assertFalse(isAllowedApkUrl(
            "https://github.com/RAR/hearth/releases/download/v1/..%2f..%2fevil.apk"))
    }

    @Test
    fun rejectsGarbage() {
        assertFalse(isAllowedApkUrl(""))
        assertFalse(isAllowedApkUrl("   "))
        assertFalse(isAllowedApkUrl("not a url"))
        // Right prefix, but not an APK.
        assertFalse(isAllowedApkUrl(
            "https://github.com/RAR/hearth/releases/download/v0.2.514/notes.txt"))
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew testDebugUnitTest --tests '*UpdateUrlTest*'`
Expected: FAIL — `isAllowedApkUrl` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/rar/hearth/update/UpdateUrl.kt`:

```kotlin
package com.rar.hearth.update

import java.net.URI

/**
 * Allowlist for APK download URLs. `POST /api/update` takes a URL off the network, so without
 * this the endpoint is an arbitrary-APK installer reachable from the LAN. The config server's
 * PIN is a real gate, but a PIN is not a reason to accept an unvalidated URL.
 *
 * Parsed with [URI] rather than prefix-matched: `https://github.com.evil.example/...` and
 * `https://github.com@evil.example/...` both survive a naive startsWith().
 */
private const val ALLOWED_HOST = "github.com"
private const val ALLOWED_PATH_PREFIX = "/RAR/hearth/releases/download/"

fun isAllowedApkUrl(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    if (!uri.isAbsolute) return false
    if (uri.scheme?.lowercase() != "https") return false
    // userInfo set means a "user@host" form -- the host is not what a reader expects.
    if (uri.userInfo != null) return false
    if (uri.host?.lowercase() != ALLOWED_HOST) return false
    val path = uri.rawPath ?: return false
    if (!path.startsWith(ALLOWED_PATH_PREFIX)) return false
    // Reject traversal in both raw and percent-decoded form.
    val decoded = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrNull() ?: return false
    if (path.contains("..") || decoded.contains("..")) return false
    return decoded.endsWith(".apk")
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew testDebugUnitTest --tests '*UpdateUrlTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the gate and commit**

```bash
./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"
git add app/src/main/java/com/rar/hearth/update/UpdateUrl.kt app/src/test/java/com/rar/hearth/update/UpdateUrlTest.kt
git commit -m "feat(update): allowlist APK download URLs to the hearth releases path"
```

---

### Task 3: Update state types (pure)

**Files:**
- Create: `app/src/main/java/com/rar/hearth/update/UpdateState.kt`
- Test: `app/src/test/java/com/rar/hearth/update/UpdateStateTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class UpdateStage { IDLE, DOWNLOADING, VERIFYING, AWAITING_CONFIRMATION, FAILED }`
  - `data class UpdateStatus(val stage: UpdateStage = UpdateStage.IDLE, val versionName: String? = null, val progressPct: Int = 0, val error: String? = null)`
  - `fun UpdateStatus.isBusy(): Boolean`
  - `fun UpdateStatus.toJson(): String`

**Why separate:** both `ApkUpdater` (Task 4) and `ConfigServer` (Task 5) need these types, and the JSON shape is what the web UI in Task 6 reads. Defining it once, on the JVM, keeps the contract testable without an emulator.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rar/hearth/update/UpdateStateTest.kt`:

```kotlin
package com.rar.hearth.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStateTest {

    @Test
    fun idleAndFailedAreNotBusyButTheWorkingStagesAre() {
        // "Busy" is what rejects a second concurrent update request.
        assertFalse(UpdateStatus().isBusy())
        assertFalse(UpdateStatus(stage = UpdateStage.FAILED, error = "boom").isBusy())
        assertTrue(UpdateStatus(stage = UpdateStage.DOWNLOADING).isBusy())
        assertTrue(UpdateStatus(stage = UpdateStage.VERIFYING).isBusy())
        // Staged and waiting for a human to tap Install still owns the slot.
        assertTrue(UpdateStatus(stage = UpdateStage.AWAITING_CONFIRMATION).isBusy())
    }

    @Test
    fun serialisesTheShapeTheWebUiReads() {
        val json = Json.parseToJsonElement(
            UpdateStatus(
                stage = UpdateStage.DOWNLOADING,
                versionName = "0.2.514+abc1234",
                progressPct = 42,
            ).toJson()
        ).jsonObject
        assertEquals("downloading", json["state"]!!.jsonPrimitive.content)
        assertEquals("0.2.514+abc1234", json["versionName"]!!.jsonPrimitive.content)
        assertEquals(42, json["progressPct"]!!.jsonPrimitive.content.toInt())
        assertTrue(json.containsKey("error"))
    }

    @Test
    fun nullsSerialiseAsJsonNullNotTheStringNull() {
        val json = Json.parseToJsonElement(UpdateStatus().toJson()).jsonObject
        assertEquals("idle", json["state"]!!.jsonPrimitive.content)
        assertTrue(json["error"]!!.jsonPrimitive.isString.not())
        assertEquals("null", json["error"].toString())
        assertEquals("null", json["versionName"].toString())
    }

    @Test
    fun stageNamesAreLowercaseSnakeForTheWire() {
        assertEquals("awaiting_confirmation", UpdateStage.AWAITING_CONFIRMATION.wire())
        assertEquals("idle", UpdateStage.IDLE.wire())
        assertEquals("failed", UpdateStage.FAILED.wire())
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew testDebugUnitTest --tests '*UpdateStateTest*'`
Expected: FAIL — `UpdateStatus` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/rar/hearth/update/UpdateState.kt`:

```kotlin
package com.rar.hearth.update

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The update state machine's vocabulary. Pure Kotlin (no Android imports) so both the
 * installer and the config server can share it and the wire shape stays unit-testable.
 */
enum class UpdateStage {
    IDLE,
    DOWNLOADING,
    /** Reading the staged APK back to confirm it is our package at a newer versionCode. */
    VERIFYING,
    /** Handed to PackageInstaller; Android is showing its dialog on the device's screen. */
    AWAITING_CONFIRMATION,
    FAILED;

    fun wire(): String = name.lowercase()
}

data class UpdateStatus(
    val stage: UpdateStage = UpdateStage.IDLE,
    val versionName: String? = null,
    val progressPct: Int = 0,
    val error: String? = null,
) {
    /** True while an update owns the slot, so a second request is rejected rather than queued. */
    fun isBusy(): Boolean = stage == UpdateStage.DOWNLOADING ||
        stage == UpdateStage.VERIFYING ||
        stage == UpdateStage.AWAITING_CONFIRMATION

    fun toJson(): String = buildJsonObject {
        put("state", stage.wire())
        put("versionName", versionName?.let { JsonPrimitive(it) } ?: JsonNull)
        put("progressPct", progressPct)
        put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew testDebugUnitTest --tests '*UpdateStateTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the gate and commit**

```bash
./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"
git add app/src/main/java/com/rar/hearth/update/UpdateState.kt app/src/test/java/com/rar/hearth/update/UpdateStateTest.kt
git commit -m "feat(update): update state machine types and wire shape"
```

---

### Task 4: The downloader/installer and the restart receiver

**Files:**
- Create: `app/src/main/java/com/rar/hearth/update/ApkUpdater.kt`
- Create: `app/src/main/java/com/rar/hearth/PackageReplacedReceiver.kt`
- Create: `app/src/main/java/com/rar/hearth/InstallStatusReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `isAllowedApkUrl(url: String): Boolean`; `UpdateStage`, `UpdateStatus`, `UpdateStatus.isBusy()` from Tasks 2–3.
- Produces:
  - `class ApkUpdater(context: Context, http: OkHttpClient, scope: CoroutineScope, currentVersionCode: Int)`
  - `ApkUpdater.status: StateFlow<UpdateStatus>`
  - `ApkUpdater.start(url: String): Boolean` — false if rejected (busy or disallowed URL)

**Note on testing:** this task has no unit tests. `PackageInstaller`, `PackageManager.getPackageArchiveInfo`, and `BroadcastReceiver` are Android-framework surfaces, and the project's test policy is plain-JVM JUnit4 with no Robolectric. The logic that *can* be tested on the JVM was deliberately extracted into Tasks 1–3. This task is covered by Android Lint (which CI fails on) and by the manual verification in Task 8. Do not add an instrumented test.

- [ ] **Step 1: Add the permission and the receiver to the manifest**

In `app/src/main/AndroidManifest.xml`, add after the `RECEIVE_BOOT_COMPLETED` permission line:

```xml
    <!-- In-app update: staging an APK for PackageInstaller. Also needs a per-device grant:
         adb shell appops set com.rar.hearth REQUEST_INSTALL_PACKAGES allow -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

And add after the existing `BootReceiver` `<receiver>` block:

```xml
        <!-- Android kills the app to replace it and does NOT start it again, so without this a
             wall display stays dark after an update until somebody touches it. -->
        <receiver android:name=".PackageReplacedReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 2: Write the restart receiver**

Create `app/src/main/java/com/rar/hearth/PackageReplacedReceiver.kt`, mirroring the existing `BootReceiver`:

```kotlin
package com.rar.hearth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the dashboard after the app is replaced.
 *
 * Android kills the app to install the new APK and does not start it again. On a wall-mounted
 * display that turns an update into an outage: the screen stays dark until someone walks over.
 * Same job as [BootReceiver], different trigger.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
```

- [ ] **Step 3: Write the install-status receiver**

Create `app/src/main/java/com/rar/hearth/InstallStatusReceiver.kt`:

```kotlin
package com.rar.hearth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the PackageInstaller session's status callbacks.
 *
 * This is not optional plumbing. After `session.commit()` the system replies with
 * STATUS_PENDING_USER_ACTION and an Intent in EXTRA_INTENT; the confirmation dialog appears
 * only when somebody starts that Intent. Without this receiver the install stalls silently:
 * no dialog, no error, nothing on screen.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        // We are a background receiver, so the dialog needs its own task.
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
```

Register it in `app/src/main/AndroidManifest.xml`, beside the other receivers. It is targeted by an explicit `PendingIntent`, so it does not need to be exported:

```xml
        <receiver android:name=".InstallStatusReceiver" android:exported="false" />
```

- [ ] **Step 4: Write the updater**

Create `app/src/main/java/com/rar/hearth/update/ApkUpdater.kt`:

```kotlin
package com.rar.hearth.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads a release APK and hands it to Android's package installer. Follows
 * AndroidPhotoDownloader: OkHttp on Dispatchers.IO, temp file renamed on success so an
 * interrupted download never leaves a corrupt file under the final name.
 *
 * The install confirmation appears on the DEVICE's own screen -- this is Tier 1 in the spec.
 * Silent install would need root or device-owner and is deliberately out of scope.
 */
class ApkUpdater(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val currentVersionCode: Int,
) {
    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status

    /** Staged in app-private storage: no external-storage permission, and cleaned up on uninstall. */
    private val stagingDir: File get() = File(context.filesDir, "update").apply { mkdirs() }

    /**
     * Begins an update. Returns false and changes nothing when the URL is not allowlisted or an
     * update is already in flight.
     */
    fun start(url: String): Boolean {
        if (!isAllowedApkUrl(url)) return false
        if (_status.value.isBusy()) return false
        _status.value = UpdateStatus(stage = UpdateStage.DOWNLOADING)
        scope.launch { run(url) }
        return true
    }

    private suspend fun run(url: String) {
        val apk = download(url)
        if (apk == null) {
            fail("download failed")
            return
        }
        _status.value = _status.value.copy(stage = UpdateStage.VERIFYING, progressPct = 100)
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (info == null) {
            apk.delete(); fail("downloaded file is not a valid APK"); return
        }
        if (info.packageName != context.packageName) {
            apk.delete(); fail("APK is ${info.packageName}, expected ${context.packageName}"); return
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
        // Dirty is not knowable from the archive, so require strictly newer here. The button in
        // the web UI is what applies the dirty allowance; this is the last-line sanity check.
        if (code <= currentVersionCode) {
            apk.delete(); fail("APK is version $code, not newer than $currentVersionCode"); return
        }
        _status.value = _status.value.copy(
            stage = UpdateStage.AWAITING_CONFIRMATION,
            versionName = info.versionName,
        )
        runCatching { installViaSession(apk) }.onFailure { fail("install failed: ${it.message}") }
    }

    private suspend fun download(url: String): File? = withContext(Dispatchers.IO) {
        val tmp = File(stagingDir, "update.apk.tmp")
        val out = File(stagingDir, "update.apk")
        tmp.delete(); out.delete()
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                var read = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                _status.value = _status.value.copy(
                                    progressPct = ((read * 100) / total).toInt().coerceIn(0, 100)
                                )
                            }
                        }
                    }
                }
            }
        }.getOrElse { tmp.delete(); return@withContext null }
        if (!tmp.renameTo(out)) { tmp.delete(); return@withContext null }
        out
    }

    private fun installViaSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("hearth", 0, apk.length()).use { dest ->
                apk.inputStream().use { it.copyTo(dest) }
                session.fsync(dest)
            }
            // The commit target MUST be a broadcast we handle: the system replies with
            // STATUS_PENDING_USER_ACTION and hands back an Intent that somebody has to
            // start. Nothing shows the dialog on its own -- point this at an Activity and
            // the install stalls forever with no error.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pending = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, com.rar.hearth.InstallStatusReceiver::class.java),
                flags,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun fail(reason: String) {
        _status.value = UpdateStatus(stage = UpdateStage.FAILED, error = reason)
    }
}
```

- [ ] **Step 5: Run the gate**

```bash
./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"
./gradlew lintDebug; echo "LINT_RC=$?"
```

Both must be 0. Lint is the only automated check on the Android-framework surface here — if it flags a `NewApi` call below minSdk 27, fix it rather than suppressing it.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/hearth/update/ApkUpdater.kt \
        app/src/main/java/com/rar/hearth/PackageReplacedReceiver.kt \
        app/src/main/java/com/rar/hearth/InstallStatusReceiver.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(update): stage and install release APKs, restart after replace"
```

---

### Task 5: Config server endpoints

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/web/ConfigServer.kt`
- Modify: `app/src/main/java/com/rar/hearth/AppDeps.kt`
- Test: `app/src/test/java/com/rar/hearth/web/ConfigServerUpdateTest.kt`

**Interfaces:**
- Consumes: `UpdateStatus`, `UpdateStatus.toJson()`, `UpdateStage` from Task 3; `ApkUpdater.start(url)` and `.status` from Task 4.
- Produces: `/api/status` gains `appVersionCode` (Int); `POST /api/update` accepting `{"url": "..."}`; `GET /api/update` returning `UpdateStatus.toJson()`.

`ConfigServer` takes the updater as two lambdas rather than the concrete class, matching how every other Android-dependent capability is injected there (`disconnect`, `previewChime`, `maSignIn`). That is what keeps `ConfigServerUpdateTest` a plain-JVM test.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rar/hearth/web/ConfigServerUpdateTest.kt`:

```kotlin
package com.rar.hearth.web

import com.rar.hearth.config.ConfigStore
import com.rar.hearth.data.InMemorySettingsStore
import com.rar.hearth.ha.AuthManager
import com.rar.hearth.notify.PushNotificationStore
import com.rar.hearth.update.UpdateStage
import com.rar.hearth.update.UpdateStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ConfigServerUpdateTest {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient()
    private lateinit var server: ConfigServer
    private lateinit var base: String

    private val startCalls = mutableListOf<String>()
    private var startResult = true
    private var status = UpdateStatus()

    private fun tempDir(): File =
        File.createTempFile("cfgupd", "").let { it.delete(); it.mkdirs(); it }

    @Before
    fun setUp() {
        server = ConfigServer(
            port = 0,
            store = ConfigStore(tempDir()),
            sessions = SessionManager(random = Random(1)),
            pin = { "123456" },
            notifyToken = { "testtoken" },
            deviceName = { "Hearth (Pixel 1234)" },
            setDeviceName = { },
            pushStore = PushNotificationStore(),
            entitiesJson = { "[]" },
            setup = SetupCoordinator(
                AuthManager(InMemorySettingsStore(), OkHttpClient()), onConfigured = {}),
            configured = { true },
            connState = { "CONNECTED" },
            appVersion = { "0.2.514+abc1234" },
            appVersionCode = { 514 },
            startUpdate = { url -> startCalls += url; startResult },
            updateStatus = { status },
            previewChime = { _, _ -> },
            previewEarcon = { },
            assetReader = { null },
        )
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() = server.stop()

    private fun cookie(): String =
        http.newCall(Request.Builder().url("$base/api/login")
            .post("""{"pin":"123456"}""".toRequestBody(json)).build()).execute().use {
            it.header("Set-Cookie")!!.substringBefore(";")
        }

    private fun get(path: String, c: String) =
        http.newCall(Request.Builder().url("$base$path").header("Cookie", c).build()).execute()

    private fun post(path: String, c: String, body: String) =
        http.newCall(Request.Builder().url("$base$path").header("Cookie", c)
            .post(body.toRequestBody(json)).build()).execute()

    @Test
    fun statusCarriesTheVersionCodeSoTheBrowserCanCompare() {
        val c = cookie()
        get("/api/status", c).use { r ->
            assertEquals(200, r.code)
            val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
            assertEquals(514, o["appVersionCode"]!!.jsonPrimitive.content.toInt())
            assertEquals("0.2.514+abc1234", o["appVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun postStartsAnUpdateAndPassesTheUrlThrough() {
        val c = cookie()
        val url = "https://github.com/RAR/hearth/releases/download/v0.2.515/hearth.apk"
        post("/api/update", c, """{"url":"$url"}""").use { r ->
            assertEquals(200, r.code)
        }
        assertEquals(listOf(url), startCalls)
    }

    @Test
    fun postReportsRejectionWithoutPretendingItStarted() {
        // The updater refuses a disallowed URL or a second concurrent request; the endpoint
        // must surface that as an error, not a 200 the UI would treat as "started".
        startResult = false
        val c = cookie()
        post("/api/update", c, """{"url":"https://evil.example/x.apk"}""").use { r ->
            assertEquals(409, r.code)
        }
    }

    @Test
    fun postRejectsAMissingOrMalformedBodyWithoutCallingTheUpdater() {
        val c = cookie()
        post("/api/update", c, """{"nope":1}""").use { r -> assertEquals(400, r.code) }
        post("/api/update", c, """not json""").use { r -> assertEquals(400, r.code) }
        assertTrue("updater must not be called for a malformed request", startCalls.isEmpty())
    }

    @Test
    fun getReportsTheCurrentStage() {
        status = UpdateStatus(
            stage = UpdateStage.DOWNLOADING, versionName = "0.2.515+def", progressPct = 37)
        val c = cookie()
        get("/api/update", c).use { r ->
            assertEquals(200, r.code)
            val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
            assertEquals("downloading", o["state"]!!.jsonPrimitive.content)
            assertEquals(37, o["progressPct"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun updateEndpointsRequireASession() {
        // An unauthenticated LAN client must not be able to trigger an install.
        http.newCall(Request.Builder().url("$base/api/update")
            .post("""{"url":"https://github.com/RAR/hearth/releases/download/v1/x.apk"}"""
                .toRequestBody(json)).build()).execute().use { r ->
            assertEquals(401, r.code)
        }
        assertTrue(startCalls.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew testDebugUnitTest --tests '*ConfigServerUpdateTest*'`
Expected: FAIL — `ConfigServer` has no `appVersionCode`, `startUpdate`, or `updateStatus` parameters.

- [ ] **Step 3: Add the constructor parameters and routes**

In `ConfigServer.kt`, add these constructor parameters immediately after the existing `appVersion` parameter (around line 45). Defaults keep every existing test and `App.kt` compiling unchanged:

```kotlin
    private val appVersionCode: () -> Int = { 0 },
    /** Returns false when the URL is not allowlisted or an update is already running. */
    private val startUpdate: (String) -> Boolean = { false },
    private val updateStatus: () -> com.rar.hearth.update.UpdateStatus =
        { com.rar.hearth.update.UpdateStatus() },
```

In `handleStatus()`, add one line after the existing `appVersion` line:

```kotlin
            put("appVersionCode", appVersionCode())   // monotonic; what the web UI compares
```

In `route()`, add these two lines to the session-gated `when` block, beside `uri == "/api/status"`:

```kotlin
                uri == "/api/update" && method == Method.GET -> ok(updateStatus().toJson())
                uri == "/api/update" && method == Method.POST -> handleStartUpdate(session)
```

And add the handler beside `handleStatus`:

```kotlin
    /**
     * Session-gated. Starts an update from a release URL. The URL is validated inside the
     * updater (isAllowedApkUrl) rather than here, so the allowlist has exactly one home.
     */
    private fun handleStartUpdate(session: IHTTPSession): Response {
        val obj = runCatching {
            ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject
        }.getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        val url = obj["url"]?.jsonPrimitive?.contentOrNull
            ?: return error(Response.Status.BAD_REQUEST, "missing url")
        return if (startUpdate(url)) ok("""{"ok":true}""")
        else error(Response.Status.CONFLICT, "update rejected")
    }
```

Add `import com.rar.hearth.update.UpdateStatus` if the fully-qualified names above are replaced with a short form.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew testDebugUnitTest --tests '*ConfigServerUpdateTest*'`
Expected: PASS, 6 tests. Also run the existing suite — the defaults must have kept it green:

Run: `./gradlew testDebugUnitTest --tests '*ConfigServerTest*'`
Expected: PASS.

- [ ] **Step 5: Wire it in AppDeps**

In `AppDeps.kt`, construct the updater near the other long-lived subsystems, then pass it to `ConfigServer` beside the existing `appVersion = { BuildConfig.VERSION_NAME },` line:

```kotlin
    val apkUpdater = com.rar.hearth.update.ApkUpdater(
        context = appContext,
        http = httpClient,
        scope = appScope,
        currentVersionCode = BuildConfig.VERSION_CODE,
    )
```

```kotlin
        appVersionCode = { BuildConfig.VERSION_CODE },
        startUpdate = { url -> apkUpdater.start(url) },
        updateStatus = { apkUpdater.status.value },
```

Use whatever the surrounding code already calls the OkHttp client and the application-scope `CoroutineScope` — check the neighbouring constructor calls in `AppDeps.kt` and match them rather than introducing new names.

- [ ] **Step 6: Run the gate and commit**

```bash
./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"
git add app/src/main/java/com/rar/hearth/web/ConfigServer.kt \
        app/src/main/java/com/rar/hearth/AppDeps.kt \
        app/src/test/java/com/rar/hearth/web/ConfigServerUpdateTest.kt
git commit -m "feat(update): appVersionCode in status, /api/update endpoints"
```

---

### Task 6: Web UI — latest version and the Update button

**Files:**
- Modify: `app/src/main/assets/config/app.js` (the `renderDiag()` function, around line 1421)

**Interfaces:**
- Consumes: `lastStatus.appVersion` and the new `lastStatus.appVersionCode` from Task 5; `GET`/`POST /api/update` from Task 5.
- Produces: nothing consumed by later tasks.

Existing helpers to reuse, not reinvent: `el(tag, cls, text)`, `labeledRow(labelText, control)`, `clear(node)`, `api(method, path, body)`.

- [ ] **Step 1: Add the release check and the button**

In `app/src/main/assets/config/app.js`, inside `renderDiag()`, immediately after the existing `host.appendChild(labeledRow("App version", version));` line, add:

```js
  // Latest release. Asked of GitHub by the BROWSER, not the device: api.github.com is
  // CORS-clean, so this works even when the display itself has no route to the internet.
  const latest = el("span", "status info", "checking…");
  host.appendChild(labeledRow("Latest version", latest));

  const updateRow = el("div", "row");
  const updateBtn = el("button", "ghost", "Update");
  updateBtn.type = "button";
  updateBtn.disabled = true;
  updateRow.appendChild(updateBtn);
  const updateMsg = el("span", "status info", "");
  updateRow.appendChild(updateMsg);
  host.appendChild(updateRow);

  checkForUpdate(latest, updateBtn, updateMsg);
```

Then add these functions at the top level of the file, next to the other `renderDiag` helpers (e.g. just above `function renderDiag()`):

```js
// Tags are exactly v<major>.<minor>.<versionCode> -- the last part is the versionCode.
// Anything else is "unknown", never 0: a 0 would read as older than everything and
// wrongly enable the button.
function parseTagVersionCode(tag) {
  const m = /^v\d+\.\d+\.(\d+)$/.exec(tag || "");
  return m ? parseInt(m[1], 10) : null;
}

async function checkForUpdate(latestEl, btn, msgEl) {
  let release;
  try {
    const r = await fetch("https://api.github.com/repos/RAR/hearth/releases/latest");
    if (!r.ok) throw new Error("http " + r.status);
    release = await r.json();
  } catch (e) {
    // A display with no internet is a valid configuration, not an error state.
    latestEl.textContent = "unavailable";
    return;
  }
  const latestCode = parseTagVersionCode(release.tag_name);
  if (latestCode == null) { latestEl.textContent = "unavailable"; return; }
  latestEl.textContent = release.tag_name;

  const currentCode = lastStatus && lastStatus.appVersionCode;
  const dirty = !!(lastStatus && lastStatus.appVersion &&
                   lastStatus.appVersion.endsWith(".dirty"));
  if (typeof currentCode !== "number") return;

  // A .dirty build at the release's own code is not that release -- offer it anyway.
  const available = latestCode > currentCode || (latestCode === currentCode && dirty);
  const asset = (release.assets || []).find(a => (a.name || "").endsWith(".apk"));
  if (!available) { btn.textContent = "Up to date"; return; }
  if (!asset) { msgEl.textContent = "release has no APK attached"; return; }

  btn.disabled = false;
  btn.addEventListener("click", () => startUpdate(asset.browser_download_url, btn, msgEl));
}

async function startUpdate(url, btn, msgEl) {
  btn.disabled = true;
  msgEl.textContent = "starting…";
  const r = await api("POST", "/api/update", { url: url });
  if (!r.ok) {
    msgEl.textContent = "could not start (" + r.status + ")";
    btn.disabled = false;
    return;
  }
  pollUpdate(btn, msgEl);
}

async function pollUpdate(btn, msgEl) {
  const r = await api("GET", "/api/update");
  if (!r.ok) { msgEl.textContent = "lost contact with the device"; return; }
  const s = await r.json();
  if (s.state === "downloading") {
    msgEl.textContent = "downloading " + (s.progressPct || 0) + "%";
  } else if (s.state === "verifying") {
    msgEl.textContent = "verifying…";
  } else if (s.state === "awaiting_confirmation") {
    // The one message that matters: the dialog is on the DEVICE, not in this browser.
    msgEl.textContent = "Confirm the install on the device's screen.";
    return;
  } else if (s.state === "failed") {
    msgEl.textContent = "failed: " + (s.error || "unknown");
    btn.disabled = false;
    return;
  } else {
    msgEl.textContent = "";
    return;
  }
  setTimeout(() => pollUpdate(btn, msgEl), 1000);
}
```

- [ ] **Step 2: Syntax-check the JS**

Run: `node --check app/src/main/assets/config/app.js`
Expected: no output, exit 0.

- [ ] **Step 3: Run the gate and commit**

```bash
./gradlew testDebugUnitTest assembleDebug; echo "RC=$?"
node --check app/src/main/assets/config/app.js; echo "JS_RC=$?"
git add app/src/main/assets/config/app.js
git commit -m "feat(web): latest-version row and Update button on the Device page"
```

---

### Task 7: Release workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: repo secrets `HEARTH_KEYSTORE` (base64 of the `.jks`) and `HEARTH_KEYSTORE_PASSWORD`, both already set.
- Produces: a GitHub release per `v*` tag with `hearth-<tag>.apk` attached — the asset the web UI in Task 6 downloads.

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/release.yml`:

```yaml
name: release

on:
  push:
    tags: ['v*']
  workflow_dispatch:

permissions:
  contents: write

jobs:
  release:
    name: Publish signed APK
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          # Full history: versionCode is the commit count (app/build.gradle.kts).
          # A shallow clone would pin the build to versionCode 1 and the tag check below
          # would fail for the right reason but the wrong cause.
          fetch-depth: 0

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Decode signing keystore
        env:
          KEYSTORE_B64: ${{ secrets.HEARTH_KEYSTORE }}
        run: |
          if [ -z "$KEYSTORE_B64" ]; then
            echo "::error::HEARTH_KEYSTORE is not set -- a release signed with a generated key can never update a device"
            exit 1
          fi
          printf '%s' "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/hearth.jks"

      - name: Build + unit tests
        env:
          HEARTH_KEYSTORE: ${{ runner.temp }}/hearth.jks
          HEARTH_KEYSTORE_PASSWORD: ${{ secrets.HEARTH_KEYSTORE_PASSWORD }}
        run: ./gradlew test assembleDebug --no-daemon --stacktrace

      - name: Android Lint
        run: ./gradlew lintDebug --no-daemon --stacktrace

      - name: Verify APK signer
        run: |
          APKSIGNER=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | tail -1)
          GOT=$("$APKSIGNER" verify --print-certs app/build/outputs/apk/debug/app-debug.apk \
                | grep -m1 -i "certificate SHA-256" | awk '{print $NF}')
          echo "signer: $GOT"
          if [ "$GOT" != "1179d10d7aeb35970cd677e8711c68cb7b450a72dc9fc199aac6a505157f9a53" ]; then
            echo "::error::APK is not signed with the Hearth keystore -- it cannot update a device in place"
            exit 1
          fi

      # The web UI reads the versionCode out of the TAG. If the tag disagrees with the APK,
      # the button would either never light up or offer a build it misidentifies. Fail here
      # rather than publish a release that can never be installed correctly.
      - name: Verify the tag matches the built versionCode
        run: |
          TAG="${GITHUB_REF_NAME}"
          TAG_CODE="${TAG##*.}"
          # versionCode IS the commit count -- app/build.gradle.kts derives it with exactly
          # this command. Do not try to read it back out of Gradle: versionCode lives on the
          # Android extension, not as a Gradle project property, so `gradlew properties`
          # does not print it.
          APK_CODE=$(git rev-list --count HEAD)
          echo "tag=$TAG tag_code=$TAG_CODE apk_code=$APK_CODE"
          if [ "$TAG_CODE" != "$APK_CODE" ]; then
            echo "::error::tag $TAG encodes versionCode $TAG_CODE but the build is $APK_CODE"
            exit 1
          fi

      - name: Stage the APK under its release name
        run: cp app/build/outputs/apk/debug/app-debug.apk "hearth-${GITHUB_REF_NAME}.apk"

      - name: Publish release
        uses: softprops/action-gh-release@v2
        with:
          files: hearth-${{ github.ref_name }}.apk
          generate_release_notes: true
```

- [ ] **Step 2: Validate the YAML**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml')); print('valid')"`
Expected: `valid`

- [ ] **Step 3: Commit and push**

```bash
git add .github/workflows/release.yml
git commit -m "ci: publish a signed release APK on v* tags"
git push origin master
```

- [ ] **Step 4: Cut the first release and watch it**

```bash
CODE=$(git rev-list --count HEAD)
git tag "v0.2.$CODE"
git push origin "v0.2.$CODE"
gh run watch "$(gh run list --workflow=release.yml --limit 1 --json databaseId -q '.[0].databaseId')" --exit-status
```

Expected: the run passes, including the signer check and the tag/versionCode check.

- [ ] **Step 5: Confirm the release is real and correctly signed**

```bash
curl -s https://api.github.com/repos/RAR/hearth/releases/latest \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['tag_name'], [a['name'] for a in d['assets']])"
```

Expected: the tag you pushed, and one `hearth-v0.2.<code>.apk` asset.

---

### Task 8: Live verification

**Files:** none — this is the manual pass that unit tests structurally cannot cover.

**Interfaces:**
- Consumes: everything from Tasks 1–7.
- Produces: nothing.

Do this on **crown (10.75.1.139)** — the Show 8, which is adb-reachable, has a working `screencap`, and carries the most configuration. Do **not** do it on the Kitchen Echo (10.75.1.98): it is mid wake-capture run, still on `com.rar.echodash`, and must not be reinstalled.

- [ ] **Step 1: Flash the pre-release build and grant the install permission**

```bash
./gradlew assembleDebug
adb -s 10.75.1.139:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10.75.1.139:5555 shell appops set com.rar.hearth REQUEST_INSTALL_PACKAGES allow
adb -s 10.75.1.139:5555 shell monkey -p com.rar.hearth -c android.intent.category.LAUNCHER 1
```

The `appops` grant is required on API 26+ and is not covered by the manifest permission alone.

- [ ] **Step 2: Confirm the page shows both versions**

Open `http://10.75.1.139:8080` (PIN `2016`) → Device. Expected: `App version` shows the running build, `Latest version` shows the release tag from Task 7, and the `Update` button is enabled only if the release is newer.

To force the button on, flash a deliberately older build first:

```bash
git stash && git checkout HEAD~3 && ./gradlew assembleDebug \
  && adb -s 10.75.1.139:5555 install -r app/build/outputs/apk/debug/app-debug.apk \
  && git checkout - && git stash pop
```

- [ ] **Step 3: Press Update and watch both screens**

Expected, in order: the browser shows `downloading N%`, then `verifying…`, then **"Confirm the install on the device's screen."** The Android install dialog appears **on the device**. Confirm it there.

- [ ] **Step 4: Verify the restart receiver actually fires**

This is the specific thing that was broken before this plan existed. After confirming the install, **do not touch the device or adb**. Wait 30 seconds, then:

```bash
adb -s 10.75.1.139:5555 shell pidof com.rar.hearth
```

Expected: a non-empty pid, and the dashboard visible on screen. An empty result means `PackageReplacedReceiver` did not fire — the update is an outage, and Task 4 needs fixing.

- [ ] **Step 5: Verify nothing was lost**

```bash
curl -s -c /tmp/j -H "Content-Type: application/json" -d '{"pin":"2016"}' \
  http://10.75.1.139:8080/api/login >/dev/null
curl -s -b /tmp/j http://10.75.1.139:8080/api/status
```

Expected: `configured: true`, `connState: CONNECTED`, `deviceName: Andrew's Desk`, and `appVersion` now the release build. The whole point of the stable keystore is that an update keeps these.

- [ ] **Step 6: Confirm the button settles**

Reload the config page. Expected: `Update` now reads `Up to date` and is disabled.

- [ ] **Step 7: Record the result**

Append the outcome to `.superpowers/sdd/progress.md`, naming anything that failed and what was done about it.

---

## Notes for the implementer

**The two things most likely to bite:**

1. **`appops` is a separate grant.** `REQUEST_INSTALL_PACKAGES` in the manifest is necessary but not sufficient on API 26+. Without the `appops` command in Task 8 Step 1 the install silently does nothing, and it looks like a bug in the updater.
2. **The app does not restart itself.** This was measured on a live device: after an in-place install, `pidof` was empty until the app was launched by hand. Task 4's receiver is the fix and Task 8 Step 4 is the only check that proves it. Verifying over adb *after* having relaunched the app by hand would hide exactly the failure that matters.

**Out of scope, deliberately** — do not add these:

- Silent/unattended install (root or device-owner). The Shelly has an Android account, which blocks device owner outright.
- Automatic or scheduled updating. Every update is a deliberate per-device act; that is what keeps a bad commit from sweeping every display.
- Downgrade/rollback. `versionCode` is monotonic and Android refuses backwards in-place installs; recovery stays `adb install -r`.
- Uploading an APK from the browser.
