# Echo HA Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Native Android kiosk app for an Echo Show 5 (LineageOS 18.1) that logs into Home Assistant via OAuth2, registers as a `mobile_app` device, and shows a fullscreen dashboard with background, clock, and a live temperature.

**Architecture:** Single-module Kotlin app. Three layers: `ha/` (OAuth token client, mobile_app registration, OkHttp WebSocket with reconnect), `data/` (SettingsStore over EncryptedSharedPreferences), `ui/` (three Compose screens: Setup → EntityPicker → Dashboard, navigated by a simple state machine — no nav library).

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.3, Gradle 8.11.1, Jetpack Compose (BOM 2024.12.01), OkHttp 4.12.0, kotlinx-serialization-json 1.7.3, coroutines 1.9.0, JUnit4 + MockWebServer for JVM tests.

## Global Constraints

- Repo root: `/home/rar/android_simpla_ha_dash` (all paths below are relative to it). Already a git repo.
- `minSdk = 28`, `targetSdk = 34`, `compileSdk = 34`, `applicationId = "com.rar.echodash"`, app label **"Echo Dashboard"**.
- JDK on this machine is Corretto 21 (works with AGP 8.7); Kotlin `jvmTarget = "17"`.
- Android SDK will live at `/home/rar/android-sdk` (installed in Task 1). Gradle dist at `/home/rar/gradle-dist`. Build with `./gradlew` from repo root; JVM heap: `org.gradle.jvmargs=-Xmx4g` in `gradle.properties`.
- OAuth constants (HA convention, same pair the Companion app uses): `CLIENT_ID = "https://home-assistant.io/android"`, `REDIRECT_URI = "homeassistant://auth-callback"`. The redirect never leaves our WebView.
- All unit tests are plain-JVM (no Robolectric, no instrumented tests). UI composables are verified manually on-device later; keep pure logic (parsing, url munging, staleness, backoff) in top-level functions so it's JVM-testable.
- `android:usesCleartextTraffic="true"` (HA on LAN is usually plain http).
- No adb device is attached tonight — on-device verification is explicitly deferred; each task verifies via `./gradlew test`/`assembleDebug` only.
- Commit after every task with the message given in the task.

## File structure (final state)

```
gradle.properties, settings.gradle.kts, build.gradle.kts, local.properties, .gitignore
gradlew, gradle/wrapper/*
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/drawable/ic_launcher.xml
app/src/main/java/com/rar/echodash/
  MainActivity.kt          — entry, kiosk flags, sets EchoDashApp content
  BootReceiver.kt          — best-effort start on boot
  App.kt                   — AppDeps + EchoDashApp screen state machine
  data/SettingsStore.kt    — interface + InMemorySettingsStore + PrefsSettingsStore
  ha/WsMessages.kt         — WsIncoming/EntityPatch/EntityState + WsParser (pure)
  ha/AuthManager.kt        — OAuth exchange/refresh, AuthRevokedException
  ha/RegistrationClient.kt — mobile_app registration
  ha/HaWebSocket.kt        — connection manager, backoffMs(), wsUrl()
  ui/SetupScreen.kt        — URL entry + AuthWebView, normalizeBaseUrl()
  ui/EntityPickerScreen.kt
  ui/DashboardScreen.kt    — clock, temp, DuskBackground, isStale(), formatTime()
app/src/test/java/com/rar/echodash/
  ha/WsParserTest.kt, ha/AuthManagerTest.kt, ha/RegistrationClientTest.kt,
  ha/HaWebSocketTest.kt, data/SettingsStoreTest.kt,
  ui/SetupLogicTest.kt, ui/DashboardLogicTest.kt
```

---

### Task 1: Toolchain install + buildable app skeleton

**Files:**
- Create: `.gitignore`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `local.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/drawable/ic_launcher.xml`, `app/src/main/java/com/rar/echodash/MainActivity.kt`, `app/src/main/java/com/rar/echodash/BootReceiver.kt`
- Create (outside repo): Android SDK at `/home/rar/android-sdk`, Gradle at `/home/rar/gradle-dist`, wrapper files in repo

**Interfaces:**
- Consumes: nothing
- Produces: a project where `./gradlew assembleDebug` and `./gradlew test` succeed; later tasks only add Kotlin files (MainActivity is REPLACED wholesale in Task 10)

- [ ] **Step 1: Install Android SDK command-line tools + platform** (skip any piece that already exists)

```bash
cd /tmp/claude-1000/-home-rar-android-simpla-ha-dash/d000a73b-ee34-49d1-9dfa-da8abd6cc32d/scratchpad
curl -sSL -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p /home/rar/android-sdk/cmdline-tools
unzip -q -o cmdtools.zip -d /home/rar/android-sdk/cmdline-tools
mv /home/rar/android-sdk/cmdline-tools/cmdline-tools /home/rar/android-sdk/cmdline-tools/latest
yes | /home/rar/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
/home/rar/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```
Expected: sdkmanager prints `100% Computing updates...` style output, exits 0.

- [ ] **Step 2: Install Gradle and generate the wrapper**

```bash
curl -sSL -o /tmp/claude-1000/-home-rar-android-simpla-ha-dash/d000a73b-ee34-49d1-9dfa-da8abd6cc32d/scratchpad/gradle.zip https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
mkdir -p /home/rar/gradle-dist && unzip -q -o /tmp/claude-1000/-home-rar-android-simpla-ha-dash/d000a73b-ee34-49d1-9dfa-da8abd6cc32d/scratchpad/gradle.zip -d /home/rar/gradle-dist
cd /home/rar/android_simpla_ha_dash
/home/rar/gradle-dist/gradle-8.11.1/bin/gradle wrapper --gradle-version 8.11.1
```
Expected: `BUILD SUCCESSFUL`; `gradlew`, `gradle/wrapper/` appear.

- [ ] **Step 3: Write build configuration files**

`.gitignore`:
```
.gradle/
build/
local.properties
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`local.properties`:
```properties
sdk.dir=/home/rar/android-sdk
```

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "EchoDash"
include(":app")
```

