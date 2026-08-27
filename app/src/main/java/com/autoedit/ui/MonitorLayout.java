package com.autoedit.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Centers and sizes the preview to the project aspect ratio inside the
 * available monitor area — the image always fits fully on screen, no matter
 * the screen size or canvas ratio (9:16, 16:9, 1:1, 4:5, 4:3).
 */
public class MonitorLayout extends FrameLayout {
    private float ratio = 9f / 16f;
    private final int pad;

    public MonitorLayout(Context c) {
        super(c);
        pad = AeDesign.dp(c, 6);
    }

    public void setRatio(float wOverH) {
        if (wOverH > 0.01f) { ratio = wOverH; requestLayout(); }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int maxW = Math.max(1, w - pad * 2);
        int maxH = Math.max(1, h - pad * 2);
        int cw = Math.min(maxW, (int) (maxH * ratio));
        int ch = (int) (cw / ratio);
        if (cw > maxW) { cw = maxW; ch = (int) (cw / ratio); }
        if (ch > maxH) { ch = maxH; cw = (int) (ch * ratio); }
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
