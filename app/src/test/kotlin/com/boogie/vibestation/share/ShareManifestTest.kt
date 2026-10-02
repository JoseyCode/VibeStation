package com.boogie.vibestation.share

import com.boogie.vibestation.models.Song
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Tests for [ShareManifest] serialization, validation of untrusted input, and building from songs. */
class ShareManifestTest {

    private fun track(title: String = "Song", size: Long = 100) =
        ShareTrack(title, "Artist", "Album", 180_000, size, "$title.mp3", "audio/mpeg")

    private fun manifest(vararg tracks: ShareTrack) =
        ShareManifest(ShareKind.PLAYLIST, "Road Trip", "Desc", "b64data", tracks.toList())

    /** Everything the sender puts in survives a round trip. */
    @Test
    fun roundTrip() {
        val original = manifest(track("A"), track("B", size = 5))
        assertEquals(original, ShareManifest.fromJson(original.toJson()))
    }

    /** The wire format must not leak the sender's device-local ids. */
    @Test
    fun jsonContainsNoIds() {
        val root = JSONObject(manifest(track()).toJson())
        val first = root.getJSONArray("tracks").getJSONObject(0)
        assertFalse(first.has("id"))
    }

    /** Peer-supplied text is cut to the length limits instead of being trusted. */
    @Test
    fun longTextIsTruncated() {
        val long = "x".repeat(ShareManifest.MAX_TEXT + 50)
        val parsed = ShareManifest.fromJson(
            ShareManifest(ShareKind.SONG, long, "d".repeat(5000), "", listOf(track(long))).toJson()
        )
        assertEquals(ShareManifest.MAX_TEXT, parsed.name.length)
        assertEquals(ShareManifest.MAX_DESCRIPTION, parsed.description.length)
        assertEquals(ShareManifest.MAX_TEXT, parsed.tracks[0].title.length)
    }

    /** Malformed or hostile manifests are rejected with IllegalArgumentException. */
    @Test
    fun invalidManifestsAreRejected() {
        val good = JSONObject(manifest(track()).toJson())
        fun broken(edit: JSONObject.() -> Unit) = JSONObject(good.toString()).apply(edit).toString()
        val cases = listOf(
            "not json",
            broken { put("v", 2) },
            broken { put("kind", "MOVIE") },
            broken { put("tracks", org.json.JSONArray()) },
            broken { put("cover_b64", "c".repeat(ShareManifest.MAX_COVER_CHARS + 1)) },
            broken { getJSONArray("tracks").getJSONObject(0).put("s", -1) },
            broken { getJSONArray("tracks").getJSONObject(0).put("d", -1) },
            broken { getJSONArray("tracks").getJSONObject(0).remove("t") },
            broken { remove("name") }
        )
        for (case in cases) assertFailsWith<IllegalArgumentException>(case) { ShareManifest.fromJson(case) }
    }

    /** More than [ShareManifest.MAX_TRACKS] tracks is refused. */
    @Test
    fun tooManyTracksRejected() {
        val tracks = List(ShareManifest.MAX_TRACKS + 1) { track("T$it") }
        val json = ShareManifest(ShareKind.PLAYLIST, "Big", "", "", tracks).toJson()
        assertFailsWith<IllegalArgumentException> { ShareManifest.fromJson(json) }
    }

    /** Total size can be taken for everything or for just the tracks the receiver lacks. */
    @Test
    fun totalBytes() {
        val m = manifest(track("A", 10), track("B", 20), track("C", 30))
        assertEquals(60, m.totalBytes())
        assertEquals(40, m.totalBytes(listOf(0, 2)))
        assertEquals(0, m.totalBytes(emptyList()))
    }

    /** Building keeps song order and drops songs MediaStore has no non-empty file for. */
    @Test
    fun buildSkipsSongsWithoutFiles() {
        fun song(id: String) = Song(id, "T$id", "Ar", null, "al", "Alb", 1, 0)
        val files = mapOf(
            "1" to ShareFile("1", "one.flac", "audio/flac", 500, 1000),
            "3" to ShareFile("3", "three.mp3", "audio/mpeg", 0, 1000)
        )

        val built = ShareManifest.build(
            ShareKind.ALBUM, "Alb", "", "", listOf(song("1"), song("2"), song("3")), files
        )

        assertEquals(listOf(ShareTrack("T1", "Ar", "Alb", 1000, 500, "one.flac", "audio/flac")), built.tracks)
    }
}
