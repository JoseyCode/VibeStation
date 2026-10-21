package com.boogie.vibestation.util

import com.boogie.vibestation.fakePrefs
import com.boogie.vibestation.models.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests for the pending-deletion store and the pure JSON side of the playlist sync exchange. */
class PlaylistSyncTest {

    private val coverA = "a".repeat(32) + ".png"
    private val coverB = "b".repeat(32) + ".jpg"
    private val coversDir: File = Files.createTempDirectory("covers").toFile().apply { deleteOnExit() }

    private fun song(title: String, artist: String = "Artist") =
        Song("id-$title", title, artist, null, "alb", "Album", 1, 0L)

    private fun playlist(
        name: String,
        vararg tracks: Triple<String, String, String>,
        extra: Map<String, Any> = emptyMap()
    ): JSONObject {
        val songs = JSONArray()
        for ((id, title, artist) in tracks) {
            songs.put(JSONObject().put("id", id).put("t", title).put("a", artist).put("addedAt", 5))
        }
        val playlist = JSONObject()
            .put("name", name)
            .put("description", "")
            .put("isFire", false)
            .put("lastModified", 7)
            .put("songData", songs)
        extra.forEach { (key, value) -> playlist.put(key, value) }
        return playlist
    }

    private fun array(vararg playlists: JSONObject) = JSONArray(playlists.toList())

    // PendingDeletions

    /** Deletions accumulate across calls and survive being read back from preferences. */
    @Test
    fun pendingDeletionsAccumulate() {
        val (prefs, _) = fakePrefs()
        val pending = PendingDeletions(prefs)

        pending.recordPlaylists(listOf("Gone", "OldName"), 10)
        pending.recordTracks("Mix", listOf(song("A"), song("B")), 20)

        val read = PendingDeletions(prefs).read()
        assertEquals(10, read.getJSONObject("playlists").getLong("Gone"))
        assertEquals(10, read.getJSONObject("playlists").getLong("OldName"))
        assertEquals(listOf("A", "B"), (0 until 2).map { read.getJSONArray("tracks").getJSONObject(it).getString("t") })
        assertEquals("Mix", read.getJSONArray("tracks").getJSONObject(0).getString("playlist"))
        assertEquals(20, read.getJSONArray("tracks").getJSONObject(0).getLong("at"))
    }

    /** Nothing is written when there is nothing to record. */
    @Test
    fun pendingDeletionsIgnoreEmptyInput() {
        val (prefs, values) = fakePrefs()
        PendingDeletions(prefs).recordPlaylists(emptyList())
        PendingDeletions(prefs).recordTracks("Mix", emptyList())
        assertTrue(values.isEmpty())
    }

    /** Unreadable stored text reads as no pending deletions instead of crashing the sync. */
    @Test
    fun pendingDeletionsSurviveCorruptStorage() {
        val (prefs, _) = fakePrefs(mutableMapOf("playlists_pending_deleted" to "not json"))
        val read = PendingDeletions(prefs).read()
        assertEquals(0, read.getJSONObject("playlists").length())
        assertEquals(0, read.getJSONArray("tracks").length())
    }

    /** Only what was delivered is removed; deletions recorded during the sync are kept for the next one. */
    @Test
    fun removeSentKeepsDeletionsMadeDuringTheSync() {
        val (prefs, _) = fakePrefs()
        val pending = PendingDeletions(prefs)
        pending.recordPlaylists(listOf("Old"), 10)
        pending.recordTracks("Mix", listOf(song("A")), 10)
        val sent = pending.read()

        pending.recordPlaylists(listOf("New"), 30)
        pending.recordTracks("Mix", listOf(song("B")), 30)
        pending.removeSent(sent)

        val left = pending.read()
        assertEquals(listOf("New"), left.getJSONObject("playlists").keys().asSequence().toList())
        assertEquals(1, left.getJSONArray("tracks").length())
        assertEquals("B", left.getJSONArray("tracks").getJSONObject(0).getString("t"))
    }

