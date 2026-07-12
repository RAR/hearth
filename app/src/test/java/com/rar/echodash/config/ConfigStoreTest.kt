package com.rar.echodash.config

import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigStoreTest {
    private fun tempDir(): File =
        File.createTempFile("cfgstore", "").let { it.delete(); it.mkdirs(); it }

    private fun reg(s: String) = parseEntityRegistry(Json.parseToJsonElement(s))

    @Test
    fun freshDirNeedsSeedAndHoldsDefaults() {
        val store = ConfigStore(tempDir())
        assertTrue(store.needsSeed())
        assertEquals(DashConfig(), store.config.value)
    }

    @Test
    fun seedFromPersistsAndClearsNeedsSeed() {
        val dir = tempDir()
        val store = ConfigStore(dir)
        store.seedFrom(reg("""[{"entity_id":"weather.home","labels":["echo-weather"]}]"""))
        assertFalse(store.needsSeed())
        assertEquals("weather.home", store.config.value.entities.weather)
        // a new store over the same dir loads the persisted config and does NOT need seeding
        val reopened = ConfigStore(dir)
        assertFalse(reopened.needsSeed())
        assertEquals("weather.home", reopened.config.value.entities.weather)
    }

    @Test
    fun updateClampsPersistsAndEmits() {
        val dir = tempDir()
        val store = ConfigStore(dir)
        val stored = store.update(
            DashConfig(home = HomeSettings(idleReturnSeconds = 5, photoCacheCap = 9000))
        )
        assertEquals(15, stored.home.idleReturnSeconds)   // clamped
        assertEquals(500, stored.home.photoCacheCap)      // clamped
        assertEquals(stored, store.config.value)
        assertFalse(store.needsSeed())
        assertEquals(stored, ConfigStore(dir).config.value) // survives reload
    }

    @Test
    fun corruptFileIsRenamedToBadAndReseedable() {
        val dir = tempDir()
        File(dir, "config.json").writeText("{ this is not json")
        val store = ConfigStore(dir)
        assertTrue(store.needsSeed())                       // corrupt => treat as fresh
        assertTrue(File(dir, "config.json.bad").exists())   // corrupt file preserved
        assertEquals(DashConfig(), store.config.value)      // defaults until seeded
    }
}
