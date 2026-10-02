package com.boogie.vibestation.share

import kotlin.math.exp

/** Which colour family the waves use; the view maps these to real colours. */
internal enum class ShareTint {
    /** The normal look: an ambient colour that drifts through the whole spectrum, starting from the track's accent. */
    ACCENT,

    /** Warm, for something that wants an answer. */
    ATTENTION,

    /** Dim grey, for a screen that has finished. */
    MUTED
}

/**
 * How the waves should look and move for a state, as plain numbers the view eases toward every frame so a
 * change of state glides instead of snapping. Nothing here touches Android.
 *
 * @property amplitude  Wave height, 0 (a flat line) to 1 (as tall as the ribbon goes).
 * @property speed      How fast the ripple travels; 1 is the normal pace.
 * @property coherence  0 when the layers wander out of step with each other, 1 when they are locked into one wave.
 * @property flow       Which way the ripple drifts: 1 outward from the middle (sending), -1 inward (receiving), 0 not at all.
 * @property ping       How strongly rings expand from the middle, 0 for none; searching sends them, connecting sends them faster.
 * @property breathing  How deeply the height swells and falls slowly, 0 for none.
 * @property fill       How much of the waveform is drawn bright as a progress bar, 0 to 1, or null when it is not a progress bar.
 * @property visibility 1 for fully drawn, 0 for faded away.
 * @property tint       The colour family.
 */
internal data class ShareMotion(
    val amplitude: Float,
    val speed: Float,
    val coherence: Float,
    val flow: Float,
    val ping: Float,
    val breathing: Float,
    val fill: Float?,
    val visibility: Float,
    val tint: ShareTint
) {

    /**
     * Moves part of the way toward [target]. Numbers blend; the tint and a missing [fill] switch immediately, and a
     * fill that appears starts from empty so a progress bar grows into place.
     *
     * @param target Where this motion is heading.
     * @param factor Share of the remaining distance to cover, 0 (stay) to 1 (arrive).
     * @return The blended motion.
     */
    fun easeToward(target: ShareMotion, factor: Float): ShareMotion = ShareMotion(
        amplitude = lerp(amplitude, target.amplitude, factor),
        speed = lerp(speed, target.speed, factor),
        coherence = lerp(coherence, target.coherence, factor),
        flow = lerp(flow, target.flow, factor),
        ping = lerp(ping, target.ping, factor),
        breathing = lerp(breathing, target.breathing, factor),
        fill = target.fill?.let { lerp(fill ?: 0f, it, factor) },
        visibility = lerp(visibility, target.visibility, factor),
        tint = target.tint
    )

    /** The motions of each state, and the frame-rate independent blend factor. */
    companion object {
        private const val SMOOTHING_RATE = 6f
        private const val LOOSE_BREATHING = 0.2f
        private const val STEADY_SPEED = 0.7f
        private const val STEADY_BREATHING = 0.3f

        /** A flat, faded line: where the screen starts before the first state arrives. */
        val REST = ShareMotion(0f, 0.3f, 1f, 0f, 0f, 0f, null, 0f, ShareTint.ACCENT)

        /**
         * Chooses how the waves should look for [state].
         *
         * @param state What Share Mode is doing.
         * @return The target motion.
         */
        fun of(state: ShareState): ShareMotion = when (state) {
            is ShareState.Idle, is ShareState.Searching -> loose(amplitude = 0.22f, speed = 0.6f, ping = 0.5f)
            is ShareState.Connecting -> loose(amplitude = 0.38f, speed = 1f, ping = 1f).copy(coherence = 0.3f)
            is ShareState.Verifying ->
                steady(amplitude = if (state.confirmed) 0.25f else 0.3f, coherence = if (state.confirmed) 0.85f else 0.6f)
            is ShareState.Locked -> steady(amplitude = 0.3f, coherence = 1f).copy(speed = 0.8f, breathing = 0.15f)
            is ShareState.Offering -> steady(amplitude = 0.4f, coherence = 0.9f).copy(breathing = 0.5f, ping = 0.3f)
            is ShareState.IncomingOffer ->
                steady(amplitude = 0.45f, coherence = 0.8f).copy(breathing = 0.6f, tint = ShareTint.ATTENTION)
            is ShareState.Transferring -> steady(amplitude = 0.55f, coherence = 1f).copy(
                speed = 1.3f,
                flow = if (state.sending) 1f else -1f,
                fill = ShareScreenText.progress(state) ?: 0f
            )
            is ShareState.Closed -> REST.copy(tint = ShareTint.MUTED)
        }

        /**
         * Works out how much of the way to a target to move in one frame, so the glide takes the same time at
         * 60 and 120 frames per second.
         *
         * @param deltaSeconds Time since the last frame, in seconds; negative is treated as zero.
         * @return A factor from 0 up to (not including) 1 for [easeToward].
         */
        fun smoothing(deltaSeconds: Float): Float = 1f - exp(-SMOOTHING_RATE * deltaSeconds.coerceAtLeast(0f))

        private fun loose(amplitude: Float, speed: Float, ping: Float) =
            ShareMotion(amplitude, speed, 0f, 0f, ping, LOOSE_BREATHING, null, 1f, ShareTint.ACCENT)

        private fun steady(amplitude: Float, coherence: Float) =
            ShareMotion(amplitude, STEADY_SPEED, coherence, 0f, 0f, STEADY_BREATHING, null, 1f, ShareTint.ACCENT)

        // Written this way, not from + (to - from) * factor, so a factor of 1 lands exactly on the target.
        private fun lerp(from: Float, to: Float, factor: Float) = from * (1f - factor) + to * factor
    }
}
