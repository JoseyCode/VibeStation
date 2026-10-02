package com.boogie.vibestation.share

/** Sample states for reviewing the Share screen on one phone, without a second phone or a radio. */
internal object ShareDemo {
    private const val OFFER_BYTES = 17_700_000L
    private val tracks = listOf(
        ShareTrack("Sunset Drive", "The Examples", "Night Roads", 215_000, 8_400_000, "sunset.mp3", "audio/mpeg"),
        ShareTrack("Neon Rain", "The Examples", "Night Roads", 198_000, 7_700_000, "neon.mp3", "audio/mpeg"),
        ShareTrack("Last Exit", "The Examples", "Night Roads", 240_000, 9_300_000, "exit.mp3", "audio/mpeg")
    )
    private val playlist = ShareManifest(ShareKind.PLAYLIST, "Road Trip", "", "", tracks)

    /** Every state the screen can show, in the order a real share would pass through them. */
    val states: List<ShareState> = listOf(
        ShareState.Searching(),
        ShareState.Searching("The other phone runs a different VibeStation version"),
        ShareState.Connecting("Sam's Flip"),
        ShareState.Verifying("Sam's Flip", "4821", confirmed = false),
        ShareState.Verifying("Sam's Flip", "4821", confirmed = true),
        ShareState.Locked("Sam's Flip"),
        ShareState.Offering("Sam's Flip", playlist),
        ShareState.IncomingOffer("Sam's Flip", playlist, listOf(0, 2), OFFER_BYTES),
        transferring(sending = true, doneFiles = 1, bytesDone = 12_000_000),
        transferring(sending = false, doneFiles = 0, bytesDone = 3_000_000),
        ShareState.Locked("Sam's Flip", ShareResult.Received("Road Trip", 2, 0, "Road Trip")),
        ShareState.Locked("Sam's Flip", ShareResult.Refused(DeclineReason.USER, byPeer = true)),
        ShareState.Closed("Connection lost")
    )

    private fun transferring(sending: Boolean, doneFiles: Int, bytesDone: Long) =
        ShareState.Transferring("Sam's Flip", playlist, sending, doneFiles, tracks.size - 1, bytesDone, OFFER_BYTES)
}
