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
    private Paint paint = new Paint();
    private Path path = new Path();

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setAntiAlias(true);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
    }

    public void updateVisualizer(byte[] bytes) {
        this.fftBytes = bytes;
    }

    public void setColor(int color) {
        paint.setColor(color);
        paint.setAlpha(70);
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

        path.reset();
        path.moveTo(0, height); // Start at bottom-left corner now

        float barHeight = height / (numBins - 1);

        for (int i = 0; i < numBins; i++) {
            float magnitude;
            if (i == 0) {
                magnitude = Math.abs(fftBytes[0]);
            } else {
                magnitude = (float) Math.hypot(fftBytes[i * 2], fftBytes[i * 2 + 1]);
            }

            float amplitude = (magnitude / 128f) * (width * 0.5f);
            if (amplitude > width * 0.6f) amplitude = width * 0.6f;

            smoothedMagnitudes[i] += (amplitude - smoothedMagnitudes[i]) * 0.25f;

            // INVERTED Y AXIS: Bass (i=0) is at the bottom (height), Treble pushes UP to 0
            float y = height - (i * barHeight);
            float x = smoothedMagnitudes[i];

            if (i == 0) {
                path.lineTo(x, y);
            } else {
                float prevY = height - ((i - 1) * barHeight);
                float prevX = smoothedMagnitudes[i - 1];
                // Smooth bezier curve going UP the Y axis
                path.cubicTo(
                        prevX, prevY - barHeight / 2,
                        x, y + barHeight / 2,
                        x, y
                );
            }
        }

        // Draw to top-left, then close the shape back to the bottom-left
        path.lineTo(0, 0);
        path.close();

        canvas.drawPath(path, paint);
        invalidate();
    }
}