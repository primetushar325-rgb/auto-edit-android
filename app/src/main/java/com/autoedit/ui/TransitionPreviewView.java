package com.autoedit.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import com.autoedit.R;
import com.autoedit.engine.TransitionEngine;
import com.autoedit.model.TransitionType;

/**
 * Live preview card for a TRANSITION. Two copies of the reference photo are
 * drawn as outgoing (old) and incoming (new) layers, positioned with the SAME
 * {@link TransitionEngine} transforms preview/export use — including reveal
 * clipping and flash — so the card is a real demo of the junction, never a
 * fake. Loops at ~20fps.
 */
public class TransitionPreviewView extends View {
    private static final long FRAME_MS = 50;
    private static final float LOOP_SEC = 2.2f;

    private final TransitionEngine engine = new TransitionEngine();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private TransitionType type = TransitionType.FADE;
    private long startMs;

    public TransitionPreviewView(Context c) {
        super(c);
        startMs = SystemClock.elapsedRealtime();
    }

    public void setTransition(TransitionType t) {
        this.type = t == null ? TransitionType.NONE : t;
        startMs = SystemClock.elapsedRealtime();
        invalidate();
    }

    private final Runnable invalidator = this::tick;
    private void tick() { if (isShown()) postDelayed(() -> { if (isShown()) postInvalidate(); }, FRAME_MS); }
    @Override protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis); removeCallbacks(invalidator); if (vis == VISIBLE) tick();
    }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(invalidator); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w < 10 || h < 10) return;
        Bitmap bmp = sharedBitmapFor(w, h);
        Bitmap bmpIn = incomingBitmapFor(w, h);
        Path card = new Path();
        card.addRoundRect(0, 0, w, h, 14, 14, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(card);
        paint.setColor(AeDesign.SURFACE_2);
        paint.setAlpha(255);
        canvas.drawRoundRect(new RectF(0, 0, w, h), 14, 14, paint);
        if (bmp == null) { canvas.restore(); tick(); return; }

        float base = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
        float dw = bmp.getWidth() * base, dh = bmp.getHeight() * base;

        // mix 0..1 across the middle of the loop; hold start then end
        float loop = ((SystemClock.elapsedRealtime() - startMs) % (long) (LOOP_SEC * 1000)) / (LOOP_SEC * 1000f);
        float span = 0.7f;
        float p = loop < 0.12f ? 0f : loop > 0.12f + span ? 1f : (loop - 0.12f) / span;

        // Two genuinely different scenes so the two halves read as separate
        // clips and the direction of travel is obvious (spec §13).
        TransitionEngine.Transform out = engine.outgoing(type, p);
        TransitionEngine.Transform in = engine.incoming(type, p);

        // outgoing (old) layer
        drawLayer(canvas, bmp, dw, dh, w, h, out.dx, out.dy, out.scale, out.alpha, 0x332b5ea0, false, null);

        // incoming (new) layer, clipped for reveal transitions
        boolean masked = in.revealRadius > 0f && in.revealRadius < 1f;
        int saved = -1;
        if (masked) {
            saved = canvas.save();
            Path reveal = new Path();
            if (in.circleReveal) {
                float maxR = (float) Math.hypot(w, h) / 2f;
                reveal.addCircle(w / 2f + in.dx * w, h / 2f + in.dy * h,
                        Math.max(1f, maxR * in.revealRadius), Path.Direction.CW);
            } else {
                float cover = Math.max(w, h) * 1.2f, ext = cover * in.revealRadius, cx = w / 2f, cy = h / 2f;
                RectF r = in.wipeAxis == 1
                        ? new RectF(cx - (in.wipeSign < 0 ? ext : 0), -cover, cx + (in.wipeSign > 0 ? ext : 0), h + cover)
                        : new RectF(-cover, cy - (in.wipeSign < 0 ? ext : 0), w + cover, cy + (in.wipeSign > 0 ? ext : 0));
                reveal.addRect(r, Path.Direction.CW);
            }
            canvas.clipPath(reveal);
        }
        drawLayer(canvas, bmpIn == null ? bmp : bmpIn, dw, dh, w, h,
                in.dx, in.dy, in.scale, in.alpha, 0x33b07a2a, true, null);
        if (masked && saved >= 0) canvas.restoreToCount(saved);

        // flash overlay
        if (engine.flashes(type)) {
            int fa = (int) (255 * (1f - Math.abs(p - 0.5f) * 2f));
            paint.setAlpha(Math.max(0, fa));
            paint.setColor(Color.WHITE);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
        }
        canvas.restore();
        tick();
    }

    private void drawLayer(Canvas canvas, Bitmap bmp, float dw, float dh, int w, int h,
                           float dx, float dy, float scale, float alpha, int tint, boolean ignore, Paint extra) {
        float dw2 = dw * scale, dh2 = dh * scale;
        float cx = w / 2f + dx * w, cy = h / 2f + dy * h;
        RectF r = new RectF(cx - dw2 / 2, cy - dh2 / 2, cx + dw2 / 2, cy + dh2 / 2);
        paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, alpha))));
        canvas.drawBitmap(bmp, null, r, paint);
        // tint wash to differentiate the two clips
        paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, alpha)) * 0.35f));
        paint.setColor(tint);
        canvas.drawRect(r, paint);
        paint.setAlpha(255);
    }

    /**
     * A transition needs TWO distinguishable frames to read at all, so the
     * outgoing panel uses one scene and the incoming panel another (spec §13:
     * "two different visual frames, clear directional preview").
     */
    /** Transition cards use the Taj Mahal / India cinematic reference (spec §8). */
    private Bitmap sharedBitmapFor(int w, int h) {
        Bitmap b = PreviewArt.asset(getResources(),
                com.autoedit.R.drawable.card_transition_tajmahal, Math.max(96, w), Math.max(120, h));
        return b != null ? b : PreviewArt.get(PreviewArt.Kind.CITY, Math.max(96, w), Math.max(120, h));
    }

    /**
     * The incoming half deliberately uses a DIFFERENT landmark so the two panels
     * read as separate clips and the direction of travel is obvious.
     */
    private Bitmap incomingBitmapFor(int w, int h) {
        Bitmap b = PreviewArt.asset(getResources(),
                com.autoedit.R.drawable.card_formula_eiffel, Math.max(96, w), Math.max(120, h));
        return b != null ? b : PreviewArt.get(PreviewArt.Kind.LANDSCAPE, Math.max(96, w), Math.max(120, h));
    }
}
