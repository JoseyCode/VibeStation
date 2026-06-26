package com.example.retroclone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * A custom view designed to render a smooth, multi-layered wave visualizer.
 * It processes FFT (Fast Fourier Transform) audio data, categorizes it into
 * frequency bands (Bass, Mid, High), and draws overlapping animated spline waves.
 */
public class VisualizerView extends View {
    // Number of vertical points used to construct the wave splines
    private static final int RENDER_BINS = 32;
    
    // Target amplitudes computed from raw FFT magnitudes
    private float targetBass = 0f;
    private float targetMid = 0f;
    private float targetHigh = 0f;
    
    // Smoothed amplitudes using exponential damping to prevent visual jitter
    private float smoothedBass = 0f;
    private float smoothedMid = 0f;
    private float smoothedHigh = 0f;
    
    private final Paint wavePaint = new Paint();
    private final Path wavePath = new Path();
    private static final int WAVE_ALPHA = 30;
    
    // Damping factor used for linear interpolation (higher = faster response)
    private static final float DAMPING_FACTOR = 0.25f;

    /**
     * Initializes the paint specifications for drawing the filled waves.
     */
    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        wavePaint.setAntiAlias(true);
        wavePaint.setColor(Color.WHITE);
        wavePaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Processes raw FFT data capture bytes and filters them into Bass, Mid, and High ranges.
     * Maps the peak frequency magnitudes to target visualizer heights.
     *
     * @param bytes Raw frequency/FFT data byte array from the Android Visualizer API.
     */
    public void updateVisualizer(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;

        int fftSize = bytes.length / 2;
        if (fftSize <= 0) return;

        float bassMax = 0;
        float midMax = 0;
        float highMax = 0;

        // Bass: bins 1 to 6 (lowest frequencies, sub-bass/drums)
        int bassEnd = Math.min(6, fftSize);
        for (int k = 1; k < bassEnd; k++) {
            float mag = getMagnitude(bytes, k);
            if (mag > bassMax) bassMax = mag;
        }

        // Mid: bins 6 to 60 (vocal and primary instrument range)
        int midEnd = Math.min(60, fftSize);
        for (int k = bassEnd; k < midEnd; k++) {
            float mag = getMagnitude(bytes, k);
            if (mag > midMax) midMax = mag;
        }

        // High: bins 60 to fftSize (cymbals, high-hats, trebles)
        for (int k = midEnd; k < fftSize; k++) {
            float mag = getMagnitude(bytes, k);
            if (mag > highMax) highMax = mag;
        }

        // Apply synchronized state updates and flag an animation frame update
        synchronized (this) {
            targetBass = bassMax / 128f;
            targetMid = midMax / 128f;
            targetHigh = highMax / 128f;
        }
        postInvalidateOnAnimation();
    }

    /**
     * Computes the magnitude of a frequency bin from the real and imaginary parts of the FFT array.
     * Formula: magnitude = sqrt(real^2 + imaginary^2)
     *
     * @param bytes Raw FFT data array
     * @param k     Frequency bin index
     * @return      Magnitude of the frequency bin
     */
    private float getMagnitude(byte[] bytes, int k) {
        int rIdx = k * 2;
        int iIdx = k * 2 + 1;
        if (iIdx < bytes.length) {
            float r = bytes[rIdx];
            float im = bytes[iIdx];
            return (float) Math.sqrt(r * r + im * im);
        }
        return 0;
    }

    /**
     * Updates the base wave drawing color while preserving transparency constraints.
     *
     * @param color ARGB color value to apply.
     */
    public void setColor(int color) {
        wavePaint.setColor(color);
        wavePaint.setAlpha(WAVE_ALPHA);
    }

    /**
     * Main drawing routine. Smoothes amplitude changes, computes sinusoidal offsets
     * based on time to create a "whip" motion, and draws three overlapping wave layers.
     *
     * @param canvas The canvas to draw the visualization on.
     */
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

        // Smooth target amplitudes using low-pass exponential filter
        smoothedBass += (localBass - smoothedBass) * DAMPING_FACTOR;
        smoothedMid += (localMid - smoothedMid) * DAMPING_FACTOR;
        smoothedHigh += (localHigh - smoothedHigh) * DAMPING_FACTOR;

        // Keep requesting invalidation frames if values have not stabilized yet
        boolean needsMoreFrames = false;
        if (Math.abs(localBass - smoothedBass) > 0.005f ||
            Math.abs(localMid - smoothedMid) > 0.005f ||
            Math.abs(localHigh - smoothedHigh) > 0.005f) {
            needsMoreFrames = true;
        }

        float barHeight = height / (RENDER_BINS - 1);
        float timeSec = (float) (System.currentTimeMillis() % 100000) / 1000f;

