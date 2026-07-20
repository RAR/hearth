package com.rar.hearth.web

import com.rar.hearth.data.InMemorySettingsStore
import com.rar.hearth.ha.AuthManager
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SetupCoordinatorTest {

    /** Throwaway HA token endpoint on an ephemeral port. */
    private class FakeHa : NanoHTTPD(0) {
        @Volatile var status: Response.IStatus = Response.Status.OK
        @Volatile var body =
            """{"access_token":"AT","refresh_token":"RT","expires_in":1800,"token_type":"Bearer"}"""
        override fun serve(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            runCatching { session.parseBody(files) }
            return newFixedLengthResponse(status, "application/json", body)
        }
    }

    private lateinit var ha: FakeHa
    private lateinit var settings: InMemorySettingsStore
    private lateinit var auth: AuthManager
    private var now = 1_000_000L
    private var configuredCount = 0

    private fun coordinator() = SetupCoordinator(auth, onConfigured = { configuredCount++ }, clock = { now })
    private fun haUrl() = "http://127.0.0.1:${ha.listeningPort}"
    private fun stateOf(url: String) = url.substringAfter("state=")

    @Before
    fun setUp() {
        ha = FakeHa(); ha.start()
        settings = InMemorySettingsStore()
        auth = AuthManager(settings, OkHttpClient()) { now }
    }

    @After
    fun tearDown() { ha.stop() }

    @Test
    fun beginNormalizesUrlAndBuildsAuthorizeUrl() {
        val r = coordinator().begin("ha.local:8123", "http://10.0.0.5:8080/")
        assertTrue(r is BeginResult.Ok)
        val url = (r as BeginResult.Ok).authorizeUrl
        assertTrue(url.startsWith("http://ha.local:8123/auth/authorize?"))
        assertTrue(url.contains("client_id=http%3A%2F%2F10.0.0.5%3A8080%2F"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F10.0.0.5%3A8080%2F"))
        assertTrue(url.contains("state="))
    }

    @Test
    fun beginRejectsInvalidUrl() {
        assertTrue(coordinator().begin("   ", "http://c/") is BeginResult.Invalid)
        assertTrue(coordinator().begin("ftp://ha.local", "http://c/") is BeginResult.Invalid)
    }

    @Test
    fun beginGeneratesDistinctStates() {
        val c = coordinator()
        val a = (c.begin("ha.local", "http://c/") as BeginResult.Ok).authorizeUrl
        val b = (c.begin("ha.local", "http://c/") as BeginResult.Ok).authorizeUrl
        assertNotEquals(stateOf(a), stateOf(b))
    }

    @Test
    fun completeRejectsWrongState() {
        val c = coordinator()
        c.begin(haUrl(), "http://c/")
        assertTrue(c.complete("CODE", "not-the-state") is CompleteResult.BadState)
        assertEquals(0, configuredCount)
        assertNull(settings.refreshToken)
    }

    @Test
    fun completeRejectsExpiredState() {
        val c = coordinator()
        val url = (c.begin(haUrl(), "http://c/") as BeginResult.Ok).authorizeUrl
        now += SetupCoordinator.EXPIRY_MS + 1
        assertTrue(c.complete("CODE", stateOf(url)) is CompleteResult.BadState)
        assertEquals(0, configuredCount)
    }

    @Test
    fun completeSuccessPersistsAndFiresCallback() {
        val c = coordinator()
        val url = (c.begin(haUrl(), "http://10.0.0.5:8080/") as BeginResult.Ok).authorizeUrl
        val r = c.complete("CODE123", stateOf(url))
        assertEquals(CompleteResult.Ok, r)
        assertEquals(haUrl(), settings.baseUrl)
        assertEquals("http://10.0.0.5:8080/", settings.authClientId)
        assertEquals("RT", settings.refreshToken)
        assertEquals("AT", settings.accessToken)
        assertEquals(1, configuredCount)
    }

    @Test
    fun completeExchangeFailureKeepsPendingForRetry() {
        val c = coordinator()
        val url = (c.begin(haUrl(), "http://c/") as BeginResult.Ok).authorizeUrl
        val state = stateOf(url)
        ha.status = NanoHTTPD.Response.Status.BAD_REQUEST
        ha.body = """{"error":"invalid_grant"}"""
        assertTrue(c.complete("BADCODE", state) is CompleteResult.ExchangeFailed)
        assertEquals(0, configuredCount)
        assertNull(settings.refreshToken)
        // pending kept: a retry (same state) against a now-working HA succeeds
        ha.status = NanoHTTPD.Response.Status.OK
        ha.body = """{"access_token":"AT","refresh_token":"RT","expires_in":1800,"token_type":"Bearer"}"""
        assertEquals(CompleteResult.Ok, c.complete("GOODCODE", state))
        assertEquals(1, configuredCount)
    }
}
