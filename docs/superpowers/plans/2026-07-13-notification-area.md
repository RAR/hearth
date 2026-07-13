# Home-View Notification Area (NWS Alerts) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a general-purpose home-view notification area whose first producer is NWS weather alerts (`sensor.nws_alerts_alerts`), following the existing EV/solar card pattern (pure-JVM model → DashboardShell computes → HomeView renders), with in-memory swipe-to-dismiss.

**Architecture:** A pure-JVM model function `nwsNotifications(...)` derives `List<NotificationItem>` from config + the entity snapshot. `DashboardShell` computes it, holds process-lifetime `dismissedKeys` state, filters/prunes, and passes it plus an `onDismiss` lambda into `HomeView`, which renders the new `NotificationArea` composable. A new `NotificationsConfig` config section plus a config-page card feed the sensor id and minimum severity.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose, kotlinx.serialization.json, java.time; JUnit4 plain-JVM tests; static HTML/JS config page.

## Global Constraints

- Kotlin 2.1.0; compileSdk 34 (NEVER bump); minSdk 28.
- NO new dependencies. Dependency whitelist: NanoHTTPD 2.3.1 + org.tensorflow:tensorflow-lite:2.14.0 only. This feature adds none.
- Unit tests are plain-JVM JUnit4 only — no `android.*` imports in test files or in `ui/model/*.kt`.
- Build gate: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` must exit 0.
- Every commit message ends with the trailer line exactly: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Commits go on master (established repo flow).

**Deliberate deviation from the spec:** the spec's model signature is
`nwsNotifications(sensorId, minSeverity, entities)`. This plan adds an explicit
`nowMs: Long` parameter (`nwsNotifications(sensorId, minSeverity, entities, nowMs)`)
so the "until <time>" / same-day formatting is deterministically testable. This
mirrors how `weatherPill(..., nowMs, ...)` and `evCards(..., nowMs)` already take an
injected clock. No behavior change: DashboardShell passes `System.currentTimeMillis()`.

## File Structure

- **Create** `app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt` — pure-JVM model: `NotifSeverity`, `NotificationItem`, `notifSeverityOf(configValue)`, `nwsNotifications(...)`.
- **Create** `app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt` — plain JUnit4 tests for the model.
- **Modify** `app/src/main/java/com/rar/echodash/config/DashConfig.kt` — add `NotificationsConfig`, wire into `DashConfig`, `referencedEntityIds()`, `clamped()`.
- **Modify** `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` — add NotificationsConfig tests.
- **Create** `app/src/main/java/com/rar/echodash/ui/NotificationArea.kt` — the Compose composable (self-contained: rows, severity accent, tap-expand, swipe-dismiss, "+N more").
- **Modify** `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — add `notifications`/`onDismiss` params; render `NotificationArea` in the non-takeover branch.
- **Modify** `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — add `dismissedKeys` state, compute notifications, prune, pass into `HomeView`.
- **Modify** `app/src/main/assets/config/index.html` — add a `notifications-section` card.
- **Modify** `app/src/main/assets/config/app.js` — add `SEVERITY_OPTIONS`, `renderNotifications()`, dispatch call.

---

## Task 1: NotificationModel.kt (pure model, TDD)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt`

