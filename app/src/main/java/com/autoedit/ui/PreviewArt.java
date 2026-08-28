package com.autoedit.ui;

import android.graphics.*;
import android.util.LruCache;

/**
 * Procedurally drawn card thumbnails (spec §13).
 *
 * <h3>Why procedural</h3>
 * Every Motion / Effect / Transition card used to show the SAME bundled
 * photograph, so the thumbnails told the user nothing about what the option
 * did. Shipping a different licensed photo per option is not an option, so the
 * art is generated instead: nine distinct scenes (landscape, portrait, city,
 * car, nature, architecture, neon, dark, abstract) drawn with gradients,
 * silhouettes and light shapes. Each card deterministically picks a scene from
 * its own id, so:
 *
 * <ul>
 *   <li>different options show genuinely different imagery,</li>
 *   <li>the same option always shows the same imagery,</li>
 *   <li>nothing is downloaded and nothing blocks scrolling — each scene is a
 *       handful of canvas ops, drawn once and cached.</li>
 * </ul>
 *
 * Scenes are cached by (kind, size) in a small LRU, and every bitmap is
 * ARGB_8888 at card resolution, so a row of 40 cards costs a few hundred KB.
 */
public final class PreviewArt {

    /** The visual families a card thumbnail can use. */
    public enum Kind {
        LANDSCAPE, PORTRAIT, CITY, CAR, NATURE, ARCHITECTURE, NEON, DARK, ABSTRACT;

        private static final Kind[] ALL = values();

        /** Deterministic pick from any string id, so a card is stable. */
        public static Kind forId(String id) {
            if (id == null || id.isEmpty()) return LANDSCAPE;
            int h = 0;
            for (int i = 0; i < id.length(); i++) h = h * 31 + id.charAt(i);
            return ALL[Math.abs(h) % ALL.length];
        }
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24) {
        @Override protected int sizeOf(String key, Bitmap value) { return 1; }
    };

    private PreviewArt() {}

