package com.autoedit.ui;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import com.autoedit.R;

/**
 * Premium export hero (v1.0.7): a slowly rotating neon-blue energy ring with
 * blue smoke/energy that FLOWS AROUND the ring, a glow that cycles
 * LOW → MED → HIGH → MED → LOW, drifting + orbiting particles, rotating light
 * streaks, radial bloom, and the user-provided AutoEdit logo breathing
 * (scale 1.00 → 1.03, glow LOW → HIGH → LOW) at the center — the logo never spins.
 *
 * Logo: drawn from the transparent logo_autoedit_alpha.png, bounded small
 * (≈0.8 × ring radius, capped at 120dp) so it always sits INSIDE the ring.
 *
 * Everything is native custom drawing (time-based), GPU-friendly:
 * - smoke is a small number of pre-rendered gradient sprites, repositioned per frame
 * - the logo is decoded ONCE, then only scaled/drawn per frame
 * - no bitmaps are allocated per frame, no video is played
 * Animation stops when `running` is false or the view is not shown; when
 * `done` is set the view freezes on a calm static frame (full ring).
 */
public class ExportRingView extends View {
    private boolean running = true;
    private boolean done = false;
    private float progress = 0f; // 0..1, real export progress, drives the bright arc

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbitDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    // brightness pulse for the logo (updated in-place each frame)
    private final float[] lum = new float[]{1f,0,0,0,0, 0,1f,0,0,0, 0,0,1f,0,0, 0,0,0,1f,0};
    private final ColorMatrix lumMatrix = new ColorMatrix(lum);
    private final ColorMatrixColorFilter lumFilter = new ColorMatrixColorFilter(lumMatrix);

    private Bitmap logoBmp;   // user logo, soft-masked + downscaled once
    private Bitmap smokeSprite; // pre-rendered soft blue blob for the swirling smoke
    private static final int SMOKE = 6;
    private final float[] smokeAng = new float[SMOKE];   // current orbit angle
    private final float[] smokeSpeed = new float[SMOKE]; // rad/s (+/- direction)
    private final float[] smokeRad = new float[SMOKE];   // orbit radius factor (× R)
    private final float[] smokeSize = new float[SMOKE];  // blob size factor (× R)
    private final float[] smokeBase = new float[SMOKE];  // base alpha
    private final float[] smokePhase = new float[SMOKE];

    private static class P { float x, y, r, vx, vy, a, tw; }
    private final P[] parts = new P[22];
    private static class O { float ang, rad, speed, r, a, phase; }
    private final O[] orbits = new O[10];

    private final RectF oval = new RectF();
    private final RectF streakOval = new RectF();
    private float t0;

