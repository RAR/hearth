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

    /** Drop the cached access token so the next validAccessToken() forces a refresh. */
    fun invalidateAccessToken() {
        settings.accessToken = null
        settings.accessTokenExpiresAt = 0L
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
