package com.rar.echodash.photos

import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoStoreTest {

    private class FakeHaClient(val browse: JsonElement?) : HaClient {
        override val connectionState = MutableStateFlow(ConnState.OFFLINE)
        override suspend fun request(type: String, fields: JsonObject): JsonElement? =
            if (type == "media_source/browse_media") browse else null
        override suspend fun subscribe(type: String, fields: JsonObject, onEvent: (JsonObject) -> Unit) = 0
        override suspend fun unsubscribe(subId: Int) {}
    }

    private val browseJson = Json.parseToJsonElement(
        """{"children":[
            {"title":"a.jpg","media_class":"image","media_content_id":"media-source://media_source/local/echo-frame/a.jpg"},
            {"title":"b.png","media_class":"image","media_content_id":"media-source://media_source/local/echo-frame/b.png"},
            {"title":"notes.txt","media_class":"document","media_content_id":"x/notes.txt"}
        ]}"""
    )

    @Test
    fun parsesOnlyImageChildren() {
        val photos = parseBrowseChildren(browseJson)
        assertEquals(listOf("a.jpg", "b.png"), photos.map { it.title })
    }

    @Test
    fun diffFindsNewAndRemoved() {
        val remote = parseBrowseChildren(browseJson)
        val cached = setOf(cacheKey("media-source://media_source/local/echo-frame/a.jpg"), "stale-key")
        val diff = diffPhotos(cached, remote)
        assertEquals(listOf("b.png"), diff.toDownload.map { it.title })
        assertEquals(listOf("stale-key"), diff.toDeleteKeys)
    }

    @Test
    fun syncDownloadsNewDeletesStaleAndPublishesFiles() = runTest {
        val cacheDir = File.createTempFile("photocache", "").let { it.delete(); it.mkdirs(); it }
        // pre-seed a stale cached file that is no longer remote
        File(cacheDir, "stale-key").writeText("old")
        val downloaded = mutableListOf<String>()
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? {
                downloaded += contentId
                return File(cacheDir, cacheKey).apply { writeText("img") }
            }
        }
        val store = PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, this)
        store.sync()
        assertEquals(2, downloaded.size)                       // a.jpg + b.png
        assertTrue(!File(cacheDir, "stale-key").exists())      // stale deleted
        assertEquals(2, store.photos.value.size)               // published
        cacheDir.deleteRecursively()
    }

    @Test
    fun schedulerSyncsOnConnectAndEverySixHours() = runTest {
        val cacheDir = File.createTempFile("photocache2", "").let { it.delete(); it.mkdirs(); it }
        var syncs = 0
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val conn = MutableStateFlow(ConnState.OFFLINE)
        // subclass to count syncs deterministically
        // start() launches never-completing observers (connectionState.collect + periodic-sync
        // loop); per repo convention (see EntityHubTest) these go on backgroundScope, which
        // TestScope auto-cancels at teardown instead of failing the test as leaked jobs.
        val store = object : PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, backgroundScope, syncIntervalMs = 6 * 60 * 60_000L) {
            override suspend fun sync() { syncs++ }
        }
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, syncs)                                  // start/reconnect trigger
        advanceTimeBy(6 * 60 * 60_000L + 1); runCurrent()
        assertEquals(2, syncs)                                  // 6h periodic
        cacheDir.deleteRecursively()
    }

    @Test
    fun syncSerializesConcurrentInvocations() = runTest {
        val cacheDir = File.createTempFile("photocache3", "").let { it.delete(); it.mkdirs(); it }
        // First sync's browse call blocks on this gate until released, so we can observe whether
        // a concurrently-launched second sync starts its own browse call before the first finishes.
        val gate = CompletableDeferred<Unit>()
        var browseCalls = 0
        val order = mutableListOf<String>()
        val client = object : HaClient {
            override val connectionState = MutableStateFlow(ConnState.OFFLINE)
            override suspend fun request(type: String, fields: JsonObject): JsonElement? {
                if (type != "media_source/browse_media") return null
                browseCalls++
                val n = browseCalls
                order += "start-$n"
                if (n == 1) gate.await()
                order += "end-$n"
                return browseJson
            }
            override suspend fun subscribe(type: String, fields: JsonObject, onEvent: (JsonObject) -> Unit) = 0
            override suspend fun unsubscribe(subId: Int) {}
        }
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val store = PhotoStore(client, downloader, cacheDir, this)
        val job1 = launch { store.sync() }
        runCurrent()
        val job2 = launch { store.sync() }
        runCurrent()
        // second sync must not have started its browse call while the first is still in-flight
        assertEquals(1, browseCalls)
        gate.complete(Unit)
        job1.join()
        job2.join()
        assertEquals(listOf("start-1", "end-1", "start-2", "end-2"), order)
        cacheDir.deleteRecursively()
    }
}
