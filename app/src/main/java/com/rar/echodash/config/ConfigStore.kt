package com.rar.echodash.config

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the DashConfig document at [dir]/config.json and exposes it as a StateFlow. Writes are
 * atomic (temp file + rename). A corrupt file is renamed to config.json.bad and the store falls back
 * to DashConfig() defaults. Android-free (java.io.File injected) so it runs in plain JVM tests.
 *
 * Thread-safe: [update] may be called concurrently from NanoHTTPD request threads and app coroutines.
 * The clamp -> persist -> emit sequence is serialized on an internal lock so [config] never observes a
 * value that wasn't (or won't be) written to disk. If persistence fails outright, [update] throws
 * [java.io.IOException] rather than reporting success.
 */
class ConfigStore(
    private val dir: File,
) {
    private val file = File(dir, "config.json")
    private val _config = MutableStateFlow(DashConfig())
    val config: StateFlow<DashConfig> = _config

    private val lock = Any()

    init {
        if (!dir.exists()) dir.mkdirs()
        if (file.exists()) {
            val loaded = runCatching { decodeConfig(file.readText()) }.getOrNull()
            if (loaded != null) {
                _config.value = loaded.clamped()
            } else {
                val bad = File(dir, "config.json.bad")
                bad.delete() // clear any stale .bad so the rename below can't silently fail
                val renamed = file.renameTo(bad)
                if (renamed) {
                    android.util.Log.w("ConfigStore", "config.json corrupt; renamed to config.json.bad")
                } else {
                    android.util.Log.w("ConfigStore", "config.json corrupt; failed to rename to config.json.bad")
                }
            }
        }
    }

    /** Clamp, persist atomically, emit, and return the stored config. */
    fun update(new: DashConfig): DashConfig {
        val clamped = new.clamped()
        write(clamped)
        return clamped
    }

    private fun write(cfg: DashConfig) {
        synchronized(lock) {
            val tmp = File(dir, "config.json.tmp")
            tmp.writeText(ConfigJson.json.encodeToString(DashConfig.serializer(), cfg))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    throw java.io.IOException("Failed to persist config.json: rename from ${tmp.path} failed")
                }
            }
            _config.value = cfg
        }
    }
}
