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
