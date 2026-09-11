package com.boogie.vibestation.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom audio visualizer view rendering smooth, multi-layered wave splines
 * derived from Fast Fourier Transform frequency spectrum data.
 */
public class VisualizerView extends View {

    public static final int RENDER_BINS = 32;
    public static final int WAVE_ALPHA = 30;
    public static final float DAMPING_FACTOR = 0.25f;
    public static final int BASS_BIN_END = 6;
    public static final int MID_BIN_END = 60;
    public static final float FFT_MAGNITUDE_DIVISOR = 128f;

    private float targetBass = 0f;
    private float targetMid = 0f;
    private float targetHigh = 0f;

    private float smoothedBass = 0f;
    private float smoothedMid = 0f;
    private float smoothedHigh = 0f;

    private final Paint wavePaint = new Paint();
    private final Path wavePath = new Path();
    private int waveColor = Color.WHITE;

    /**
     * Initializes VisualizerView with context.
     *
     * @param context Application context.
     */
    public VisualizerView(Context context) {
        super(context);
        init();
    }

    /**
     * Initializes VisualizerView with XML attribute set.
     *
     * @param context Application context.
     * @param attrs   XML attributes.
     */
    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /**
     * Configures default paint specs for filled wave spline rendering.
     */
    private void init() {
        wavePaint.setAntiAlias(true);
        wavePaint.setColor(waveColor);
        wavePaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Calculates Euclidean vector magnitude from real and imaginary parts of an FFT bin.
     *
     * @param real Real frequency component byte.
     * @param imag Imaginary frequency component byte.
     * @return Calculated magnitude.
     */
    public static float calculateMagnitude(byte real, byte imag) {
        return (float) Math.sqrt(real * real + imag * imag);
    }

    /**
     * Computes magnitude of an FFT frequency bin index from interleaved byte buffer.
     *
     * @param bytes Raw FFT byte buffer.
     * @param k     Frequency bin index.
     * @return Magnitude of the indexed bin, or 0.0f if out of bounds.
     */
    public static float getMagnitude(byte[] bytes, int k) {
        if (bytes == null || k < 0) return 0f;
        int rIdx = k * 2;
        int iIdx = k * 2 + 1;
        if (iIdx < bytes.length) {
            return calculateMagnitude(bytes[rIdx], bytes[iIdx]);
        }
        return 0f;
    }

    /**
     * Extracts peak frequency magnitudes categorized into Bass, Mid, and High bands,
     * normalized against FFT_MAGNITUDE_DIVISOR.
     *
     * @param bytes Raw FFT data bytes from Android Visualizer.
     * @return 3-element float array containing [targetBass, targetMid, targetHigh].
     */
    public static float[] extractFrequencyBands(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[]{0f, 0f, 0f};
        }
        int fftSize = bytes.length / 2;
        if (fftSize <= 0) {
            return new float[]{0f, 0f, 0f};
        }

        float bassMax = 0;
        float midMax = 0;
        float highMax = 0;

        int bassEnd = Math.min(BASS_BIN_END, fftSize);
        for (int k = 1; k < bassEnd; k++) {
            float mag = getMagnitude(bytes, k);
            if (mag > bassMax) bassMax = mag;
        }

        int midEnd = Math.min(MID_BIN_END, fftSize);
        for (int k = bassEnd; k < midEnd; k++) {
            float mag = getMagnitude(bytes, k);
            if (mag > midMax) midMax = mag;
        }

        for (int k = midEnd; k < fftSize; k++) {
            float mag = getMagnitude(bytes, k);
            if (mag > highMax) highMax = mag;
        }

        return new float[]{
                bassMax / FFT_MAGNITUDE_DIVISOR,
                midMax / FFT_MAGNITUDE_DIVISOR,
                highMax / FFT_MAGNITUDE_DIVISOR
        };
    }

    /**
     * Performs linear exponential smoothing step between current and target amplitudes.
     *
     * @param current Current smoothed amplitude.
     * @param target  Target amplitude.
     * @param factor  Damping interpolation factor.
     * @return Interpolated smoothed amplitude.
     */
    public static float applyDamping(float current, float target, float factor) {
        return current + (target - current) * factor;
    }

    /**
     * Calculates peak visualizer wave excursion width based on device orientation.
     *
     * @param isLandscape True if landscape mode, false for portrait.
     * @param width       View width in pixels.
     * @param height      View height in pixels.
     * @return Maximum allowed wave excursion width.
     */
    public static float calculateMaxAllowedWidth(boolean isLandscape, float width, float height) {
        return isLandscape ? height * 0.95f : width * 0.18f;
    }

