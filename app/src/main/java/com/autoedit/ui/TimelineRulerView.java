package com.autoedit.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import com.autoedit.model.EditProject;
import com.autoedit.model.TimelineClip;

/**
 * Real timeline ruler: second ticks, clip boundaries, and a moving playhead
 * that uses the same px-per-second geometry as the timeline chips, so the
 * playhead always points at the exact frame being previewed.
 *
 * Chip geometry contract (shared with MainActivity):
 *   chip width  = durationSec * VEL dp
 *   chip gap    = GAP dp (marginStart)
 */
public class TimelineRulerView extends View {
    public static final float VEL_DP = 13f; // dp per second
    public static final float GAP_DP = 3f;  // dp gap before each chip

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private EditProject project;
    private float timeSec = 0f;

    public TimelineRulerView(Context c) {
        super(c);
        playP.setColor(AeDesign.ACCENT);
        playP.setStrokeWidth(3f);
    }

    public void setProject(EditProject p) { this.project = p; invalidate(); }
    public void setTime(float t) { timeSec = Math.max(0f, t); invalidate(); }

    public static float contentWidthPx(Context c, EditProject p) {
        float w = 0;
        if (p != null) for (TimelineClip clip : p.clips) w += clip.durationSec * VEL_DP + GAP_DP;
        return AeDesign.dp(c, (int) (w + 10));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        float pxPerSec = VEL_DP * d;
        float gap = GAP_DP * d;
        int w = getWidth();
        float baseY = h - 8 * d;
        if (project == null) return;

        float total = project.totalDurationSec();
        int step = total <= 60 ? 1 : total <= 300 ? 5 : 10;

        p.setColor(0x2249A8FF); p.setStrokeWidth(1f);
        p.setTextSize(9.5f * d); p.setFakeBoldText(false);

        float x = 0; float acc = 0; float playX = -1;
        for (int i = 0; i < project.clips.size(); i++) {
            TimelineClip c = project.clips.get(i);
            float cw = c.durationSec * pxPerSec;
            // clip boundary
            p.setColor(0x3349A8FF);
            canvas.drawLine(x + gap, 4 * d, x + gap, h, p);
            // ticks at each whole second inside this clip (local seconds 0..dur)
            int seconds = Math.round(c.durationSec);
            for (int s = 0; s <= seconds; s++) {
                float frac = Math.min(1f, s / Math.max(.001f, c.durationSec));
                float tx = x + gap + frac * cw;
                boolean major = (Math.round(acc + frac * c.durationSec) % step == 0);
                p.setColor(major ? 0x6649A8FF : 0x2249A8FF);
                float tickH = major ? 12 * d : 7 * d;
                canvas.drawLine(tx, baseY - tickH, tx, baseY, p);
                if (major) {
                    int secLabel = Math.round(acc + frac * c.durationSec);
                    p.setColor(0x889EB8CC);
                    canvas.drawText(secLabel + "s", tx + 2 * d, 9 * d, p);
                }
            }
            if (playX < 0 && timeSec >= acc && timeSec < acc + c.durationSec) {
                playX = x + gap + (timeSec - acc) / Math.max(.001f, c.durationSec) * cw;
            }
            x += cw + gap; acc += c.durationSec;
        }
        // total end tick
        p.setColor(0x6649A8FF);
        canvas.drawLine(x, 4 * d, x, h, p);
        if (playX < 0) playX = x;

        // playhead
        canvas.drawLine(playX, 0, playX, h, playP);
        canvas.drawCircle(playX, 3 * d, 3.5f * d, playP);
        // current time text on the right
        p.setColor(AeDesign.ACCENT);
        p.setTextSize(10f * d); p.setFakeBoldText(true);
        String t = String.format(java.util.Locale.US, "%.1fs", timeSec);
        float tw = p.measureText(t);
        float tx2 = Math.min(w - tw - 6 * d, Math.max(0, playX + 6 * d));
        canvas.drawText(t, tx2, 12 * d, p);
        p.setFakeBoldText(false);
    }
}
