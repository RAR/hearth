package com.rar.hearth.ha

import com.rar.hearth.data.SettingsStore
import java.io.IOException
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
        private const val EXPIRY_MARGIN_MS = 60_000L
    }

    /** Exchange an authorization code obtained via the browser setup flow. [baseUrl] is used directly
     *  (settings.baseUrl is not set yet). On success persists baseUrl, then authClientId, then tokens. */
    suspend fun exchangeSetupCode(baseUrl: String, clientId: String, code: String) {
        val tokens = tokenRequest(
            baseUrl,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", clientId)
                .build()
        )
        settings.baseUrl = baseUrl
        settings.authClientId = clientId
        store(tokens)
    }

    suspend fun validAccessToken(): String {
        val token = settings.accessToken
        if (token != null && clock() < settings.accessTokenExpiresAt - EXPIRY_MARGIN_MS) return token
        return refresh()
    }

    /** Drop the cached access token so the next validAccessToken() forces a refresh. */
    fun invalidateAccessToken() {
        settings.accessToken = null
        settings.accessTokenExpiresAt = 0L
    }

    private suspend fun refresh(): String {
        val refreshToken = settings.refreshToken ?: throw AuthRevokedException()
        val base = settings.baseUrl ?: throw IOException("no base url configured")
        val tokens = try {
            tokenRequest(
                base,
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", settings.authClientId ?: CLIENT_ID)
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

    private suspend fun tokenRequest(base: String, body: FormBody): TokenResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$base/auth/token").post(body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string().orEmpty()
                android.util.Log.w("AuthManager", "token endpoint HTTP ${resp.code}: $errorBody")
                // Only a definitive invalid_grant means the token is dead. Any other
                // failure — e.g. HA rejecting a local_only user whose request arrived
                // via the external path during a DNS blip — must stay retryable, or a
                // transient network wobble self-wipes the device's auth.
                if (resp.code == 400 && errorField(errorBody) == "invalid_grant") throw TokenRejectedException()
                throw IOException("token endpoint HTTP ${resp.code}")
            }
            val obj = Json.parseToJsonElement(resp.body!!.string()).jsonObject
            TokenResponse(
                accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: throw IOException("no access_token in response"),
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
                expiresInSec = obj["expires_in"]?.jsonPrimitive?.long ?: 1800L,
            )
        }
    }

    private fun errorField(body: String): String? = try {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }
}