**Interfaces:**
- Consumes: `com.rar.echodash.ha.EntityState` — `EntityState(entityId, state, attributes: JsonObject, lastUpdatedMs)`; `attributes` is a `kotlinx.serialization.json.JsonObject`; the NWS `Alerts` attribute is `attributes["Alerts"] as? JsonArray` of `JsonObject`s.
- Produces (relied on by Tasks 2 & 3):
  - `enum class NotifSeverity { INFO, WARNING, CRITICAL }` (ordinal order = ascending severity; CRITICAL is highest).
  - `data class NotificationItem(val key: String, val severity: NotifSeverity, val title: String, val detail: String?)`.
  - `fun notifSeverityOf(configValue: String): NotifSeverity` — maps config strings `"minor"→INFO`, `"moderate"→WARNING`, `"severe"→CRITICAL` (case/blank tolerant; anything else → INFO).
  - `fun nwsNotifications(sensorId: String?, minSeverity: NotifSeverity, entities: Map<String, EntityState>, nowMs: Long): List<NotificationItem>`.

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt` with EXACTLY this content:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationModelTest {

    // Time-formatting tests convert to the JVM default zone. Pin it to UTC so the
    // crafted "+00:00" alert times render at the same wall-clock we assert on.
    private var savedTz: TimeZone? = null

    @Before
    fun pinUtc() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTz() {
        savedTz?.let { TimeZone.setDefault(it) }
    }

    // Tuesday 2026-07-14 12:00 UTC — the "now" anchor for the time tests.
    private val nowMs = java.time.Instant.parse("2026-07-14T12:00:00Z").toEpochMilli()

    private val SENSOR = "sensor.nws_alerts_alerts"

    /** Build the sensor entity: [count] state + an Alerts array assembled from [alerts]. */
    private fun sensor(count: String, alerts: JsonObject): EntityState =
        EntityState(SENSOR, count, alerts, 0L)

    /** One Alerts object with the whole array wrapper, from field pairs. Null values are omitted. */
    private fun alertsAttr(vararg objects: Map<String, String?>): JsonObject =
        buildJsonObject {
            putJsonArray("Alerts") {
                objects.forEach { fields ->
                    addJsonObject {
                        fields.forEach { (k, v) -> if (v != null) put(k, v) }
                    }
                }
            }
        }

    private fun run(
        state: String,
        alerts: JsonObject,
        minSeverity: NotifSeverity = NotifSeverity.INFO,
        sensorId: String? = SENSOR,
        now: Long = nowMs,
    ): List<NotificationItem> =
        nwsNotifications(sensorId, minSeverity, mapOf(SENSOR to sensor(state, alerts)), now)

    // ---- config severity mapping ----

    @Test
    fun notifSeverityOfMapsConfigStrings() {
        assertEquals(NotifSeverity.INFO, notifSeverityOf("minor"))
        assertEquals(NotifSeverity.WARNING, notifSeverityOf("moderate"))
        assertEquals(NotifSeverity.CRITICAL, notifSeverityOf("severe"))
        assertEquals(NotifSeverity.CRITICAL, notifSeverityOf("  SEVERE  ")) // trim + case
        assertEquals(NotifSeverity.INFO, notifSeverityOf("bogus"))          // unknown -> INFO
    }

    // ---- severity mapping incl. unknown ----

    @Test
    fun severityMappingIncludingUnknown() {
        fun sevOf(raw: String): NotifSeverity =
            run("1", alertsAttr(mapOf("Event" to "E", "ID" to "1", "Severity" to raw))).single().severity
        assertEquals(NotifSeverity.CRITICAL, sevOf("Extreme"))
        assertEquals(NotifSeverity.CRITICAL, sevOf("Severe"))
        assertEquals(NotifSeverity.WARNING, sevOf("Moderate"))
        assertEquals(NotifSeverity.INFO, sevOf("Minor"))
        assertEquals(NotifSeverity.INFO, sevOf("Unknown"))
        assertEquals(NotifSeverity.INFO, sevOf("wat"))
    }

    // ---- min-severity filtering ----

    @Test
    fun minSeverityFiltersAtEachThreshold() {
        val attr = alertsAttr(
            mapOf("Event" to "Minor thing", "ID" to "m", "Severity" to "Minor"),
            mapOf("Event" to "Moderate thing", "ID" to "o", "Severity" to "Moderate"),
            mapOf("Event" to "Severe thing", "ID" to "s", "Severity" to "Severe"),
        )
        assertEquals(3, run("3", attr, NotifSeverity.INFO).size)
        assertEquals(setOf("o", "s"), run("3", attr, NotifSeverity.WARNING).map { it.key }.toSet())
        assertEquals(setOf("s"), run("3", attr, NotifSeverity.CRITICAL).map { it.key }.toSet())
    }

    // ---- sort: severity, then onset asc (unparseable last), then title ----

    @Test
    fun sortsBySeverityThenOnsetThenTitle() {
        val attr = alertsAttr(
            mapOf("Event" to "Bravo", "ID" to "b", "Severity" to "Moderate", "Onset" to "2026-07-14T10:00:00+00:00"),
            mapOf("Event" to "Alpha", "ID" to "a", "Severity" to "Severe", "Onset" to "2026-07-14T09:00:00+00:00"),
            mapOf("Event" to "Charlie", "ID" to "c", "Severity" to "Severe", "Onset" to "2026-07-14T08:00:00+00:00"),
            mapOf("Event" to "Delta", "ID" to "d", "Severity" to "Moderate", "Onset" to "not-a-date"),
        )
        // CRITICAL first, earliest onset first within a tier; unparseable onset sinks to the tier's end.
        assertEquals(listOf("c", "a", "b", "d"), run("4", attr).map { it.key })
    }

    @Test
    fun sortTieBreaksOnTitleWhenSeverityAndOnsetEqual() {
        val attr = alertsAttr(
            mapOf("Event" to "Zebra", "ID" to "z", "Severity" to "Severe"),
            mapOf("Event" to "Apple", "ID" to "a", "Severity" to "Severe"),
        )
        // Same severity, both onsets unparseable/absent -> title ascending.
        assertEquals(listOf("Apple", "Zebra"), run("2", attr).map { it.title })
    }

    // ---- title "until" formatting ----

    @Test
    fun titleUsesEndsWhenPresentOtherDay() {
        val attr = alertsAttr(mapOf(
            "Event" to "Winter Storm Warning", "ID" to "1", "Severity" to "Severe",
            "Ends" to "2026-07-16T19:00:00+00:00", // Thursday
        ))
        assertEquals("Winter Storm Warning · until Thu 7:00 PM", run("1", attr).single().title)
    }

    @Test
    fun titleFallsBackToExpiresWhenEndsNull() {
        val attr = alertsAttr(mapOf(
            "Event" to "Flood Watch", "ID" to "1", "Severity" to "Moderate",
            "Ends" to null, "Expires" to "2026-07-16T19:00:00+00:00",
        ))
        assertEquals("Flood Watch · until Thu 7:00 PM", run("1", attr).single().title)
    }

    @Test
    fun titleSameDayOmitsWeekday() {
        val attr = alertsAttr(mapOf(
            "Event" to "Heat Advisory", "ID" to "1", "Severity" to "Minor",
            "Ends" to "2026-07-14T19:00:00+00:00", // same UTC day as nowMs
        ))
        assertEquals("Heat Advisory · until 7:00 PM", run("1", attr).single().title)
    }

    @Test
    fun titleBareEventWhenBothUnparseable() {
        val attr = alertsAttr(mapOf(
            "Event" to "Special Weather Statement", "ID" to "1",
            "Ends" to "junk", "Expires" to "",
        ))
        assertEquals("Special Weather Statement", run("1", attr).single().title)
    }

    // ---- detail assembly ----

    @Test
    fun detailJoinsPresentPartsWithBlankLines() {
        val attr = alertsAttr(mapOf(
            "Event" to "E", "ID" to "1",
            "Headline" to "Head", "Description" to "Desc", "Instruction" to "Do this",
        ))
        assertEquals("Head\n\nDesc\n\nDo this", run("1", attr).single().detail)
    }

    @Test
    fun detailSkipsNullAndBlankParts() {
        val attr = alertsAttr(mapOf(
            "Event" to "E", "ID" to "1",
            "Headline" to "Head", "Description" to "  ", "Instruction" to null,
        ))
        assertEquals("Head", run("1", attr).single().detail)
    }

    @Test
    fun detailNullWhenAllPartsBlank() {
        val attr = alertsAttr(mapOf(
            "Event" to "E", "ID" to "1",
            "Headline" to "", "Description" to "   ", "Instruction" to null,
        ))
        assertNull(run("1", attr).single().detail)
    }

    // ---- empty / degraded inputs ----

    @Test
    fun unavailableStateYieldsEmpty() {
        val attr = alertsAttr(mapOf("Event" to "E", "ID" to "1"))
        assertTrue(run("unavailable", attr).isEmpty())
        assertTrue(run("unknown", attr).isEmpty())
    }

    @Test
    fun missingAlertsAttrYieldsEmpty() {
        // Numeric state but no Alerts key at all.
        val noAlerts = buildJsonObject { put("friendly_name", "NWS") }
        assertTrue(run("2", noAlerts).isEmpty())
    }

    @Test
    fun alertsNotAnArrayYieldsEmpty() {
        val wrong = buildJsonObject { put("Alerts", "nope") }
        assertTrue(run("1", wrong).isEmpty())
    }

    @Test
    fun nullSensorIdOrMissingEntityYieldsEmpty() {
        val attr = alertsAttr(mapOf("Event" to "E", "ID" to "1"))
        assertTrue(run("1", attr, sensorId = null).isEmpty())
        assertTrue(nwsNotifications("sensor.absent", NotifSeverity.INFO, emptyMap(), nowMs).isEmpty())
    }

    // ---- malformed entries skipped ----

    @Test
    fun malformedEntriesSkippedSiblingsKept() {
        val attr = buildJsonObject {
            putJsonArray("Alerts") {
                addJsonObject { put("Event", "Missing ID") }              // no ID -> skipped
                addJsonObject { put("ID", "x") }                          // no Event -> skipped
                add("not-an-object")                                      // wrong type -> skipped
                addJsonObject { put("Event", "Good"); put("ID", "g"); put("Severity", "Severe") }
            }
        }
        val items = run("4", attr)
        assertEquals(1, items.size)
        assertEquals("g", items.single().key)
        assertEquals("Good", items.single().title)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.NotificationModelTest"`
