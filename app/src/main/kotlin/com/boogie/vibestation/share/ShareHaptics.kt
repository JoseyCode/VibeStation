package com.boogie.vibestation.share

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Plays a [ShareCue] as a tap. Most cues go through the view's haptic feedback, so they follow the user's
 * system touch-feedback setting and the phone's own tuned effects; each picks the newest effect the phone's
 * Android version has and falls back to an older one. The incoming-offer cue is a short pattern played on
 * the vibrator, since it should be noticed even when the screen is not being touched.
 *
 * @param view The view that haptics are performed on; it must be attached to a window to be felt.
 */
internal class ShareHaptics(private val view: View) {

    private val vibrator: Vibrator? = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /**
     * Plays [cue] now.
     *
     * @param cue What happened.
     */
    fun play(cue: ShareCue) {
        if (cue == ShareCue.OFFER_IN) playOfferIn() else view.performHapticFeedback(effectFor(cue))
    }

    /** The newest effect this Android version has for [cue], or the closest older one. */
    private fun effectFor(cue: ShareCue): Int {
        val sdk = Build.VERSION.SDK_INT
        return when (cue) {
            ShareCue.ARM -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            } else {
                HapticFeedbackConstants.CONTEXT_CLICK
            }
            ShareCue.DISARM -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            ShareCue.COMMIT_YES, ShareCue.DONE, ShareCue.CONNECTED ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
            ShareCue.COMMIT_NO, ShareCue.REFUSED, ShareCue.LOST ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            ShareCue.PRESS, ShareCue.FILE_DONE, ShareCue.CODE, ShareCue.OFFER_IN -> HapticFeedbackConstants.CLOCK_TICK
        }
    }

    /** A rising swell and a click where the phone can do that, otherwise two short buzzes. */
    private fun playOfferIn() {
        val motor = vibrator?.takeIf { it.hasVibrator() } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            motor.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, VibrationEffect.Composition.PRIMITIVE_CLICK)
        ) {
            motor.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, RISE_SCALE)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, CLICK_DELAY_MS)
                    .compose()
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            motor.vibrate(VibrationEffect.createWaveform(BUZZ_PATTERN, NO_REPEAT))
        } else {
            @Suppress("DEPRECATION") // the pattern overload is the only one before Android 8
            motor.vibrate(BUZZ_PATTERN, NO_REPEAT)
        }
    }

    private companion object {
        const val RISE_SCALE = 0.8f
        const val CLICK_DELAY_MS = 80
        const val NO_REPEAT = -1

        /** Wait, buzz, pause, buzz: milliseconds. */
        val BUZZ_PATTERN = longArrayOf(0, 25, 60, 40)
    }
}
