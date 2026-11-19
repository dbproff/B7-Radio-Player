package com.example.radioplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Interactive circular Hue + Saturation color wheel.
 */
public class ColorWheelView extends View {

    public interface OnColorChangedListener {
        void onColorChanged(float hue, float saturation);
    }

    private static final int[] HUE_COLORS = {
            0xFFFF0000, // 0 deg: Red
            0xFFFFFF00, // 60 deg: Yellow
            0xFF00FF00, // 120 deg: Green
            0xFF00FFFF, // 180 deg: Cyan
            0xFF0000FF, // 240 deg: Blue
            0xFFFF00FF, // 300 deg: Magenta
            0xFFFF0000  // 360 deg: Red
    };

    private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectorFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectorOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectorInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cx;
    private float cy;
    private float radius;
    private float hue = 0f;
    private float saturation = 1f;
    private float brightness = 1f;
    private OnColorChangedListener listener;

    public ColorWheelView(Context context) {
        super(context);
        init();
    }

    public ColorWheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        float density = getResources().getDisplayMetrics().density;
        selectorOuterPaint.setStyle(Paint.Style.STROKE);
        selectorOuterPaint.setStrokeWidth(2.5f * density);
        selectorOuterPaint.setColor(Color.WHITE);

        selectorInnerPaint.setStyle(Paint.Style.STROKE);
        selectorInnerPaint.setStrokeWidth(1.5f * density);
        selectorInnerPaint.setColor(0xAA000000);

        selectorFillPaint.setStyle(Paint.Style.FILL);
    }

    public void setOnColorChangedListener(OnColorChangedListener listener) {
        this.listener = listener;
    }

    public void setHsv(float hue, float saturation, float brightness) {
        this.hue = ((hue % 360f) + 360f) % 360f;
        this.saturation = Math.max(0f, Math.min(1f, saturation));
        this.brightness = Math.max(0f, Math.min(1f, brightness));
        invalidate();
    }

    public void setBrightness(float brightness) {
        this.brightness = Math.max(0f, Math.min(1f, brightness));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float density = getResources().getDisplayMetrics().density;
        float pad = 12f * density;
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.max(1f, Math.min(w, h) / 2f - pad);

        Shader sweep = new SweepGradient(cx, cy, HUE_COLORS, null);
        Shader radial = new RadialGradient(cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP);
        wheelPaint.setShader(new ComposeShader(sweep, radial, PorterDuff.Mode.SRC_OVER));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0f) return;

        canvas.drawCircle(cx, cy, radius, wheelPaint);

        double angleRad = Math.toRadians(hue);
        float sx = cx + (float) (Math.cos(angleRad) * saturation * radius);
        float sy = cy + (float) (Math.sin(angleRad) * saturation * radius);
        float density = getResources().getDisplayMetrics().density;
        float r = 10f * density;

        selectorFillPaint.setColor(Color.HSVToColor(new float[]{hue, saturation, brightness}));
        canvas.drawCircle(sx, sy, r, selectorFillPaint);
        canvas.drawCircle(sx, sy, r, selectorOuterPaint);
        canvas.drawCircle(sx, sy, r + 1.5f * density, selectorInnerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            updateFromTouch(event.getX(), event.getY());
            return true;
        } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            updateFromTouch(event.getX(), event.getY());
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateFromTouch(float x, float y) {
        if (radius <= 0f) return;
        float dx = x - cx;
        float dy = y - cy;
        float dist = (float) Math.hypot(dx, dy);
        hue = ((float) Math.toDegrees(Math.atan2(dy, dx)) + 360f) % 360f;
        saturation = Math.min(1f, dist / radius);
        invalidate();
        if (listener != null) {
            listener.onColorChanged(hue, saturation);
        }
    }
}
