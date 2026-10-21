package com.boogie.vibestation.util

import java.util.Locale

/**
 * Track identity shared by library sync, playlist sync and the web server. Ids differ per device
 * (MediaStore rows here, file paths on the server), so tracks are matched by title and artist after
 * stripping case, whitespace and punctuation. The server computes the same key in `lib/playlists.js`.
 */
object MatchKey {

    private val NON_KEY_CHARS = Regex("[^\\p{L}\\p{N}_]")

    /**
     * Builds the normalized identity key of a track.
     *
     * @param title  The song title.
     * @param artist The song artist.
     * @return Lowercase "title_artist" with only letters, digits and underscores, or `unknown_track` when both are blank.
     */
    fun of(title: String, artist: String): String {
        val safeTitle = title.trim()
        val safeArtist = artist.trim()
        if (safeTitle.isEmpty() && safeArtist.isEmpty()) return "unknown_track"
        return "${safeTitle}_$safeArtist".lowercase(Locale.ROOT).replace(NON_KEY_CHARS, "")
    }
}
