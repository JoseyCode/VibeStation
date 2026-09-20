package com.boogie.vibestation.share

import android.content.ContentResolver
import com.boogie.vibestation.util.MusicLibraryUtil

/**
 * One thing the user can pick to send.
 *
 * @property label   Text shown in the picker.
 * @property prepare Reads the files and builds the offer; does blocking I/O, so call it off the main thread.
 */
internal class ShareChoice(val label: String, val prepare: () -> ShareOffer?) {

    /** Lists what can be picked from the library. */
    companion object {
        /**
         * Lists the library's songs, albums or playlists as choices.
         *
         * @param kind     Which kind the user chose.
         * @param library  The assembled library.
         * @param resolver Resolver the offers read files with.
         * @return One choice per item, in library order.
         */
        fun of(kind: ShareKind, library: MusicLibraryUtil.Library, resolver: ContentResolver): List<ShareChoice> =
            when (kind) {
                ShareKind.SONG -> library.songs.map { song ->
                    ShareChoice("${song.title} - ${song.artist}") { ShareOffers.forSong(resolver, song) }
                }
                ShareKind.ALBUM -> library.albums.map { album ->
                    ShareChoice(album.name) { ShareOffers.forAlbum(resolver, album) }
                }
                ShareKind.PLAYLIST -> library.playlists.map { playlist ->
                    ShareChoice(playlist.name) { ShareOffers.forPlaylist(resolver, playlist) }
                }
            }
    }
}
