package com.mirsad.metalscope.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;

/** Lightweight rolling plot; points are absolute baseline deviations in microtesla. */
public final class SignalChartView extends View {
    private static final int GRID = 0xFF263943;
    private static final int MUTED = 0xFF7E96A1;
    private static final int TEAL = 0xFF58E6C5;
    private static final int AMBER = 0xFFFFC56D;

    private final Deque<Double> values = new ArrayDeque<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float density;
    private double threshold = 6.0;

    public SignalChartView(Context context) {
        super(context);
        init(context);
    }

    public SignalChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        setContentDescription("رسم حي لانحراف المجال المغناطيسي أثناء المسح");
    }

    public void setThreshold(double value) {
        if (value > 0.0) {
            threshold = value;
        }
        invalidate();
    }

    public void addSample(double anomaly) {
        if (Double.isNaN(anomaly) || Double.isInfinite(anomaly)) {
            return;
        }
        values.addLast(Math.max(0.0, anomaly));
        while (values.size() > 200) {
            values.removeFirst();
        }
        invalidate();
    }

    public void clear() {
        values.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(8);
        float right = getWidth() - dp(8);
        float top = dp(10);
        float bottom = getHeight() - dp(18);
        if (right <= left || bottom <= top) {
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(GRID);
        paint.setPathEffect(null);
        for (int line = 0; line <= 3; line++) {
            float y = top + (bottom - top) * line / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }
        for (int line = 0; line <= 4; line++) {
            float x = left + (right - left) * line / 4f;
            canvas.drawLine(x, top, x, bottom, paint);
        }

        double maximum = threshold * 3.0;
        for (double value : values) {
            maximum = Math.max(maximum, value * 1.15);
        }
        maximum = Math.max(8.0, maximum);
        float thresholdY = bottom - (float) (Math.min(threshold, maximum) / maximum) * (bottom - top);
        paint.setColor(0xFFB6844C);
        paint.setStrokeWidth(dp(1));
        paint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));
        canvas.drawLine(left, thresholdY, right, thresholdY, paint);
        paint.setPathEffect(null);

        if (values.size() < 2) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(MUTED);
            paint.setTextSize(dp(11));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("يظهر المسار هنا عند بدء المسح", getWidth() / 2f,
                    (top + bottom) / 2f, paint);
            return;
        }

        Path linePath = new Path();
        Path fillPath = new Path();
        int index = 0;
        int count = values.size();
        for (double value : values) {
            float x = left + (right - left) * index / Math.max(1f, count - 1f);
            float y = bottom - (float) (Math.min(value, maximum) / maximum) * (bottom - top);
            if (index == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, bottom);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            index++;
        }
        fillPath.lineTo(right, bottom);
        fillPath.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2458E6C5);
        canvas.drawPath(fillPath, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(values.peekLast() != null && values.peekLast() >= threshold ? AMBER : TEAL);
        canvas.drawPath(linePath, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MUTED);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(dp(9));
        canvas.drawText("20 ث", left, getHeight() - dp(2), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("الآن", right, getHeight() - dp(2), paint);
    }

    private float dp(float value) {
        return value * density;
    }
}