`build.gradle.kts` (root):
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}
```

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.rar.echodash"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.rar.echodash"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 4: Write manifest, icon, MainActivity placeholder, BootReceiver**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <application
        android:label="Echo Dashboard"
        android:icon="@drawable/ic_launcher"
        android:usesCleartextTraffic="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|keyboard|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

`app/src/main/res/drawable/ic_launcher.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp" android:height="48dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF2B2E4A" android:pathData="M0,0h24v24H0z"/>
    <path android:fillColor="#FFFFFFFF" android:pathData="M12,3L2,12h3v8h14v-8h3L12,3z"/>
</vector>
```

`app/src/main/java/com/rar/echodash/MainActivity.kt` (placeholder — replaced in Task 10):
```kotlin
package com.rar.echodash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("EchoDash") }
    }
}
```

`app/src/main/java/com/rar/echodash/BootReceiver.kt`:
```kotlin
package com.rar.echodash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Best-effort: on API 29+ background activity starts are blocked unless this app
// is the default HOME launcher — which is the supported kiosk configuration.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
```

- [ ] **Step 5: Build**

Run: `cd /home/rar/android_simpla_ha_dash && ./gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: buildable app skeleton with kiosk manifest (launcher/HOME/boot)"
```

---

### Task 2: WebSocket message types + parser (pure JVM)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ha/WsMessages.kt`
- Test: `app/src/test/java/com/rar/echodash/ha/WsParserTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `sealed interface WsIncoming` (`AuthRequired`, `AuthOk`, `AuthInvalid(message)`, `EntityUpdate(states: Map<String, EntityPatch>)`, `Result(id: Int, success: Boolean, result: JsonElement?)`, `Unknown(type)`); `data class EntityPatch(state: String?, unit: String?, friendlyName: String?)`; `data class EntityState(entityId: String, state: String, unit: String?, friendlyName: String?)`; `object WsParser { fun parse(text: String): WsIncoming; fun temperatureSensors(result: JsonElement): List<EntityState> }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/ha/WsParserTest.kt`:
```kotlin
package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WsParserTest {

    @Test
    fun parsesAuthHandshakeMessages() {
        assertEquals(WsIncoming.AuthRequired, WsParser.parse("""{"type":"auth_required","ha_version":"2025.1.0"}"""))
        assertEquals(WsIncoming.AuthOk, WsParser.parse("""{"type":"auth_ok","ha_version":"2025.1.0"}"""))
        assertEquals(WsIncoming.AuthInvalid("Invalid access token"),
            WsParser.parse("""{"type":"auth_invalid","message":"Invalid access token"}"""))
    }

    @Test
    fun parsesEntityAddEvent() {
        val msg = WsParser.parse(
            """{"id":1,"type":"event","event":{"a":{"sensor.outside_temperature":
               {"s":"15.6","a":{"unit_of_measurement":"°C","friendly_name":"Outside Temperature","device_class":"temperature"}}}}}"""
                .replace("\n", "").replace("               ", "")
        )
        val update = msg as WsIncoming.EntityUpdate
        assertEquals(
            EntityPatch(state = "15.6", unit = "°C", friendlyName = "Outside Temperature"),
            update.states["sensor.outside_temperature"]
        )
    }

    @Test
    fun parsesEntityChangeEvent() {
        val msg = WsParser.parse(
            """{"id":1,"type":"event","event":{"c":{"sensor.outside_temperature":{"+":{"s":"16.0","lc":1720000000}}}}}"""
        )
        val update = msg as WsIncoming.EntityUpdate
        assertEquals(
            EntityPatch(state = "16.0", unit = null, friendlyName = null),
            update.states["sensor.outside_temperature"]
        )
    }

    @Test
    fun parsesResultMessage() {
        val msg = WsParser.parse("""{"id":7,"type":"result","success":true,"result":[1,2]}""")
        val result = msg as WsIncoming.Result
        assertEquals(7, result.id)
        assertTrue(result.success)
    }

    @Test
    fun filtersTemperatureSensorsFromGetStates() {
        val states = Json.parseToJsonElement(
            """[
              {"entity_id":"sensor.outside_temperature","state":"15.6","attributes":{"device_class":"temperature","unit_of_measurement":"°C","friendly_name":"Outside Temperature"}},
              {"entity_id":"sensor.outside_temperature_battery","state":"12","attributes":{"device_class":"battery"}},
              {"entity_id":"light.kitchen","state":"on","attributes":{}},
              {"entity_id":"sensor.no_attrs","state":"x","attributes":{}}
            ]"""
        )
        val sensors = WsParser.temperatureSensors(states)
        assertEquals(1, sensors.size)
        assertEquals(
            EntityState("sensor.outside_temperature", "15.6", "°C", "Outside Temperature"),
            sensors[0]
        )
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ha.WsParserTest" --console=plain`
Expected: compilation FAILURE (`WsParser` unresolved).

- [ ] **Step 3: Implement**

`app/src/main/java/com/rar/echodash/ha/WsMessages.kt`:
```kotlin
package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface WsIncoming {
    data object AuthRequired : WsIncoming
    data object AuthOk : WsIncoming
    data class AuthInvalid(val message: String) : WsIncoming
    data class EntityUpdate(val states: Map<String, EntityPatch>) : WsIncoming
    data class Result(val id: Int, val success: Boolean, val result: JsonElement?) : WsIncoming
    data class Unknown(val type: String) : WsIncoming
}

/** Partial entity state from a subscribe_entities event; null field = unchanged. */
data class EntityPatch(val state: String?, val unit: String?, val friendlyName: String?)

/** Full entity state from get_states. */
data class EntityState(
    val entityId: String,
    val state: String,
    val unit: String?,
    val friendlyName: String?,
)

object WsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): WsIncoming {
        val obj = json.parseToJsonElement(text).jsonObject
        return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> WsIncoming.AuthRequired
            "auth_ok" -> WsIncoming.AuthOk
            "auth_invalid" -> WsIncoming.AuthInvalid(
                obj["message"]?.jsonPrimitive?.contentOrNull ?: "auth failed"
            )
            "event" -> parseEntityEvent(obj)
            "result" -> WsIncoming.Result(
                id = obj["id"]?.jsonPrimitive?.int ?: -1,
                success = obj["success"]?.jsonPrimitive?.boolean ?: false,
                result = obj["result"],
            )
            else -> WsIncoming.Unknown(type ?: "?")
        }
    }

    private fun parseEntityEvent(obj: JsonObject): WsIncoming {
        val event = obj["event"]?.jsonObject ?: return WsIncoming.Unknown("event")
        val patches = mutableMapOf<String, EntityPatch>()
        event["a"]?.jsonObject?.forEach { (id, v) -> patches[id] = patchOf(v.jsonObject) }
        event["c"]?.jsonObject?.forEach { (id, v) ->
            v.jsonObject["+"]?.jsonObject?.let { patches[id] = patchOf(it) }
        }
        return WsIncoming.EntityUpdate(patches)
    }

    private fun patchOf(e: JsonObject): EntityPatch {
        val attrs = e["a"]?.jsonObject
        return EntityPatch(
            state = e["s"]?.jsonPrimitive?.contentOrNull,
            unit = attrs?.get("unit_of_measurement")?.jsonPrimitive?.contentOrNull,
            friendlyName = attrs?.get("friendly_name")?.jsonPrimitive?.contentOrNull,
        )
    }

    fun temperatureSensors(result: JsonElement): List<EntityState> =
        result.jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["entity_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!id.startsWith("sensor.")) return@mapNotNull null
            val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            if (attrs["device_class"]?.jsonPrimitive?.contentOrNull != "temperature") return@mapNotNull null
            EntityState(
                entityId = id,
                state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "?",
                unit = attrs["unit_of_measurement"]?.jsonPrimitive?.contentOrNull,
                friendlyName = attrs["friendly_name"]?.jsonPrimitive?.contentOrNull,
            )
        }
}
```

- [ ] **Step 4: Run tests, verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ha.WsParserTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: HA websocket message parser with entity-event and get_states support"
```

---

### Task 3: SettingsStore

**Files:**
- Create: `app/src/main/java/com/rar/echodash/data/SettingsStore.kt`
- Test: `app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
```kotlin
interface SettingsStore {
    var baseUrl: String?
    var accessToken: String?
    var accessTokenExpiresAt: Long   // epoch ms, 0 = unset
    var refreshToken: String?
    var webhookId: String?
    var temperatureEntityId: String?
    fun clearAuth()   // clears tokens + expiry + webhookId; KEEPS baseUrl and temperatureEntityId
}
class InMemorySettingsStore : SettingsStore
class PrefsSettingsStore(context: Context) : SettingsStore   // EncryptedSharedPreferences
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt`:
```kotlin
package com.rar.echodash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun roundTripsAllFields() {
        val s: SettingsStore = InMemorySettingsStore()
        s.baseUrl = "http://ha.local:8123"
        s.accessToken = "at"
        s.accessTokenExpiresAt = 123L
        s.refreshToken = "rt"
        s.webhookId = "wh"
        s.temperatureEntityId = "sensor.outside_temperature"
        assertEquals("http://ha.local:8123", s.baseUrl)
        assertEquals("at", s.accessToken)
        assertEquals(123L, s.accessTokenExpiresAt)
        assertEquals("rt", s.refreshToken)
        assertEquals("wh", s.webhookId)
        assertEquals("sensor.outside_temperature", s.temperatureEntityId)
    }

    @Test
    fun clearAuthKeepsUrlAndEntity() {
        val s: SettingsStore = InMemorySettingsStore()
        s.baseUrl = "http://ha.local:8123"
        s.accessToken = "at"
        s.accessTokenExpiresAt = 123L
        s.refreshToken = "rt"
        s.webhookId = "wh"
        s.temperatureEntityId = "sensor.x"
        s.clearAuth()
        assertNull(s.accessToken)
        assertEquals(0L, s.accessTokenExpiresAt)
        assertNull(s.refreshToken)
        assertNull(s.webhookId)
        assertEquals("http://ha.local:8123", s.baseUrl)
        assertEquals("sensor.x", s.temperatureEntityId)
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.data.SettingsStoreTest" --console=plain`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

`app/src/main/java/com/rar/echodash/data/SettingsStore.kt`:
```kotlin
package com.rar.echodash.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface SettingsStore {
    var baseUrl: String?
    var accessToken: String?
    var accessTokenExpiresAt: Long
    var refreshToken: String?
    var webhookId: String?
    var temperatureEntityId: String?
    fun clearAuth()
}

class InMemorySettingsStore : SettingsStore {
    override var baseUrl: String? = null
    override var accessToken: String? = null
    override var accessTokenExpiresAt: Long = 0L
    override var refreshToken: String? = null
    override var webhookId: String? = null
    override var temperatureEntityId: String? = null

    override fun clearAuth() {
        accessToken = null
        accessTokenExpiresAt = 0L
        refreshToken = null
        webhookId = null
    }
}

class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "echodash_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun string(key: String) = prefs.getString(key, null)
    private fun put(key: String, value: String?) =
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()

    override var baseUrl: String?
        get() = string("base_url"); set(v) = put("base_url", v)
    override var accessToken: String?
        get() = string("access_token"); set(v) = put("access_token", v)
    override var accessTokenExpiresAt: Long
        get() = prefs.getLong("access_token_expires_at", 0L)
        set(v) = prefs.edit().putLong("access_token_expires_at", v).apply()
    override var refreshToken: String?
        get() = string("refresh_token"); set(v) = put("refresh_token", v)
    override var webhookId: String?
        get() = string("webhook_id"); set(v) = put("webhook_id", v)
    override var temperatureEntityId: String?
        get() = string("temperature_entity_id"); set(v) = put("temperature_entity_id", v)

    override fun clearAuth() {
        prefs.edit()
            .remove("access_token")
            .remove("access_token_expires_at")
            .remove("refresh_token")
            .remove("webhook_id")
            .apply()
    }
}
```

- [ ] **Step 4: Run tests, verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.data.SettingsStoreTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: settings store with encrypted prefs implementation"
```

---

### Task 4: AuthManager (OAuth2 exchange + refresh)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ha/AuthManager.kt`
- Test: `app/src/test/java/com/rar/echodash/ha/AuthManagerTest.kt`

**Interfaces:**
- Consumes: `SettingsStore` (Task 3)
- Produces:
```kotlin
class AuthRevokedException : Exception
class AuthManager(settings: SettingsStore, client: OkHttpClient, clock: () -> Long = System::currentTimeMillis) {
    companion object { const val CLIENT_ID = "https://home-assistant.io/android"
                       const val REDIRECT_URI = "homeassistant://auth-callback" }
    fun authorizeUrl(baseUrl: String): String
    suspend fun exchangeCode(code: String)          // stores access+refresh tokens in settings
    suspend fun validAccessToken(): String          // refreshes if <60s left; throws AuthRevokedException
}
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/ha/AuthManagerTest.kt`:
```kotlin
package com.rar.echodash.ha

import com.rar.echodash.data.InMemorySettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuthManagerTest {
    private val settings = InMemorySettingsStore()
    private val client = OkHttpClient()
    private var now = 1_000_000L

    private fun auth() = AuthManager(settings, client) { now }

    @Test
    fun authorizeUrlEncodesParams() {
        val url = auth().authorizeUrl("http://ha.local:8123")
        assertTrue(url.startsWith("http://ha.local:8123/auth/authorize?"))
        assertTrue(url.contains("client_id=https%3A%2F%2Fhome-assistant.io%2Fandroid"))
        assertTrue(url.contains("redirect_uri=homeassistant%3A%2F%2Fauth-callback"))
    }

    @Test
    fun exchangeCodeStoresTokens() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"access_token":"AT","refresh_token":"RT","expires_in":1800,"token_type":"Bearer"}"""))
            server.start()
            settings.baseUrl = server.url("/").toString().trimEnd('/')
            auth().exchangeCode("CODE123")
            assertEquals("AT", settings.accessToken)
            assertEquals("RT", settings.refreshToken)
            assertEquals(now + 1800_000L, settings.accessTokenExpiresAt)
            val req = server.takeRequest()
            assertEquals("/auth/token", req.path)
            val body = req.body.readUtf8()
            assertTrue(body.contains("grant_type=authorization_code"))
            assertTrue(body.contains("code=CODE123"))
        }
    }

    @Test
    fun validTokenReturnsCachedWhenFresh() = runBlocking {
        settings.accessToken = "AT"
        settings.accessTokenExpiresAt = now + 120_000L
        assertEquals("AT", auth().validAccessToken())
    }

    @Test
    fun validTokenRefreshesWhenExpired() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"access_token":"AT2","expires_in":1800,"token_type":"Bearer"}"""))
            server.start()
            settings.baseUrl = server.url("/").toString().trimEnd('/')
            settings.accessToken = "AT-old"
            settings.accessTokenExpiresAt = now + 10_000L  // < 60s margin
            settings.refreshToken = "RT"
            assertEquals("AT2", auth().validAccessToken())
            assertEquals("AT2", settings.accessToken)
            assertTrue(server.takeRequest().body.readUtf8().contains("grant_type=refresh_token"))
        }
    }

    @Test
    fun revokedRefreshClearsAuthAndThrows() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            server.start()
            settings.baseUrl = server.url("/").toString().trimEnd('/')
            settings.accessToken = null
            settings.refreshToken = "RT-revoked"
            try {
                auth().validAccessToken()
                fail("expected AuthRevokedException")
            } catch (e: AuthRevokedException) {
                assertNull(settings.refreshToken)
                assertNull(settings.accessToken)
            }
        }
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ha.AuthManagerTest" --console=plain`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

`app/src/main/java/com/rar/echodash/ha/AuthManager.kt`:
```kotlin
package com.rar.echodash.ha

import com.rar.echodash.data.SettingsStore
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class AuthRevokedException : Exception("Home Assistant refresh token rejected")

private class TokenRejectedException : Exception()

private data class TokenResponse(val accessToken: String, val refreshToken: String?, val expiresInSec: Long)

class AuthManager(
    private val settings: SettingsStore,
    private val client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val CLIENT_ID = "https://home-assistant.io/android"
        const val REDIRECT_URI = "homeassistant://auth-callback"
        private const val EXPIRY_MARGIN_MS = 60_000L
    }

    fun authorizeUrl(baseUrl: String): String =
        "$baseUrl/auth/authorize?client_id=${enc(CLIENT_ID)}&redirect_uri=${enc(REDIRECT_URI)}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun exchangeCode(code: String) {
        val tokens = tokenRequest(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .build()
        )
        store(tokens)
    }

    suspend fun validAccessToken(): String {
        val token = settings.accessToken
        if (token != null && clock() < settings.accessTokenExpiresAt - EXPIRY_MARGIN_MS) return token
        return refresh()
    }

    private suspend fun refresh(): String {
        val refreshToken = settings.refreshToken ?: throw AuthRevokedException()
        val tokens = try {
            tokenRequest(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", CLIENT_ID)
                    .build()
            )
        } catch (e: TokenRejectedException) {
            settings.clearAuth()
            throw AuthRevokedException()
        }
        store(tokens)
        return tokens.accessToken
    }

    private fun store(tokens: TokenResponse) {
        settings.accessToken = tokens.accessToken
        settings.accessTokenExpiresAt = clock() + tokens.expiresInSec * 1000
        tokens.refreshToken?.let { settings.refreshToken = it }
    }

    private suspend fun tokenRequest(body: FormBody): TokenResponse = withContext(Dispatchers.IO) {
        val base = settings.baseUrl ?: throw IOException("no base url configured")
        val request = Request.Builder().url("$base/auth/token").post(body).build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 400) throw TokenRejectedException()
            if (!resp.isSuccessful) throw IOException("token endpoint HTTP ${resp.code}")
            val obj = Json.parseToJsonElement(resp.body!!.string()).jsonObject
            TokenResponse(
                accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: throw IOException("no access_token in response"),
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
                expiresInSec = obj["expires_in"]?.jsonPrimitive?.long ?: 1800L,
            )
        }
    }
}
```

- [ ] **Step 4: Run tests, verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ha.AuthManagerTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: OAuth2 auth manager with token exchange, refresh, and revocation handling"
```

---

### Task 5: RegistrationClient (mobile_app device registration)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ha/RegistrationClient.kt`
- Test: `app/src/test/java/com/rar/echodash/ha/RegistrationClientTest.kt`

**Interfaces:**
- Consumes: `SettingsStore` (Task 3), `AuthManager.validAccessToken()` (Task 4)
- Produces:
```kotlin
data class DeviceInfo(val deviceName: String, val manufacturer: String, val model: String, val osVersion: String)
class RegistrationClient(settings: SettingsStore, auth: AuthManager, client: OkHttpClient) {
    suspend fun register(device: DeviceInfo)   // POSTs /api/mobile_app/registrations, stores settings.webhookId
}
```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/ha/RegistrationClientTest.kt`:
```kotlin
package com.rar.echodash.ha

import com.rar.echodash.data.InMemorySettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationClientTest {

    @Test
    fun registerPostsDeviceInfoAndStoresWebhookId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"webhook_id":"WH123","secret":null,"cloudhook_url":null,"remote_ui_url":null}"""))
            server.start()
            val settings = InMemorySettingsStore().apply {
                baseUrl = server.url("/").toString().trimEnd('/')
                accessToken = "AT"
                accessTokenExpiresAt = Long.MAX_VALUE
            }
            val client = OkHttpClient()
            val auth = AuthManager(settings, client) { 0L }
            RegistrationClient(settings, auth, client)
                .register(DeviceInfo("Echo Dashboard", "Amazon", "Echo Show 5", "11"))

            assertEquals("WH123", settings.webhookId)
            val req = server.takeRequest()
            assertEquals("/api/mobile_app/registrations", req.path)
            assertEquals("Bearer AT", req.getHeader("Authorization"))
            val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals("Echo Dashboard", body["device_name"]?.jsonPrimitive?.contentOrNull)
            assertEquals("com.rar.echodash", body["app_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("Echo Show 5", body["model"]?.jsonPrimitive?.contentOrNull)
        }
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ha.RegistrationClientTest" --console=plain`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

`app/src/main/java/com/rar/echodash/ha/RegistrationClient.kt`:
```kotlin
package com.rar.echodash.ha

import com.rar.echodash.data.SettingsStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class DeviceInfo(
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val osVersion: String,
)

class RegistrationClient(
    private val settings: SettingsStore,
    private val auth: AuthManager,
    private val client: OkHttpClient,
) {
    suspend fun register(device: DeviceInfo) = withContext(Dispatchers.IO) {
        val base = settings.baseUrl ?: throw IOException("no base url configured")
        val payload = buildJsonObject {
            put("device_id", device.deviceName.lowercase().replace(' ', '_'))
            put("app_id", "com.rar.echodash")
            put("app_name", "Echo Dashboard")
            put("app_version", "0.1")
            put("device_name", device.deviceName)
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("os_name", "Android")
            put("os_version", device.osVersion)
            put("supports_encryption", false)
        }
        val token = auth.validAccessToken()
        val request = Request.Builder()
            .url("$base/api/mobile_app/registrations")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("registration failed HTTP ${resp.code}")
            val obj = Json.parseToJsonElement(resp.body!!.string()).jsonObject
            settings.webhookId = obj["webhook_id"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("no webhook_id in registration response")
        }
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ha.RegistrationClientTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: mobile_app device registration client"
```

---

### Task 6: HaWebSocket connection manager

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ha/HaWebSocket.kt`
- Test: `app/src/test/java/com/rar/echodash/ha/HaWebSocketTest.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `AuthManager.validAccessToken()`/`AuthRevokedException`, `WsParser`/`WsIncoming`/`EntityPatch`/`EntityState`
- Produces:
```kotlin
enum class ConnState { CONNECTING, CONNECTED, OFFLINE, AUTH_FAILED }
data class TempReading(val value: String, val unit: String?, val updatedAtMs: Long)
fun wsUrl(baseUrl: String): String                  // http→ws, https→wss, + /api/websocket
fun backoffMs(attempt: Int): Long                   // 2s,4s,8s,... capped at 60s
class HaWebSocket(settings, auth, client: OkHttpClient, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis) {
    val connectionState: StateFlow<ConnState>
    val reading: StateFlow<TempReading?>
    fun start(entityId: String?)                    // (re)connect; subscribe if entityId != null
    fun stop()
    suspend fun fetchTemperatureSensors(): List<EntityState>  // waits for CONNECTED, get_states
}
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/ha/HaWebSocketTest.kt`:
```kotlin
package com.rar.echodash.ha

import com.rar.echodash.data.InMemorySettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class HaWebSocketTest {

    @Test
    fun wsUrlConversion() {
        assertEquals("ws://ha.local:8123/api/websocket", wsUrl("http://ha.local:8123"))
        assertEquals("wss://ha.example.com/api/websocket", wsUrl("https://ha.example.com"))
    }

    @Test
    fun backoffDoublesAndCaps() {
        assertEquals(2_000L, backoffMs(0))
        assertEquals(4_000L, backoffMs(1))
        assertEquals(32_000L, backoffMs(4))
        assertEquals(60_000L, backoffMs(5))
        assertEquals(60_000L, backoffMs(20))
    }

    /** Fake HA server: performs auth handshake, acks subscribe_entities, pushes one state. */
    private fun haServerListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"auth_required","ha_version":"2025.1.0"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = Json.parseToJsonElement(text).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "auth" -> webSocket.send("""{"type":"auth_ok","ha_version":"2025.1.0"}""")
                "subscribe_entities" -> {
                    val id = obj["id"]!!.jsonPrimitive.int
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    webSocket.send("""{"id":$id,"type":"event","event":{"a":{"sensor.outside_temperature":{"s":"15.6","a":{"unit_of_measurement":"°C"}}}}}""")
                }
                "get_states" -> {
                    val id = obj["id"]!!.jsonPrimitive.int
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":[{"entity_id":"sensor.outside_temperature","state":"15.6","attributes":{"device_class":"temperature","unit_of_measurement":"°C","friendly_name":"Outside Temperature"}}]}""")
                }
            }
        }
    }

    @Test
    fun connectsAuthenticatesSubscribesAndReceivesReading() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(haServerListener()))
            server.start()
            val settings = InMemorySettingsStore().apply {
                baseUrl = server.url("/").toString().trimEnd('/')
                accessToken = "AT"
                accessTokenExpiresAt = Long.MAX_VALUE
            }
            val client = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ws = HaWebSocket(settings, AuthManager(settings, client) { 0L }, client, scope) { 42L }
            try {
                ws.start("sensor.outside_temperature")
                val reading = withTimeout(10_000) { ws.reading.first { it != null } }!!
                assertEquals("15.6", reading.value)
                assertEquals("°C", reading.unit)
                assertEquals(42L, reading.updatedAtMs)
                assertEquals(ConnState.CONNECTED, ws.connectionState.value)
            } finally {
                ws.stop(); scope.cancel()
            }
        }
    }

    @Test
    fun fetchesTemperatureSensorsViaGetStates() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(haServerListener()))
            server.start()
            val settings = InMemorySettingsStore().apply {
                baseUrl = server.url("/").toString().trimEnd('/')
                accessToken = "AT"
                accessTokenExpiresAt = Long.MAX_VALUE
            }
            val client = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ws = HaWebSocket(settings, AuthManager(settings, client) { 0L }, client, scope)
            try {
                ws.start(null)
                val sensors = withTimeout(10_000) { ws.fetchTemperatureSensors() }
                assertEquals(1, sensors.size)
                assertEquals("sensor.outside_temperature", sensors[0].entityId)
            } finally {
                ws.stop(); scope.cancel()
            }
        }
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ha.HaWebSocketTest" --console=plain`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

