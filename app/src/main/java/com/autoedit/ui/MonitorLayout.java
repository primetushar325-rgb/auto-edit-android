package com.autoedit.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Centers and sizes the preview to the project aspect ratio inside the
 * available monitor area — the image always fits fully on screen, no matter
 * the screen size or canvas ratio (9:16, 16:9, 1:1, 4:5, 4:3).
 *
 * <p>Also enforces a floor on its own measured height so a parent LinearLayout
 * with weight=1 can never collapse the monitor into a near-zero strip when
 * siblings (timeline / tools / panel) greedily consume wrap_content space.
 */
public class MonitorLayout extends FrameLayout {
    private float ratio = 9f / 16f;
    private final int pad;
    /** Soft minimum height in px; 0 = no floor (parent fully controls). */
    private int minHeightPx = 0;

    public MonitorLayout(Context c) {
        super(c);
        pad = AeDesign.dp(c, 6);
        // Default floor: ~42% of the shorter screen edge, clamped to a usable band.
        // Keeps the monitor dominant on phones without pushing the timeline off-screen.
        int shortEdge = Math.min(
                c.getResources().getDisplayMetrics().widthPixels,
                c.getResources().getDisplayMetrics().heightPixels);
        minHeightPx = Math.max(AeDesign.dp(c, 200), Math.min(AeDesign.dp(c, 420), (int) (shortEdge * 0.42f)));
    }

    public void setRatio(float wOverH) {
        if (wOverH > 0.01f) { ratio = wOverH; requestLayout(); }
    }

    /** Override the default floor (px). Pass 0 to disable. */
    public void setMinPreviewHeightPx(int px) {
        minHeightPx = Math.max(0, px);
        requestLayout();
    }

    public int getMinPreviewHeightPx() { return minHeightPx; }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int hMode = MeasureSpec.getMode(heightSpec);

        // When the parent hands us an AT_MOST / EXACTLY height smaller than our
        // floor (typical: LinearLayout weight leftover after greedy wrap_content
        // siblings), raise the measured height so the preview stays visibly large.
        if (minHeightPx > 0 && (hMode == MeasureSpec.UNSPECIFIED || h < minHeightPx)) {
            h = minHeightPx;
            heightSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        }

        int maxW = Math.max(1, w - pad * 2);
        int maxH = Math.max(1, h - pad * 2);
        // Fit the canvas aspect inside the available box (letterbox/pillarbox).
        int cw = Math.min(maxW, (int) (maxH * ratio));
        int ch = Math.max(1, (int) (cw / ratio));
        if (cw > maxW) { cw = maxW; ch = Math.max(1, (int) (cw / ratio)); }
        if (ch > maxH) { ch = maxH; cw = Math.max(1, (int) (ch * ratio)); }
        View child = getChildCount() > 0 ? getChildAt(0) : null;
        if (child != null) {
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        View child = getChildCount() > 0 ? getChildAt(0) : null;
        if (child == null) return;
        int cw = child.getMeasuredWidth();
        int ch = child.getMeasuredHeight();
        int cl = (r - l - cw) / 2;
        int ct = (b - t - ch) / 2;
        child.layout(cl, ct, cl + cw, ct + ch);
    }
}
