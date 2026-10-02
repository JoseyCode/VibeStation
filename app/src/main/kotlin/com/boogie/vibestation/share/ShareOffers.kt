package com.boogie.vibestation.share

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.boogie.vibestation.models.Album
import com.boogie.vibestation.models.Playlist
import com.boogie.vibestation.models.Song
import java.io.IOException
import java.io.InputStream

/** Prepares what the user picked to send: a song, an album, or a playlist. */
internal object ShareOffers {

    /**
     * Builds an offer from songs and their file details. Songs without a readable file, and any beyond the
     * manifest's track limit, are left out, and the manifest indices refer to the songs that remain, so
     * the receiver's index always opens the right file.
     *
     * @param kind        What is being shared.
     * @param name        Song, album, or playlist name.
     * @param description Playlist description, or empty.
     * @param cover       Base64 cover, or empty; dropped when too big for the manifest.
     * @param songs       Songs in play order.
     * @param files       File details keyed by song id, see [queryShareFiles].
     * @param open        Opens the bytes of the song with the given id.
     * @return The offer, or null when nothing in [songs] can be sent.
     */
    fun prepare(
        kind: ShareKind,
        name: String,
        description: String,
        cover: String,
        songs: List<Song>,
        files: Map<String, ShareFile>,
        open: (songId: String) -> InputStream
    ): ShareOffer? {
        val sendable = songs.filter { (files[it.id]?.sizeBytes ?: 0L) > 0L }.take(ShareManifest.MAX_TRACKS)
        val fittingCover = if (cover.length <= ShareManifest.MAX_COVER_CHARS) cover else ""
        val manifest = ShareManifest.build(kind, name, description, fittingCover, sendable, files)
        if (manifest.tracks.isEmpty()) return null
        return ShareOffer(manifest) { index -> open(sendable[index].id) }
    }

    /**
     * Offers a single song.
     *
     * @param resolver Resolver used to read files.
     * @param song     The song.
     * @return The offer, or null if its file cannot be read.
     */
    fun forSong(resolver: ContentResolver, song: Song): ShareOffer? =
        fromLibrary(resolver, ShareKind.SONG, song.title, "", null, listOf(song))

    /**
     * Offers an album.
     *
     * @param resolver Resolver used to read files.
     * @param album    The album with its tracks.
     * @return The offer, or null if none of its tracks can be read.
     */
    fun forAlbum(resolver: ContentResolver, album: Album): ShareOffer? =
        fromLibrary(resolver, ShareKind.ALBUM, album.name, "", null, album.songs)

    /**
     * Offers a playlist with its description and cover.
     *
     * @param resolver Resolver used to read files and the cover.
     * @param playlist The playlist.
     * @return The offer, or null if none of its songs can be read.
     */
    fun forPlaylist(resolver: ContentResolver, playlist: Playlist): ShareOffer? =
        fromLibrary(resolver, ShareKind.PLAYLIST, playlist.name, playlist.description, playlist.imageUri, playlist.songs)

    private fun fromLibrary(
        resolver: ContentResolver,
        kind: ShareKind,
        name: String,
        description: String,
        imageUri: String?,
        songs: List<Song>
    ): ShareOffer? {
        val files = queryShareFiles(resolver, songs.map { it.id })
        val cover = ShareCovers.encode(resolver, imageUri)
        return prepare(kind, name, description, cover, songs, files) { id ->
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toLong())
            resolver.openInputStream(uri) ?: throw IOException("Cannot open $id")
        }
    }
}