`app/src/main/java/com/rar/echodash/ha/HaWebSocket.kt`:
```kotlin
package com.rar.echodash.ha

import com.rar.echodash.data.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnState { CONNECTING, CONNECTED, OFFLINE, AUTH_FAILED }

data class TempReading(val value: String, val unit: String?, val updatedAtMs: Long)

fun wsUrl(baseUrl: String): String = baseUrl.replaceFirst("http", "ws") + "/api/websocket"

fun backoffMs(attempt: Int): Long =
    (2_000L * (1L shl attempt.coerceAtMost(5))).coerceAtMost(60_000L)

class HaWebSocket(
    private val settings: SettingsStore,
    private val auth: AuthManager,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _connectionState = MutableStateFlow(ConnState.OFFLINE)
    val connectionState: StateFlow<ConnState> = _connectionState

    private val _reading = MutableStateFlow<TempReading?>(null)
    val reading: StateFlow<TempReading?> = _reading

    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null
    private val idCounter = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    @Volatile private var entityId: String? = null

    fun start(entityId: String?) {
        this.entityId = entityId
        job?.cancel()
        socket?.cancel()
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, null)
        socket = null
        _connectionState.value = ConnState.OFFLINE
    }

    suspend fun fetchTemperatureSensors(): List<EntityState> {
        connectionState.first { it == ConnState.CONNECTED }
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        socket?.send("""{"id":$id,"type":"get_states"}""")
            ?: run { pending.remove(id); return emptyList() }
        val result = deferred.await() ?: return emptyList()
        return WsParser.temperatureSensors(result)
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _connectionState.value = ConnState.CONNECTING
            val session = Session()
            try {
                val token = auth.validAccessToken()
                socket = openSocket(token, session)
                session.closed.await()
            } catch (e: AuthRevokedException) {
                _connectionState.value = ConnState.AUTH_FAILED
                return
            } catch (e: Exception) {
                // network error before/at connect — fall through to backoff
            }
            _connectionState.value = ConnState.OFFLINE
            attempt = if (session.sawAuthOk) 0 else attempt + 1
            delay(backoffMs(attempt))
        }
    }

    private class Session {
        val closed = CompletableDeferred<Unit>()
        @Volatile var sawAuthOk = false
    }

    private fun openSocket(token: String, session: Session): WebSocket {
        val base = settings.baseUrl ?: error("no base url configured")
        val request = Request.Builder().url(wsUrl(base)).build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val msg = WsParser.parse(text)) {
                    is WsIncoming.AuthRequired ->
                        webSocket.send("""{"type":"auth","access_token":"$token"}""")
                    is WsIncoming.AuthOk -> {
                        session.sawAuthOk = true
                        _connectionState.value = ConnState.CONNECTED
                        entityId?.let { id ->
                            webSocket.send(
                                """{"id":${idCounter.getAndIncrement()},"type":"subscribe_entities","entity_ids":["$id"]}"""
                            )
                        }
                    }
                    // Possibly an expired token raced the connect; close and let the
                    // reconnect loop refresh via validAccessToken().
                    is WsIncoming.AuthInvalid -> webSocket.close(1000, "auth invalid")
                    is WsIncoming.EntityUpdate -> {
                        val patch = entityId?.let { msg.states[it] } ?: return
                        val prev = _reading.value
                        val value = patch.state ?: prev?.value ?: return
                        _reading.value = TempReading(
                            value = value,
                            unit = patch.unit ?: prev?.unit,
                            updatedAtMs = clock(),
                        )
                    }
                    is WsIncoming.Result -> pending.remove(msg.id)?.complete(msg.result)
                    is WsIncoming.Unknown -> {}
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                session.closed.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                session.closed.complete(Unit)
            }
        })
    }
}
```

