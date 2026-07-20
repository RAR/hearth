package com.rar.hearth.photos

import com.rar.hearth.config.DashConfig
import com.rar.hearth.config.HomeSettings
import com.rar.hearth.ha.ConnState
import com.rar.hearth.ha.HaClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoStoreTest {

    /** Answers browse for whatever folder id is requested; records the requested content ids. */
    private class FakeHaClient(val browse: JsonElement?) : HaClient {
        override val connectionState = MutableStateFlow(ConnState.OFFLINE)
        val browseContentIds = mutableListOf<String>()
        override suspend fun request(type: String, fields: JsonObject): JsonElement? {
            if (type == "media_source/browse_media") {
                (fields["media_content_id"] as? JsonPrimitive)?.contentOrNull?.let { browseContentIds += it }
                return browse
            }
            return null
        }
        override suspend fun subscribe(type: String, fields: JsonObject, onEvent: (JsonObject) -> Unit) = 0
        override suspend fun unsubscribe(subId: Int) {}
    }

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

    private val browseJson = Json.parseToJsonElement(
        """{"children":[
            {"title":"a.jpg","media_class":"image","media_content_id":"media-source://media_source/local/echo-frame/a.jpg"},
            {"title":"b.png","media_class":"image","media_content_id":"media-source://media_source/local/echo-frame/b.png"}
        ]}"""
    )

    private fun cfg(folder: String = "echo-frame", cap: Int = 50, slideshow: Boolean = true) =
        DashConfig(home = HomeSettings(photoFolder = folder, photoCacheCap = cap, slideshowEnabled = slideshow))

    @Test
    fun syncDownloadsNewDeletesStaleAndPublishesFiles() = runTest {
        val cacheDir = tempDir("photocache")
        File(cacheDir, "stale-key").writeText("old")
        val downloaded = mutableListOf<String>()
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? {
                downloaded += contentId
                return File(cacheDir, cacheKey).apply { writeText("img") }
            }
        }
        val store = PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, this, MutableStateFlow(cfg()))
        store.sync()
        assertEquals(2, downloaded.size)
        assertTrue(!File(cacheDir, "stale-key").exists())
        assertEquals(2, store.photos.value.size)
        cacheDir.deleteRecursively()
    }

    @Test
    fun syncUsesConfiguredFolderForBrowse() = runTest {
        val cacheDir = tempDir("photocache_folder")
        val client = FakeHaClient(browseJson)
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? =
                File(cacheDir, cacheKey).apply { writeText("img") }
        }
        val store = PhotoStore(client, downloader, cacheDir, this, MutableStateFlow(cfg(folder = "nas-photos")))
        store.sync()
        assertEquals(
            listOf("media-source://media_source/local/nas-photos"),
            client.browseContentIds,
        )
        cacheDir.deleteRecursively()
    }

    @Test
    fun disabledSlideshowSkipsSync() = runTest {
        val cacheDir = tempDir("photocache_disabled")
        val client = FakeHaClient(browseJson)
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val store = PhotoStore(client, downloader, cacheDir, this, MutableStateFlow(cfg(slideshow = false)))
        store.sync()
        assertTrue(client.browseContentIds.isEmpty()) // never browsed
        cacheDir.deleteRecursively()
    }

    @Test
    fun schedulerSyncsOnConnectAndEverySixHours() = runTest {
        val cacheDir = tempDir("photocache2")
        var syncs = 0
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val store = object : PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, backgroundScope, MutableStateFlow(cfg()), syncIntervalMs = 6 * 60 * 60_000L) {
            override suspend fun sync() { syncs++ }
        }
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, syncs)
        advanceTimeBy(6 * 60 * 60_000L + 1); runCurrent()
        assertEquals(2, syncs)
        cacheDir.deleteRecursively()
    }

    @Test
    fun resyncsWhenFolderOrCapChanges() = runTest {
        val cacheDir = tempDir("photocache_cfgchange")
        var syncs = 0
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val config = MutableStateFlow(cfg(folder = "echo-frame", cap = 50))
        val store = object : PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, backgroundScope, config) {
            override suspend fun sync() { syncs++ }
        }
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, syncs)                              // connect trigger
        config.value = cfg(folder = "new-folder", cap = 50); runCurrent()
        assertEquals(2, syncs)                              // folder change resyncs
        config.value = cfg(folder = "new-folder", cap = 25); runCurrent()
        assertEquals(3, syncs)                              // cap change resyncs
        // re-emitting an identical (folder, cap, slideshow) triple must not resync:
        config.value = cfg(folder = "new-folder", cap = 25); runCurrent()
        assertEquals(3, syncs)                              // identical config => distinctUntilChanged dedups, no extra sync
        cacheDir.deleteRecursively()
    }

    @Test
    fun resyncsWhenSlideshowEnabledFromDisabled() = runTest {
        val cacheDir = tempDir("photocache_slideshow_enable")
        var syncs = 0
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val config = MutableStateFlow(cfg(folder = "echo-frame", cap = 50, slideshow = false))
        val store = object : PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, backgroundScope, config) {
            override suspend fun sync() { syncs++ }
        }
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, syncs)                              // connect trigger (sync() itself would no-op when disabled; here it's stubbed)
        config.value = cfg(folder = "echo-frame", cap = 50, slideshow = true); runCurrent()
        assertEquals(2, syncs)                              // enabling the slideshow (folder/cap unchanged) resyncs
        cacheDir.deleteRecursively()
    }

    @Test
    fun secondStartIsNoOp() = runTest {
        val cacheDir = tempDir("photocache4")
        var syncs = 0
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val store = object : PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, backgroundScope, MutableStateFlow(cfg())) {
            override suspend fun sync() { syncs++ }
        }
        store.start(conn)
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, syncs)
        cacheDir.deleteRecursively()
    }
}
