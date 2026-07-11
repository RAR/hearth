package com.rar.echodash.ha

import com.rar.echodash.data.InMemorySettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.boolean
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
            assertEquals("Echo Dashboard", body["app_name"]?.jsonPrimitive?.contentOrNull)
            assertEquals(false, body["supports_encryption"]?.jsonPrimitive?.boolean)
        }
    }
}