Expected: FAIL — compilation error, `nwsNotifications` / `NotifSeverity` / `NotificationItem` / `notifSeverityOf` unresolved.

- [ ] **Step 3: Write the model implementation**

Create `app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt` with EXACTLY this content:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Notification severity; display order is CRITICAL first. Ordinal order = ascending severity. */
enum class NotifSeverity { INFO, WARNING, CRITICAL }

/**
 * One notification row. [key] is the stable identity (NWS alert ID) used for dismissal and list keys.
 * [detail] null means the row is not expandable.
 */
data class NotificationItem(
    val key: String,
    val severity: NotifSeverity,
    val title: String,
    val detail: String?,
)

/** Map a config min-severity string to the enum. Unknown/blank -> INFO (show everything). */
fun notifSeverityOf(configValue: String): NotifSeverity =
    when (configValue.trim().lowercase(Locale.US)) {
        "severe" -> NotifSeverity.CRITICAL
        "moderate" -> NotifSeverity.WARNING
        else -> NotifSeverity.INFO
    }

/** Map an NWS `Severity` attribute value to the enum. Extreme/Severe -> CRITICAL, Moderate -> WARNING,
 *  Minor/Unknown/anything else -> INFO. */
private fun severityOfAlert(raw: String?): NotifSeverity =
    when (raw?.trim()?.lowercase(Locale.US)) {
        "extreme", "severe" -> NotifSeverity.CRITICAL
        "moderate" -> NotifSeverity.WARNING
        else -> NotifSeverity.INFO
    }

private val DAY_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH)
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** Parse an ISO-8601-with-offset string; null on absent/blank/unparseable. */
private fun parseOffset(raw: String?): OffsetDateTime? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

/** "until <time>" suffix from Ends (fallback Expires); null when neither parses. Weekday dropped
 *  when the end lands on the same local day as [nowMs]. */
