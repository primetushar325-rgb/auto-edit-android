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
import com.autoedit.engine.TransitionDraw;
import com.autoedit.engine.TransitionEngine;
import com.autoedit.engine.TransitionRegistry;
import com.autoedit.model.TransitionPreset;
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
    private TransitionPreset preset;
    private long startMs;

    public TransitionPreviewView(Context c) {
        super(c);
        startMs = SystemClock.elapsedRealtime();
    }

    public void setTransition(TransitionType t) {
        this.type = t == null ? TransitionType.NONE : t;
        this.preset = null;
        startMs = SystemClock.elapsedRealtime();
        invalidate();
    }

    /** Library preset — the card demos the full preset (direction/tint/intensity). */
    public void setTransition(TransitionPreset p) {
        this.preset = p;
        this.type = p == null ? TransitionType.NONE : p.type;
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
        TransitionEngine.Transform out = preset != null ? engine.outgoing(preset, p) : engine.outgoing(type, p);
        TransitionEngine.Transform in = preset != null ? engine.incoming(preset, p) : engine.incoming(type, p);

        // outgoing (old) layer with full transform (3D/squeeze/shake via TransitionDraw)
        drawLayer(canvas, bmp, dw, dh, w, h, out, 0x332b5ea0);

        // incoming (new) layer, clipped for reveal/shape transitions
        int saved = TransitionDraw.clipReveal(canvas, w, h, in);
        drawLayer(canvas, bmpIn == null ? bmp : bmpIn, dw, dh, w, h, in, 0x33b07a2a);
        if (saved >= 0) canvas.restoreToCount(saved);

        // overlays (flash/light/dip) + grain
        TransitionDraw.drawOverlay(canvas, w, h, in);
        TransitionDraw.drawOverlay(canvas, w, h, out);
        if (in.grain > 0.02f || out.grain > 0.02f)
            TransitionDraw.drawGrain(canvas, w, h, Math.max(in.grain, out.grain), in.seed + p);
        if (engine.flashes(type) && in.overlayAlpha <= 0.001f && out.overlayAlpha <= 0.001f) {
            int fa = (int) (255 * (1f - Math.abs(p - 0.5f) * 2f));
            paint.setAlpha(Math.max(0, fa));
            paint.setColor(engine.flashColor(type));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
        }
        canvas.restore();
        tick();
    }

    private void drawLayer(Canvas canvas, Bitmap bmp, float dw, float dh, int w, int h,
                           TransitionEngine.Transform t, int tint) {
        int layer = TransitionDraw.apply(canvas, w / 2f, h / 2f, t);
        TransitionDraw.applySqueeze(canvas, w / 2f, h / 2f, t);
        float dw2 = dw * t.scale, dh2 = dh * t.scale;
        float ox = (t.dx + t.shakeX), oy = (t.dy + t.shakeY);
        float cx = w / 2f + ox * w, cy = h / 2f + oy * h;
        RectF r = new RectF(cx - dw2 / 2, cy - dh2 / 2, cx + dw2 / 2, cy + dh2 / 2);
        canvas.save(); canvas.rotate(t.rotZ, cx, cy);
        paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, t.alpha))));
        canvas.drawBitmap(bmp, null, r, paint);
        if (t.chroma > 0.02f) TransitionDraw.drawChromaSplit(canvas, r, bmp, t.chroma, t.rotZ, cx, cy);
        canvas.restore();
        paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, t.alpha)) * 0.35f));
        paint.setColor(tint);
        canvas.drawRect(r, paint);
        paint.setAlpha(255);
        canvas.restoreToCount(layer);
    }

    /** Transition cards use distinguishable reference scenes so the two clips read. */
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
