package com.boogie.vibestation.share

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * The shapes behind the Share screen's waveform, as pure maths so they can be tested: how thick each of the
 * layered waves is at a point across the screen, how the ribbon is pulled toward a finger, and how a ping
 * travels outward. The view turns these numbers into paths. Positions across the screen are fractions from
 * 0 (left edge) to 1 (right edge).
 */
internal object ShareWaves {

    /** How many translucent waves are stacked. */
    const val LAYER_COUNT = 6

    /** How fast the ambient colour drifts round the wheel; 20 degrees a second is one full turn every 18 seconds. */
    const val HUE_DEGREES_PER_SECOND = 20f

    private const val HALF = 0.5f
    private const val FULL_TURN = 360f
    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val TAPER_POWER = 1.4f
    private const val BASE_FREQUENCY = 1.4f
    private const val RIPPLE_FLOOR = 0.55f
    private const val RIPPLE_DEPTH = 0.45f
    private const val PING_WIDTH = 0.14f
    private const val SMOOTH_A = 3f
    private const val SMOOTH_B = 2f
    private const val BACK_C1 = 1.70158f
    private const val BACK_C3 = BACK_C1 + 1f

    /**
     * How one layer differs from the shared wave when the layers are out of step.
     *
     * @property frequency How many ripples fit across half the screen.
     * @property speed     Pace relative to the others.
     * @property offset    Starting phase in radians.
     * @property weight    Thickness relative to the others.
     */
    private class Layer(val frequency: Float, val speed: Float, val offset: Float, val weight: Float)

    private val layers = listOf(
        Layer(1.1f, 1.0f, 0.0f, 1.0f),
        Layer(1.8f, 1.3f, 1.9f, 0.85f),
        Layer(2.4f, 0.8f, 3.4f, 0.7f),
        Layer(3.0f, 1.6f, 4.6f, 0.55f),
        Layer(3.8f, 1.1f, 5.7f, 0.4f),
        Layer(4.7f, 1.9f, 0.8f, 0.3f)
    )

    /**
     * Tapers the waves to nothing at the screen edges.
     *
     * @param across Position across the screen, 0 to 1.
     * @return 0 at either edge, 1 in the middle.
     */
    fun taper(across: Float): Float = sin(PI * across.coerceIn(0f, 1f)).toFloat().pow(TAPER_POWER)

    /**
     * How thick a layer is at a point, before the overall height is applied. The ripple is mirrored around the
     * middle of the screen and drifts along it, so it reads as spreading out from, or gathering into, the middle.
     *
     * @param layer     Which wave, 0 until [LAYER_COUNT].
     * @param across    Position across the screen, 0 to 1.
     * @param phase     How far the ripple has travelled, in radians; its sign sets the direction.
     * @param coherence 0 when the layers each do their own thing, 1 when they are all the same wave.
     * @return A thickness from 0 up to 1.
     */
    fun thickness(layer: Int, across: Float, phase: Float, coherence: Float): Float {
        val l = layers[layer]
        val loose = 1f - coherence.coerceIn(0f, 1f)
        val frequency = lerp(BASE_FREQUENCY, l.frequency, loose)
        val speed = lerp(1f, l.speed, loose)
        val weight = lerp(1f, l.weight, loose)
        val fromMiddle = abs(2f * across - 1f)
        val ripple = sin(frequency * TWO_PI * fromMiddle * HALF - phase * speed + l.offset * loose)
        return weight * (RIPPLE_FLOOR + RIPPLE_DEPTH * ripple) * taper(across)
    }

    /**
     * How much a point on the ribbon is pulled toward the finger: a bell shape centred under it.
     *
     * @param across       Position across the screen, 0 to 1.
     * @param fingerAcross Where the finger is across the screen, 0 to 1.
     * @param width        How wide the bell is, as a fraction of the screen.
     * @return 1 right under the finger, falling toward 0 either side.
     */
    fun pull(across: Float, fingerAcross: Float, width: Float): Float {
        val distance = (across - fingerAcross) / width
        return exp(-distance * distance)
    }

    /**
     * The bump a ping adds to the ribbon, travelling outward from the middle and fading as it goes.
     *
     * @param across Position across the screen, 0 to 1.
     * @param travel How far the ping has gone, 0 (at the middle) to 1 (at the edges).
     * @return 0 far from the ping, up to 1 at its crest when it has only just left the middle.
     */
    fun ping(across: Float, travel: Float): Float {
        val fromMiddle = abs(2f * across - 1f)
        val distance = (fromMiddle - travel) / PING_WIDTH
        return exp(-distance * distance) * (1f - travel.coerceIn(0f, 1f))
    }

    /**
     * Where a stream of evenly spaced pings is, given a clock that advances while pinging.
     *
     * @param clock Ever-growing count of pings sent.
     * @param index Which of [count] pings in flight, 0 until [count].
     * @param count How many pings are in flight at once.
     * @return How far that ping has travelled, 0 up to (not including) 1.
     */
    fun pingTravel(clock: Float, index: Int, count: Int): Float {
        val shifted = clock + index.toFloat() / count
        return shifted - floor(shifted)
    }

    /**
     * Where the ambient colour is on the colour wheel, drifting slowly all the way round.
     *
     * @param start   Hue the drift starts from, in degrees.
     * @param seconds How long it has been drifting.
     * @return A hue from 0 up to (not including) 360 degrees.
     */
    fun hue(start: Float, seconds: Float): Float {
        val turned = (start + seconds * HUE_DEGREES_PER_SECOND) % FULL_TURN
        return if (turned < 0f) turned + FULL_TURN else turned
    }

    /**
     * A gentle ease in and out.
     *
     * @param t Progress from 0 to 1; outside that it is clamped.
     * @return 0 at the start and 1 at the end, slow at both ends.
     */
    fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (SMOOTH_A - SMOOTH_B * x)
    }

    /**
     * An ease that overshoots a little before settling, for the verification digits popping in.
     *
     * @param t Progress from 0 to 1; outside that it is clamped.
     * @return 0 at the start, 1 at the end, and above 1 for part of the way.
     */
    fun overshoot(t: Float): Float {
        val x = t.coerceIn(0f, 1f) - 1f
        return 1f + BACK_C3 * x * x * x + BACK_C1 * x * x
    }

    private fun lerp(from: Float, to: Float, amount: Float) = from * (1f - amount) + to * amount
}