- [ ] **Step 4: Run tests, verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ha.HaWebSocketTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: HA websocket manager with auth handshake, subscribe, get_states, reconnect backoff"
```

---

### Task 7: Setup screen (URL entry + OAuth WebView)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/SetupScreen.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/SetupLogicTest.kt`

**Interfaces:**
- Consumes: `AuthManager` (authorizeUrl, exchangeCode, REDIRECT_URI), `RegistrationClient`/`DeviceInfo`, `SettingsStore` — all via `AppDeps` (defined in Task 10; for THIS task declare the composable to take the dependencies directly, see signature below, so it compiles standalone)
- Produces:
```kotlin
fun normalizeBaseUrl(input: String): String?   // trims, strips trailing /, defaults scheme to http://, null if invalid
@Composable fun SetupScreen(settings: SettingsStore, auth: AuthManager, registration: RegistrationClient, onDone: () -> Unit)
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/ui/SetupLogicTest.kt`:
```kotlin
package com.rar.echodash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupLogicTest {
    @Test
    fun addsHttpSchemeWhenMissing() {
        assertEquals("http://ha.local:8123", normalizeBaseUrl("ha.local:8123"))
    }

    @Test
    fun keepsExplicitSchemeAndStripsTrailingSlash() {
        assertEquals("https://ha.example.com", normalizeBaseUrl("https://ha.example.com/"))
        assertEquals("http://192.168.1.10:8123", normalizeBaseUrl(" http://192.168.1.10:8123/ "))
    }

    @Test
    fun rejectsBlankAndNonHttpSchemes() {
        assertNull(normalizeBaseUrl("   "))
        assertNull(normalizeBaseUrl("ftp://ha.local"))
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ui.SetupLogicTest" --console=plain`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

