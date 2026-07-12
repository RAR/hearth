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
    var vacaSettingsJson: String?
    var configPin: String?
    fun clearAuth()
}

class InMemorySettingsStore : SettingsStore {
    override var baseUrl: String? = null
    override var accessToken: String? = null
    override var accessTokenExpiresAt: Long = 0L
    override var refreshToken: String? = null
    override var vacaSettingsJson: String? = null
    override var configPin: String? = null

    override fun clearAuth() {
        accessToken = null
        accessTokenExpiresAt = 0L
        refreshToken = null
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
    override var vacaSettingsJson: String?
        get() = string("vaca_settings"); set(v) = put("vaca_settings", v)
    override var configPin: String?
        get() = string("config_pin"); set(v) = put("config_pin", v)

    override fun clearAuth() {
        prefs.edit()
            .remove("access_token")
            .remove("access_token_expires_at")
            .remove("refresh_token")
            .apply()
    }
}