        float maxAllowedWidth = width * 0.18f;
        float bassAmp = smoothedBass * maxAllowedWidth;
        float midAmp = smoothedMid * maxAllowedWidth;
        float highAmp = smoothedHigh * maxAllowedWidth;

        // Clamp individual amplitudes to visualizer max allowed boundary (95% width)
        float maxBoundary = width * 0.95f;
        if (bassAmp > maxBoundary) bassAmp = maxBoundary;
        if (midAmp > maxBoundary) midAmp = maxBoundary;
        if (highAmp > maxBoundary) highAmp = maxBoundary;

        // Arrays holding horizontal anchor offsets for each layer
        float[] drawXBass = new float[RENDER_BINS];
        float[] drawXMid = new float[RENDER_BINS];
        float[] drawXHigh = new float[RENDER_BINS];

        for (int i = 0; i < RENDER_BINS; i++) {
            float pct = (float) i / (RENDER_BINS - 1);
            // Whip factor increases from top (pct = 1) to bottom (pct = 0)
            float whipFactor = 1.0f;
            
            // Baseline shifts from top-left (0) to bottom-right (width * 0.55f)
            float baselineX = 0f;

            // Bass (low frequency): slower wave speed, lower phase shift
            float bassSine = (float) Math.sin(i * 0.15f - timeSec * 2.5f) * (bassAmp * 0.25f + width * 0.005f);
            drawXBass[i] = baselineX + (bassAmp * 0.8f + bassSine) * whipFactor;
            if (drawXBass[i] < 0) drawXBass[i] = 0;
            if (drawXBass[i] > maxBoundary) drawXBass[i] = maxBoundary;

            // Mid (vocal frequency): moderate wave speed, medium phase shift
            float midSine = (float) Math.sin(i * 0.25f - timeSec * 4.5f) * (midAmp * 0.35f + width * 0.008f);
            drawXMid[i] = baselineX + (midAmp * 0.9f + midSine) * whipFactor;
            if (drawXMid[i] < 0) drawXMid[i] = 0;
            if (drawXMid[i] > maxBoundary) drawXMid[i] = maxBoundary;

            // High (treble frequency): fast wave speed, high phase shift
            float highSine = (float) Math.sin(i * 0.4f - timeSec * 7.0f) * (highAmp * 0.45f + width * 0.012f);
            drawXHigh[i] = baselineX + (highAmp * 1.0f + highSine) * whipFactor;
            if (drawXHigh[i] < 0) drawXHigh[i] = 0;
            if (drawXHigh[i] > maxBoundary) drawXHigh[i] = maxBoundary;
        }

        // Draw Layer 1: Bass Wave (Deep background layer, lowest opacity)
        drawSplineWave(canvas, drawXBass, (int) (WAVE_ALPHA * 0.5f), barHeight, height);

        // Draw Layer 2: Mid Wave (Middle layer, medium opacity)
        drawSplineWave(canvas, drawXMid, (int) (WAVE_ALPHA * 0.75f), barHeight, height);

        // Draw Layer 3: High Wave (Foreground layer, highest opacity)
        drawSplineWave(canvas, drawXHigh, WAVE_ALPHA, barHeight, height);

        // Force repaint if we are still animating
        if (needsMoreFrames || bassAmp > 0.1f || midAmp > 0.1f || highAmp > 0.1f) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * Constructs and draws a continuous cubic spline wave using path curves (Catmull-Rom style control points).
     *
     * @param canvas    Target canvas
     * @param drawX     Array of anchor X values
     * @param alpha     Alpha opacity for this layer
     * @param barHeight Distance between vertical nodes
     * @param height    Total canvas height
     */
    private void drawSplineWave(Canvas canvas, float[] drawX, int alpha, float barHeight, float height) {
        wavePath.reset();
        wavePath.moveTo(0, height);
        for (int i = 0; i < RENDER_BINS; i++) {
            float currentY = height - (i * barHeight);
            float currentX = drawX[i];
            if (i == 0) {
                wavePath.lineTo(currentX, currentY);
            } else {
                float x1 = drawX[i - 1];
                float y1 = height - (i - 1) * barHeight;
                float x2 = drawX[i];
                float y2 = height - i * barHeight;
                float x0 = (i < 2) ? x1 : drawX[i - 2];
                float x3 = (i >= RENDER_BINS - 1) ? x2 : drawX[i + 1];
                
                // Calculate control points for cubic bezier interpolation to ensure smooth curve transitions
                wavePath.cubicTo(
                        x1 + (x2 - x0) / 6f, y1 - barHeight / 3f,
                        x2 - (x3 - x1) / 6f, y2 + barHeight / 3f,
                        x2, y2
                );
            }
        }
        wavePath.lineTo(0, 0);
        wavePath.close();
        wavePaint.setAlpha(alpha);
        canvas.drawPath(wavePath, wavePaint);
    }
}