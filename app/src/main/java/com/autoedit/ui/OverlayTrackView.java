package com.autoedit.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import com.autoedit.model.EditProject;
import com.autoedit.model.OverlayLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The overlay-lane of the 4-track timeline (v1.8). One bar per
 * {@link OverlayLayer}, placed by its start/end times on the shared
 * pxPerSec geometry. Tap a bar to select that layer in the Layers panel.
 */
public class OverlayTrackView extends View {

    public interface OnSelect {
        /** @param index position in project.overlays (-1 = background tap) */
        void onLayerSelected(int index);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);

    private EditProject project;
    private float pxPerSec = 13f;
    private float padPx = 8f;
    private float playheadSec = 0f;
    private int selected = -1;
    private OnSelect onSelect;
    private final List<RectF> hits = new ArrayList<>();

    public OverlayTrackView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f);
        head.setStrokeWidth(2f);
        head.setColor(AeDesign.ACCENT);
        txt.setTextSize(11f * c.getResources().getDisplayMetrics().density);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setGeometry(float pxPerSec, float padPx, float totalSec) {
        this.pxPerSec = pxPerSec;
        this.padPx = padPx;
        invalidate();
    }

    public void setProject(EditProject p) { this.project = p; invalidate(); }

    public void setPlayhead(float t) {
        if (t != playheadSec) { playheadSec = t; invalidate(); }
    }

    public void setSelected(int i) { this.selected = i; invalidate(); }

    public void setOnSelect(OnSelect l) { this.onSelect = l; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int h = getHeight();
        float baseY = h - dp(3);
        txt.setColor(0x2249A8FF);
        canvas.drawLine(0, baseY, getWidth(), baseY, txt);
        hits.clear();
        if (project == null) return;

        float total = project.totalDurationSec();
        for (int i = 0; i < project.overlays.size(); i++) {
            OverlayLayer o = project.overlays.get(i);
            float start = Math.max(0f, o.startSec);
            float end = Math.min(o.endResolvedSec(project), total);
            if (end <= start) end = start + 0.5f;
            float x0 = padPx + start * pxPerSec;
            float x1 = padPx + end * pxPerSec;
            float bw = Math.max(dp(28), x1 - x0);
            RectF r = new RectF(x0, dp(5), x0 + bw, h - dp(6));
            hits.add(r);

            boolean img = o.kind == OverlayLayer.Kind.IMAGE;
            int color = img ? 0xFF2E5E8C : 0xFF7C4A6E;
            fill.setColor(color);
            if (o.hidden) fill.setColor(0x44FFFFFF);
            Path p = new Path();
            float rad = Math.min(dp(8), r.height() / 2f);
            p.addRoundRect(r, rad, rad, Path.Direction.CW);
            canvas.drawPath(p, fill);
            if (i == selected) {
                stroke.setColor(AeDesign.ACCENT);
                canvas.drawPath(p, stroke);
            }
            // locked tick
            if (o.locked) {
                txt.setColor(0xCCFFD54A);
                canvas.drawCircle(x0 + dp(9), r.top + dp(9), dp(2.5f), txt);
            }
            // label
            txt.setColor(0xE6FFFFFF);
            String name = img ? (o.uri == null ? "image" : "image") : (o.text == null ? "text" : o.text);
            if (name.length() > 12) name = name.substring(0, 12) + "…";
            canvas.drawText(name, x0 + dp(6), r.top + dp(15), txt);
        }

        // playhead
        if (playheadSec >= 0f) {
            float px = padPx + playheadSec * pxPerSec;
            canvas.drawLine(px, dp(2), px, baseY, head);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP) {
            for (int i = 0; i < hits.size(); i++) {
                RectF r = hits.get(i);
                if (r.contains(ev.getX(), ev.getY())) {
                    if (onSelect != null) onSelect.onLayerSelected(i);
                    return true;
                }
            }
            if (onSelect != null && !inContent(ev.getX())) {
                onSelect.onLayerSelected(-1);
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    private boolean inContent(float x) {
        float w = (project == null ? 0f : project.totalDurationSec()) * pxPerSec + padPx * 2f;
        return x >= 0 && x <= w;
    }
}
