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
import com.autoedit.engine.EffectEngine;
import com.autoedit.model.EffectType;

/**
 * Live preview card for an EFFECT. Draws the shared reference photo with the
 * SAME {@link EffectEngine} paint/post pass the preview and export use, so the
 * card shows the real effect (color matrix, vignette, glow, grain). Loops
 * lightly (~20fps) only while visible.
 */
public class EffectPreviewView extends View {
    private static final long FRAME_MS = 50;
    private static Bitmap sharedBitmap;
    private static int sharedW, sharedH;

    private final EffectEngine effects = new EffectEngine();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final RectF bounds = new RectF();
    private EffectType effect = EffectType.NONE;
    private float intensity = 0.7f;
    private long startMs;

    public EffectPreviewView(Context c) {
        super(c);
        startMs = SystemClock.elapsedRealtime();
    }

    public void setEffect(EffectType t, float intensity) {
        this.effect = t == null ? EffectType.NONE : t;
        this.intensity = intensity;
        startMs = SystemClock.elapsedRealtime();
        invalidate();
    }

    private final Runnable invalidator = this::tick;
    private void tick() { if (isShown()) postDelayed(() -> { if (isShown()) postInvalidate(); }, FRAME_MS); }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        removeCallbacks(invalidator);
        if (visibility == VISIBLE) tick();
    }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(invalidator); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w < 10 || h < 10) return;
        Bitmap bmp = sharedBitmapFor(w, h);
        Path clip = new Path();
        clip.addRoundRect(0, 0, w, h, 14, 14, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setColor(AeDesign.SURFACE_2);
        paint.setAlpha(255);
        canvas.drawRoundRect(new RectF(0, 0, w, h), 14, 14, paint);
        if (bmp != null) {
            float base = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
            float dw = bmp.getWidth() * base, dh = bmp.getHeight() * base;
            dst.set(w / 2f - dw / 2, h / 2f - dh / 2, w / 2f + dw / 2, h / 2f + dh / 2);
            Paint p = effects.paintFor(effect, intensity);
            canvas.drawBitmap(bmp, null, dst, p);
            // post effects use absolute canvas size; card is small, fine.
            effects.drawPost(canvas, w, h, effect, intensity);
        }
        canvas.restore();
        tick();
    }

    private Bitmap sharedBitmapFor(int w, int h) {
        int tw = Math.max(160, w * 2), th = Math.max(240, h * 2);
        if (sharedBitmap != null && !sharedBitmap.isRecycled() && sharedW == tw && sharedH == th) return sharedBitmap;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeResource(getResources(), R.drawable.formula_eiffel, o);
        if (b == null) return null;
        if (b.getWidth() < tw * 2 || b.getHeight() < th * 2) {
            Bitmap s = Bitmap.createScaledBitmap(b, tw, th, true);
            if (s != b) b.recycle();
            b = s;
        }
        if (sharedBitmap != null && !sharedBitmap.isRecycled()) sharedBitmap.recycle();
        sharedBitmap = b; sharedW = tw; sharedH = th;
        return b;
    }
}
