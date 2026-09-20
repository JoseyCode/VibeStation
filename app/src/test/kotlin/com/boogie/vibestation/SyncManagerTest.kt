package com.boogie.vibestation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.boogie.vibestation.models.Song
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for SyncManager: match-key and filename rules, the server catalog/upload/playlist HTTP
 * exchanges (against a local MockWebServer), batch transfer bookkeeping, and the end-to-end run.
 * MediaStore download (ContentValues/scoped storage) is not covered here.
 */
class SyncManagerTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var baseUrl: String

    @BeforeTest
    fun setUp() {
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    private fun song(title: String, artist: String = "Artist", path: String? = null) =
        Song("id-$title", title, artist, path, "alb", "Album", 1, 0L)

    private fun tempAudio(content: String = "AUDIO-BYTES") =
        File.createTempFile("song", ".mp3").apply {
            writeText(content)
            deleteOnExit()
        }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    // makeMatchKey

    /**
     * Verifies case, whitespace, and punctuation differences do not change a track's match key.
     */
    @Test
    fun matchKeyNormalizesCaseWhitespaceAndPunctuation() {
        val key = SyncManager.makeMatchKey("  Get Lucky! ", "Daft Punk")

        assertEquals("getlucky_daftpunk", key)
        assertEquals(key, SyncManager.makeMatchKey("get   lucky", "DAFT PUNK"))
    }

    /**
     * Verifies letters from any script and digits survive normalization.
     */
    @Test
    fun matchKeyKeepsUnicodeLettersAndDigits() {
        assertEquals("beyoncé_halo", SyncManager.makeMatchKey("Beyoncé", "Halo"))
        assertEquals("track1_band2", SyncManager.makeMatchKey("Track 1", "Band 2"))
    }

    /**
     * Verifies a fully blank title and artist collapse to one sentinel, but a single blank side does not.
     */
    @Test
    fun matchKeyHandlesBlankMetadata() {
        assertEquals("unknown_track", SyncManager.makeMatchKey("  ", ""))
        assertEquals("_artist", SyncManager.makeMatchKey("", "Artist"))
    }

    /**
     * Characterizes a known weakness: underscores are kept, so the title/artist boundary is ambiguous and
     * ("a_b", "c") collides with ("a", "b_c"). A collision makes sync treat two tracks as one.
     */
    @Test
    fun matchKeyCanCollideAcrossTheSeparator() {
        assertEquals(SyncManager.makeMatchKey("a_b", "c"), SyncManager.makeMatchKey("a", "b_c"))
    }

    // safeFileName

    /**
     * Verifies characters that are invalid on common filesystems, and control characters, become underscores.
     */
    @Test
    fun safeFileNameReplacesInvalidCharacters() {
        assertEquals("AC_DC_ Back_In_Black_ live_", SyncManager.safeFileName("""AC/DC: Back*In?Black" live|"""))
        assertEquals("a_b_c", SyncManager.safeFileName("a\\b<c"))
        assertEquals("tab_here", SyncManager.safeFileName("tab\there"))
    }

    /**
     * Verifies ordinary names, spaces, and non-ASCII letters pass through unchanged.
     */
    @Test
    fun safeFileNameKeepsValidNames() {
        assertEquals("Get Lucky (feat. Pharrell)", SyncManager.safeFileName("Get Lucky (feat. Pharrell)"))
        assertEquals("Beyoncé", SyncManager.safeFileName("Beyoncé"))
    }

    /**
     * Verifies a blank name gets a generated unique track name instead of an empty filename.
     */
    @Test
    fun safeFileNameGeneratesNameWhenBlank() {
        val name = SyncManager.safeFileName("   ")

        assertTrue(name.startsWith("track_"))
        assertNotEquals("track_", name)
    }

    // fetchRemoteSongs

    /**
     * Verifies the catalog request path and that each JSON entry maps to a remote song.
     */
    @Test
    fun fetchRemoteSongsParsesCatalog() {
        server.enqueue(
            json(
                """[{"id":"7","title":"Nightcall","artist":"Kavinsky","album":"OutRun"},""" +
                    """{"id":"8","title":"B","artist":"C","album":"D"}]"""
            )
        )

        val songs = SyncManager.fetchRemoteSongs(client, baseUrl)

        assertEquals("/api/songs", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals(listOf("7", "8"), songs.map { it.id })
        assertEquals("Nightcall", songs[0].title)
        assertEquals("Kavinsky", songs[0].artist)
        assertEquals("OutRun", songs[0].album)
    }

    /**
     * Verifies missing fields fall back to display defaults and an empty catalog is valid.
     */
    @Test
    fun fetchRemoteSongsAppliesDefaultsAndAllowsEmptyCatalog() {
        server.enqueue(json("""[{}]"""))
        server.enqueue(json("[]"))

        val defaults = SyncManager.fetchRemoteSongs(client, baseUrl).single()

        assertEquals("", defaults.id)
        assertEquals("Unknown Title", defaults.title)
        assertEquals("Unknown Artist", defaults.artist)
        assertEquals("Unknown Album", defaults.album)
        assertTrue(SyncManager.fetchRemoteSongs(client, baseUrl).isEmpty())
    }

    /**
     * Verifies HTTP failures surface as IOException carrying the status, and bad JSON as JSONException.
     */
    @Test
    fun fetchRemoteSongsSurfacesServerAndParseErrors() {
        server.enqueue(json("oops", code = 503))
        server.enqueue(json("not json"))

        val http = assertFailsWith<IOException> { SyncManager.fetchRemoteSongs(client, baseUrl) }
        assertContains(http.message.orEmpty(), "503")
        assertFailsWith<JSONException> { SyncManager.fetchRemoteSongs(client, baseUrl) }
    }

    // transferAll

    /**
     * Verifies progress is reported before each item using the batch offset and overall total.
     */
    @Test
    fun transferAllReportsOffsetProgress() {
        val events = mutableListOf<Triple<Int, Int, String>>()
        val callback = object : SyncManager.SyncCallback {
            override fun onProgress(progress: Int, max: Int, message: String) {
                events += Triple(progress, max, message)
            }

            override fun onComplete(result: String) = Unit

            override fun onError(error: String) = Unit
        }

        val done = SyncManager.transferAll(listOf("A", "B"), "Uploading", "upload", 3, 5, callback, { it }) {}

        assertEquals(2, done)
        assertEquals(
            listOf(Triple(3, 5, "Uploading (4/5):\nA"), Triple(4, 5, "Uploading (5/5):\nB")),
            events
        )
    }

    /**
     * Verifies one failing item is logged and skipped while the rest of the batch still transfers.
     */
    @Test
    fun transferAllContinuesPastFailures() {
        val attempted = mutableListOf<String>()
        val callback = object : SyncManager.SyncCallback {
            override fun onProgress(progress: Int, max: Int, message: String) = Unit

            override fun onComplete(result: String) = Unit

            override fun onError(error: String) = Unit
        }

        val done = SyncManager.transferAll(listOf("A", "B", "C"), "Downloading", "download", 0, 3, callback, { it }) {
            attempted += it
            if (it == "B") throw IOException("boom")
        }

        assertEquals(listOf("A", "B", "C"), attempted)
        assertEquals(2, done)
        verify { Log.e(any(), match { it.contains("download") && it.contains("B") }, any()) }
    }

    // uploadSong

    /**
     * Verifies the file is posted as multipart form data under the "files" field with its name and bytes.
     */
    @Test
    fun uploadSongPostsMultipartFile() {
        server.enqueue(MockResponse().setResponseCode(200))
        val file = tempAudio("AUDIO-BYTES")

        SyncManager.uploadSong(client, baseUrl, song("Tune", path = file.absolutePath))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = request.body.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("/api/upload", request.path)
        assertContains(request.getHeader("Content-Type").orEmpty(), "multipart/form-data")
        assertContains(body, """name="files"""")
        assertContains(body, """filename="${file.name}"""")
        assertContains(body, "AUDIO-BYTES")
    }

    /**
     * Verifies a rejected upload throws with the song title so the batch can log which track failed.
     */
    @Test
    fun uploadSongThrowsOnServerRejection() {
        server.enqueue(MockResponse().setResponseCode(500))

        val failure = assertFailsWith<IOException> {
            SyncManager.uploadSong(client, baseUrl, song("Tune", path = tempAudio().absolutePath))
        }

        assertContains(failure.message.orEmpty(), "Tune")
    }

    /**
     * Verifies songs without a path, or whose file is gone, are skipped without contacting the server.
     */
    @Test
    fun uploadSongSkipsMissingFiles() {
        SyncManager.uploadSong(client, baseUrl, song("NoPath", path = null))
        SyncManager.uploadSong(client, baseUrl, song("Gone", path = "/definitely/not/here.mp3"))

        assertEquals(0, server.requestCount)
    }

    // syncPlaylists

    private fun contextWithPrefs(stored: String, saved: (String) -> Unit = {}): Context {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val value = slot<String>()
        every { prefs.getString("playlists", "[]") } returns stored
        every { prefs.edit() } returns editor
        every { editor.putString("playlists", capture(value)) } answers {
            saved(value.captured)
            editor
        }
        every { editor.apply() } just runs
        return mockk<Context>().also { every { it.getSharedPreferences("RetroPrefs", 0) } returns prefs }
    }

    /**
     * Verifies local playlists are posted as JSON and the server's merged answer replaces the local copy.
     */
    @Test
    fun syncPlaylistsPostsLocalAndStoresMergedResult() {
        server.enqueue(json("""[{"name":"Merged"}]"""))
        var stored: String? = null
        val context = contextWithPrefs("""[{"name":"Local"}]""") { stored = it }

        SyncManager.syncPlaylists(context, client, baseUrl)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/playlists", request.path)
        assertContains(request.getHeader("Content-Type").orEmpty(), "application/json")
        assertEquals("""[{"name":"Local"}]""", request.body.readUtf8())
        assertEquals("""[{"name":"Merged"}]""", stored)
    }

    /**
     * Verifies a failed sync response leaves local playlists untouched, and network errors are swallowed.
     */
    @Test
    fun syncPlaylistsKeepsLocalDataOnFailure() {
        server.enqueue(json("error", code = 500))
        var writes = 0
        val context = contextWithPrefs("""[{"name":"Local"}]""") { writes++ }

        SyncManager.syncPlaylists(context, client, baseUrl)
        SyncManager.syncPlaylists(context, client, "http://127.0.0.1:1")

        assertEquals(0, writes)
    }

    // startSync (end to end)

    private class Recorder : SyncManager.SyncCallback {
        val events = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)

        override fun onProgress(progress: Int, max: Int, message: String) {
            events += "progress $progress/$max $message"
        }

        override fun onComplete(result: String) {
            events += "complete $result"
            finished.countDown()
        }

        override fun onError(error: String) {
            events += "error $error"
            finished.countDown()
        }
    }

    private fun runSync(context: Context, local: List<Song>): Recorder {
        val recorder = Recorder()
        SyncManager.startSync(context, baseUrl, local, recorder)
        assertTrue(recorder.finished.await(15, TimeUnit.SECONDS), "sync did not finish")
        return recorder
    }

    /**
     * Verifies a local-only track is uploaded, an already-synced track is not, and playlists sync last.
     */
    @Test
    fun startSyncUploadsOnlyMissingTracksThenSyncsPlaylists() {
        server.enqueue(json("""[{"id":"1","title":"Shared","artist":"Artist","album":"A"}]"""))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(json("[]"))
        val local = listOf(
            song("Shared", path = tempAudio().absolutePath),
            song("OnlyLocal", path = tempAudio().absolutePath)
        )

        val recorder = runSync(contextWithPrefs("[]"), local)

        val paths = List(3) {
            server.takeRequest(5, TimeUnit.SECONDS)?.path
        }
        assertEquals(listOf("/api/songs", "/api/upload", "/api/playlists"), paths)
        assertEquals("complete Uploaded 1 tracks, Downloaded 0 tracks.", recorder.events.last())
        assertContains(recorder.events, "progress 0/0 Querying server library...")
        assertContains(recorder.events, "progress 0/1 Uploading (1/1):\nOnlyLocal")
        assertContains(recorder.events, "progress 1/1 Syncing playlists database...")
    }

    /**
     * Verifies matching ignores case and punctuation, so equivalent local and remote tracks transfer nothing.
     */
    @Test
    fun startSyncTreatsEquivalentMetadataAsSameTrack() {
        server.enqueue(json("""[{"id":"1","title":"get lucky!","artist":"DAFT PUNK","album":"A"}]"""))
        server.enqueue(json("[]"))

        val recorder = runSync(contextWithPrefs("[]"), listOf(song("Get Lucky", "Daft Punk", tempAudio().absolutePath)))

        assertEquals("complete Uploaded 0 tracks, Downloaded 0 tracks.", recorder.events.last())
        assertEquals(2, server.requestCount)
    }

    /**
     * Verifies a remote-only track is scheduled for download and the run still completes. The download
     * itself needs the Android MediaStore, so on the JVM it fails and is skipped by the batch.
     */
    @Test
    fun startSyncSchedulesRemoteOnlyTrackForDownload() {
        server.enqueue(json("""[{"id":"9","title":"OnlyRemote","artist":"Artist","album":"A"}]"""))
        server.enqueue(json("[]"))

        val recorder = runSync(contextWithPrefs("[]"), emptyList())

        assertContains(recorder.events, "progress 0/1 Downloading (1/1):\nOnlyRemote")
        assertTrue(recorder.events.last().startsWith("complete Uploaded 0 tracks"))
    }

    /**
     * Verifies a catalog failure aborts the run with an error and never touches uploads or playlists.
     */
    @Test
    fun startSyncReportsErrorWhenCatalogFails() {
        server.enqueue(json("down", code = 500))

        val recorder = runSync(contextWithPrefs("[]"), listOf(song("Any", path = tempAudio().absolutePath)))

        assertContains(recorder.events.last(), "Server error: 500")
        assertTrue(recorder.events.last().startsWith("error"))
        assertEquals(1, server.requestCount)
    }
}
