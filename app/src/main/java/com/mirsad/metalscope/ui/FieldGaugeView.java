package com.mirsad.metalscope.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.mirsad.metalscope.core.SignalProcessor;

import java.util.Locale;

/** A custom, dependency-free gauge for the measured change in magnetic field (µT). */
public final class FieldGaugeView extends View {
    private static final int TRACK = 0xFF253945;
    private static final int MUTED = 0xFF8EA4AF;
    private static final int TEXT = 0xFFF4FAFB;
    private static final int TEAL = 0xFF58E6C5;
    private static final int AMBER = 0xFFFFC56D;
    private static final int RED = 0xFFFF6F72;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SignalProcessor.Reading reading;
    private float density;

    public FieldGaugeView(Context context) {
        super(context);
        init(context);
    }

    public FieldGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("مؤشر انحراف المجال المغناطيسي");
    }

    public void setReading(SignalProcessor.Reading value) {
        reading = value;
        if (value != null) {
            String description = value.hasBaseline
                    ? String.format(Locale.US,
                    "انحراف المجال %.1f ميكروتسلا، المجال الكلي %.1f ميكروتسلا، عتبة التنبيه %.1f ميكروتسلا",
                    value.anomaly, value.filteredMagnitude, value.threshold)
                    : String.format(Locale.US, "المجال الكلي %.1f ميكروتسلا. عاير خط الأساس أولاً",
                    value.filteredMagnitude);
            setContentDescription(description);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        float centerX = width * 0.5f;
        float centerY = height * 0.49f;
        float radius = Math.min(width * 0.34f, height * 0.39f);
        radius = Math.max(dp(54), radius);
        RectF arc = new RectF(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius);
        float stroke = dp(11);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(TRACK);
        canvas.drawArc(arc, 135f, 270f, false, paint);

        drawTicks(canvas, centerX, centerY, radius, stroke);

        double threshold = reading == null ? 6.0 : Math.max(1.0, reading.threshold);
        double anomaly = reading == null ? 0.0 : reading.anomaly;
        float ratio = (float) Math.max(0.0, Math.min(1.0, anomaly / (threshold * 3.0)));
        int signalColor = reading != null && reading.alerting ? RED
                : (anomaly >= threshold ? AMBER : TEAL);
        if (ratio > 0.001f) {
            paint.setColor(signalColor);
            paint.setStrokeWidth(stroke);
            canvas.drawArc(arc, 135f, Math.max(2f, 270f * ratio), false, paint);
        }

        drawCenter(canvas, centerX, centerY, radius, signalColor);
    }

    private void drawTicks(Canvas canvas, float centerX, float centerY, float radius, float arcStroke) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int index = 0; index <= 27; index++) {
            float angle = (float) Math.toRadians(135.0 + index * 10.0);
            boolean major = index % 3 == 0;
            float inner = radius - arcStroke * 0.95f - (major ? dp(7) : dp(4));
            float outer = radius - arcStroke * 0.95f - dp(1);
            paint.setStrokeWidth(major ? dp(1.6f) : dp(1));
            paint.setColor(major ? 0xFF6D8793 : 0xFF3A505C);
            canvas.drawLine(
                    centerX + (float) Math.cos(angle) * inner,
                    centerY + (float) Math.sin(angle) * inner,
                    centerX + (float) Math.cos(angle) * outer,
                    centerY + (float) Math.sin(angle) * outer,
                    paint);
        }
    }

    private void drawCenter(Canvas canvas, float centerX, float centerY, float radius, int signalColor) {
        boolean calibrated = reading != null && reading.hasBaseline;
        String value = calibrated
                ? String.format(Locale.US, "%.1f", reading.anomaly)
                : "—";

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextSize(dp(37));
        paint.setColor(calibrated ? TEXT : MUTED);
        canvas.drawText(value, centerX, centerY + dp(1), paint);

        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(dp(13));
        paint.setColor(signalColor);
        canvas.drawText("µT  ·  ΔB", centerX, centerY + dp(24), paint);

        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(dp(11));
        paint.setColor(MUTED);
        canvas.drawText(calibrated ? "انحراف عن خط الأساس" : "عاير خط الأساس أولاً",
                centerX, centerY + dp(47), paint);

        if (reading != null && reading.filteredMagnitude > 0.0) {
            paint.setTextSize(dp(10));
            paint.setColor(0xFFB5C6CC);
            canvas.drawText(String.format(Locale.US, "كلي %.1f µT", reading.filteredMagnitude),
                    centerX, centerY + dp(67), paint);
        }

        paint.setTextSize(dp(9));
        paint.setColor(MUTED);
        canvas.drawText("0", centerX - radius * 0.74f, centerY + radius * 0.78f, paint);
        canvas.drawText("3×", centerX + radius * 0.74f, centerY + radius * 0.78f, paint);
    }

    private float dp(float value) {
        return value * density;
    }
}