    /**
     * Cached, downsampled decode of a bundled category thumbnail (spec §8/§37).
     *
     * Every editing category owns one distinct image - Formula = Eiffel Tower,
     * Transition = Taj Mahal, Motion = Burj Khalifa, Effect = neon light art -
     * so the tool grid and the card rows read at a glance without any text.
     * Assets live in {@code drawable-nodpi} and are decoded once at card size,
     * never on the scroll path, and nothing is fetched at runtime so the whole
     * UI still works offline.
     *
     * The returned bitmap is owned by the cache - callers must NOT recycle it.
     */
    public static Bitmap asset(android.content.res.Resources res, int resId, int w, int h) {
        int bw = Math.max(8, w), bh = Math.max(8, h);
        String key = "res_" + resId + "_" + bw + "x" + bh;
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeResource(res, resId, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= bw && o.outHeight / (sample * 2) >= bh) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap decoded = android.graphics.BitmapFactory.decodeResource(res, resId, o);
        if (decoded == null) return null;
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, bw, bh, true);
        if (scaled != decoded) decoded.recycle();
        CACHE.put(key, scaled);
        return scaled;
    }

    /**
     * Returns a cached scene bitmap of exactly {@code w x h} pixels.
     * The returned bitmap is owned by the cache — callers must NOT recycle it.
     */
    public static Bitmap get(Kind kind, int w, int h) {
        int bw = Math.max(8, w), bh = Math.max(8, h);
        String key = kind.name() + "_" + bw + "x" + bh;
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        draw(c, kind, bw, bh);
        CACHE.put(key, b);
        return b;
    }

    /** Draws {@code kind} onto any canvas — used directly by card views too. */
    public static void draw(Canvas c, Kind kind, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (kind) {
            case LANDSCAPE:    landscape(c, p, w, h); break;
            case PORTRAIT:     portrait(c, p, w, h); break;
            case CITY:         city(c, p, w, h); break;
            case CAR:          car(c, p, w, h); break;
            case NATURE:       nature(c, p, w, h); break;
            case ARCHITECTURE: architecture(c, p, w, h); break;
            case NEON:         neon(c, p, w, h); break;
            case DARK:         dark(c, p, w, h); break;
            case ABSTRACT:
            default:           abstractArt(c, p, w, h); break;
        }
    }

    // ------------------------------------------------------------------ scenes

    private static void landscape(Canvas c, Paint p, int w, int h) {
        LinearGradient sky = new LinearGradient(0, 0, 0, h * 0.7f,
                new int[]{0xff1d4e7a, 0xff5fa8d3, 0xffe8b98a},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        p.setShader(sky);
        c.drawRect(0, 0, w, h * 0.7f, p);
        p.setShader(null);
        // sun
        p.setColor(0xffffe6b0);
        c.drawCircle(w * 0.72f, h * 0.42f, w * 0.09f, p);
        // mountains
        p.setColor(0xff2c4a63);
        Path m = new Path();
        m.moveTo(0, h * 0.7f);
        m.lineTo(w * 0.22f, h * 0.40f); m.lineTo(w * 0.42f, h * 0.66f);
        m.lineTo(w * 0.62f, h * 0.36f); m.lineTo(w * 0.86f, h * 0.62f);
        m.lineTo(w, h * 0.52f); m.lineTo(w, h * 0.7f); m.close();
        c.drawPath(m, p);
        // water
        p.setShader(new LinearGradient(0, h * 0.7f, 0, h,
                new int[]{0xff33607f, 0xff16283a}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.7f, w, h, p);
        p.setShader(null);
        p.setColor(0x33ffffff);
        for (int i = 0; i < 5; i++) c.drawRect(w * 0.1f, h * (0.76f + i * 0.045f), w * 0.9f, h * (0.765f + i * 0.045f), p);
    }

    private static void portrait(Canvas c, Paint p, int w, int h) {
        c.drawColor(0xff1b2430);
        // bokeh background
        int[] cols = {0x6649A8FF, 0x557C5CFF, 0x44ffb0d0};
        for (int i = 0; i < 14; i++) {
            float x = ((i * 0.381966f) % 1f) * w;
            float y = ((i * 0.618034f) % 1f) * h;
            p.setColor(cols[i % cols.length]);
            c.drawCircle(x, y, w * (0.05f + (i % 4) * 0.025f), p);
        }
        // silhouette head + shoulders
        p.setColor(0xff0d141c);
        c.drawCircle(w * 0.5f, h * 0.36f, w * 0.17f, p);
        RectF body = new RectF(w * 0.22f, h * 0.52f, w * 0.78f, h * 1.2f);
        c.drawOval(body, p);
        // rim light
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, w * 0.018f));
        p.setColor(0xaa8fd0ff);
        c.drawArc(new RectF(w * 0.33f, h * 0.19f, w * 0.67f, h * 0.53f), 200f, 140f, false, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static void city(Canvas c, Paint p, int w, int h) {
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xff0a1024, 0xff2a2050, 0xff4a2a55}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // skyline
        float x = 0;
        int i = 0;
        while (x < w) {
            float bw = w * (0.10f + ((i * 37) % 11) / 100f);
            float bh = h * (0.28f + ((i * 53) % 40) / 100f);
            p.setColor(i % 2 == 0 ? 0xff101828 : 0xff161f33);
            c.drawRect(x, h - bh, x + bw, h, p);
            // windows
            p.setColor(0xaaffd98a);
            for (float wy = h - bh + h * 0.05f; wy < h - h * 0.06f; wy += h * 0.07f)
                for (float wx = x + bw * 0.2f; wx < x + bw * 0.8f; wx += bw * 0.3f)
                    if (((int) (wx * 7 + wy * 3)) % 3 != 0)
                        c.drawRect(wx, wy, wx + bw * 0.14f, wy + h * 0.025f, p);
            x += bw + w * 0.015f;
            i++;
        }
        // moon glow
        p.setShader(new RadialGradient(w * 0.8f, h * 0.18f, w * 0.3f,
                new int[]{0x66ffffff, 0x00ffffff}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.5f, p);
        p.setShader(null);
    }

    private static void car(Canvas c, Paint p, int w, int h) {
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xff241a2e, 0xff3d2740, 0xff14101c}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // road
        p.setColor(0xff1c1c22);
        Path road = new Path();
        road.moveTo(0, h); road.lineTo(w * 0.36f, h * 0.52f);
        road.lineTo(w * 0.64f, h * 0.52f); road.lineTo(w, h); road.close();
        c.drawPath(road, p);
        // lane dashes
        p.setColor(0xccf2e6c8);
        for (int i = 0; i < 5; i++) {
            float t = 0.55f + i * 0.10f;
            float cw = w * 0.012f * (1f + i * 0.5f);
            c.drawRect(w * 0.5f - cw, h * t, w * 0.5f + cw, h * (t + 0.05f), p);
        }
        // light streaks
        p.setShader(new LinearGradient(0, h * 0.55f, w, h * 0.55f,
                new int[]{0x00ff5a5a, 0xddff5a5a}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.52f, w, h * 0.545f, p);
        p.setShader(new LinearGradient(0, h * 0.6f, w, h * 0.6f,
                new int[]{0xddffe9a8, 0x00ffe9a8}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.575f, w, h * 0.6f, p);
        p.setShader(null);
    }

    private static void nature(Canvas c, Paint p, int w, int h) {
        p.setShader(new LinearGradient(0, 0, 0, h * 0.6f,
                new int[]{0xffa8d8e8, 0xffdff0d8}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.62f, p);
        p.setShader(null);
        // field
        p.setShader(new LinearGradient(0, h * 0.6f, 0, h,
                new int[]{0xff7aa84a, 0xff35602a}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.6f, w, h, p);
        p.setShader(null);
        // trees
        for (int i = 0; i < 5; i++) {
            float tx = w * (0.08f + i * 0.21f);
            float th = h * (0.20f + (i % 3) * 0.07f);
            p.setColor(0xff3a2a1c);
            c.drawRect(tx - w * 0.012f, h * 0.62f - th * 0.4f, tx + w * 0.012f, h * 0.62f, p);
            p.setColor(i % 2 == 0 ? 0xff2f6b34 : 0xff3f8a42);
            c.drawCircle(tx, h * 0.62f - th * 0.55f, th * 0.38f, p);
        }
    }

    private static void architecture(Canvas c, Paint p, int w, int h) {
        c.drawColor(0xff12161f);
        p.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xff2b3a55, 0xff131a26}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // colonnade
        int n = 5;
        for (int i = 0; i < n; i++) {
            float cx = w * (0.12f + i * 0.19f);
            float cw = w * 0.075f;
            p.setShader(new LinearGradient(cx - cw, 0, cx + cw, 0,
                    new int[]{0xff6f7f99, 0xffcfd8e6, 0xff4a5568}, null, Shader.TileMode.CLAMP));
            c.drawRect(cx - cw / 2f, h * 0.18f, cx + cw / 2f, h * 0.9f, p);
        }
        p.setShader(null);
        // architrave + steps
        p.setColor(0xff9aa6b8);
        c.drawRect(0, h * 0.10f, w, h * 0.19f, p);
        p.setColor(0xff7e8a9c);
        c.drawRect(0, h * 0.9f, w, h, p);
        p.setColor(0x22000000);
        for (int i = 1; i < 4; i++) c.drawRect(0, h * (0.9f + i * 0.025f), w, h * (0.9f + i * 0.025f) + 2f, p);
    }

    private static void neon(Canvas c, Paint p, int w, int h) {
        c.drawColor(0xff07060f);
        // wet-floor glow
        p.setShader(new RadialGradient(w * 0.5f, h * 0.95f, w * 0.8f,
                new int[]{0x5549A8FF, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // neon tubes
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(3f, w * 0.03f));
        p.setStrokeCap(Paint.Cap.ROUND);
        int[] cols = {0xffff4fa3, 0xff49A8FF, 0xff7C5CFF, 0xff4dffd2};
        for (int i = 0; i < 4; i++) {
            p.setColor(cols[i]);
            RectF r = new RectF(w * (0.14f + i * 0.03f), h * (0.16f + i * 0.16f),
                    w * (0.86f - i * 0.03f), h * (0.30f + i * 0.16f));
            c.drawRoundRect(r, w * 0.1f, w * 0.1f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void dark(Canvas c, Paint p, int w, int h) {
        p.setShader(new RadialGradient(w * 0.5f, h * 0.45f, w * 0.75f,
                new int[]{0xff2b2f3a, 0xff0a0b0f}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // single key light on a form
        p.setShader(new LinearGradient(w * 0.2f, 0, w * 0.8f, h,
                new int[]{0x66ffffff, 0x00ffffff}, null, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(w * 0.3f, h * 0.28f, w * 0.72f, h * 0.86f), p);
        p.setShader(null);
        // heavy vignette so dark-scene effects are readable
        p.setShader(new RadialGradient(w * 0.5f, h * 0.5f, w * 0.7f,
                new int[]{0x00000000, 0xcc000000}, new float[]{0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    private static void abstractArt(Canvas c, Paint p, int w, int h) {
        p.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xff0d2440, 0xff25406b, 0xff4a2f6b}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        int[] cols = {0x8849A8FF, 0x667C5CFF, 0x55ffd166};
        for (int i = 0; i < 6; i++) {
            p.setColor(cols[i % cols.length]);
            float r = w * (0.12f + (i % 3) * 0.09f);
            c.drawCircle(w * (0.2f + ((i * 0.29f) % 0.7f)), h * (0.22f + ((i * 0.37f) % 0.66f)), r, p);
        }
        // diagonal streaks
        p.setColor(0x33ffffff);
        p.setStrokeWidth(Math.max(2f, w * 0.02f));
        for (int i = 0; i < 4; i++) {
            float y = h * (0.2f + i * 0.2f);
            c.drawLine(0, y, w, y - h * 0.18f, p);
        }
    }
}
