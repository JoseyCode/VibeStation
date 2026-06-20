package com.example.retroclone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.Arrays;

public class VisualizerView extends View {
    private static final int MAX_BINS = 128;
    private final float[] targetAmplitudes = new float[MAX_BINS];
    private final float[] smoothedMagnitudes = new float[MAX_BINS];
    private final Paint wavePaint = new Paint();
    private final Path wavePath = new Path();
    private static final int WAVE_ALPHA = 70;
    private static final float DAMPING_FACTOR = 0.25f;

    public static final int STYLE_WAVY = 0;
    public static final int STYLE_SPIKY = 1;
    private int visualizerStyle = STYLE_WAVY;
    private int renderBins = 32; // Default for wavy curves

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        wavePaint.setAntiAlias(true);
        wavePaint.setColor(Color.WHITE);
        wavePaint.setStyle(Paint.Style.FILL);
    }

    public void setVisualizerStyle(int style) {
        this.visualizerStyle = style;
        this.renderBins = (style == STYLE_WAVY) ? 32 : 96;
        postInvalidateOnAnimation();
    }

    public void updateVisualizer(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;

        // Process FFT and group into logarithmic bands on the background data capture thread
        int fftSize = bytes.length / 2;
        if (fftSize <= 0) return;

        float logMin = (float) Math.log(1);
        float logMax = (float) Math.log(fftSize);
        float width = getWidth();
        if (width <= 0) width = 1080f; // Fallback

        synchronized (this) {
            for (int i = 0; i < renderBins; i++) {
                float pct = (float) i / (renderBins - 1);
                int startBin = (int) Math.exp(logMin + pct * (logMax - logMin));
                int endBin = (int) Math.exp(logMin + (pct + 1f / renderBins) * (logMax - logMin));

                if (endBin <= startBin) {
                    endBin = startBin + 1;
                }
                if (endBin > fftSize) {
                    endBin = fftSize;
                }

                float maxMagnitude = 0;
                for (int k = startBin; k < endBin; k++) {
                    int rIdx = k * 2;
                    int iIdx = k * 2 + 1;
                    if (iIdx < bytes.length) {
                        float r = bytes[rIdx];
                        float im = bytes[iIdx];
                        float mag = (float) Math.sqrt(r * r + im * im);
                        if (mag > maxMagnitude) {
                            maxMagnitude = mag;
                        }
                    }
                }
                float magnitude = maxMagnitude;
                float amplitude = (magnitude / 128f) * (width * 0.15f);
                if (amplitude > width * 0.18f) {
                    amplitude = width * 0.18f;
                }
                targetAmplitudes[i] = amplitude;
            }
        }
        postInvalidateOnAnimation();
    }

    public void setColor(int color) {
        wavePaint.setColor(color);
        wavePaint.setAlpha(WAVE_ALPHA);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float[] localTargets = new float[renderBins];
        synchronized (this) {
            System.arraycopy(targetAmplitudes, 0, localTargets, 0, renderBins);
        }

        boolean needsMoreFrames = false;
        for (int i = 0; i < renderBins; i++) {
            float diff = localTargets[i] - smoothedMagnitudes[i];
            if (Math.abs(diff) > 0.5f) {
                smoothedMagnitudes[i] += diff * DAMPING_FACTOR;
                needsMoreFrames = true;
            } else {
                smoothedMagnitudes[i] = localTargets[i];
            }
        }

        wavePath.reset();
        wavePath.moveTo(0, height);
        float barHeight = height / (renderBins - 1);

        for (int i = 0; i < renderBins; i++) {
            float currentY = height - (i * barHeight);
            float currentX = smoothedMagnitudes[i];

            if (i == 0) {
                wavePath.lineTo(currentX, currentY);
            } else {
                if (visualizerStyle == STYLE_WAVY) {
                    float x1 = smoothedMagnitudes[i - 1];
                    float y1 = height - (i - 1) * barHeight;
                    float x2 = smoothedMagnitudes[i];
                    float y2 = height - i * barHeight;

                    float x0 = (i < 2) ? x1 : smoothedMagnitudes[i - 2];
                    float x3 = (i >= renderBins - 1) ? x2 : smoothedMagnitudes[i + 1];

                    wavePath.cubicTo(
                            x1 + (x2 - x0) / 6f, y1 - barHeight / 3f,
                            x2 - (x3 - x1) / 6f, y2 + barHeight / 3f,
                            x2, y2
                    );
                } else {
                    wavePath.lineTo(currentX, currentY);
                }
            }
        }

        wavePath.lineTo(0, 0);
        wavePath.close();
        canvas.drawPath(wavePath, wavePaint);

        if (needsMoreFrames) {
            postInvalidateOnAnimation();
        }
    }
}