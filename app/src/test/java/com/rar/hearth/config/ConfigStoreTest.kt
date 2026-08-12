package com.rar.hearth.config

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigStoreTest {
    private fun tempDir(): File =
        File.createTempFile("cfgstore", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun freshDirHoldsDefaults() {
        val store = ConfigStore(tempDir())
        assertEquals(DashConfig(), store.config.value)
    }

    @Test
    fun updateClampsPersistsAndEmits() {
        val dir = tempDir()
        val store = ConfigStore(dir)
        val stored = store.update(
            DashConfig(home = HomeSettings(idleReturnSeconds = 5, photoBufferDepth = 9000))
        )
        assertEquals(15, stored.home.idleReturnSeconds)   // clamped
        assertEquals(100, stored.home.photoBufferDepth)   // clamped
        assertEquals(stored, store.config.value)
        assertEquals(stored, ConfigStore(dir).config.value) // survives reload
    }

    @Test
    fun persistedConfigReloadsFromDisk() {
        val dir = tempDir()
        ConfigStore(dir).update(DashConfig(entities = Entities(weather = "weather.home")))
        val reopened = ConfigStore(dir)
        assertEquals("weather.home", reopened.config.value.entities.weather)
    }

    @Test
    fun corruptFileIsRenamedToBadAndFallsBackToDefaults() {
        val dir = tempDir()
        File(dir, "config.json").writeText("{ this is not json")
        val store = ConfigStore(dir)
        assertTrue(File(dir, "config.json.bad").exists())   // corrupt file preserved
        assertEquals(DashConfig(), store.config.value)      // defaults
    }

    @Test
    fun preExistingBadFileDoesNotBlockCorruptFileRecovery() {
        val dir = tempDir()
        File(dir, "config.json.bad").writeText("stale bytes from a previous corruption")
        File(dir, "config.json").writeText("{ still not valid json")
        val store = ConfigStore(dir)
        assertEquals(DashConfig(), store.config.value)       // defaults
        val bad = File(dir, "config.json.bad")
        assertTrue(bad.exists())
        assertEquals("{ still not valid json", bad.readText())
        assertFalse(File(dir, "config.json").exists())       // corrupt original moved out of the way

        // still works normally afterwards
        store.update(DashConfig(entities = Entities(weather = "weather.home")))
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
        val onDisk = decodeConfig(File(dir, "config.json").readText())
        assertEquals(onDisk, store.config.value)
    }
}