    /**
     * Processes raw FFT data capture bytes and updates frequency band targets.
     *
     * @param bytes Raw frequency/FFT data byte array from the Android Visualizer API.
     */
    public void updateVisualizer(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;

        float[] bands = extractFrequencyBands(bytes);
        synchronized (this) {
            targetBass = bands[0];
            targetMid = bands[1];
            targetHigh = bands[2];
        }
        postInvalidateOnAnimation();
    }

    /**
     * Updates wave drawing color while preserving visualizer transparency.
     *
     * @param color ARGB color value.
     */
    public void setColor(int color) {
        this.waveColor = color;
        wavePaint.setColor(color);
        wavePaint.setAlpha(WAVE_ALPHA);
    }

    /**
     * Retrieves active wave color.
     *
     * @return ARGB color value.
     */
    public int getColor() {
        return waveColor;
    }

    /**
     * Retrieves current target bass amplitude.
     *
     * @return Normalized bass amplitude [0.0, 1.0+].
     */
    public synchronized float getTargetBass() {
        return targetBass;
    }

    /**
     * Retrieves current target mid amplitude.
     *
     * @return Normalized mid amplitude [0.0, 1.0+].
     */
    public synchronized float getTargetMid() {
        return targetMid;
    }

    /**
     * Retrieves current target high amplitude.
     *
     * @return Normalized high amplitude [0.0, 1.0+].
     */
    public synchronized float getTargetHigh() {
        return targetHigh;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float localBass, localMid, localHigh;
        synchronized (this) {
            localBass = targetBass;
            localMid = targetMid;
            localHigh = targetHigh;
        }

        smoothedBass = applyDamping(smoothedBass, localBass, DAMPING_FACTOR);
        smoothedMid = applyDamping(smoothedMid, localMid, DAMPING_FACTOR);
        smoothedHigh = applyDamping(smoothedHigh, localHigh, DAMPING_FACTOR);

        boolean needsMoreFrames = false;
        if (Math.abs(localBass - smoothedBass) > 0.005f ||
                Math.abs(localMid - smoothedMid) > 0.005f ||
                Math.abs(localHigh - smoothedHigh) > 0.005f) {
            needsMoreFrames = true;
        }

        float barHeight = height / (RENDER_BINS - 1);
        float timeSec = (float) (System.currentTimeMillis() % 100000) / 1000f;

        boolean isLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        float maxAllowedWidth = calculateMaxAllowedWidth(isLandscape, width, height);
        float bassAmp = smoothedBass * maxAllowedWidth;
        float midAmp = smoothedMid * maxAllowedWidth;
        float highAmp = smoothedHigh * maxAllowedWidth;

        float[] drawXLayer1 = new float[RENDER_BINS];
        float[] drawXLayer2 = new float[RENDER_BINS];
        float[] drawXLayer3 = new float[RENDER_BINS];
        float[] drawXLayer4 = new float[RENDER_BINS];
        float[] drawXLayer5 = new float[RENDER_BINS];
        float[] drawXLayer6 = new float[RENDER_BINS];

        for (int i = 0; i < RENDER_BINS; i++) {
            float whipFactor = 1.0f;
            float baselineX = 0f;

            float l1Sine = (float) Math.sin(i * 0.15f - timeSec * 2.5f) * (bassAmp * 0.25f + width * 0.005f);
            drawXLayer1[i] = baselineX + (bassAmp * 0.8f + l1Sine) * whipFactor;
            if (drawXLayer1[i] < 0) drawXLayer1[i] = 0;

            float l2Sine = (float) Math.sin(i * 0.20f - timeSec * 3.2f) * (bassAmp * 0.30f + width * 0.006f);
            drawXLayer2[i] = baselineX + (bassAmp * 0.6f + l2Sine) * whipFactor;
            if (drawXLayer2[i] < 0) drawXLayer2[i] = 0;

            float l3Sine = (float) Math.sin(i * 0.25f - timeSec * 4.5f) * (midAmp * 0.35f + width * 0.008f);
            drawXLayer3[i] = baselineX + (midAmp * 0.9f + l3Sine) * whipFactor;
            if (drawXLayer3[i] < 0) drawXLayer3[i] = 0;

            float l4Sine = (float) Math.sin(i * 0.32f - timeSec * 5.5f) * (midAmp * 0.40f + width * 0.010f);
            drawXLayer4[i] = baselineX + (midAmp * 0.7f + l4Sine) * whipFactor;
            if (drawXLayer4[i] < 0) drawXLayer4[i] = 0;

            float l5Sine = (float) Math.sin(i * 0.40f - timeSec * 7.0f) * (highAmp * 0.45f + width * 0.012f);
            drawXLayer5[i] = baselineX + (highAmp * 1.0f + l5Sine) * whipFactor;
            if (drawXLayer5[i] < 0) drawXLayer5[i] = 0;

            float l6Sine = (float) Math.sin(i * 0.50f - timeSec * 9.0f) * (highAmp * 0.55f + width * 0.015f);
            drawXLayer6[i] = baselineX + (highAmp * 0.8f + l6Sine) * whipFactor;
            if (drawXLayer6[i] < 0) drawXLayer6[i] = 0;
        }

        drawSplineWave(canvas, drawXLayer1, (int) (WAVE_ALPHA * 0.3f), barHeight, height);
        drawSplineWave(canvas, drawXLayer2, (int) (WAVE_ALPHA * 0.4f), barHeight, height);
        drawSplineWave(canvas, drawXLayer3, (int) (WAVE_ALPHA * 0.6f), barHeight, height);
        drawSplineWave(canvas, drawXLayer4, (int) (WAVE_ALPHA * 0.7f), barHeight, height);
        drawSplineWave(canvas, drawXLayer5, (int) (WAVE_ALPHA * 0.9f), barHeight, height);
        drawSplineWave(canvas, drawXLayer6, WAVE_ALPHA, barHeight, height);

        if (needsMoreFrames || bassAmp > 0.1f || midAmp > 0.1f || highAmp > 0.1f) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * Constructs and draws a continuous cubic spline wave using Catmull-Rom style control points.
     *
     * @param canvas    Target canvas.
     * @param drawX     Array of anchor X values.
     * @param alpha     Alpha opacity for this layer.
     * @param barHeight Distance between vertical nodes.
     * @param height    Total canvas height.
     */
    private void drawSplineWave(Canvas canvas, float[] drawX, int alpha, float barHeight, float height) {
        boolean isLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        float width = getWidth();
        float barSize = isLandscape ? (width / (RENDER_BINS - 1)) : barHeight;

        wavePath.reset();
        if (isLandscape) {
            wavePath.moveTo(0, height);
            for (int i = 0; i < RENDER_BINS; i++) {
                float currentX = i * barSize;
                float currentY = height - drawX[i];
                if (i == 0) {
                    wavePath.lineTo(currentX, currentY);
                } else {
                    float x1 = (i - 1) * barSize;
                    float y1 = height - drawX[i - 1];
                    float x2 = i * barSize;
                    float y2 = height - drawX[i];
                    float y0 = (i < 2) ? y1 : height - drawX[i - 2];
                    float y3 = (i >= RENDER_BINS - 1) ? y2 : height - drawX[i + 1];

                    wavePath.cubicTo(
                            x1 + barSize / 3f, y1 + (y2 - y0) / 6f,
                            x2 - barSize / 3f, y2 - (y3 - y1) / 6f,
                            x2, y2
                    );
                }
            }
            wavePath.lineTo(width, height);
            wavePath.lineTo(0, height);
        } else {
            wavePath.moveTo(0, height);
            for (int i = 0; i < RENDER_BINS; i++) {
                float currentY = height - (i * barSize);
                float currentX = drawX[i];
                if (i == 0) {
                    wavePath.lineTo(currentX, currentY);
                } else {
                    float x1 = drawX[i - 1];
                    float y1 = height - (i - 1) * barSize;
                    float x2 = drawX[i];
                    float y2 = height - i * barSize;
                    float x0 = (i < 2) ? x1 : drawX[i - 2];
                    float x3 = (i >= RENDER_BINS - 1) ? x2 : drawX[i + 1];

                    wavePath.cubicTo(
                            x1 + (x2 - x0) / 6f, y1 - barSize / 3f,
                            x2 - (x3 - x1) / 6f, y2 + barSize / 3f,
                            x2, y2
                    );
                }
            }
            wavePath.lineTo(0, 0);
        }
        wavePath.close();
        wavePaint.setAlpha(alpha);
        canvas.drawPath(wavePath, wavePaint);
    }
}
