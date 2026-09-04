package com.autoedit.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import com.autoedit.model.AudioTrack;

/**
 * The waveform lane of the 4-track timeline (v1.8). Draws the primary
 * audio track's decoded peaks, aligned to its position on the project
 * timeline via the SAME pxPerSec geometry as the ruler and clip lanes, so
 * a playhead position always points at the right audio sample.
 *
 * Peaks arrive asynchronously from {@link com.autoedit.project.WaveformCache}
 * (off-main-thread decode); until then the lane shows a placeholder bar.
 */
public class WaveformTrackView extends View {

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    private AudioTrack track;
    private float[] peaks;
    private float totalSec = 0f;     // project total (seconds)
    private float pxPerSec = 13f;    // shared geometry
    private float padPx = 8f;
    private float playheadSec = 0f;
    private boolean dirty = false;

    public WaveformTrackView(Context c) {
        super(c);
        bar.setColor(0x6649A8FF);
        trim.setColor(0x33FFFFFF);
        head.setColor(AeDesign.ACCENT);
        head.setStrokeWidth(2f);
        label.setColor(0x889EB8CC);
    }

    /** dp helper (float-safe). */
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setGeometry(float pxPerSec, float padPx, float totalSec) {
        this.pxPerSec = pxPerSec;
        this.padPx = padPx;
        this.totalSec = totalSec;
        invalidate();
    }

    public void setTrack(AudioTrack t) { this.track = t; invalidate(); }

    public void setPeaks(float[] p) {
        this.peaks = p;
        dirty = false;
        invalidate();
    }

    public void setPlayhead(float t) {
        if (t != playheadSec) { playheadSec = t; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int h = getHeight();
        float mid = h / 2f;
        float baseY = h - dp(4);
        // baseline
        label.setStrokeWidth(1f);
        label.setColor(0x2249A8FF);
        canvas.drawLine(0, baseY, getWidth(), baseY, label);

        if (track == null || track.uri == null) {
            label.setColor(0x559EB8CC);
            canvas.drawText("no audio", padPx, mid + dp(3), label);
            return;
        }

        float x0 = padPx + track.startSec * pxPerSec;
        float usedSec = track.effectiveDurationSec();
        float shownSec = track.loop ? totalSec - track.startSec : usedSec;
        if (shownSec <= 0f) return;
        float x1 = padPx + (track.startSec + shownSec) * pxPerSec;

        if (peaks != null && peaks.length > 1) {
            int n = peaks.length;
            float span = x1 - x0;
            int bars = Math.max(8, (int) (span / dp(2)));
            for (int i = 0; i < bars; i++) {
                int p0 = (int) (i * (float) n / bars);
                int p1 = Math.max(p0 + 1, (int) ((i + 1) * (float) n / bars));
                float v = 0;
                for (int k = p0; k < p1; k++) if (peaks[k] > v) v = peaks[k];
                float bh = Math.max(dp(1.5f), v * (h - dp(8)));
                canvas.drawRect(x0 + i * span / bars, mid - bh / 2f,
                        x0 + (i + 1) * span / bars - dp(1), mid + bh / 2f, bar);
            }
        } else {
            // placeholder until the async decode lands
            canvas.drawRect(x0, mid - dp(3), x1, mid + dp(3), trim);
        }

        // playhead
        if (playheadSec >= 0f) {
            float px = padPx + playheadSec * pxPerSec;
            canvas.drawLine(px, dp(2), px, baseY, head);
        }
    }
}
