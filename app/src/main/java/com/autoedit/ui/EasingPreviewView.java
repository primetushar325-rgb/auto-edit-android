package com.autoedit.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

import com.autoedit.model.Easing;

/**
 * Live preview card for an EASING curve (spec §12: "a tiny graph/animated
 * visual showing the curve").
 *
 * The static plot shows the curve over 0..1, and a marker dot travels the
 * curve while a shadow dot travels LINEARLY beneath it, so the difference
 * between the curve and uniform motion is obvious at a glance. Overshooting
 * curves (Back In / Out / In Out) visibly leave the 0..1 band.
 */
public class EasingPreviewView extends View {

    private static final long LOOP_MS = 1600;

    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linearDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();

    private Easing easing = Easing.DEFAULT;
    private long startMs;

    public EasingPreviewView(Context c) {
        super(c);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        linearPaint.setStyle(Paint.Style.STROKE);
        linearPaint.setColor(0x44ffffff);
        gridPaint.setColor(0x22ffffff);
        dotPaint.setColor(AeDesign.ACCENT);
        linearDotPaint.setColor(0x66ffffff);
        startMs = android.os.SystemClock.elapsedRealtime();
    }

    public void setEasing(Easing e) {
        this.easing = e == null ? Easing.DEFAULT : e;
        startMs = android.os.SystemClock.elapsedRealtime();
        invalidate();
    }

    private final Runnable invalidator = this::tick;
    private void tick() { if (isShown()) postDelayed(() -> { if (isShown()) postInvalidate(); }, 40); }
    @Override protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        removeCallbacks(invalidator);
        if (vis == VISIBLE) tick();
    }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(invalidator); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w < 10 || h < 10) return;

        canvas.drawColor(AeDesign.SURFACE_2);
        curvePaint.setColor(easing.overshoots() ? 0xffffa86b : AeDesign.ACCENT);
        curvePaint.setStrokeWidth(Math.max(2.5f, w * 0.045f));
        gridPaint.setStrokeWidth(Math.max(1f, w * 0.012f));
        linearPaint.setStrokeWidth(Math.max(1.5f, w * 0.022f));

        // Leave headroom so overshooting curves stay inside the card.
        float padX = w * 0.14f, padY = h * 0.22f;
        float iw = w - padX * 2f, ih = h - padY * 2f;

        // grid + linear reference
        canvas.drawLine(padX, padY, padX, padY + ih, gridPaint);
        canvas.drawLine(padX, padY + ih, padX + iw, padY + ih, gridPaint);
        canvas.drawLine(padX, padY + ih, padX + iw, padY, linearPaint);

        // the curve, sampled densely enough to look smooth
        curve.reset();
        int n = 64;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float x = padX + t * iw;
            float y = padY + ih - easing.apply(t) * ih;
            if (i == 0) curve.moveTo(x, y); else curve.lineTo(x, y);
        }
        canvas.drawPath(curve, curvePaint);

        // travelling markers
        float loop = ((android.os.SystemClock.elapsedRealtime() - startMs) % LOOP_MS) / (float) LOOP_MS;
        float t = loop < 0.08f ? 0f : loop > 0.88f ? 1f : (loop - 0.08f) / 0.80f;
        float r = Math.max(3f, w * 0.055f);
        canvas.drawCircle(padX + t * iw, padY + ih - t * ih, r * 0.7f, linearDotPaint);
        canvas.drawCircle(padX + t * iw, padY + ih - easing.apply(t) * ih, r, dotPaint);

        tick();
    }
}
