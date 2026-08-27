package com.autoedit.ui;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import com.autoedit.R;

/**
 * Premium export hero: rotating neon-blue energy ring + pulsing glow + the
 * AutoEdit logo, over subtle drifting particles / light streaks.
 *
 * GPU-friendly: one custom view, no bitmaps per frame except the (cached)
 * logo, all animation is time-based and stops when `running` is false or the
 * view is not shown. Independent of the export thread.
 */
public class ExportRingView extends View {
    private boolean running = true;
    private boolean done = false;
    private float progress = 0f; // 0..1, drives a subtle ring "fill"

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Drawable logo;

    private static class P { float x, y, r, vx, vy, a; }
    private final P[] parts = new P[26];
    private float t0;

    public ExportRingView(Context c) {
        super(c);
        setWillNotDraw(false);
        // neon ring
        ring.setColor(0xff49A8FF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(4));
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setShadowLayer(dp(14), 0, 0, 0x9949A8FF);
        // subtle full track
        track.setColor(0x2249A8FF);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(dp(4));
        // progress arc (brighter)
        ringFill.setColor(0xff8FD0FF);
        ringFill.setStyle(Paint.Style.STROKE);
        ringFill.setStrokeWidth(dp(4));
        ringFill.setStrokeCap(Paint.Cap.ROUND);
        ringFill.setShadowLayer(dp(18), 0, 0, 0xcc49A8FF);
        // particle
        particle.setColor(0xff49A8FF);
        particle.setShadowLayer(dp(6), 0, 0, 0x8849A8FF);
        // streak
        streak.setShader(new LinearGradient(0, 0, dp(320), dp(60), 0x0049A8FF, 0x6649A8FF, Shader.TileMode.CLAMP));
        streak.setStrokeWidth(dp(2));

        for (int i = 0; i < parts.length; i++) {
            P p = new P();
            p.x = (float) Math.random(); p.y = (float) Math.random();
            p.r = dp(1.2f) + (float) Math.random() * dp(2.6f);
            p.vx = (float) (Math.random() - .5) * .02f; // per second (fraction of width)
            p.vy = (float) (Math.random() * .03f + .01f);
            p.a = .12f + (float) Math.random() * .4f;
            parts[i] = p;
        }
        t0 = SystemClock.elapsedRealtime();
    }

    public void setRunning(boolean r) { running = r; if (r) tick(); }
    public void setDone(boolean d) { done = d; if (d) { removeCallbacks(tickRunnable); invalidate(); } }
    public void setProgress(float p) { progress = Math.max(0f, Math.min(1f, p)); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (logo == null) {
            // the existing app logo (already navy + electric blue) is kept as-is —
            // the neon look comes from the glow layers drawn around it, so the
            // identity/shape stays fully recognizable.
            logo = getResources().getDrawable(R.drawable.ic_auto_edit, null);
            logo.mutate();
        }
        tick();
    }

    private void tick() {
        if (!isShown() || !running || done) { removeCallbacks(tickRunnable); return; }
        postDelayed(tickRunnable, 16);
    }
    private final Runnable tickRunnable = this::tick;

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.elapsedRealtime();
        float t = (now - t0) / 1000f;
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) * .34f;

        drawParticles(canvas, w, h, t);
        drawStreaks(canvas, w, h, t);

        // pulsing energy glow behind the ring (frozen when done — animation stops)
        float pulse = done ? .5f : .5f + .5f * (float) Math.sin(t * 2.1f);
        float glowR = R * (1.55f + 0.18f * pulse);
        RadialGradient rg = new RadialGradient(cx, cy, glowR,
                new int[]{0x5549A8FF, 0x2249A8FF, 0x0049A8FF},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP);
        glow.setShader(rg);
        glow.setColor(0xffffffff);
        canvas.drawCircle(cx, cy, glowR, glow);
        glow.setShader(null);

        // soft outer bloom
        glow.setColor(0x2249A8FF);
        canvas.drawCircle(cx, cy, R * 1.18f + 3f * pulse, glow);

        // full faint track
        RectF oval = new RectF(cx - R, cy - R, cx + R, cy + R);
        canvas.drawOval(oval, track);

        // rotating energy arc (stops when done -> full ring)
        if (running) {
            float rot = (float) (t * 130f) % 360f;
            canvas.save();
            canvas.rotate(rot, cx, cy);
            canvas.drawArc(oval, -90f, 300f, false, ring);
            canvas.restore();
        } else {
            // done: draw a complete bright ring
            canvas.drawOval(oval, ringFill);
        }

        // progress arc overlay (real progress, subtle)
        if (!done && progress > 0.001f) {
            canvas.drawArc(oval, -90f, 360f * progress, false, ringFill);
        }

        // logo centered
        int ls = (int) (R * 1.05f);
        int lx = (int) (cx - ls / 2f), ly = (int) (cy - ls / 2f);
        logo.setBounds(lx, ly, lx + ls, ly + ls);
        // bloom under logo
        RadialGradient lg = new RadialGradient(cx, cy, ls * .7f,
                new int[]{0x6649A8FF, 0x0049A8FF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        glow.setShader(lg);
        glow.setColor(0xffffffff);
        canvas.drawCircle(cx, cy, ls * .72f, glow);
        glow.setShader(null);
        logo.draw(canvas);

        tick();
    }

    private void drawParticles(Canvas canvas, int w, int h, float t) {
        for (P p : parts) {
            p.x += p.vx; p.y += p.vy;
            if (p.y > 1.05f) { p.y = -0.05f; p.x = (float) Math.random(); }
            if (p.x < -0.05f) p.x = 1.05f;
            if (p.x > 1.05f) p.x = -0.05f;
            float px = p.x * w, py = p.y * h;
            float tw = .6f + .4f * (float) Math.sin(t * 2f + p.x * 9f);
            particle.setAlpha((int) (255 * p.a * tw));
            canvas.drawCircle(px, py, p.r, particle);
        }
    }

    private void drawStreaks(Canvas canvas, int w, int h, float t) {
        float s = (float) (t * .12f % 1f);
        canvas.save();
        canvas.rotate(-18, w / 2f, h / 2f);
        float y = (float) (h * (0.2f + 0.6f * s));
        canvas.drawLine(w * .1f, y, w * .9f, y - h * .06f, streak);
        float y2 = (float) (h * (1.0f - (0.2f + 0.6f * s)));
        canvas.drawLine(w * .2f, y2, w * .8f, y2 + h * .05f, streak);
        canvas.restore();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
