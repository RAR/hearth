package com.rar.echodash.config

import com.rar.echodash.ha.parseEntityRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test
    fun preExistingBadFileDoesNotBlockCorruptFileRecovery() {
        val dir = tempDir()
        File(dir, "config.json.bad").writeText("stale bytes from a previous corruption")
        File(dir, "config.json").writeText("{ still not valid json")
        val store = ConfigStore(dir)
        assertTrue(store.needsSeed())                        // corrupt => treat as fresh
        assertEquals(DashConfig(), store.config.value)       // defaults until seeded
        val bad = File(dir, "config.json.bad")
        assertTrue(bad.exists())
        // the stale .bad must have been replaced, not left in place blocking the rename
        assertEquals("{ still not valid json", bad.readText())
        assertFalse(File(dir, "config.json").exists())       // corrupt original moved out of the way

        // the store still works normally afterwards (corruption recovery doesn't wedge it)
        store.seedFrom(reg("""[{"entity_id":"weather.home","labels":["echo-weather"]}]"""))
        assertFalse(store.needsSeed())
        assertEquals("weather.home", store.config.value.entities.weather)
    }

    @Test
    fun concurrentUpdatesDoNotCorruptStoreOrDisk() {
        val dir = tempDir()
        val store = ConfigStore(dir)
        val threadCount = 16
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val errors = ConcurrentLinkedQueue<Throwable>()

        val threads = (0 until threadCount).map { i ->
            Thread {
                ready.countDown()
                start.await()
                try {
                    store.update(DashConfig(home = HomeSettings(idleReturnSeconds = 15 + i)))
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }
        }
        threads.forEach { it.start() }
        ready.await()
        start.countDown()
        done.await()

        assertTrue("no exceptions expected from concurrent update(): $errors", errors.isEmpty())

        // The store's in-memory value and the on-disk file must agree: no torn writes, no lost
        // update where the StateFlow moved on but the file (or vice versa) reflects a stale value.
        val onDisk = decodeConfig(File(dir, "config.json").readText())
        assertEquals(onDisk, store.config.value)
    }
}
