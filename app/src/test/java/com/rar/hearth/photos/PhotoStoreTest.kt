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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun ledgerIn(dir: File) = PhotoLedger(File(dir, "seen.txt"))

    /** Writes a real file per download so buffer/history/delete behavior is exercised for real. */
    private fun writingDownloader(cacheDir: File, log: MutableList<String>? = null) =
        object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File {
                log?.add(contentId)
                return File(cacheDir, cacheKey).apply { writeText("img") }
            }
        }

    private fun photoJson(vararg names: String) = Json.parseToJsonElement(
        names.joinToString(",", """{"children":[""", "]}") {
            """{"title":"$it","media_class":"image","media_content_id":"media-source://media_source/local/echo-frame/$it"}"""
        }
    )

    private val browseJson = photoJson("a.jpg", "b.png")

    private fun cfg(
        folder: String = "echo-frame",
        depth: Int = 20,
        slideshow: Boolean = true,
        intervalMinutes: Int = 360,
    ) = DashConfig(
        home = HomeSettings(
            photoFolder = folder,
            photoBufferDepth = depth,
            photoSyncIntervalMinutes = intervalMinutes,
            slideshowEnabled = slideshow,
        )
    )

    @Test
    fun refreshFillsBufferAndPublishesFirstPhoto() = runTest {
        val cacheDir = tempDir("photobuf")
        val ledgerDir = tempDir("photoledger")
        val downloaded = mutableListOf<String>()
        val store = PhotoStore(
            FakeHaClient(browseJson), writingDownloader(cacheDir, downloaded), cacheDir, this,
            MutableStateFlow(cfg()), ledgerIn(ledgerDir),
        )

        store.refresh()

        assertEquals(2, downloaded.size)
        assertTrue(store.current.value != null)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun displayedPhotosAreRecordedAndNeverRedrawn() = runTest {
        val cacheDir = tempDir("photoseen")
        val ledgerDir = tempDir("photoledger2")
        val ledger = ledgerIn(ledgerDir)
        // A depth well below the archive size, so the unseen pool never empties and no epoch
        // reset clears the ledger mid-test.
        val store = PhotoStore(
            FakeHaClient(photoJson("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg", "f.jpg")),
            writingDownloader(cacheDir), cacheDir, this, MutableStateFlow(cfg(depth = 2)), ledger,
        )

        store.refresh()
        val first = store.current.value!!
        store.advance()
        runCurrent()
        val second = store.current.value!!

        assertTrue(first.name in ledger.read())
        assertTrue(second.name in ledger.read())
        assertFalse(first.name == second.name)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun historyIsBoundedAndPastPhotosAreDeleted() = runTest {
        val cacheDir = tempDir("photohist")
        val ledgerDir = tempDir("photoledger3")
        // Archive far larger than the buffer, so the unseen pool never empties: an epoch reset
        // would make the archive eligible again and re-download the photos just deleted.
        val store = PhotoStore(
            FakeHaClient(photoJson(*Array(12) { "p$it.jpg" })),
            writingDownloader(cacheDir), cacheDir, this, MutableStateFlow(cfg(depth = 2)),
            ledgerIn(ledgerDir),
        )
        store.refresh()

        val shown = mutableListOf(store.current.value!!)
        repeat(5) { store.advance(); runCurrent(); shown += store.current.value!! }

        // current + HISTORY_DEPTH retained; everything older is gone from disk.
        val onDisk = cacheDir.listFiles()!!.map { it.name }.toSet()
        assertTrue(shown.first().name !in onDisk)
        assertTrue(shown.last().name in onDisk)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun backReturnsToThePreviousPhoto() = runTest {
        val cacheDir = tempDir("photoback")
        val ledgerDir = tempDir("photoledger4")
        val store = PhotoStore(
            FakeHaClient(photoJson("a.jpg", "b.jpg", "c.jpg")),
            writingDownloader(cacheDir), cacheDir, this, MutableStateFlow(cfg()), ledgerIn(ledgerDir),
        )
        store.refresh()
        val first = store.current.value!!
        store.advance(); runCurrent()

        store.back()

        assertEquals(first, store.current.value)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun backWithNoHistoryIsANoOp() = runTest {
        val cacheDir = tempDir("photoback2")
        val ledgerDir = tempDir("photoledger5")
        val store = PhotoStore(
            FakeHaClient(browseJson), writingDownloader(cacheDir), cacheDir, this,
            MutableStateFlow(cfg()), ledgerIn(ledgerDir),
        )
        store.refresh()
        val first = store.current.value

        store.back()

        assertEquals(first, store.current.value)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun emptyBufferCyclesRetainedPhotosWithoutDeleting() = runTest {
        val cacheDir = tempDir("photooffline")
        val ledgerDir = tempDir("photoledger6")
        File(cacheDir, "left-over-1").writeText("img")
        File(cacheDir, "left-over-2").writeText("img")
        // Browse returns nothing usable, so the buffer can never refill.
        val store = PhotoStore(
            FakeHaClient(null), writingDownloader(cacheDir), cacheDir, this,
            MutableStateFlow(cfg()), ledgerIn(ledgerDir),
        )

        // Drain the adopted buffer, then keep advancing past it.
        repeat(4) { store.advance(); runCurrent() }

        // Nothing was deleted -- the screen keeps cycling what it has.
        assertEquals(2, cacheDir.listFiles()!!.size)
        assertTrue(store.current.value != null)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun exhaustedArchiveClearsTheLedgerForAFreshEpoch() = runTest {
        val cacheDir = tempDir("photoepoch")
        val ledgerDir = tempDir("photoledger7")
        val ledger = ledgerIn(ledgerDir)
        // Both photos already shown in a previous epoch.
        ledger.add(
            listOf("a.jpg", "b.png").map {
                cacheKey("media-source://media_source/local/echo-frame/$it")
            }
        )
        val store = PhotoStore(
            FakeHaClient(browseJson), writingDownloader(cacheDir), cacheDir, this,
            MutableStateFlow(cfg()), ledger,
        )

        store.refresh()

        // The epoch reset cleared the ledger so the archive is eligible again, but whatever is
        // on screen stays recorded -- an immediate repeat of the visible photo is the one the
        // user would actually notice.
        val onScreen = store.current.value!!.name
        assertTrue(onScreen in ledger.read())
        assertTrue(ledger.read().size < 2)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun refreshUsesConfiguredFolderForBrowse() = runTest {
        val cacheDir = tempDir("photofolder")
        val ledgerDir = tempDir("photoledger8")
        val client = FakeHaClient(browseJson)
        val store = PhotoStore(
            client, writingDownloader(cacheDir), cacheDir, this,
            MutableStateFlow(cfg(folder = "nas-photos")), ledgerIn(ledgerDir),
        )

        store.refresh()

        assertEquals(listOf("media-source://media_source/local/nas-photos"), client.browseContentIds)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun disabledSlideshowSkipsRefresh() = runTest {
        val cacheDir = tempDir("photodisabled")
        val ledgerDir = tempDir("photoledger9")
        val client = FakeHaClient(browseJson)
        val store = PhotoStore(
            client, writingDownloader(cacheDir), cacheDir, this,
            MutableStateFlow(cfg(slideshow = false)), ledgerIn(ledgerDir),
        )

        store.refresh()

        assertTrue(client.browseContentIds.isEmpty())
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun adoptsLeftoverFilesBeforeAnyRefresh() = runTest {
        val cacheDir = tempDir("photoadopt")
        val ledgerDir = tempDir("photoledger10")
        File(cacheDir, "survivor").writeText("img")

        val store = PhotoStore(
            FakeHaClient(null), writingDownloader(cacheDir), cacheDir, this,
            MutableStateFlow(cfg()), ledgerIn(ledgerDir),
        )

        assertEquals("survivor", store.current.value?.name)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun emptyCacheAndNoNetworkLeavesNoCurrentPhoto() = runTest {
        val cacheDir = tempDir("photoempty")
        val ledgerDir = tempDir("photoledger11")

        val store = PhotoStore(
            FakeHaClient(null), writingDownloader(cacheDir), cacheDir, this,
            MutableStateFlow(cfg()), ledgerIn(ledgerDir),
        )

        assertNull(store.current.value)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun schedulerRefreshesOnConnectAndOnTheConfiguredInterval() = runTest {
        val cacheDir = tempDir("photosched")
        val ledgerDir = tempDir("photoledger12")
        var refreshes = 0
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val store = object : PhotoStore(
            FakeHaClient(browseJson), writingDownloader(cacheDir), cacheDir, backgroundScope,
            MutableStateFlow(cfg(intervalMinutes = 60)), ledgerIn(ledgerDir),
        ) {
            override suspend fun refresh() { refreshes++ }
        }

        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, refreshes)
        advanceTimeBy(60 * 60_000L + 1); runCurrent()
        assertEquals(2, refreshes)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun intervalChangeTakesEffectOnTheNextTick() = runTest {
        val cacheDir = tempDir("photointerval")
        val ledgerDir = tempDir("photoledger13")
        var refreshes = 0
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val config = MutableStateFlow(cfg(intervalMinutes = 360))
        val store = object : PhotoStore(
            FakeHaClient(browseJson), writingDownloader(cacheDir), cacheDir, backgroundScope,
            config, ledgerIn(ledgerDir),
        ) {
            override suspend fun refresh() { refreshes++ }
        }
        store.start(conn)
        runCurrent()

        // Shorten the interval, then let the first (still 360-minute) sleep elapse.
        config.value = cfg(intervalMinutes = 15)
        advanceTimeBy(360 * 60_000L + 1); runCurrent()
        val afterFirstTick = refreshes

        // The next sleep must be the NEW 15-minute one, not another 360.
        advanceTimeBy(15 * 60_000L + 1); runCurrent()

        assertEquals(afterFirstTick + 1, refreshes)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun refreshesWhenFolderDepthOrSlideshowFlagChanges() = runTest {
        val cacheDir = tempDir("photocfg")
        val ledgerDir = tempDir("photoledger14")
        var refreshes = 0
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val config = MutableStateFlow(cfg(folder = "echo-frame", depth = 20))
        val store = object : PhotoStore(
            FakeHaClient(browseJson), writingDownloader(cacheDir), cacheDir, backgroundScope,
            config, ledgerIn(ledgerDir),
        ) {
            override suspend fun refresh() { refreshes++ }
        }
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, refreshes)

        config.value = cfg(folder = "new-folder", depth = 20); runCurrent()
        assertEquals(2, refreshes)
        config.value = cfg(folder = "new-folder", depth = 30); runCurrent()
        assertEquals(3, refreshes)
        // An identical triple must not re-trigger.
        config.value = cfg(folder = "new-folder", depth = 30); runCurrent()
        assertEquals(3, refreshes)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }

    @Test
    fun secondStartIsNoOp() = runTest {
        val cacheDir = tempDir("photostart")
        val ledgerDir = tempDir("photoledger15")
        var refreshes = 0
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val store = object : PhotoStore(
            FakeHaClient(browseJson), writingDownloader(cacheDir), cacheDir, backgroundScope,
            MutableStateFlow(cfg()), ledgerIn(ledgerDir),
        ) {
            override suspend fun refresh() { refreshes++ }
        }

        store.start(conn)
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()

        assertEquals(1, refreshes)
        cacheDir.deleteRecursively(); ledgerDir.deleteRecursively()
    }
}
