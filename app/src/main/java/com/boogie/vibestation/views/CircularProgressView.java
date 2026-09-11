package com.boogie.vibestation.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom circular progress ring displaying media playback advancement.
 */
public class CircularProgressView extends View {

    public static final float DEFAULT_STROKE_WIDTH = 12f;
    public static final float START_ANGLE = 90f;
    public static final int DEFAULT_COLOR = 0xFFFFFFFF;

    private Paint paint;
    private RectF rect;
    private float progress = 0f;
    private int currentColor = DEFAULT_COLOR;

    /**
     * Initializes CircularProgressView with context.
     *
     * @param context Application context.
     */
    public CircularProgressView(Context context) {
        super(context);
        init();
    }

    /**
     * Initializes CircularProgressView with XML attribute set.
     *
     * @param context Application context.
     * @param attrs   XML attributes.
     */
    public CircularProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /**
     * Sets up paint specifications and stroke geometry.
     */
    private void init() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(DEFAULT_STROKE_WIDTH);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(currentColor);
        rect = new RectF();
    }

    /**
     * Clamps fractional progress within normalized bounds [0.0, 1.0].
     *
     * @param progress Raw progress input value.
     * @return Clamped progress in the range 0.0f to 1.0f inclusive.
     */
    public static float clampProgress(float progress) {
        return Math.max(0f, Math.min(1f, progress));
    }

    /**
     * Computes arc sweep angle in degrees for the given progress value.
     *
     * @param progress Progress fractional value [0.0, 1.0].
     * @return Sweep angle from 0.0f to 360.0f degrees.
     */
    public static float calculateSweepAngle(float progress) {
        return clampProgress(progress) * 360f;
    }

    /**
     * Updates current completion progress and triggers view invalidation.
     *
     * @param progress Completion fraction between 0.0f and 1.0f.
     */
    public void setProgress(float progress) {
        this.progress = clampProgress(progress);
        invalidate();
    }

    /**
     * Retrieves current normalized completion progress.
     *
     * @return Current progress value between 0.0f and 1.0f.
     */
    public float getProgress() {
        return progress;
    }

    /**
     * Updates stroke drawing color and triggers view invalidation.
     *
     * @param color ARGB color integer.
     */
    public void setColor(int color) {
        this.currentColor = color;
        if (paint != null) {
            paint.setColor(color);
        }
        invalidate();
    }

    /**
     * Retrieves active stroke drawing color.
     *
     * @return ARGB color integer.
     */
    public int getColor() {
        return currentColor;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = (paint != null ? paint.getStrokeWidth() : DEFAULT_STROKE_WIDTH) / 2f;
        rect.set(padding, padding, w - padding, h - padding);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float sweepAngle = calculateSweepAngle(progress);
        // Start arc at 90 degrees (6 o'clock position)
        canvas.drawArc(rect, START_ANGLE, sweepAngle, false, paint);
    }
}
