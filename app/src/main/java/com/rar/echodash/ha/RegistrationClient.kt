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
