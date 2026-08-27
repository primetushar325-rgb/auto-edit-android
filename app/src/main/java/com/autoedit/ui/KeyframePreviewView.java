package com.autoedit.ui;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;

import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.Formula;
import com.autoedit.model.KeyframeState;

import java.io.InputStream;

/**
 * Lightweight looping live preview for the Custom Formula editor.
 *
 * Renders the user's selected preview image through the SAME FormulaEngine
 * math as the editor preview and export (KeyframeState lerp + easing across
 * the formula's sequence steps) using only Matrix + alpha — no video, no
 * per-frame bitmaps, no temp files. ~30fps while visible and playing.
 */
public class KeyframePreviewView extends View {
    private static final long FRAME_MS = 33;

    private final FormulaEngine engine = new FormulaEngine();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final Rect dst = new Rect();
    private final Rect src = new Rect();

    private Formula formula;
    private Bitmap bitmap;
    private Uri pendingUri;
    private boolean playing = true;
    private long startMs = SystemClock.elapsedRealtime();
    private float pausedSec = 0f;

    public KeyframePreviewView(Context c) {
        super(c);
        setBackgroundColor(AeDesign.SURFACE);
    }

    public void setFormula(Formula f) {
        formula = f;
        startMs = SystemClock.elapsedRealtime();
        invalidate();
    }

    public void setImageUri(Uri uri) {
        pendingUri = uri;
        bitmap = null;
        invalidate();
        if (uri == null) return;
        new Thread(() -> {
            try {
                Bitmap b = decodeSampled(getContext(), uri, 480);
                if (b != null) {
                    post(() -> { if (pendingUri != null && pendingUri.equals(uri)) { bitmap = b; } else if (b != null && !b.isRecycled()) b.recycle(); invalidate(); });
                }
            } catch (Exception ignored) {}
        }, "CustomFormulaPreviewDecode").start();
    }

    public void setPlaying(boolean p) {
        if (playing == p) return;
        if (!p) pausedSec = currentT();
        playing = p;
        if (p) { startMs = SystemClock.elapsedRealtime() - (long) (pausedSec * 1000f); postInvalidateDelayedFrame(); }
        else removeCallbacks(invalidator);
    }

    public boolean isPlaying() { return playing; }

    public void stop() { setPlaying(false); removeCallbacks(invalidator); }

    private final Runnable invalidator = this::postInvalidateDelayedFrame;

    private void postInvalidateDelayedFrame() {
        if (playing && formula != null && isShown()) postDelayed(() -> postInvalidate(), FRAME_MS);
    }

    /** Current loop position in seconds (0..totalDuration). */
    private float currentT() {
        if (formula == null) return 0f;
        float total = Math.max(0.1f, formula.totalDurationSec());
        return ((SystemClock.elapsedRealtime() - startMs) % (long) (total * 1000f)) / 1000f;
    }

    private static Bitmap decodeSampled(Context c, Uri uri, int maxDim) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w < 10 || h < 10) return;
        if (bitmap == null || formula == null) {
            Paint ph = new Paint(Paint.ANTI_ALIAS_FLAG);
            ph.setColor(AeDesign.SURFACE_2);
            canvas.drawRoundRect(0, 0, w, h, 14, 14, ph);
            ph.setColor(AeDesign.MUTED);
            ph.setTextAlign(Paint.Align.CENTER);
            ph.setTextSize(AeDesign.dp(getContext(), 13));
            canvas.drawText(bitmap == null ? "Select a preview image" : "—", w / 2f, h / 2f, ph);
            return;
        }
        float total = Math.max(0.1f, formula.totalDurationSec());
        float t = playing ? currentT() : pausedSec;

        KeyframeState st = engine.stateAt(formula, Math.min(1f, t / total));

        Path clip = new Path();
        clip.addRoundRect(0, 0, w, h, 14, 14, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        float base = Math.max(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
        float scale = base * st.scale;
        float dw = bitmap.getWidth() * scale, dh = bitmap.getHeight() * scale;
        float cx = w / 2f + st.x * w, cy = h / 2f + st.y * h;
        dst.set((int) (cx - dw / 2), (int) (cy - dh / 2), (int) (cx + dw / 2), (int) (cy + dh / 2));
        src.set(0, 0, bitmap.getWidth(), bitmap.getHeight());

        paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, st.opacity))));
        matrix.reset();
        matrix.postRotate(st.rotation, w / 2f, h / 2f);
        canvas.save();
        canvas.concat(matrix);
        canvas.drawBitmap(bitmap, src, dst, paint);
        canvas.restore();
        canvas.restore();
        postInvalidateDelayedFrame();
    }
}
