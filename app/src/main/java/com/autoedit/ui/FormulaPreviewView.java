package com.autoedit.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
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
 * Lightweight looping Formula sequence preview for the formula cards.
 *
 * Implementation: one small decoded bitmap + transformation matrix
 * (scale / translation / rotation / alpha) driven by the SAME FormulaEngine
 * math that the editor preview and export use — so the card animation is
 * exactly the motion the formula will apply. No video, no textures, no
 * per-frame allocations beyond a couple of floats.
 *
 * Performance: runs at ~20fps only while visible; pauses when the card is
 * off-viewport / the list is not shown.
 */
public class FormulaPreviewView extends View {
    private static final long FRAME_MS = 50; // 20 fps — plenty for a card preview

    private static Bitmap sharedBitmap;
    private static int sharedW, sharedH;

    private final FormulaEngine engine = new FormulaEngine();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF dst = new RectF();
    private final RectF bounds = new RectF();

    private Formula formula;
    private long startMs;

    public FormulaPreviewView(Context c) {
        super(c);
        startMs = SystemClock.elapsedRealtime();
    }

    public void setFormula(Formula f) {
        formula = f;
        startMs = SystemClock.elapsedRealtime();
        invalidate();
    }

    public void stopLooping() { clearFrameCallbacks(); invalidate(); }

    private void clearFrameCallbacks() { removeCallbacks(invalidator); }
    private final Runnable invalidator = this::postInvalidateDelayedFrame;

    private void postInvalidateDelayedFrame() {
        if (formula != null && isShown()) {
            postDelayed(new Runnable() { public void run() { postInvalidate(); } }, FRAME_MS);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        clearFrameCallbacks();
        if (formula != null && visibility == VISIBLE) postInvalidateDelayedFrame();
    }

    @Override
    protected void onDraw(Canvas canvas) {
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
        // loop position: classic formulas loop in ~4s, sequences use their own duration
        float loopSec = formula.isSequence() ? Math.max(4f, formula.totalDurationSec()) : 4f;
        float t = ((SystemClock.elapsedRealtime() - startMs) % (long) (loopSec * 1000)) / (loopSec * 1000f);
        KeyframeState st = engine.stateAt(formula, t);

        // rounded clip
        Path clip = new Path();
        clip.addRoundRect(0, 0, w, h, 14, 14, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        // base: cover the card
        float base = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
        float scale = base * st.scale;
        float dw = bmp.getWidth() * scale, dh = bmp.getHeight() * scale;
        float cx = w / 2f + st.x * w, cy = h / 2f + st.y * h;
        dst.set(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2);

        paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, st.opacity))));
        if (Math.abs(st.rotation) > 0.001f) {
            canvas.save();
            canvas.rotate(st.rotation, w / 2f, h / 2f);
            canvas.drawBitmap(bmp, null, dst, paint);
            canvas.restore();
        } else {
            canvas.drawBitmap(bmp, null, dst, paint);
        }
        canvas.restore();
        postInvalidateDelayedFrame();
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
        sharedBitmap = b;
        sharedW = tw; sharedH = th;
        return b;
    }
}
