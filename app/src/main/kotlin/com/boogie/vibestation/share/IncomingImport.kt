package com.boogie.vibestation.share

import java.io.IOException
import java.io.InputStream

/**
 * Bookkeeping for one accepted offer on the receiving side: which tracks are still expected, what got
 * stored, and, at the end, the playlist. It never sees the sender's ids, only this device's.
 *
 * @param manifest The offer being imported.
 * @param matches  For each manifest track, the local song that already covers it, or null if it must be received.
 * @param host     Storage and playlist access.
 */
internal class IncomingImport(
    val manifest: ShareManifest,
    private val matches: List<LocalTrack?>,
    private val host: ShareHost
) {
    /** Manifest indices this phone lacks, ascending. */
    val missing: List<Int> = matches.indices.filter { matches[it] == null }

    /** Bytes the missing tracks add. */
    val needBytes: Long = manifest.totalBytes(missing)

    /** Tracks that were written. */
    var stored = 0
        private set

    /** Tracks that arrived or were expected but could not be written. */
    var failed = 0
        private set

    private val remaining = missing.toMutableSet()
    private val inserted = HashMap<Int, LocalTrack>()
    private val partial = HashMap<Int, Long>()

    /** True once every expected track has been stored or has failed. */
    val isComplete: Boolean get() = remaining.isEmpty()

    /** Tracks stored or failed so far. */
    val handled: Int get() = stored + failed

    /** Bytes received so far, counting finished tracks in full and running ones by their last report. */
    val bytesDone: Long
        get() = missing.sumOf { if (it in remaining) partial[it] ?: 0L else manifest.tracks[it].sizeBytes }

    /**
     * Whether track [index] is one this import is still waiting for. Anything else is unsolicited.
     *
     * @param index Manifest index reported by the transport.
     * @return True if the track was requested and has not arrived yet.
     */
    fun expects(index: Int): Boolean = index in remaining

    /** Records that [bytes] of track [index] have arrived. */
    fun progress(index: Int, bytes: Long) {
        if (index in remaining) partial[index] = bytes
    }

    /**
     * Stores an arrived track. A storage failure is counted, not thrown, so one bad file does not sink the rest.
     *
     * @param index Manifest index of the track.
     * @param input The file's bytes; closed here.
     */
    fun receive(index: Int, input: InputStream) {
        try {
            inserted[index] = host.storeTrack(manifest.tracks[index], input)
            stored++
        } catch (ignored: IOException) {
            failed++
        } finally {
            runCatching { input.close() }
        }
        remaining.remove(index)
    }

    /** Counts a track whose transfer failed. */
    fun fail(index: Int) {
        if (remaining.remove(index)) failed++
    }

    /**
     * Completes the import. For a playlist, creates it under a name that does not clash, with a cover if one
     * came along, and songs identified by this device's ids (already-owned tracks and freshly stored ones).
     *
     * @return The outcome to show and to report to the sender.
     */
    fun finish(): ShareResult.Received {
        var playlistName: String? = null
        if (manifest.kind == ShareKind.PLAYLIST) {
            val name = ShareImport.uniqueName(manifest.name, host.existingPlaylistNames())
            val resolved = matches.mapIndexed { index, owned -> inserted[index] ?: owned }
            val entry = ShareImport.playlistEntry(manifest, name, host.storeCover(manifest.coverBase64), resolved)
            playlistName = try {
                host.addPlaylist(entry)
                name
            } catch (ignored: IOException) {
                null
            }
        }
        return ShareResult.Received(manifest.name, stored, failed, playlistName)
    }
}