`app/src/main/java/com/rar/echodash/ui/SetupScreen.kt`:
```kotlin
package com.rar.echodash.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.AuthManager
import com.rar.echodash.ha.DeviceInfo
import com.rar.echodash.ha.RegistrationClient
import kotlinx.coroutines.launch

fun normalizeBaseUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/').trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val ok = withScheme.startsWith("http://") || withScheme.startsWith("https://")
    return if (ok) withScheme.trimEnd('/') else null
}

private sealed interface SetupPhase {
    data object EnterUrl : SetupPhase
    data class Login(val authorizeUrl: String) : SetupPhase
    data object Working : SetupPhase
}

@Composable
fun SetupScreen(
    settings: SettingsStore,
    auth: AuthManager,
    registration: RegistrationClient,
    onDone: () -> Unit,
) {
    var phase by remember { mutableStateOf<SetupPhase>(SetupPhase.EnterUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var urlText by remember { mutableStateOf(settings.baseUrl ?: "") }
    val scope = rememberCoroutineScope()

    when (val p = phase) {
        is SetupPhase.EnterUrl -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connect to Home Assistant", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("HA URL, e.g. http://homeassistant.local:8123") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val base = normalizeBaseUrl(urlText)
                if (base == null) {
                    error = "Enter a valid http(s) URL"
                } else {
                    settings.baseUrl = base
                    error = null
                    phase = SetupPhase.Login(auth.authorizeUrl(base))
                }
            }) { Text("Connect") }
        }

        is SetupPhase.Login -> AuthWebView(
            authorizeUrl = p.authorizeUrl,
            onCode = { code ->
                phase = SetupPhase.Working
                scope.launch {
                    try {
                        auth.exchangeCode(code)
                        registration.register(
                            DeviceInfo(
                                deviceName = "Echo Dashboard",
                                manufacturer = Build.MANUFACTURER,
                                model = Build.MODEL,
                                osVersion = Build.VERSION.RELEASE ?: "?",
                            )
                        )
                        onDone()
                    } catch (e: Exception) {
                        error = "Login failed: ${e.message}"
                        phase = SetupPhase.EnterUrl
                    }
                }
            },
            onError = { msg ->
                error = "Can't reach Home Assistant: $msg"
                phase = SetupPhase.EnterUrl
            },
        )

        SetupPhase.Working -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AuthWebView(authorizeUrl: String, onCode: (String) -> Unit, onError: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith(AuthManager.REDIRECT_URI)) {
                            val code = Uri.parse(url).getQueryParameter("code")
                            if (code != null) onCode(code) else onError("no code in redirect")
                            return true
                        }
                        return false
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onError(error?.description?.toString() ?: "page load error")
                        }
                    }
                }
                loadUrl(authorizeUrl)
            }
        },
    )
}
```

