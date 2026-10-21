package com.boogie.vibestation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.boogie.vibestation.models.Song
import com.boogie.vibestation.util.CoverImage
import com.boogie.vibestation.util.PendingDeletions
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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

    private fun tempAudio(content: String = "AUDIO-BYTES", extension: String = ".mp3") =
        File.createTempFile("song", extension).apply {
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

    private val coversDir = File.createTempFile("covers", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }
    private val coverA = "a".repeat(32) + ".png"

    private fun playlistsJson(vararg names: String) =
        names.joinToString(prefix = "[", postfix = "]") {
            """{"name":"$it","description":"","isFire":false,"cover":"","imageUri":"","lastModified":1,"songData":[]}"""
        }

    private fun syncPlaylists(
        prefs: SharedPreferences,
        readImage: (String) -> CoverImage? = { null },
        url: String = baseUrl
    ) =
        PlaylistSyncManager.sync(prefs, client, url, coversDir, readImage)

    private fun contextWithPrefs(stored: String, values: MutableMap<String, String> = mutableMapOf("playlists" to stored)): Context {
        val (prefs, _) = fakePrefs(values)
        return mockk<Context>().also {
            every { it.getSharedPreferences("RetroPrefs", 0) } returns prefs
            every { it.filesDir } returns coversDir
            every { it.contentResolver } returns mockk(relaxed = true)
        }
    }

    /**
     * Verifies playlists go out with the pending deletions and the server's merged answer replaces the
     * local copy, with the delivered deletions cleared.
     */
    @Test
    fun syncPlaylistsPostsPlaylistsAndDeletionsAndStoresMergedResult() {
        server.enqueue(json("""[{"name":"Merged","description":"d","isFire":true,"cover":"","lastModified":9,"songData":[]}]"""))
        val (prefs, values) = fakePrefs(mutableMapOf("playlists" to playlistsJson("Local")))
        PendingDeletions(prefs).recordPlaylists(listOf("Gone"), 10)

        syncPlaylists(prefs)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/playlists", request.path)
        assertContains(request.getHeader("Content-Type").orEmpty(), "application/json")
        val body = JSONObject(request.body.readUtf8())
        assertEquals("Local", body.getJSONArray("playlists").getJSONObject(0).getString("name"))
        assertEquals(10, body.getJSONObject("deleted").getJSONObject("playlists").getLong("Gone"))
        val stored = JSONArray(values.getValue("playlists")).getJSONObject(0)
        assertEquals("Merged", stored.getString("name"))
        assertTrue(stored.getBoolean("isFire"))
        assertEquals(0, PendingDeletions(prefs).read().getJSONObject("playlists").length())
    }

    /**
     * Verifies a failed sync leaves local playlists and pending deletions untouched, and that network
     * errors are swallowed.
     */
    @Test
    fun syncPlaylistsKeepsLocalDataOnFailure() {
        server.enqueue(json("error", code = 500))
        val (prefs, values) = fakePrefs(mutableMapOf("playlists" to playlistsJson("Local")))
        PendingDeletions(prefs).recordPlaylists(listOf("Gone"), 10)
        val before = values.toMap()

        syncPlaylists(prefs)
        syncPlaylists(prefs, url = "http://127.0.0.1:1")

        assertEquals(before, values)
    }

    /**
     * Verifies an image that has no server cover yet is uploaded at original quality and the stored name
     * is sent with the playlist.
     */
    @Test
    fun syncPlaylistsUploadsPendingCoverBeforePosting() {
        server.enqueue(json("""{"cover":"$coverA"}"""))
        server.enqueue(json("[]"))
        val stored = """[{"name":"Mix","imageUri":"content://img/1","cover":"","songData":[]}]"""
        val (prefs, _) = fakePrefs(mutableMapOf("playlists" to stored))
        val image = CoverImage(byteArrayOf(1, 2, 3), "image/png")

        syncPlaylists(prefs, readImage = { if (it == "content://img/1") image else null })

        val upload = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/covers", upload.path)
        assertContains(upload.getHeader("Content-Type").orEmpty(), "multipart/form-data")
        assertContains(upload.body.readUtf8(), "name=\"cover\"")
        val post = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals(coverA, post.getJSONArray("playlists").getJSONObject(0).getString("cover"))
    }

    /**
     * Verifies a cover that cannot be read or uploaded does not stop the playlists from syncing.
     */
    @Test
    fun syncPlaylistsStillPostsWhenCoverUploadFails() {
        server.enqueue(json("bad", code = 400))
        server.enqueue(json(playlistsJson("Mix")))
        val stored = """[{"name":"Mix","imageUri":"content://img/1","cover":"","songData":[]}]"""
        val (prefs, values) = fakePrefs(mutableMapOf("playlists" to stored))

        syncPlaylists(prefs, readImage = { CoverImage(byteArrayOf(1), "image/png") })
        server.takeRequest(5, TimeUnit.SECONDS)

        val post = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("", post.getJSONArray("playlists").getJSONObject(0).getString("cover"))
        assertEquals("Mix", JSONArray(values.getValue("playlists")).getJSONObject(0).getString("name"))
    }

    /**
     * Verifies a cover the device does not have is downloaded with the password header and linked.
     */
    @Test
    fun syncPlaylistsDownloadsNewServerCover() {
        server.enqueue(json("""[{"name":"Mix","cover":"$coverA","songData":[]}]"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("PNG-BYTES"))
        val (prefs, values) = fakePrefs(mutableMapOf("playlists" to playlistsJson("Mix")))
        val authed = SyncManager.buildClient("test-password")

        PlaylistSyncManager.sync(prefs, authed, baseUrl, coversDir) { null }

        server.takeRequest(5, TimeUnit.SECONDS)
        val download = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/covers/$coverA", download.path)
        assertEquals("test-password", download.getHeader(SyncManager.PASSWORD_HEADER))
        assertEquals("PNG-BYTES", File(coversDir, coverA).readText())
        val storedUri = JSONArray(values.getValue("playlists")).getJSONObject(0).getString("imageUri")
        assertEquals("file://${File(coversDir, coverA).absolutePath}", storedUri)
        assertTrue(coversDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    /**
     * Verifies edits made while the request was in flight are not overwritten by the older merged answer.
     */
    @Test
    fun syncPlaylistsDoesNotOverwriteEditsMadeDuringTheSync() {
        val (prefs, values) = fakePrefs(mutableMapOf("playlists" to playlistsJson("Local")))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                values["playlists"] = playlistsJson("Local", "EditedMeanwhile")
                return json(playlistsJson("Local"))
            }
        }

        syncPlaylists(prefs)

        assertEquals(playlistsJson("Local", "EditedMeanwhile"), values["playlists"])
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

    private fun runSync(context: Context, local: List<Song>, password: String = ""): Recorder {
        val recorder = Recorder()
        SyncManager.startSync(context, baseUrl, password, local, recorder)
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

    // password

    /**
     * Verifies the password rides on every request of a run, not only the catalog call.
     */
    @Test
    fun startSyncSendsPasswordOnEveryRequest() {
        server.enqueue(json("[]"))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(json("[]"))

        val recorder = runSync(contextWithPrefs("[]"), listOf(song("Up", path = tempAudio().absolutePath)), password = "test-password")

        assertTrue(recorder.events.last().startsWith("complete"))
        val headers = List(3) { server.takeRequest(5, TimeUnit.SECONDS)?.getHeader(SyncManager.PASSWORD_HEADER) }
        assertEquals(listOf("test-password", "test-password", "test-password"), headers)
    }

    /**
     * Verifies a blank password sends no header, so servers without a password keep working.
     */
    @Test
    fun buildClientOmitsHeaderForBlankPassword() {
        server.enqueue(json("[]"))

        SyncManager.fetchRemoteSongs(SyncManager.buildClient(" "), baseUrl)

        assertEquals(null, server.takeRequest(5, TimeUnit.SECONDS)?.getHeader(SyncManager.PASSWORD_HEADER))
    }

    /**
     * Verifies a rejected password aborts the run with a message the user can act on.
     */
    @Test
    fun startSyncReportsRejectedPassword() {
        server.enqueue(json("""{"error":"Authentication required"}""", code = 401))

        val recorder = runSync(contextWithPrefs("[]"), emptyList(), password = "wrong")

        assertContains(recorder.events.last(), "Wrong or missing sync password")
        assertEquals(1, server.requestCount)
    }

    // normalizeServerUrl

    /**
     * Verifies typed addresses gain a scheme and lose surrounding whitespace and trailing slashes.
     */
    @Test
    fun normalizeServerUrlAddsSchemeAndTrimsSlashes() {
        assertEquals("http://192.168.1.5:1337", SyncManager.normalizeServerUrl(" 192.168.1.5:1337/ "))
        assertEquals("https://pi.local", SyncManager.normalizeServerUrl("https://pi.local//"))
        assertEquals("", SyncManager.normalizeServerUrl("   "))
    }

    // formats

    /**
     * Verifies local files the server cannot store are never uploaded, so they are not retried on every sync.
     */
    @Test
    fun startSyncSkipsFormatsTheServerCannotStore() {
        server.enqueue(json("[]"))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(json("[]"))
        val flac = tempAudio("FLAC", ".flac")
        val wma = tempAudio("WMA", ".wma")

        val local = listOf(song("Lossless", path = flac.absolutePath), song("Windows", path = wma.absolutePath))
        val recorder = runSync(contextWithPrefs("[]"), local)

        assertEquals("complete Uploaded 1 tracks, Downloaded 0 tracks.", recorder.events.last())
        val upload = List(3) { server.takeRequest(5, TimeUnit.SECONDS) }[1]!!
        val body = upload.body.readUtf8()
        assertContains(body, "filename=\"${flac.name}\"")
        assertContains(body, "Content-Type: audio/flac")
        assertTrue(wma.name !in body)
    }

    /**
     * Verifies the server-reported format is parsed, and left blank for servers that do not report one.
     */
    @Test
    fun fetchRemoteSongsReadsFormat() {
        server.enqueue(json("""[{"id":"1","title":"A","artist":"B","album":"C","format":"flac"},{"id":"2","title":"D"}]"""))

        val songs = SyncManager.fetchRemoteSongs(client, baseUrl)

        assertEquals(listOf("flac", ""), songs.map { it.format })
    }
}