private fun untilSuffix(endsRaw: String?, expiresRaw: String?, nowMs: Long): String? {
    val odt = parseOffset(endsRaw) ?: parseOffset(expiresRaw) ?: return null
    val zone = ZoneId.systemDefault()
    val local = odt.atZoneSameInstant(zone)
    val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val fmt = if (local.toLocalDate() == nowDate) TIME_FMT else DAY_TIME_FMT
    return "until " + local.format(fmt)
}

/**
 * Derive notification rows from the NWS alerts sensor. Returns an empty list when [sensorId] is
 * null, the entity is missing, the state is non-numeric (unavailable/unknown), or the `Alerts`
 * attribute is absent/not an array. Never throws: malformed entries are skipped.
 */
fun nwsNotifications(
    sensorId: String?,
    minSeverity: NotifSeverity,
    entities: Map<String, EntityState>,
    nowMs: Long,
): List<NotificationItem> {
    val entity = sensorId?.let { entities[it] } ?: return emptyList()
    if (entity.state.toIntOrNull() == null) return emptyList()
    val alerts = entity.attributes["Alerts"] as? JsonArray ?: return emptyList()

    data class Row(val item: NotificationItem, val onset: Instant?)

    val rows = alerts.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        fun field(k: String): String? = (obj[k] as? JsonPrimitive)?.contentOrNull
        val event = field("Event")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val id = field("ID")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val severity = severityOfAlert(field("Severity"))
        if (severity.ordinal < minSeverity.ordinal) return@mapNotNull null

        val title = buildString {
            append(event)
            untilSuffix(field("Ends"), field("Expires"), nowMs)?.let { append(" · ").append(it) }
        }
        val detail = listOf(field("Headline"), field("Description"), field("Instruction"))
            .mapNotNull { it?.takeIf { s -> s.isNotBlank() } }
            .joinToString("\n\n")
            .ifBlank { null }

        Row(
            item = NotificationItem(key = id, severity = severity, title = title, detail = detail),
            onset = parseOffset(field("Onset"))?.toInstant(),
        )
    }

    return rows
        .sortedWith(
            compareByDescending<Row> { it.item.severity.ordinal }
                .thenBy(nullsLast()) { it.onset }
                .thenBy { it.item.title },
        )
        .map { it.item }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.NotificationModelTest"`
