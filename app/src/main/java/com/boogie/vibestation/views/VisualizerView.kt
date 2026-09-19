package com.boogie.vibestation.views

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom audio visualizer view rendering smooth, multi-layered wave splines
 * derived from Fast Fourier Transform frequency spectrum data.
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Normalized bass amplitude [0.0, 1.0+]; written from the capture thread, read while drawing. */
    var targetBass: Float = 0f
        @Synchronized get
        private set

    /** Normalized mid amplitude [0.0, 1.0+]. */
    var targetMid: Float = 0f
        @Synchronized get
        private set

    /** Normalized high amplitude [0.0, 1.0+]. */
    var targetHigh: Float = 0f
        @Synchronized get
        private set

    private var smoothedBass = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f

    private val wavePaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val wavePath = Path()
    private val drawXLayers = Array(LAYERS.size) { FloatArray(RENDER_BINS) }

    /** Wave drawing color; assigning preserves the visualizer's base transparency. */
    var color: Int = Color.WHITE
        set(value) {
            field = value
            wavePaint.color = value
            wavePaint.alpha = WAVE_ALPHA
        }

    /**
     * Processes raw FFT data capture bytes and updates frequency band targets.
     *
     * @param bytes Raw frequency/FFT data byte array from the Android Visualizer API.
     */
    fun updateVisualizer(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return

        val bands = extractFrequencyBands(bytes)
        synchronized(this) {
            targetBass = bands[0]
            targetMid = bands[1]
            targetHigh = bands[2]
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return

        val localBass: Float
        val localMid: Float
        val localHigh: Float
        synchronized(this) {
            localBass = targetBass
            localMid = targetMid
            localHigh = targetHigh
        }

        smoothedBass = applyDamping(smoothedBass, localBass, DAMPING_FACTOR)
        smoothedMid = applyDamping(smoothedMid, localMid, DAMPING_FACTOR)
        smoothedHigh = applyDamping(smoothedHigh, localHigh, DAMPING_FACTOR)

        val needsMoreFrames = abs(localBass - smoothedBass) > FRAME_SETTLE_EPSILON ||
            abs(localMid - smoothedMid) > FRAME_SETTLE_EPSILON ||
            abs(localHigh - smoothedHigh) > FRAME_SETTLE_EPSILON

        val barHeight = height / (RENDER_BINS - 1)
        val timeSec = (System.currentTimeMillis() % 100000).toFloat() / 1000f

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val maxAllowedWidth = calculateMaxAllowedWidth(isLandscape, width, height)
        val bandAmps = floatArrayOf(
            smoothedBass * maxAllowedWidth,
            smoothedMid * maxAllowedWidth,
            smoothedHigh * maxAllowedWidth
        )

        for ((index, layer) in LAYERS.withIndex()) {
            val amp = bandAmps[layer.band]
            val drawX = drawXLayers[index]
            for (i in 0 until RENDER_BINS) {
                val sine = sin(i * layer.frequency - timeSec * layer.speed) *
                    (amp * layer.sineAmplitude + width * layer.widthFactor)
                val x = amp * layer.baseAmplitude + sine
                drawX[i] = if (x < 0f) 0f else x
            }
        }

        for ((index, layer) in LAYERS.withIndex()) {
            drawSplineWave(canvas, drawXLayers[index], (WAVE_ALPHA * layer.alphaFactor).toInt(), barHeight, height, isLandscape)
        }

        if (needsMoreFrames || bandAmps.any { it > 0.1f }) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Constructs and draws a continuous cubic spline wave using Catmull-Rom style control points.
     *
     * @param canvas      Target canvas.
     * @param drawX       Array of anchor X values.
     * @param alpha       Alpha opacity for this layer.
     * @param barHeight   Distance between vertical nodes.
     * @param height      Total canvas height.
     * @param isLandscape True if the wave spans the horizontal axis.
     */
    private fun drawSplineWave(canvas: Canvas, drawX: FloatArray, alpha: Int, barHeight: Float, height: Float, isLandscape: Boolean) {
        val width = width.toFloat()
        val barSize = if (isLandscape) width / (RENDER_BINS - 1) else barHeight

        wavePath.reset()
        if (isLandscape) {
            buildLandscapeWavePath(wavePath, drawX, barSize, height, width)
        } else {
            buildPortraitWavePath(wavePath, drawX, barSize, height)
        }
        wavePath.close()
        wavePaint.alpha = alpha
        canvas.drawPath(wavePath, wavePaint)
    }

    /**
     * Constructs cubic Bezier spline wave path across horizontal width for landscape display.
     *
     * @param path    Target path object.
     * @param drawX   Amplitude data points.
     * @param barSize Horizontal spacing between bins.
     * @param height  Canvas height.
     * @param width   Canvas width.
     */
    private fun buildLandscapeWavePath(path: Path, drawX: FloatArray, barSize: Float, height: Float, width: Float) {
        path.moveTo(0f, height)
        for (i in 0 until RENDER_BINS) {
            val x2 = i * barSize
            val y2 = height - drawX[i]
            if (i == 0) {
                path.lineTo(x2, y2)
            } else {
                val x1 = (i - 1) * barSize
                val y1 = height - drawX[i - 1]
                val y0 = if (i < 2) y1 else height - drawX[i - 2]
                val y3 = if (i >= RENDER_BINS - 1) y2 else height - drawX[i + 1]

                path.cubicTo(
                    x1 + barSize / 3f, y1 + (y2 - y0) / 6f,
                    x2 - barSize / 3f, y2 - (y3 - y1) / 6f,
                    x2, y2
                )
            }
        }
        path.lineTo(width, height)
        path.lineTo(0f, height)
    }

    /**
     * Constructs cubic Bezier spline wave path across vertical height for portrait display.
     *
     * @param path    Target path object.
     * @param drawX   Amplitude data points.
     * @param barSize Vertical spacing between bins.
     * @param height  Canvas height.
     */
    private fun buildPortraitWavePath(path: Path, drawX: FloatArray, barSize: Float, height: Float) {
        path.moveTo(0f, height)
        for (i in 0 until RENDER_BINS) {
            val x2 = drawX[i]
            val y2 = height - i * barSize
            if (i == 0) {
                path.lineTo(x2, y2)
            } else {
                val x1 = drawX[i - 1]
                val y1 = height - (i - 1) * barSize
                val x0 = if (i < 2) x1 else drawX[i - 2]
                val x3 = if (i >= RENDER_BINS - 1) x2 else drawX[i + 1]

                path.cubicTo(
                    x1 + (x2 - x0) / 6f, y1 - barSize / 3f,
                    x2 - (x3 - x1) / 6f, y2 + barSize / 3f,
                    x2, y2
                )
            }
        }
        path.lineTo(0f, 0f)
    }

    /**
     * Tuning for one rendered wave layer: which FFT band drives it, its sine ripple, and opacity.
     */
    private class WaveLayer(
        val band: Int,
        val frequency: Float,
        val speed: Float,
        val baseAmplitude: Float,
        val sineAmplitude: Float,
        val widthFactor: Float,
        val alphaFactor: Float
    )

    companion object {
        const val RENDER_BINS = 32
        const val WAVE_ALPHA = 30
        const val DAMPING_FACTOR = 0.25f
        const val BASS_BIN_END = 6
        const val MID_BIN_END = 60
        const val FFT_MAGNITUDE_DIVISOR = 128f

        private const val FRAME_SETTLE_EPSILON = 0.005f
        private const val BASS = 0
        private const val MID = 1
        private const val HIGH = 2

        /** Wave layers drawn back-to-front; two per frequency band with increasing ripple speed. */
        private val LAYERS = listOf(
            WaveLayer(BASS, 0.15f, 2.5f, 0.8f, 0.25f, 0.005f, 0.3f),
            WaveLayer(BASS, 0.20f, 3.2f, 0.6f, 0.30f, 0.006f, 0.4f),
            WaveLayer(MID, 0.25f, 4.5f, 0.9f, 0.35f, 0.008f, 0.6f),
            WaveLayer(MID, 0.32f, 5.5f, 0.7f, 0.40f, 0.010f, 0.7f),
            WaveLayer(HIGH, 0.40f, 7.0f, 1.0f, 0.45f, 0.012f, 0.9f),
            WaveLayer(HIGH, 0.50f, 9.0f, 0.8f, 0.55f, 0.015f, 1.0f)
        )

        /**
         * Calculates Euclidean vector magnitude from real and imaginary parts of an FFT bin.
         *
         * @param real Real frequency component byte.
         * @param imag Imaginary frequency component byte.
         * @return Calculated magnitude.
         */
        @JvmStatic
        fun calculateMagnitude(real: Byte, imag: Byte): Float =
            sqrt((real * real + imag * imag).toFloat())

        /**
         * Computes magnitude of an FFT frequency bin index from interleaved byte buffer.
         *
         * @param bytes Raw FFT byte buffer.
         * @param k     Frequency bin index.
         * @return Magnitude of the indexed bin, or 0.0f if out of bounds.
         */
        @JvmStatic
        fun getMagnitude(bytes: ByteArray?, k: Int): Float {
            if (bytes == null || k < 0) return 0f
            val realIndex = k * 2
            val imagIndex = realIndex + 1
            return if (imagIndex < bytes.size) calculateMagnitude(bytes[realIndex], bytes[imagIndex]) else 0f
        }

        /**
         * Extracts peak frequency magnitudes categorized into Bass, Mid, and High bands,
         * normalized against FFT_MAGNITUDE_DIVISOR.
         *
         * @param bytes Raw FFT data bytes from Android Visualizer.
         * @return 3-element float array containing [targetBass, targetMid, targetHigh].
         */
        @JvmStatic
        fun extractFrequencyBands(bytes: ByteArray?): FloatArray {
            if (bytes == null || bytes.isEmpty()) return FloatArray(3)
            val fftSize = bytes.size / 2
            if (fftSize <= 0) return FloatArray(3)

            val bassEnd = minOf(BASS_BIN_END, fftSize)
            val midEnd = minOf(MID_BIN_END, fftSize)

            return floatArrayOf(
                peakMagnitude(bytes, 1, bassEnd) / FFT_MAGNITUDE_DIVISOR,
                peakMagnitude(bytes, bassEnd, midEnd) / FFT_MAGNITUDE_DIVISOR,
                peakMagnitude(bytes, midEnd, fftSize) / FFT_MAGNITUDE_DIVISOR
            )
        }

        /** Largest bin magnitude in [start, end), or 0 when the range is empty. */
        private fun peakMagnitude(bytes: ByteArray, start: Int, end: Int): Float {
            var peak = 0f
            for (k in start until end) {
                val magnitude = getMagnitude(bytes, k)
                if (magnitude > peak) peak = magnitude
            }
            return peak
        }

        /**
         * Performs linear exponential smoothing step between current and target amplitudes.
         *
         * @param current Current smoothed amplitude.
         * @param target  Target amplitude.
         * @param factor  Damping interpolation factor.
         * @return Interpolated smoothed amplitude.
         */
        @JvmStatic
        fun applyDamping(current: Float, target: Float, factor: Float): Float =
            current + (target - current) * factor

        /**
         * Calculates peak visualizer wave excursion width based on device orientation.
         *
         * @param isLandscape True if landscape mode, false for portrait.
         * @param width       View width in pixels.
         * @param height      View height in pixels.
         * @return Maximum allowed wave excursion width.
         */
        @JvmStatic
        fun calculateMaxAllowedWidth(isLandscape: Boolean, width: Float, height: Float): Float =
            if (isLandscape) height * 0.95f else width * 0.18f
    }
}
