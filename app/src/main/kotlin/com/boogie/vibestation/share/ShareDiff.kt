package com.boogie.vibestation.share

import com.boogie.vibestation.SyncManager
import kotlin.math.abs

/**
 * A song already in this device's library, reduced to what matching needs.
 *
 * @property songId     MediaStore id on THIS device.
 * @property title      Title as stored locally.
 * @property artist     Artist as stored locally.
 * @property durationMs Length in milliseconds, 0 if unknown.
 */
internal data class LocalTrack(val songId: String, val title: String, val artist: String, val durationMs: Long)

/**
 * Decides which tracks of a [ShareManifest] the receiver already owns. A track matches a local song when
 * their normalized title and artist agree ([SyncManager.makeMatchKey]) and their durations are within
 * [DURATION_TOLERANCE_MS]. The key alone is not enough: a live and a studio version, or a remaster, can
 * share title and artist.
 */
internal object ShareDiff {

    /** How far apart two durations may be, in milliseconds, and still count as the same recording. */
    const val DURATION_TOLERANCE_MS = 1000L

    /**
     * Finds the local song for each manifest track.
     *
     * @param tracks Manifest tracks in order.
     * @param local  Songs in this device's library.
     * @return One entry per manifest track: the closest-duration local match, or null when the receiver lacks it.
     */
    fun match(tracks: List<ShareTrack>, local: List<LocalTrack>): List<LocalTrack?> {
        val byKey = local.groupBy { SyncManager.makeMatchKey(it.title, it.artist) }
        return tracks.map { track ->
            byKey[SyncManager.makeMatchKey(track.title, track.artist)]
                ?.filter { sameLength(track.durationMs, it.durationMs) }
                ?.minByOrNull { abs(it.durationMs - track.durationMs) }
        }
    }

    /**
     * Lists the manifest positions the receiver must be sent.
     *
     * @param tracks Manifest tracks in order.
     * @param local  Songs in this device's library.
     * @return Indices into [tracks] with no local match, ascending.
     */
    fun missingIndices(tracks: List<ShareTrack>, local: List<LocalTrack>): List<Int> =
        match(tracks, local).withIndex().filter { it.value == null }.map { it.index }

    /** An unknown (0) duration on either side cannot rule a match out, so only the key decides. */
    private fun sameLength(a: Long, b: Long): Boolean = a <= 0 || b <= 0 || abs(a - b) <= DURATION_TOLERANCE_MS
}
