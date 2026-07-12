package com.rar.echodash.config

import com.rar.echodash.ha.RegistryIndex
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the DashConfig document at [dir]/config.json and exposes it as a StateFlow. Writes are
 * atomic (temp file + rename). A corrupt file is renamed to config.json.bad and the store falls back
 * to defaults, flagged as [needsSeed] so the caller can seed from labels once the registry arrives.
 * Android-free (java.io.File injected) so it runs in plain JVM tests.
 *
 * Thread-safe: [update] and [seedFrom] may be called concurrently from NanoHTTPD request threads
 * and app coroutines. The clamp -> persist -> emit sequence is serialized on an internal lock so
 * [config] never observes a value that wasn't (or won't be) written to disk. If persistence fails
 * outright, [update]/[seedFrom] throw [java.io.IOException] rather than reporting success.
 */
class ConfigStore(
    private val dir: File,
    private val seeder: (RegistryIndex) -> DashConfig = ::seedConfig,
) {
    private val file = File(dir, "config.json")
    private val _config = MutableStateFlow(DashConfig())
    val config: StateFlow<DashConfig> = _config
    @Volatile private var persisted = false

    /**
     * Guards the load/write mutation path (clamp -> persist -> emit). `update()` must stay a
     * plain non-suspend function (it's called from NanoHTTPD request threads as well as app
     * coroutines on Dispatchers.IO), so a monitor lock is used instead of a kotlinx Mutex.
     */
    private val lock = Any()

    init {
        if (!dir.exists()) dir.mkdirs()
        if (file.exists()) {
            val loaded = runCatching { decodeConfig(file.readText()) }.getOrNull()
            if (loaded != null) {
                _config.value = loaded.clamped()
                persisted = true
            } else {
                val bad = File(dir, "config.json.bad")
                bad.delete() // clear any stale .bad from a prior corruption so the rename below can't silently fail
                val renamed = file.renameTo(bad)
                if (renamed) {
                    android.util.Log.w("ConfigStore", "config.json corrupt; renamed to config.json.bad")
                } else {
                    android.util.Log.w("ConfigStore", "config.json corrupt; failed to rename to config.json.bad")
                }
            }
        }
    }

    /** True until a valid config has been persisted (fresh install or recovered corruption). */
    fun needsSeed(): Boolean = !persisted

    /** Seed from the registry, persist, and emit. No-op semantics: safe even with an empty registry. */
    fun seedFrom(registry: RegistryIndex) {
        write(seeder(registry).clamped())
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
                // Some filesystems refuse rename onto an existing file; fall back to delete + rename.
                file.delete()
                if (!tmp.renameTo(file)) {
                    // Persistence failed: do not update in-memory state so config/disk can't diverge.
                    // Callers (the HTTP PUT handler) catch and surface this as a 500.
                    throw java.io.IOException("Failed to persist config.json: rename from ${tmp.path} failed")
                }
            }
            _config.value = cfg
            persisted = true
        }
    }
}