- [ ] **Step 4: Run tests + full build**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ui.SetupLogicTest" assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: setup screen with HA OAuth login in WebView and device registration"
```

---

### Task 8: Entity picker screen

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/EntityPickerScreen.kt`

**Interfaces:**
- Consumes: `HaWebSocket.start(null)`/`fetchTemperatureSensors()`, `SettingsStore.temperatureEntityId`, `EntityState`
- Produces: `@Composable fun EntityPickerScreen(settings: SettingsStore, ws: HaWebSocket, onPicked: () -> Unit)`

No new pure logic (sensor filtering already tested in Task 2), so no new unit test — verified by compilation + on-device later.

- [ ] **Step 1: Implement**

`app/src/main/java/com/rar/echodash/ui/EntityPickerScreen.kt`:
```kotlin
package com.rar.echodash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.HaWebSocket
import kotlinx.coroutines.withTimeout

const val DEFAULT_TEMPERATURE_ENTITY = "sensor.outside_temperature"

@Composable
fun EntityPickerScreen(settings: SettingsStore, ws: HaWebSocket, onPicked: () -> Unit) {
    var sensors by remember { mutableStateOf<List<EntityState>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        sensors = null
        ws.start(null)
        try {
            val fetched = withTimeout(15_000) { ws.fetchTemperatureSensors() }
            // default sensor first, then alphabetical by display name
            sensors = fetched.sortedWith(
                compareByDescending<EntityState> { it.entityId == DEFAULT_TEMPERATURE_ENTITY }
                    .thenBy { it.friendlyName ?: it.entityId }
            )
        } catch (e: Exception) {
            error = "Couldn't load sensors: ${e.message}"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pick a temperature sensor", style = MaterialTheme.typography.headlineSmall)
        when {
            error != null -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = { attempt++ }) { Text("Retry") }
            }
            sensors == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            sensors!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No temperature sensors found in Home Assistant")
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(sensors!!, key = { it.entityId }) { sensor ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                settings.temperatureEntityId = sensor.entityId
                                onPicked()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                    ) {
                        Text(
                            "${sensor.friendlyName ?: sensor.entityId} — ${sensor.state}${sensor.unit ?: ""}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(sensor.entityId, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: temperature entity picker screen"
```

