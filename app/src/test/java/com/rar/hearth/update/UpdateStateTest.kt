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