    public ExportRingView(Context c) {
        super(c);
        setWillNotDraw(false);
        // faint full track
        track.setColor(0x2649A8FF);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(dp(4));
        // bright core arc
        ringCore.setColor(0xff49A8FF);
        ringCore.setStyle(Paint.Style.STROKE);
        ringCore.setStrokeWidth(dp(4));
        ringCore.setStrokeCap(Paint.Cap.ROUND);
        // wide halo arc (alpha driven per frame by the glow cycle)
        ringHalo.setColor(0x8849A8FF);
        ringHalo.setStyle(Paint.Style.STROKE);
        ringHalo.setStrokeWidth(dp(13));
        ringHalo.setStrokeCap(Paint.Cap.ROUND);
        // counter-rotating accent arc
        accentArc.setColor(0x998FD0FF);
        accentArc.setStyle(Paint.Style.STROKE);
        accentArc.setStrokeWidth(dp(2.5f));
        accentArc.setStrokeCap(Paint.Cap.ROUND);
        particle.setColor(0xff49A8FF);
        orbitDot.setColor(0xff8FD0FF);
        streak.setColor(0x558FD0FF);
        streak.setStyle(Paint.Style.STROKE);
        streak.setStrokeWidth(dp(2));
        streak.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < parts.length; i++) {
            P p = new P();
            p.x = (float) Math.random(); p.y = (float) Math.random();
            p.r = dp(1.2f) + (float) Math.random() * dp(2.6f);
            p.vx = (float) (Math.random() - .5) * .02f;
            p.vy = (float) (Math.random() * .03f + .01f);
            p.a = .10f + (float) Math.random() * .35f;
            p.tw = 1.2f + (float) Math.random() * 2.2f; // twinkle speed
            parts[i] = p;
        }
        for (int i = 0; i < orbits.length; i++) {
            O o = new O();
            o.ang = (float) (Math.random() * Math.PI * 2);
            o.rad = 1.04f + (float) Math.random() * 0.22f; // × R
            o.speed = (float) (0.25 + Math.random() * 0.5) * (i % 2 == 0 ? 1 : -1);
            o.r = dp(1.4f) + (float) Math.random() * dp(2.2f);
            o.a = .35f + (float) Math.random() * .5f;
            o.phase = (float) (Math.random() * Math.PI * 2);
            orbits[i] = o;
        }
        // smoke sprites orbiting the ring — each on its own radius/speed/phase
        for (int i = 0; i < SMOKE; i++) {
            smokeAng[i] = (float) (Math.PI * 2 * i / SMOKE + Math.random() * 0.6);
            smokeSpeed[i] = (0.18f + 0.22f * (i % 3) * 0.5f + (float) (Math.random() * 0.06f)) * (i % 2 == 0 ? 1 : -1);
            smokeRad[i] = 1.0f + 0.16f * (((i * 7) % 3) - 1); // 0.84 / 1.0 / 1.16 × R
            smokeSize[i] = 0.42f + 0.16f * ((i * 5) % 3);     // 0.42 / 0.58 / 0.74 × R
            smokeBase[i] = 0.16f + 0.09f * ((i * 3) % 3);
            smokePhase[i] = (float) (Math.random() * Math.PI * 2);
        }
        t0 = SystemClock.elapsedRealtime();
    }

    public void setRunning(boolean r) { running = r; if (r) tick(); }
    public void setDone(boolean d) { done = d; if (d) { removeCallbacks(tickRunnable); invalidate(); } }
    public void setProgress(float p) { progress = Math.max(0f, Math.min(1f, p)); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (logoBmp == null) {
            // BUGFIX (v1.0.7+): use the transparent logo asset (logo_autoedit_alpha.png,
            // generated from the supplied logo with its baked-in background keyed out —
            // exact shape, proportions and colors preserved). The previous runtime
            // "soft mask" painted an opaque white square (Canvas.drawColor ignores the
            // shader), which is why a huge white square covered the export screen.
            Bitmap src = BitmapFactory.decodeResource(getResources(), R.drawable.logo_autoedit_alpha);
            logoBmp = downsample(src, 640);
            if (logoBmp != src) src.recycle();
        }
        if (smokeSprite == null) smokeSprite = smokeSprite(256);
        tick();
    }

    private static Bitmap downsample(Bitmap src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        float s = Math.min(1f, max / (float) Math.max(w, h));
        if (s >= 1f) return src;
        return Bitmap.createScaledBitmap(src, Math.max(2, (int) (w * s)), Math.max(2, (int) (h * s)), true);
    }

    private static Bitmap smokeSprite(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size / 2f;
        RadialGradient g = new RadialGradient(r, r, r,
                new int[]{0x9949A8FF, 0x4449A8FF, 0x0049A8FF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(g);
        c.drawCircle(r, r, r, p);
        return b;
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
        float t = done ? 0f : (now - t0) / 1000f; // frozen when done
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) * .34f;

        // glow cycle LOW → MED → HIGH → MED → LOW (smooth, 3.6 s period)
        float glowLvl = done ? 0.55f : 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * t / 3.6f);

        drawParticles(canvas, w, h, t, R);
        drawStreaks(canvas, cx, cy, R, glowLvl, t);

        // ---- radial bloom behind the ring (breathes with the glow cycle) ----
        float glowR = R * (1.5f + 0.22f * glowLvl);
        RadialGradient rg = new RadialGradient(cx, cy, glowR,
                new int[]{0x5549A8FF, 0x1f49A8FF, 0x0049A8FF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        glow.setShader(rg);
        glow.setColor(0xffffffff);
        glow.setAlpha((int) (140 + 115 * glowLvl));
        canvas.drawCircle(cx, cy, glowR, glow);
        glow.setShader(null);

        // ---- swirling smoke/energy AROUND the ring (the key effect) ----
        drawSmoke(canvas, cx, cy, R, glowLvl, t);

        // ---- the ring itself ----
        oval.set(cx - R, cy - R, cx + R, cy + R);
        canvas.drawOval(oval, track);
        if (done) {
            // calm complete ring when export finished
            ringHalo.setAlpha(120);
            canvas.drawOval(oval, ringHalo);
            ringCore.setAlpha(255);
            canvas.drawOval(oval, ringCore);
        } else {
            float rot = (float) (t * 80f) % 360f;          // slow continuous rotation
            ringHalo.setAlpha((int) (60 + 130 * glowLvl));    // halo brightens with the cycle
            canvas.save();
            canvas.rotate(rot, cx, cy);
            canvas.drawArc(oval, -90f, 300f, false, ringHalo);
            canvas.drawArc(oval, -90f, 300f, false, ringCore);
            canvas.restore();
            // counter-rotating short accent arc for depth
            float rot2 = (float) (-t * 52f + 140f) % 360f;
            accentArc.setAlpha((int) (90 + 100 * glowLvl));
            canvas.save();
            canvas.rotate(rot2, cx, cy);
            canvas.drawArc(oval, 0f, 110f, false, accentArc);
            canvas.restore();
        }

        // real export progress arc (subtle, on top)
        if (!done && progress > 0.001f) {
            ringCore.setColor(0xffB9E1FF);
            ringCore.setStrokeWidth(dp(2.5f));
            canvas.drawArc(oval, -90f, 360f * progress, false, ringCore);
            ringCore.setColor(0xff49A8FF);
            ringCore.setStrokeWidth(dp(4));
        }

        // ---- center logo: breathing scale 1.00→1.03 + glow LOW→HIGH→LOW, no spin ----
        // BUGFIX: logo must stay SMALL inside the ring — it no longer fills the
        // entire ring diameter (was R*1.0). Bounded so the breathing peak keeps it
        // fully inside the ring and it never overlaps the text below.
        float breath = done ? 0.5f : 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * t / 3.2f);
        float ls = Math.min(R * 0.8f, dp(120f)) * (1.00f + 0.03f * breath);
        float lgR = ls * 0.60f;
        RadialGradient lg = new RadialGradient(cx, cy, lgR,
                new int[]{0x6649A8FF, 0x0049A8FF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        glow.setShader(lg);
        glow.setAlpha((int) (70 + 130 * breath));
        canvas.drawCircle(cx, cy, lgR, glow);
        glow.setShader(null);
        if (logoBmp != null) {
            float br = 1f + 0.10f * breath; // subtle brightness pulse
            lum[0] = br; lum[5] = br; lum[10] = br;
            lumMatrix.set(lum);
            logoPaint.setColorFilter(lumFilter);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(1f + 0.03f * breath, 1f + 0.03f * breath);
            canvas.drawBitmap(logoBmp, -ls / 2f, -ls / 2f, logoPaint);
            canvas.restore();
            logoPaint.setColorFilter(null);
        }

        tick();
    }

    /** Soft blue blobs orbiting the ring at different radii/speeds — reads as
     *  smoke/energy flowing around the circle. Sprites are pre-rendered; only
     *  position + alpha change per frame. */
    private void drawSmoke(Canvas canvas, float cx, float cy, float R, float glow, float t) {
        float boost = 0.75f + 0.5f * glow; // smoke rides the glow cycle
        for (int i = 0; i < SMOKE; i++) {
            if (!done) smokeAng[i] += smokeSpeed[i] * 0.016f;
            float rad = R * smokeRad[i];
            float x = cx + (float) Math.cos(smokeAng[i]) * rad;
            float y = cy + (float) Math.sin(smokeAng[i]) * rad * 0.96f; // slight ellipse = depth
            float tw = 0.7f + 0.3f * (float) Math.sin(t * 1.4f + smokePhase[i]);
            int a = (int) (255 * smokeBase[i] * tw * boost);
            if (a <= 0) continue;
            spritePaint.setAlpha(a);
            float s = R * smokeSize[i] * (1f + 0.12f * tw);
            canvas.drawBitmap(smokeSprite, x - s / 2f, y - s / 2f, spritePaint);
        }
    }

    private void drawParticles(Canvas canvas, int w, int h, float t, float R) {
        for (P p : parts) {
            if (!done) {
                p.x += p.vx; p.y += p.vy;
                if (p.y > 1.05f) { p.y = -0.05f; p.x = (float) Math.random(); }
                if (p.x < -0.05f) p.x = 1.05f;
                if (p.x > 1.05f) p.x = -0.05f;
            }
            float px = p.x * w, py = p.y * h;
            float tw = .55f + .45f * (float) Math.sin(t * p.tw + p.x * 9f);
            particle.setAlpha((int) (255 * p.a * tw));
            canvas.drawCircle(px, py, p.r, particle);
        }
        // energy dots orbiting just outside the ring
        float cx = w / 2f, cy = h / 2f;
        for (O o : orbits) {
            if (!done) o.ang += o.speed * 0.016f;
            float rad = R * o.rad;
            float x = cx + (float) Math.cos(o.ang) * rad;
            float y = cy + (float) Math.sin(o.ang) * rad;
            float tw = .4f + .6f * (0.5f + 0.5f * (float) Math.sin(t * 2.2f + o.phase));
            orbitDot.setAlpha((int) (255 * o.a * tw));
            canvas.drawCircle(x, y, o.r, orbitDot);
        }
    }

    /** Soft light streaks sweeping the outer area (two counter-rotating arcs). */
    private void drawStreaks(Canvas canvas, float cx, float cy, float R, float glow, float t) {
        float r1 = R * 1.30f, r2 = R * 1.45f;
        streakOval.set(cx - r1, cy - r1, cx + r1, cy + r1);
        streakOval.set(cx - r2, cy - r2, cx + r2, cy + r2);
        int a = (int) (40 + 90 * glow);
        streak.setAlpha(a);
        float a1 = (float) (t * 24f) % 360f;
        canvas.drawArc(streakOval, a1, 70f, false, streak);
        float a2 = (float) (-t * 17f + 180f) % 360f;
        streak.setAlpha(a * 2 / 3);
        canvas.drawArc(streakOval, a2, 50f, false, streak);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