---

### Task 9: Dashboard screen

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/DashboardScreen.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/DashboardLogicTest.kt`

**Interfaces:**
- Consumes: `TempReading`, `ConnState`
- Produces:
```kotlin
fun isStale(nowMs: Long, updatedAtMs: Long?): Boolean   // true if updated >15 min ago
@Composable fun DashboardScreen(reading: TempReading?, connState: ConnState,
    onChangeSensor: () -> Unit, onLogout: () -> Unit)
```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/ui/DashboardLogicTest.kt`:
```kotlin
package com.rar.echodash.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLogicTest {
    @Test
    fun freshReadingIsNotStale() {
        assertFalse(isStale(nowMs = 1_000_000L, updatedAtMs = 1_000_000L - 14 * 60_000L))
    }

    @Test
    fun oldReadingIsStale() {
        assertTrue(isStale(nowMs = 1_000_000L + 16 * 60_000L, updatedAtMs = 1_000_000L))
    }

    @Test
    fun missingReadingIsNotStale() {
        assertFalse(isStale(nowMs = 1_000_000L, updatedAtMs = null))
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ui.DashboardLogicTest" --console=plain`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

`app/src/main/java/com/rar/echodash/ui/DashboardScreen.kt`:
```kotlin
package com.rar.echodash.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.TempReading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.delay

fun isStale(nowMs: Long, updatedAtMs: Long?): Boolean =
    updatedAtMs != null && nowMs - updatedAtMs > 15 * 60_000L

@Composable
private fun rememberMinuteTicker(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(60_000 - now.longValue % 60_000)
        }
    }
    return now
}

@Composable
private fun DuskBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color(0xFF0B1026),
                0.55f to Color(0xFF2B2E4A),
                0.8f to Color(0xFF7A4A6B),
                1.0f to Color(0xFFC98A5E),
            )
        )
        val rng = Random(42)
        repeat(80) {
            drawCircle(
                color = Color.White.copy(alpha = 0.2f + rng.nextFloat() * 0.5f),
                radius = 0.4f + rng.nextFloat() * 1.8f,
                center = Offset(rng.nextFloat() * size.width, rng.nextFloat() * size.height * 0.55f),
            )
        }
    }
}

@Composable
fun DashboardScreen(
    reading: TempReading?,
    connState: ConnState,
    onChangeSensor: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val now by rememberMinuteTicker()

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) }
    ) {
        DuskBackground()

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            Text(
                SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)),
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
            )
            val stale = isStale(now, reading?.updatedAtMs)
            Text(
                reading?.let { "${it.value}${it.unit ?: "°"}" } ?: "--",
                color = Color.White.copy(alpha = if (stale) 0.4f else 0.85f),
                fontSize = 40.sp,
            )
        }

        if (connState != ConnState.CONNECTED) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(8.dp)
                    .background(Color(0xFFE0A030), CircleShape)
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Change sensor") },
                onClick = { menuOpen = false; onChangeSensor() },
            )
            DropdownMenuItem(
                text = { Text("Android settings") },
                onClick = {
                    menuOpen = false
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                },
            )
            DropdownMenuItem(
                text = { Text("Log out") },
                onClick = { menuOpen = false; onLogout() },
            )
        }
    }
}
```

