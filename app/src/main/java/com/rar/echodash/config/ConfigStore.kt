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
 */
class ConfigStore(
    private val dir: File,
    private val seeder: (RegistryIndex) -> DashConfig = ::seedConfig,
) {
    private val file = File(dir, "config.json")
    private val _config = MutableStateFlow(DashConfig())
    val config: StateFlow<DashConfig> = _config
    private var persisted = false

    init {
        if (!dir.exists()) dir.mkdirs()
        if (file.exists()) {
            val loaded = runCatching { decodeConfig(file.readText()) }.getOrNull()
            if (loaded != null) {
                _config.value = loaded.clamped()
                persisted = true
            } else {
                runCatching { file.renameTo(File(dir, "config.json.bad")) }
                android.util.Log.w("ConfigStore", "config.json corrupt; renamed to config.json.bad")
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
        val tmp = File(dir, "config.json.tmp")
        tmp.writeText(ConfigJson.json.encodeToString(DashConfig.serializer(), cfg))
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse rename onto an existing file; fall back to delete + rename.
            file.delete()
            tmp.renameTo(file)
        }
        _config.value = cfg
        persisted = true
    }
}