    /** A playlist deleted again after the request was built keeps its newer deletion time. */
    @Test
    fun removeSentKeepsNewerDeletionOfSameName() {
        val (prefs, _) = fakePrefs()
        val pending = PendingDeletions(prefs)
        pending.recordPlaylists(listOf("Mix"), 10)
        val sent = pending.read()
        pending.recordPlaylists(listOf("Mix"), 40)

        pending.removeSent(sent)

        assertEquals(40, pending.read().getJSONObject("playlists").getLong("Mix"))
    }

    // request building and cover selection

    /** The request carries both the playlists and the deletions under the keys the server reads. */
    @Test
    fun buildRequestWrapsPlaylistsAndDeleted() {
        val deleted = JSONObject().put("playlists", JSONObject().put("X", 1)).put("tracks", JSONArray())
        val body = JSONObject(PlaylistSync.buildRequest(array(playlist("Mix")), deleted))

        assertEquals("Mix", body.getJSONArray("playlists").getJSONObject(0).getString("name"))
        assertEquals(1, body.getJSONObject("deleted").getJSONObject("playlists").getLong("X"))
    }

    /** Only playlists with a local image and no uploaded cover need an upload. */
    @Test
    fun coversNeedingUploadSelectsPendingImages() {
        val playlists = array(
            playlist("Pending", extra = mapOf("imageUri" to "content://x/1", "cover" to "")),
            playlist("Uploaded", extra = mapOf("imageUri" to "content://x/2", "cover" to coverA)),
            playlist("NoImage", extra = mapOf("imageUri" to "", "cover" to "")),
            playlist("Bare")
        )
        assertEquals(listOf(0), PlaylistSync.coversNeedingUpload(playlists))
    }

    /** Only names the server issues are trusted as file names. */
    @Test
    fun isCoverNameRejectsAnythingElse() {
        assertTrue(PlaylistSync.isCoverName(coverA))
        assertFalse(PlaylistSync.isCoverName("../../evil.png"))
        assertFalse(PlaylistSync.isCoverName("a".repeat(32) + ".exe"))
        assertFalse(PlaylistSync.isCoverName(""))
    }

    // reconcile

    private val noDownload: (String, File) -> Boolean = { _, _ -> error("must not download") }

    private fun reconcile(sent: JSONArray, server: JSONArray, fetch: (String, File) -> Boolean = { _, _ -> false }) =
        PlaylistSync.reconcile(sent, server, coversDir, fetch)

    /** Fields and tracks come from the server, and the device's own track ids are kept where it has them. */
    @Test
    fun reconcileTakesServerDataButKeepsLocalTrackIds() {
        val sent = array(playlist("Mix", Triple("12", "Get Lucky", "Daft Punk")))
        val fromWeb = mapOf("description" to "from web", "isFire" to true, "lastModified" to 99)
        val server = array(
            playlist("Mix", Triple("c2VydmVy", "get lucky!", "daft punk"), Triple("bmV3", "New Song", "Band"), extra = fromWeb)
        )

        val result = reconcile(sent, server).getJSONObject(0)

        assertEquals("from web", result.getString("description"))
        assertTrue(result.getBoolean("isFire"))
        assertEquals(99, result.getLong("lastModified"))
        val tracks = result.getJSONArray("songData")
        assertEquals("12", tracks.getJSONObject(0).getString("id"))
        assertEquals("bmV3", tracks.getJSONObject(1).getString("id"))
        assertEquals(5, tracks.getJSONObject(1).getLong("addedAt"))
        assertFalse(result.has("renamedFrom"))
    }

    /** A playlist the server no longer lists was deleted elsewhere, so it is dropped locally. */
    @Test
    fun reconcileDropsPlaylistsTheServerRemoved() {
        val result = reconcile(array(playlist("Gone"), playlist("Kept")), array(playlist("Kept")))
        assertEquals(listOf("Kept"), (0 until result.length()).map { result.getJSONObject(it).getString("name") })
    }