- [ ] **Step 4: Run tests + build**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.ui.DashboardLogicTest" assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: dashboard screen with clock, temperature, dusk background, long-press menu"
```

---

### Task 10: App wiring, kiosk MainActivity, final verification

**Files:**
- Create: `app/src/main/java/com/rar/echodash/App.kt`
- Modify (REPLACE ENTIRE FILE): `app/src/main/java/com/rar/echodash/MainActivity.kt`

**Interfaces:**
- Consumes: everything above
- Produces: runnable app; `AppDeps` (constructed once in MainActivity); `EchoDashApp(deps)` root composable

- [ ] **Step 1: Write App.kt**

`app/src/main/java/com/rar/echodash/App.kt`:
```kotlin
package com.rar.echodash

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rar.echodash.data.PrefsSettingsStore
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.AuthManager
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaWebSocket
import com.rar.echodash.ha.RegistrationClient
import com.rar.echodash.ui.DashboardScreen
import com.rar.echodash.ui.EntityPickerScreen
import com.rar.echodash.ui.SetupScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppDeps(context: Context) {
    val settings: SettingsStore = PrefsSettingsStore(context.applicationContext)
    val client = OkHttpClient()
    val auth = AuthManager(settings, client)
    val registration = RegistrationClient(settings, auth, client)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ws = HaWebSocket(settings, auth, client, scope)
}

sealed interface Screen {
    data object Setup : Screen
    data object Picker : Screen
    data object Dashboard : Screen
}

fun initialScreen(settings: SettingsStore): Screen = when {
    settings.refreshToken == null -> Screen.Setup
    settings.temperatureEntityId == null -> Screen.Picker
    else -> Screen.Dashboard
}

@Composable
fun EchoDashApp(deps: AppDeps) {
    var screen by remember { mutableStateOf(initialScreen(deps.settings)) }
    val connState by deps.ws.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(connState) {
        if (connState == ConnState.AUTH_FAILED) {
            deps.ws.stop()
            screen = Screen.Setup
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        when (screen) {
            Screen.Setup -> SetupScreen(deps.settings, deps.auth, deps.registration) {
                screen = Screen.Picker
            }
            Screen.Picker -> EntityPickerScreen(deps.settings, deps.ws) {
                screen = Screen.Dashboard
            }
            Screen.Dashboard -> {
                LaunchedEffect(Unit) { deps.ws.start(deps.settings.temperatureEntityId) }
                val reading by deps.ws.reading.collectAsStateWithLifecycle()
                DashboardScreen(
                    reading = reading,
                    connState = connState,
                    onChangeSensor = { screen = Screen.Picker },
                    onLogout = {
                        deps.ws.stop()
                        deps.settings.clearAuth()
                        screen = Screen.Setup
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 2: Replace MainActivity.kt with the kiosk version**

`app/src/main/java/com/rar/echodash/MainActivity.kt` (full replacement):
```kotlin
package com.rar.echodash

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val deps = AppDeps(applicationContext)
        setContent { EchoDashApp(deps) }
    }
}
```

- [ ] **Step 3: Full verification**

Run: `./gradlew test assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`; all unit tests pass (WsParser 5, SettingsStore 2, AuthManager 5, Registration 1, HaWebSocket 4, SetupLogic 3, DashboardLogic 3 = 23 tests); APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: app wiring, screen state machine, kiosk main activity"
```

---

## Deferred to morning (requires the physical device)

Not part of tonight's tasks — listed so nothing is forgotten:

1. `adb connect <echo-ip>` (or USB), `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Walk through Setup → login (`http://<ha-host>:8123`) → verify "Echo Dashboard" appears in HA Settings → Devices & Services → Mobile App.
3. Pick `sensor.outside_temperature`; confirm live value; toggle Wi-Fi to see the offline dot; long-press menu items.
4. Optionally set as default launcher (Settings → Apps → Default apps → Home) and reboot to verify boot-to-dashboard.
