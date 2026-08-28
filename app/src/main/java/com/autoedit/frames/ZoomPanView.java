package com.autoedit.frames;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.autoedit.ui.AeDesign;

/**
 * Interactive video-frame preview for the frame extractor (spec §9, §10).
 *
 * <h3>What it does</h3>
 * Two-finger pinch zooms, one-finger drag pans, and the framing the user ends
 * up with is what gets extracted. That last part is the point: the view derives
 * its visible region from {@link FrameUtils#zoomPanRect} - the very same
 * function the extraction service calls - so the preview and the saved frame
 * cannot drift apart. There is no second, preview-only transform.
 *
 * <h3>Why a crop window rather than a scaled canvas</h3>
 * Zooming is modelled as choosing a sub-rectangle of the source and filling the
 * view with it. Panning is therefore clamped for free: the window can never
 * leave the source, so the user can push part of a 16:9 video outside the frame
 * without ever exposing empty space.
 */
public class ZoomPanView extends View {

    /** Live callback so the host can show the current zoom and enable Extract. */
    public interface Listener {
        void onFramingChanged(float zoom, float panX, float panY);
    }

    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 8f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private Bitmap bmp;
    private float zoom = 1f;
    private float panX = 0f, panY = 0f;
    private Listener listener;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector dragDetector;

    public ZoomPanView(Context c) {
        super(c);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3f);
        framePaint.setColor(AeDesign.ACCENT);

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                setZoom(zoom * d.getScaleFactor());
                return true;
            }
        });
        dragDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoom <= 1.0001f) return false;
                // Dragging right must move the content right, i.e. reduce panX.
                float travelX = Math.max(1f, bmp == null ? 1f : bmp.getWidth() / zoom);
                float travelY = Math.max(1f, bmp == null ? 1f : bmp.getHeight() / zoom);
                setPan(panX - (dx / travelX) * 2f, panY - (dy / travelY) * 2f);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                // Quick toggle between whole frame and a useful 2x inspection.
                setFraming(zoom > 1.5f ? 1f : 2f, panX, panY);
                return true;
            }
        });
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Alias kept for readability at the call site. */
    public void setImageBitmapSafe(Bitmap b) { setImage(b); }

    public void setImage(Bitmap b) {
        this.bmp = b;
        this.zoom = 1f; this.panX = 0f; this.panY = 0f;
        invalidate();
        notifyChanged();
    }

    public float getZoom() { return zoom; }
    public float getPanX() { return panX; }
    public float getPanY() { return panY; }

    public void setZoom(float z) { setFraming(z, panX, panY); }

    public void setPan(float x, float y) { setFraming(zoom, x, y); }

    /** Sets zoom + pan together, clamped, and repaints. */
    public void setFraming(float z, float x, float y) {
        float nz = z < MIN_ZOOM ? MIN_ZOOM : (z > MAX_ZOOM ? MAX_ZOOM : z);
        float nx = x < -1f ? -1f : (x > 1f ? 1f : x);
        float ny = y < -1f ? -1f : (y > 1f ? 1f : y);
        if (nz == zoom && nx == panX && ny == panY) return;
        zoom = nz; panX = nx; panY = ny;
        invalidate();
        notifyChanged();
    }

    public void reset() { setFraming(1f, 0f, 0f); }

    private void notifyChanged() {
        if (listener != null) listener.onFramingChanged(zoom, panX, panY);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        boolean handled = scaleDetector.onTouchEvent(e);
        handled |= dragDetector.onTouchEvent(e);
        return handled || super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(AeDesign.SURFACE_2);
        if (bmp == null || bmp.isRecycled()) {
            framePaint.setStyle(Paint.Style.FILL);
            framePaint.setColor(AeDesign.MUTED);
            canvas.drawText("Select a video", w / 2f - 60, h / 2f, framePaint);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setColor(AeDesign.ACCENT);
            return;
        }

        // Exactly the window the extractor will crop - one source of truth.
        int[] r = FrameUtils.zoomPanRect(bmp.getWidth(), bmp.getHeight(), zoom, panX, panY);
        srcRect.set(r[0], r[1], r[0] + r[2], r[1] + r[3]);

        // Letterbox the window inside the view, preserving its aspect.
        float scale = Math.min(w / (float) r[2], h / (float) r[3]);
        float dw = r[2] * scale, dh = r[3] * scale;
        dstRect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);

        matrix.reset();
        canvas.drawBitmap(bmp, srcRect, dstRect, paint);
        canvas.drawRect(dstRect, framePaint);
    }
}