    /** The device keeps its own playlist order; playlists new to it follow in the server's order. */
    @Test
    fun reconcileKeepsTheDevicePlaylistOrder() {
        val sent = array(playlist("C"), playlist("A"), playlist("B"))
        val server = array(playlist("A"), playlist("New1"), playlist("B"), playlist("C"), playlist("New2"))

        val result = reconcile(sent, server)

        assertEquals(
            listOf("C", "A", "B", "New1", "New2"),
            (0 until result.length()).map { result.getJSONObject(it).getString("name") }
        )
    }

    /** A playlist that only exists on the server (made on the web) appears locally. */
    @Test
    fun reconcileAddsServerOnlyPlaylists() {
        val result = reconcile(array(), array(playlist("Web only", Triple("1", "A", "B"))))
        assertEquals("Web only", result.getJSONObject(0).getString("name"))
    }

    /** An unchanged cover keeps its existing local image and downloads nothing. */
    @Test
    fun reconcileKeepsLocalImageForUnchangedCover() {
        val existing = File(coversDir, "local.jpg").apply { writeText("image") }
        val sent = array(playlist("Mix", extra = mapOf("imageUri" to "file://${existing.absolutePath}", "cover" to coverA)))
        val server = array(playlist("Mix", extra = mapOf("cover" to coverA)))

        val result = reconcile(sent, server, noDownload)

        assertEquals("file://${existing.absolutePath}", result.getJSONObject(0).getString("imageUri"))
        assertEquals(coverA, result.getJSONObject(0).getString("cover"))
    }

    /** A new server cover is downloaded once into the covers folder and linked by file URI. */
    @Test
    fun reconcileDownloadsNewServerCover() {
        val fetched = mutableListOf<String>()
        val sent = array(playlist("Mix", extra = mapOf("imageUri" to "content://old", "cover" to coverA)))
        val server = array(playlist("Mix", extra = mapOf("cover" to coverB)))

        val result = reconcile(sent, server) { name, file ->
            fetched += name
            file.writeText("image")
            true
        }

        assertEquals(listOf(coverB), fetched)
        assertEquals("file://${File(coversDir, coverB).absolutePath}", result.getJSONObject(0).getString("imageUri"))

        // The file is there now, so a second reconcile does not download again
        reconcile(array(), server, noDownload)
    }

    /** A cover file that was deleted from the device is downloaded again instead of kept as a dead link. */
    @Test
    fun reconcileRedownloadsAMissingCoverFile() {
        val gone = File(coversDir, "gone-$coverA")
        val sent = array(playlist("Mix", extra = mapOf("imageUri" to "file://${gone.absolutePath}", "cover" to coverA)))
        val server = array(playlist("Mix", extra = mapOf("cover" to coverA)))

        val result = reconcile(sent, server) { _, file ->
            file.writeText("image")
            true
        }

        assertEquals("file://${File(coversDir, coverA).absolutePath}", result.getJSONObject(0).getString("imageUri"))
    }

    /** A failed download keeps whatever image the device already had. */
    @Test
    fun reconcileKeepsOldImageWhenDownloadFails() {
        val missing = "c".repeat(32) + ".png"
        val sent = array(playlist("Mix", extra = mapOf("imageUri" to "content://old", "cover" to coverA)))

        val result = reconcile(sent, array(playlist("Mix", extra = mapOf("cover" to missing))))

        assertEquals("content://old", result.getJSONObject(0).getString("imageUri"))
    }

    /** An image whose upload has not gone through yet stays on the playlist until it does. */
    @Test
    fun reconcileKeepsPendingImageWhenServerHasNoCover() {
        val sent = array(playlist("Mix", extra = mapOf("imageUri" to "content://pending", "cover" to "")))

        val result = reconcile(sent, array(playlist("Mix")))

        assertEquals("content://pending", result.getJSONObject(0).getString("imageUri"))
    }

    /** A cover name that is not one the server issues is never used as a file path. */
    @Test
    fun reconcileIgnoresUntrustedCoverNames() {
        val server = array(playlist("Mix", extra = mapOf("cover" to "../../escape.png")))

        val result = reconcile(array(), server, noDownload)

        assertEquals("", result.getJSONObject(0).getString("imageUri"))
    }
}
