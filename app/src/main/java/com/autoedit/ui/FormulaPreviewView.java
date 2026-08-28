package com.autoedit.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import com.autoedit.R;
import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.Formula;
import com.autoedit.model.KeyframeState;

/**
 * Lightweight looping preview for formula/motion cards. PATTERN formulas cycle
 * virtual CLIPS: clip k runs pattern step (k % size) and inside that clip ONE
 * motion is interpolated start->end. Never more than one motion per clip.
 */
public class FormulaPreviewView extends View {
    private static final long FRAME_MS = 50;
    private static final float PER_CLIP_SEC = 1.1f;
    private final FormulaEngine engine = new FormulaEngine();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final RectF bounds = new RectF();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Formula formula;
    private long startMs;

    public FormulaPreviewView(Context c) { super(c); startMs = SystemClock.elapsedRealtime(); }

    public void setFormula(Formula f) { formula = f; startMs = SystemClock.elapsedRealtime(); invalidate(); }
    public void stopLooping() { removeCallbacks(invalidator); invalidate(); }
    private final Runnable invalidator = this::postInvalidateDelayedFrame;
    private void postInvalidateDelayedFrame() {
        if (formula != null && isShown()) postDelayed(() -> { if (isShown()) postInvalidate(); }, FRAME_MS);
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        removeCallbacks(invalidator);
        if (formula != null && visibility == VISIBLE) postInvalidateDelayedFrame();
    }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(invalidator); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w < 10 || h < 10) return;
        Bitmap bmp = sharedBitmapFor(w, h);
        if (bmp == null || formula == null) {
            paint.setColor(AeDesign.SURFACE_2);
            bounds.set(0, 0, w, h);
            canvas.drawRoundRect(bounds, 14, 14, paint);
            return;
        }
        int pattern = formula.isPattern() ? formula.patternSize() : 1;
        float loopSec = pattern * PER_CLIP_SEC;
        float tSec = ((SystemClock.elapsedRealtime() - startMs) % (long) (loopSec * 1000)) / 1000f;
        int virtualClip = Math.min(pattern - 1, (int) (tSec / PER_CLIP_SEC));
        float p = (tSec - virtualClip * PER_CLIP_SEC) / PER_CLIP_SEC;
        if (p > 1f) p = 1f;
        KeyframeState st = engine.stateForClip(formula, virtualClip, p);

        Path clip = new Path();
        clip.addRoundRect(0, 0, w, h, 14, 14, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setColor(AeDesign.SURFACE_2);
        paint.setAlpha(255);
        canvas.drawRoundRect(new RectF(0, 0, w, h), 14, 14, paint);

        float base = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
        float scale = base * st.scale;
        float dw = bmp.getWidth() * scale, dh = bmp.getHeight() * scale;
        float cx = w / 2f + st.x * w, cy = h / 2f + st.y * h;
        dst.set(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2);
        paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, st.opacity))));
        canvas.save();
        if (Math.abs(st.rotation) > 0.001f) canvas.rotate(st.rotation, w / 2f, h / 2f);
        canvas.drawBitmap(bmp, null, dst, paint);
        canvas.restore();
        canvas.restore();

        if (formula.isPattern() && pattern > 1) {
            float r = 2.5f, gap = 7f;
            float total = pattern * gap - (gap - 2 * r);
            float startX = w / 2f - total / 2f + r;
            for (int i = 0; i < pattern; i++) {
                dotPaint.setColor(i == virtualClip ? AeDesign.ACCENT : 0x55ffffff);
                canvas.drawCircle(startX + i * gap, h - 8, r, dotPaint);
            }
        }
        postInvalidateDelayedFrame();
    }

    /**
     * A formula card shows a CINEMATIC SEQUENCE scene picked from the formula
     * id, so two different formulas never show the same photograph (spec §13).
     */
    /**
     * Formula cards use the Eiffel Tower / Paris cinematic reference (spec §8,
     * §37). Subclasses override this to give their own category a distinct
     * image - MotionPreviewView swaps in the Burj Khalifa.
     */
    protected int sceneRes() { return com.autoedit.R.drawable.card_formula_eiffel; }

    private Bitmap sharedBitmapFor(int w, int h) {
        Bitmap b = PreviewArt.asset(getResources(), sceneRes(), Math.max(96, w), Math.max(120, h));
        // Never fall back to a blank card if the asset is somehow unavailable.
        return b != null ? b
                : PreviewArt.get(PreviewArt.Kind.LANDSCAPE, Math.max(96, w), Math.max(120, h));
    }
}
