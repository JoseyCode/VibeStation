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
    private byte[] fftBytes;
    private float[] smoothedMagnitudes;
    private final Paint wavePaint = new Paint();
    private final Path wavePath = new Path();
    private static final int WAVE_ALPHA = 70;
    private static final float DAMPING_FACTOR = 0.25f;

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        wavePaint.setAntiAlias(true);
        wavePaint.setColor(Color.WHITE);
        wavePaint.setStyle(Paint.Style.FILL);
    }

    public void updateVisualizer(byte[] bytes) {
        this.fftBytes = bytes;
        postInvalidate(); // Draw only when visualizer updates to conserve CPU and battery
    }

    public void setColor(int color) {
        wavePaint.setColor(color);
        wavePaint.setAlpha(WAVE_ALPHA);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (fftBytes == null) return;

        float width = getWidth();
        float height = getHeight();

        int numBins = fftBytes.length / 3;
        if (numBins == 0) return;

        if (smoothedMagnitudes == null || smoothedMagnitudes.length != numBins) {
            smoothedMagnitudes = new float[numBins];
            Arrays.fill(smoothedMagnitudes, 0f);
        }

        wavePath.reset();
        wavePath.moveTo(0, height); // Start at bottom-left corner

        float barHeight = height / (numBins - 1);

        for (int i = 0; i < numBins; i++) {
            float magnitude;
            if (i == 0) {
                magnitude = Math.abs(fftBytes[0]);
            } else {
                magnitude = (float) Math.hypot(fftBytes[i * 2], fftBytes[i * 2 + 1]);
            }

            float amplitude = (magnitude / 128f) * (width * 0.5f);
            if (amplitude > width * 0.6f) {
                amplitude = width * 0.6f;
            }

            smoothedMagnitudes[i] += (amplitude - smoothedMagnitudes[i]) * DAMPING_FACTOR;

            // Inverted Y-axis: bass at bottom, treble pushes up
            float currentY = height - (i * barHeight);
            float currentX = smoothedMagnitudes[i];

            if (i == 0) {
                wavePath.lineTo(currentX, currentY);
            } else {
                float previousY = height - ((i - 1) * barHeight);
                float previousX = smoothedMagnitudes[i - 1];
                // Smooth bezier curve going UP the Y axis
                wavePath.cubicTo(
                        previousX, previousY - barHeight / 2,
                        currentX, currentY + barHeight / 2,
                        currentX, currentY
                );
            }
        }

        // Draw to top-left, then close the shape back to the bottom-left
        wavePath.lineTo(0, 0);
        wavePath.close();

        canvas.drawPath(wavePath, wavePaint);
    }
}