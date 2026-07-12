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
    fun invalidateAccessTokenForcesRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"access_token":"AT-new","expires_in":1800,"token_type":"Bearer"}"""))
            server.start()
            settings.baseUrl = server.url("/").toString().trimEnd('/')
            // Cached token is fresh — would normally be returned without a network call.
            settings.accessToken = "AT-cached"
            settings.accessTokenExpiresAt = now + 1800_000L
            settings.refreshToken = "RT"
            val auth = auth()
            auth.invalidateAccessToken()
            assertNull(settings.accessToken)
            assertEquals(0L, settings.accessTokenExpiresAt)
            assertEquals("AT-new", auth.validAccessToken())
            assertEquals("AT-new", settings.accessToken)
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

    @Test
    fun refreshUsesStoredAuthClientId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"access_token":"AT2","expires_in":1800,"token_type":"Bearer"}"""))
            server.start()
            settings.baseUrl = server.url("/").toString().trimEnd('/')
            settings.authClientId = "http://10.0.0.5:8080/"
            settings.refreshToken = "RT"
            assertEquals("AT2", auth().validAccessToken())
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("grant_type=refresh_token"))
            assertTrue(body.contains("client_id=http%3A%2F%2F10.0.0.5%3A8080%2F"))
        }
    }

    @Test
    fun refreshFallsBackToLegacyClientIdWhenAbsent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"access_token":"AT2","expires_in":1800,"token_type":"Bearer"}"""))
            server.start()
            settings.baseUrl = server.url("/").toString().trimEnd('/')
            settings.authClientId = null
            settings.refreshToken = "RT"
            assertEquals("AT2", auth().validAccessToken())
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("client_id=https%3A%2F%2Fhome-assistant.io%2Fandroid"))
        }
    }

    @Test
    fun exchangeSetupCodePersistsBaseUrlClientIdAndTokens() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"access_token":"AT","refresh_token":"RT","expires_in":1800,"token_type":"Bearer"}"""))
            server.start()
            val ha = server.url("/").toString().trimEnd('/')
            assertNull(settings.baseUrl)
            auth().exchangeSetupCode(ha, "http://10.0.0.5:8080/", "CODE123")
            assertEquals(ha, settings.baseUrl)
            assertEquals("http://10.0.0.5:8080/", settings.authClientId)
            assertEquals("AT", settings.accessToken)
            assertEquals("RT", settings.refreshToken)
            val req = server.takeRequest()
            assertEquals("/auth/token", req.path)
            val body = req.body.readUtf8()
            assertTrue(body.contains("grant_type=authorization_code"))
            assertTrue(body.contains("code=CODE123"))
            assertTrue(body.contains("client_id=http%3A%2F%2F10.0.0.5%3A8080%2F"))
        }
    }
}