Expected: PASS (all tests green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt \
        app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt
git commit -m "feat(notifications): NWS alerts notification model

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 2: DashConfig NotificationsConfig

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
- Test: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces (relied on by Tasks 3 & 4):
  - `data class NotificationsConfig(val nwsAlerts: String? = null, val nwsMinSeverity: String = "minor")` with `fun clamped(): NotificationsConfig` and `companion object { val MIN_SEVERITIES: Set<String> = setOf("minor", "moderate", "severe") }`.
  - New field `notifications: NotificationsConfig = NotificationsConfig()` on `DashConfig`.
  - `notifications.nwsAlerts` appended to `referencedEntityIds()` after `media.companionEntity`.

- [ ] **Step 1: Write the failing tests**

Add these test methods to the end of `DashConfigTest.kt` (before the final closing `}` of the class):

```kotlin
    @Test
    fun notificationsDefaults() {
        val n = DashConfig().notifications
        assertEquals(null, n.nwsAlerts)
        assertEquals("minor", n.nwsMinSeverity)
        // absent from JSON -> defaults, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1}""")
        assertEquals(null, cfg.notifications.nwsAlerts)
        assertEquals("minor", cfg.notifications.nwsMinSeverity)
    }

    @Test
    fun notificationsRoundTrips() {
        val cfg = DashConfig(
            notifications = NotificationsConfig(nwsAlerts = "sensor.nws_alerts_alerts", nwsMinSeverity = "severe"),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("sensor.nws_alerts_alerts", decodeConfig(text).notifications.nwsAlerts)
        assertEquals("severe", decodeConfig(text).notifications.nwsMinSeverity)
    }

    @Test
    fun notificationsClampedTrimsSensorAndClampsSeverity() {
        val cfg = DashConfig(
            notifications = NotificationsConfig(nwsAlerts = "  sensor.a  ", nwsMinSeverity = "  Moderate  "),
        ).clamped().notifications
        assertEquals("sensor.a", cfg.nwsAlerts)      // trimmed
        assertEquals("moderate", cfg.nwsMinSeverity) // lower-cased, kept
    }

    @Test
    fun notificationsClampedBlankSensorToNullAndBadSeverityToMinor() {
        val blank = DashConfig(
            notifications = NotificationsConfig(nwsAlerts = "   ", nwsMinSeverity = "bogus"),
        ).clamped().notifications
        assertEquals(null, blank.nwsAlerts)
        assertEquals("minor", blank.nwsMinSeverity)  // unknown -> minor
        val empty = DashConfig(
            notifications = NotificationsConfig(nwsAlerts = "", nwsMinSeverity = ""),
        ).clamped().notifications
        assertEquals(null, empty.nwsAlerts)
        assertEquals("minor", empty.nwsMinSeverity)
    }

    @Test
    fun notificationsSurvivesClampedAndDefaultsOnOldConfig() {
        assertEquals("severe",
            DashConfig(notifications = NotificationsConfig(nwsMinSeverity = "severe")).clamped().notifications.nwsMinSeverity)
        // old config document with no "notifications" key -> defaults fill in
        val cfg = decodeConfig("""{"version":1,"home":{"photoFolder":"nas"}}""")
        assertEquals(null, cfg.notifications.nwsAlerts)
        assertEquals("minor", cfg.notifications.nwsMinSeverity)
    }

    @Test
    fun referencedEntityIdsIncludesNwsSensor() {
        val cfg = DashConfig(
            entities = Entities(tempSensor = "sensor.t"),
            notifications = NotificationsConfig(nwsAlerts = "sensor.nws_alerts_alerts"),
        )
        assertEquals(listOf("sensor.t", "sensor.nws_alerts_alerts"), cfg.referencedEntityIds())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"`
Expected: FAIL — `NotificationsConfig` unresolved / `notifications` not a member of `DashConfig`.

- [ ] **Step 3a: Add the `NotificationsConfig` data class**

In `DashConfig.kt`, immediately after the `NightSettings` data class (the block ending at the `}` on the line before the `/** The whole device configuration; ... */` comment), insert:

```kotlin
@Serializable
data class NotificationsConfig(
    val nwsAlerts: String? = null,        // NWS alerts sensor entity id (nws_alerts integration)
    val nwsMinSeverity: String = "minor", // "minor" | "moderate" | "severe"
) {
    /** Trim the sensor id (blank -> null) and clamp the min-severity to the valid set (default minor). */
    fun clamped(): NotificationsConfig = copy(
        nwsAlerts = nwsAlerts?.trim()?.ifBlank { null },
        nwsMinSeverity = nwsMinSeverity.trim().lowercase().let { if (it in MIN_SEVERITIES) it else "minor" },
    )

    companion object {
        /** The three recognized minimum-severity ids. */
        val MIN_SEVERITIES: Set<String> = setOf("minor", "moderate", "severe")
    }
}
```

- [ ] **Step 3b: Add the field to `DashConfig`**

Locate the `DashConfig` primary constructor (currently ending with `val night: NightSettings = NightSettings(),`). Change:

```kotlin
    val night: NightSettings = NightSettings(),
) {
```

to:

```kotlin
    val night: NightSettings = NightSettings(),
    val notifications: NotificationsConfig = NotificationsConfig(),
) {
```

- [ ] **Step 3c: Wire into `referencedEntityIds()`**

In `referencedEntityIds()`, change:

```kotlin
        media.companionEntity?.let { add(it) }
    }.distinct()
```

to:

```kotlin
        media.companionEntity?.let { add(it) }
        notifications.nwsAlerts?.let { add(it) }
    }.distinct()
```

- [ ] **Step 3d: Wire into `clamped()`**

In `clamped()`'s `return copy(...)`, change the trailing lines:

```kotlin
            voice = voice.clamped(),
            media = media.clamped(),
            night = night.clamped(),
        )
```

to:

```kotlin
            voice = voice.clamped(),
            media = media.clamped(),
            night = night.clamped(),
            notifications = notifications.clamped(),
        )
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/config/DashConfig.kt \
        app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
git commit -m "feat(notifications): NotificationsConfig section

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 3: NotificationArea composable + HomeView/DashboardShell wiring

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/NotificationArea.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt` (signature ~136-155; render in the `else` branch ~275-286)
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` (state ~87; HOME branch ~105-140)

**Interfaces:**
- Consumes from Task 1: `NotificationItem`, `NotifSeverity`, `nwsNotifications(sensorId, minSeverity, entities, nowMs)`, `notifSeverityOf(configValue)` (all in package `com.rar.echodash.ui.model`).
- Consumes from Task 2: `config.notifications.nwsAlerts: String?`, `config.notifications.nwsMinSeverity: String`.
- Produces: `@Composable fun NotificationArea(notifications: List<NotificationItem>, onDismiss: (String) -> Unit, modifier: Modifier = Modifier)` in package `com.rar.echodash.ui`.

**Test cycle:** composables are not unit-tested here; the gate for this task is the full build gate (`:app:testDebugUnitTest :app:assembleDebug` exits 0).

- [ ] **Step 1: Create `NotificationArea.kt`**

Create `app/src/main/java/com/rar/echodash/ui/NotificationArea.kt` with EXACTLY this content:

```kotlin
package com.rar.echodash.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.model.NotifSeverity
import com.rar.echodash.ui.model.NotificationItem
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val MAX_ROWS = 4
private const val SWIPE_DISMISS_FRACTION = 0.30f

private fun accentColor(severity: NotifSeverity): Color = when (severity) {
    NotifSeverity.CRITICAL -> Color(0xFFE05555)
    NotifSeverity.WARNING -> Color(0xFFE0A030) // matches GaugeAmber / connection dot
    NotifSeverity.INFO -> Color(0xFF9E9E9E)
}

/**
 * The home-view notification stack. Renders up to [MAX_ROWS] rows; extra rows collapse to a
 * non-interactive "+N more" line. Tapping a row with detail toggles in-place expansion (only one
 * expanded at a time). Swiping a row left past [SWIPE_DISMISS_FRACTION] of its width dismisses it.
 * The caller anchors/sizes this via [modifier]; empty lists should not be rendered by the caller.
 */
@Composable
fun NotificationArea(
    notifications: List<NotificationItem>,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val shown = notifications.take(MAX_ROWS)
    val overflow = notifications.size - shown.size

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { item ->
            key(item.key) {
                NotificationRow(
                    item = item,
                    expanded = expandedKey == item.key,
                    onToggle = { expandedKey = if (expandedKey == item.key) null else item.key },
                    onDismiss = { onDismiss(item.key) },
                )
            }
        }
        if (overflow > 0) {
            Text(
                "+$overflow more",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun NotificationRow(
    item: NotificationItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .pointerInput(item.key) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = widthPx * SWIPE_DISMISS_FRACTION
                        if (widthPx > 0 && -offsetX.value >= threshold) {
                            scope.launch {
                                offsetX.animateTo(-widthPx.toFloat(), tween(200))
                                onDismiss()
                            }
                        } else {
                            scope.launch { offsetX.animateTo(0f, tween(200)) }
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    // Only left drags move the row; right drags clamp back to 0.
                    scope.launch { offsetX.snapTo((offsetX.value + dragAmount).coerceAtMost(0f)) }
                }
            },
    ) {
        val rowModifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .then(if (item.detail != null) Modifier.clickable { onToggle() } else Modifier)
            .height(IntrinsicSize.Min)

        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(accentColor(item.severity)),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded && item.detail != null) {
                    Text(
                        item.detail,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Add params to the `HomeView` signature**

In `HomeView.kt`, the `HomeView` composable's parameter list has (lines ~140-141):

```kotlin
    evs: List<EvCard> = emptyList(),
    solar: SolarCard? = null,
```

Change to:

```kotlin
    evs: List<EvCard> = emptyList(),
    solar: SolarCard? = null,
    notifications: List<NotificationItem> = emptyList(),
    onDismiss: (String) -> Unit = {},
```

- [ ] **Step 3: Add the `NotificationItem` import to `HomeView.kt`**

The imports block already has (lines ~69-73):

```kotlin
import com.rar.echodash.ui.model.AqiPill
import com.rar.echodash.ui.model.BattFlow
import com.rar.echodash.ui.model.EvCard
import com.rar.echodash.ui.model.SolarCard
import com.rar.echodash.ui.model.WeatherPill
```

Change to (insert the NotificationItem line alphabetically):

```kotlin
import com.rar.echodash.ui.model.AqiPill
import com.rar.echodash.ui.model.BattFlow
import com.rar.echodash.ui.model.EvCard
import com.rar.echodash.ui.model.NotificationItem
import com.rar.echodash.ui.model.SolarCard
import com.rar.echodash.ui.model.WeatherPill
```

- [ ] **Step 4: Add the `widthIn`/`heightIn` layout imports to `HomeView.kt`**

The imports block has (lines ~26-32):

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
```

Change to (add `heightIn` and `widthIn`):

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
```

Also add (anywhere in the import block, alphabetical position shown is fine):

```kotlin
import androidx.compose.ui.draw.clipToBounds
```

- [ ] **Step 5: Render `NotificationArea` in the non-takeover branch**

In `HomeView.kt`, the `else` branch of `if (takeoverVisible)` currently ends with the EV/solar `AnimatedVisibility` block (lines ~275-286):

```kotlin
            AnimatedVisibility(
                visible = evs.isNotEmpty() || solar != null,
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    evs.forEach { EvCardView(it) }
                    if (solar != null) SolarCardView(solar)
                }
            }
        }
```

Change to (insert a new `AnimatedVisibility` for notifications before the branch-closing `}`):

```kotlin
            AnimatedVisibility(
                visible = evs.isNotEmpty() || solar != null,
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    evs.forEach { EvCardView(it) }
                    if (solar != null) SolarCardView(solar)
                }
            }

            // Notification area: just below the weather/AQI pill row (top = 70dp), width-capped so
            // it never collides with the EV/solar stack, height-capped + clipped so the bottom-left
            // clock is never covered.
            AnimatedVisibility(
                visible = notifications.isNotEmpty(),
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 28.dp, top = 70.dp),
            ) {
                NotificationArea(
                    notifications = notifications,
                    onDismiss = onDismiss,
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .heightIn(max = 280.dp)
                        .clipToBounds(),
                )
            }
        }
```

- [ ] **Step 6: Add imports to `DashboardShell.kt`**

The imports block has (lines ~30-36):

```kotlin
import com.rar.echodash.ui.model.aqiPill
import com.rar.echodash.ui.model.evCards
import com.rar.echodash.ui.model.lightSections
import com.rar.echodash.ui.model.solarCard
import com.rar.echodash.ui.model.solarFlow
import com.rar.echodash.ui.model.thermostats
import com.rar.echodash.ui.model.weatherPill
```

Change to (add `notifSeverityOf` and `nwsNotifications`, keeping alphabetical order):

```kotlin
import com.rar.echodash.ui.model.aqiPill
import com.rar.echodash.ui.model.evCards
import com.rar.echodash.ui.model.lightSections
import com.rar.echodash.ui.model.notifSeverityOf
import com.rar.echodash.ui.model.nwsNotifications
import com.rar.echodash.ui.model.solarCard
import com.rar.echodash.ui.model.solarFlow
import com.rar.echodash.ui.model.thermostats
import com.rar.echodash.ui.model.weatherPill
```

- [ ] **Step 7: Add the `dismissedKeys` state at DashboardShell scope**

In `DashboardShell.kt`, immediately after (line ~87):

```kotlin
    var railTouches by remember { mutableStateOf(0) }
```

insert:

```kotlin
    // Process-lifetime notification dismissals. Held here (NOT inside the Crossfade HOME branch) so
    // the set survives view switches and takeover unmounts; a dismissed alert returns only if NWS
    // reissues it under a new ID or the app restarts.
    var dismissedKeys by remember { mutableStateOf(setOf<String>()) }
```

- [ ] **Step 8: Compute notifications in the HOME branch and pass into HomeView**

In `DashboardShell.kt`, the HOME branch computes `solar` then calls `HomeView(...)`. Change (lines ~116-118):

```kotlin
                    val solar = remember(entities, config.entities.solar) {
                        solarCard(config.entities.solar, entities)
                    }
```

to:

```kotlin
                    val solar = remember(entities, config.entities.solar) {
                        solarCard(config.entities.solar, entities)
                    }
                    val allNotifications = remember(entities, config.notifications) {
                        nwsNotifications(
                            config.notifications.nwsAlerts,
                            notifSeverityOf(config.notifications.nwsMinSeverity),
                            entities,
                            System.currentTimeMillis(),
                        )
                    }
                    val notifications = allNotifications.filter { it.key !in dismissedKeys }
                    // Prune dismissed keys no longer present so the set can't grow unboundedly.
                    LaunchedEffect(allNotifications) {
                        val present = allNotifications.mapTo(HashSet()) { it.key }
                        val pruned = dismissedKeys intersect present
                        if (pruned != dismissedKeys) dismissedKeys = pruned
                    }
```

Then, in the same branch's `HomeView(...)` call, change (lines ~124-125):

```kotlin
                        evs = evs,
                        solar = solar,
```

to:

```kotlin
                        evs = evs,
                        solar = solar,
                        notifications = notifications,
                        onDismiss = { key -> dismissedKeys = dismissedKeys + key },
```

- [ ] **Step 9: Run the full build gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
Expected: exit 0 — all unit tests pass and the debug APK compiles (Compose wiring type-checks).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/NotificationArea.kt \
        app/src/main/java/com/rar/echodash/ui/HomeView.kt \
        app/src/main/java/com/rar/echodash/ui/DashboardShell.kt
git commit -m "feat(notifications): NotificationArea composable + home wiring

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 4: Config page — Notifications card

**Files:**
- Modify: `app/src/main/assets/config/index.html` (add a `notifications-section` after `media-section`, ~line 124)
- Modify: `app/src/main/assets/config/app.js` (add `SEVERITY_OPTIONS` const ~line 33; add `renderNotifications();` to `render()` ~line 285; add `renderNotifications()` function)

**Interfaces:**
- Consumes from Task 2: the config document field `config.notifications = { nwsAlerts, nwsMinSeverity }` (default `{ nwsAlerts: null, nwsMinSeverity: "minor" }`), and the server-side clamp (`nwsMinSeverity` -> `minor|moderate|severe`).
- Produces: no code consumed by other tasks. Assets are packaged verbatim (not compiled).

**Test cycle:** the build gate (`:app:testDebugUnitTest :app:assembleDebug`) must stay green (assets are packaged, not compiled). Manual browser verification of the card happens at the controller level after flashing — out of scope for this task's automated gate.

- [ ] **Step 1: Add the HTML card**

In `index.html`, the `media-section` block ends at (lines ~113-124):

```html
      <section id="media-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M10.2 8.3 16 12l-5.8 3.7Z" fill="currentColor" stroke="none"/></svg>
          </span>
          <div class="card-titles">
            <h2>Media</h2>
            <p>Album art and track info for on-device playback.</p>
          </div>
        </div>
        <div id="media"></div>
      </section>
```

Immediately after that block's closing `</section>`, insert:

```html
      <section id="notifications-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Notifications</h2>
            <p>Weather alerts shown on the home screen.</p>
          </div>
        </div>
        <div id="notifications"></div>
      </section>
```

- [ ] **Step 2: Add the `SEVERITY_OPTIONS` constant**

In `app.js`, after the `WAKE_WORD_OPTIONS` constant (lines ~29-33):

```javascript
const WAKE_WORD_OPTIONS = [
  ["okay_nabu", "Okay Nabu"],
  ["hey_jarvis", "Hey Jarvis"],
  ["alexa", "Alexa"],
];
```

insert:

```javascript
const SEVERITY_OPTIONS = [
  ["minor", "Minor"],
  ["moderate", "Moderate"],
  ["severe", "Severe"],
];
```

- [ ] **Step 3: Add `renderNotifications()` to the render dispatch**

In `app.js`, the `render()` function currently reads (lines ~281-290):

```javascript
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderHome();
  renderOptions();
  renderVoice();
  renderNight();
  renderEv();
}
```

Change to (add `renderNotifications();` after `renderMedia();`):

```javascript
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderNotifications();
  renderHome();
  renderOptions();
  renderVoice();
  renderNight();
  renderEv();
}
```

- [ ] **Step 4: Add the `renderNotifications()` function**

In `app.js`, immediately after the `renderMedia()` function (the block ending at line ~411 with its closing `}`), insert:

```javascript
function renderNotifications() {
  const host = document.getElementById("notifications");
  clear(host);
  // Defensive defaults for configs saved before notifications existed (same pattern as Media/Night).
  if (!config.notifications) config.notifications = { nwsAlerts: null, nwsMinSeverity: "minor" };
  const n = config.notifications;
  if (n.nwsMinSeverity == null) n.nwsMinSeverity = "minor";

  // Same populated picker pattern as the AQI sensor: shared sensor datalist; blank -> null.
  host.appendChild(labeledRow("NWS alerts sensor",
    entityPicker(["sensor"], n.nwsAlerts, v => n.nwsAlerts = v)));

  const sev = el("select");
  SEVERITY_OPTIONS.forEach(([val, lbl]) => {
    const o = el("option", null, lbl); o.value = val;
    if (n.nwsMinSeverity === val) o.selected = true;
    sev.appendChild(o);
  });
  sev.addEventListener("change", () => n.nwsMinSeverity = sev.value);
  host.appendChild(labeledRow("Minimum severity", sev));

  host.appendChild(el("div", "muted",
    "Point this at the nws_alerts integration's sensor (e.g. sensor.nws_alerts_alerts) to show active " +
    "alerts under the weather; swipe left to dismiss. Only alerts at or above the minimum severity " +
    "appear (Minor = show all)."));
}
```

- [ ] **Step 5: Run the build gate to confirm assets still package**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
Expected: exit 0 (assets are copied verbatim into the APK; nothing to compile, but this confirms no accidental damage elsewhere).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/assets/config/index.html app/src/main/assets/config/app.js
git commit -m "feat(notifications): config-page Notifications card

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Self-Review

**1. Spec coverage** — every spec section maps to a task:

- Model `NotifSeverity`/`NotificationItem`/`nwsNotifications` → Task 1. Rules (sensorId null/missing/non-numeric/no-array → empty; severity mapping incl. Unknown→INFO; min-severity filter; "until" from Ends→Expires with same-day short form; detail join skipping blanks, all-blank→null; malformed entries skipped; sort severity→onset→title; key=ID) → all covered by explicit tests + implementation.
- Config `NotificationsConfig` (new top-level `notifications`, trim/ifBlank + clamp, joins watch-list via `referencedEntityIds`, severity string map) → Task 2. (The `minor/moderate/severe → INFO/WARNING/CRITICAL` mapping used at runtime is `notifSeverityOf` in Task 1, wired in Task 3.)
- Config page Notifications card (sensor picker + minimum-severity select + muted hint) → Task 4.
- UI `NotificationArea`: TopStart `start=28,top=70`, `widthIn 640`, `heightIn 280` + clipping, hidden under takeover/empty, 600ms fade, pill styling (black 35% + rounded), 4dp severity accent (CRITICAL 0xFFE05555 / WARNING 0xFFE0A030 / INFO 0xFF9E9E9E), title 18sp white single-line ellipsized, tap-expand detail 14sp 90% white max 6 lines, single-expanded, null-detail non-expandable, max 4 rows + "+N more", swipe-left ≥30% dismiss / right snaps back → Task 3. (Clipping: `heightIn(max=280.dp)` caps the measured height and an explicit `.clipToBounds()` guarantees overflow content — e.g. 3 rows + one expanded detail, which lands right at the cap — can never draw past the bounds onto the clock.)
- Dismissal state at DashboardShell scope, applied as filter, pruned each recompute → Task 3.
- Data flow (EntityHub watch-list via `referencedEntityIds` → snapshot → `nwsNotifications` → minus dismissed → HomeView → NotificationArea) → Tasks 2 + 3.
- Non-goals respected: in-memory only (no persistence), no read-state/history/sounds, home-view only, no HA-push producer, no client expiry filtering.

**2. Placeholder scan** — no TBD/TODO/"add error handling"/"similar to Task N". Every code step contains complete, transcribable code; every referenced symbol (`NotifSeverity`, `NotificationItem`, `nwsNotifications`, `notifSeverityOf`, `NotificationsConfig`, `NotificationArea`) is defined in an earlier step within this plan.

**3. Type consistency** — `nwsNotifications(sensorId: String?, minSeverity: NotifSeverity, entities: Map<String, EntityState>, nowMs: Long)` is defined identically in Task 1 and called identically in Task 3. `notifSeverityOf(String): NotifSeverity` defined Task 1, used Task 3. `NotificationItem(key, severity, title, detail)` field names consistent between model, tests, and `NotificationArea`. `NotificationsConfig(nwsAlerts, nwsMinSeverity)` field names consistent across DashConfig, tests, and app.js JSON keys. `NotificationArea(notifications, onDismiss, modifier)` signature matches the HomeView call site; HomeView's new params (`notifications`, `onDismiss`) match the DashboardShell call site.

**Known minor points (intentional, not defects):**
- `nowMs` param is an explicit deviation from the spec signature, documented in Global Constraints.
- `notifications` recompute keys on `entities` + `config.notifications` (not on a live clock), matching how `weatherPill`/`evCards` already snapshot `System.currentTimeMillis()` inside `remember`; time only affects "until" text, which refreshes on the next entity update.
