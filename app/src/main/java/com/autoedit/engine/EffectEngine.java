package com.autoedit.engine;

import android.graphics.*;
import com.autoedit.model.EffectType;
import java.util.HashMap;
import java.util.Map;

/**
 * Effect rendering shared by preview and export (spec §10, §16).
 *
 * A clip carries an ORDERED stack of effects. Each one is applied as:
 * <ol>
 *   <li>an optional {@link #paintFor colour-matrix pass} while the frame is drawn,</li>
 *   <li>an optional {@link #drawPost post overlay} painted over the frame,</li>
 *   <li>an optional {@link #blurStrengthFor softening halo} / {@link #channelShift
 *       channel offset} that the caller renders.</li>
 * </ol>
 *
 * Colour filters are cached by (type, rounded intensity) so a 1000-frame export
 * allocates one ColorMatrix per distinct setting, not one per frame. Nothing in
 * here ever writes back to the source bitmap — the effect stack is state.
 */
public class EffectEngine {

    private final Map<Integer, ColorMatrixColorFilter> filterCache = new HashMap<>();
    private final Map<Integer, ColorMatrixColorFilter> stackCache = new HashMap<>();
    private final Paint postPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    // ------------------------------------------------------------- colour pass

    public Paint paintFor(EffectType type, float intensity) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (type == null || type == EffectType.NONE) return p;
        float i = clamp01(intensity);
        ColorMatrixColorFilter cf = filterFor(type, i);
        if (cf != null) p.setColorFilter(cf);
        else if (type == EffectType.FADE) p.setAlpha((int) (255 * (1f - 0.35f * i)));
        return p;
    }

    /** Cached colour filter for a (type, intensity) pair, or null if the effect
     *  is spatial rather than chromatic. */
    public ColorMatrixColorFilter filterFor(EffectType type, float intensity) {
        if (type == null || type == EffectType.NONE) return null;
        float i = clamp01(intensity);
        int key = type.ordinal() * 101 + Math.round(i * 100);
        ColorMatrixColorFilter cached = filterCache.get(key);
        if (cached != null) return cached;
        float[] m = matrixFor(type, i);
        if (m == null) return null;
        ColorMatrixColorFilter cf = new ColorMatrixColorFilter(new ColorMatrix(m));
        filterCache.put(key, cf);
        return cf;
    }

    /**
     * The 4x5 colour matrix for one effect, or null when the effect is spatial
     * (blur, vignette, grain, flare...) rather than chromatic.
     *
     * This is pure arithmetic — no {@code ColorMatrix} calls — so the entire
     * effect colour table is unit-testable on the JVM, and the composition
     * order is explicit instead of hidden inside {@code postConcat}.
     */
    public float[] matrixFor(EffectType t, float i) {
        float k;
        switch (t) {
            case BRIGHTNESS:
                k = i * 80f;
                return offsets(k, k, k);
            case CONTRAST: {
                float s = 1f + i * 0.8f;
                return scaleOffsets(s, s, s, 128f * (1f - s));
            }
            case SATURATION:
                return sat(1f + i * 1.6f);
            case COLOR_BOOST:
                return mul(scaleOffsets(1f + i * 0.18f, 1f + i * 0.18f, 1f + i * 0.18f,
                        128f * (1f - (1f + i * 0.18f))), sat(1f + i * 0.9f));
            case EXPOSURE: {
                float e = 1f + i * 0.7f;
                return scaleOffsets(e, e, e, 0f);
            }
            case BLACK_WHITE:
                return sat(0f);
            case SEPIA:
                // Desaturate first, then tone — the same order the old
                // setSaturation + postConcat produced.
                return mul(new float[]{
                        .393f, .769f, .189f, 0, 0, .349f, .686f, .168f, 0, 0,
                        .272f, .534f, .131f, 0, 0, 0, 0, 0, 1, 0}, sat(1f - 0.6f * i));
            case CINEMATIC:
                // The old code called setSaturation() AFTER building this
                // matrix, which discarded it entirely. Composing explicitly
                // keeps both the teal-shift and the desaturation.
                return mul(sat(1f - 0.18f * i), new float[]{
                        1.05f, 0, 0, 0, 8, 0, 1.02f, 0, 0, 4,
                        0, 0, 0.92f, 0, 6, 0, 0, 0, 1, 0});
            case VINTAGE:
                return new float[]{
                        0.9f, 0.05f, 0, 0, 18, 0, 0.85f, 0.05f, 0, 14,
                        0, 0.05f, 0.8f, 0, 10, 0, 0, 0, 1, 0};
            case FILM:
                return mul(offsets(6, 6, 10), sat(1f - 0.25f * i));
            case DREAM:
                return new float[]{
                        1.05f, 0.08f, 0.08f, 0, 10, 0.05f, 1.05f, 0.1f, 0, 8,
                        0.05f, 0.1f, 1.08f, 0, 12, 0, 0, 0, 1, 0};
            case TEMPERATURE:
            case WARM:
                return new float[]{
                        1f + 0.12f * i, 0, 0, 0, 10f * i, 0, 1f, 0, 0, 4f * i,
                        0, 0, 1f - 0.1f * i, 0, -6f * i, 0, 0, 0, 1, 0};
            case COOL:
                return new float[]{
                        1f - 0.10f * i, 0, 0, 0, -4f * i, 0, 1f, 0, 0, 2f * i,
                        0, 0, 1f + 0.14f * i, 0, 12f * i, 0, 0, 0, 1, 0};
            case HIGHLIGHTS:
            case HIGHLIGHT_GLOW:
                k = i * 30f;
                return new float[]{
                        1, 0, 0, 0, k, 0, 1, 0, 0, k, 0, 0, 1, 0, k * 0.6f, 0, 0, 0, 1, 0};
            case SHADOWS:
            case CINEMATIC_SHADOWS: {
                float s = 1f + i * 0.25f;
                return mul(offsets(6f * i, 5f * i, 3f * i), new float[]{
                        s, 0, 0, 0, -12f * i, 0, s, 0, 0, -10f * i,
                        0, 0, s, 0, -6f * i, 0, 0, 0, 1, 0});
            }
            case SHARPEN:
                return new float[]{
                        1.08f, 0, 0, 0, -8, 0, 1.08f, 0, 0, -8,
                        0, 0, 1.08f, 0, -8, 0, 0, 0, 1, 0};
            case CINEMATIC_GLOW:
                return mul(sat(1f - 0.10f * i), new float[]{
                        1.06f, 0.02f, 0, 0, 12, 0, 1.03f, 0.02f, 0, 8,
                        0, 0, 0.98f, 0, 4, 0, 0, 0, 1, 0});
            case DREAM_GLOW:
                return new float[]{
                        1.07f, 0.06f, 0.09f, 0, 14, 0.04f, 1.05f, 0.11f, 0, 10,
                        0.06f, 0.09f, 1.10f, 0, 16, 0, 0, 0, 1, 0};
            case SUBTLE_NOISE: {
                float s = 1f + i * 0.10f;
                return scaleOffsets(s, s, s, 128f * (1f - s));
            }
            default:
                return null;
        }
    }

    /** The standard Rec.601 luminance saturation matrix. */
    private static float[] sat(float s) {
        float ir = 0.3086f * (1f - s);
        float ig = 0.6094f * (1f - s);
        float ib = 0.0820f * (1f - s);
        return new float[]{
                ir + s, ig, ib, 0, 0,
                ir, ig + s, ib, 0, 0,
                ir, ig, ib + s, 0, 0,
                0, 0, 0, 1, 0};
    }

    private static float[] offsets(float dr, float dg, float db) {
        return new float[]{
                1, 0, 0, 0, dr, 0, 1, 0, 0, dg, 0, 0, 1, 0, db, 0, 0, 0, 1, 0};
    }

    private static float[] scaleOffsets(float sr, float sg, float sb, float off) {
        return new float[]{
                sr, 0, 0, 0, off, 0, sg, 0, 0, off, 0, 0, sb, 0, off, 0, 0, 0, 1, 0};
    }

    /** Row-major 5x5 product, matching {@code ColorMatrix.postConcat} semantics. */
    private static float[] mul(float[] a, float[] b) {
        float[] o = new float[20];
        for (int r = 0; r < 4; r++) {
            int ro = r * 5;
            for (int c = 0; c < 5; c++) {
                float v = 0f;
                for (int k = 0; k < 4; k++) v += a[ro + k] * b[k * 5 + c];
                o[ro + c] = v;
            }
        }
        return o;
    }

    /**
     * Composes a clip's whole ordered effect stack into ONE cached colour
     * filter, so a clip with Glow + Vignette + Film Grain costs one filter
     * lookup per frame rather than three.
     *
     * @return null when the stack has no chromatic component
     */
    public ColorMatrixColorFilter stackFilter(java.util.List<com.autoedit.model.EffectLayer> layers) {
        if (layers == null || layers.isEmpty()) return null;
        int key = 17;
        for (com.autoedit.model.EffectLayer l : layers)
            key = key * 31 + l.type.ordinal() * 101 + Math.round(EffectLayerIntensity(l) * 100);
        ColorMatrixColorFilter cached = stackCache.get(key);
        if (cached != null) return cached;
        float[] combined = null;
        for (com.autoedit.model.EffectLayer l : layers) {
            float[] m = matrixFor(l.type, EffectLayerIntensity(l));
            if (m == null) continue;
            // postConcat semantics: the later layer is applied to the output of
            // the earlier one, so it multiplies on the LEFT.
            combined = combined == null ? m : mul(m, combined);
        }
        if (combined == null) return null;
        ColorMatrixColorFilter cf = new ColorMatrixColorFilter(new ColorMatrix(combined));
        stackCache.put(key, cf);
        return cf;
    }

    private static float EffectLayerIntensity(com.autoedit.model.EffectLayer l) {
        float v = l.intensity;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Multiplicative alpha the stack asks for (FADE). 1 when nothing fades. */
    public float stackAlphaScale(java.util.List<com.autoedit.model.EffectLayer> layers) {
        float a = 1f;
        if (layers == null) return 1f;
        for (com.autoedit.model.EffectLayer l : layers)
            if (l.type == EffectType.FADE) a *= (1f - 0.35f * EffectLayerIntensity(l));
        return a < 0f ? 0f : a;
    }

    // -------------------------------------------------------------- post pass

    /** Backwards-compatible overload. */
    public void drawPost(Canvas canvas, int w, int h, EffectType t, float intensity) {
        drawPost(canvas, w, h, t, intensity, 0f);
    }

    /**
     * Paints the spatial part of an effect over the whole frame. {@code timeSec}
     * drives the animated effects (flicker, dust, particles) so preview and
     * export animate identically from the same timeline clock.
     */
    public void drawPost(Canvas canvas, int w, int h, EffectType t, float intensity, float timeSec) {
        if (t == null) return;
        float i = clamp01(intensity);
        if (i <= 0.001f) return;
        postPaint.reset();
        postPaint.setAntiAlias(true);
        switch (t) {
            case VIGNETTE: {
                float r = Math.max(w, h) * 0.75f;
                postPaint.setShader(new RadialGradient(w / 2f, h / 2f, r,
                        new int[]{0x00000000, ((int) (180 * i) << 24)},
                        new float[]{0.55f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, postPaint);
                postPaint.setShader(null);
                break;
            }
            case GLOW:
            case SOFT_GLOW:
            case BLOOM:
            case CINEMATIC_GLOW:
            case DREAM_GLOW:
            case HIGHLIGHT_GLOW: {
                int base;
                switch (t) {
                    case BLOOM:          base = 0x00ffd9a0; break;
                    case CINEMATIC_GLOW: base = 0x00ffe8c0; break;
                    case DREAM_GLOW:     base = 0x00ffd0e8; break;
                    case HIGHLIGHT_GLOW: base = 0x00fffbe8; break;
                    default:             base = 0x00fff4d6; break;
                }
                postPaint.setColor(base | ((int) (90 * i) << 24));
                canvas.drawRect(0, 0, w, h, postPaint);
                break;
            }
            case LIGHT_LEAK: {
                postPaint.setShader(new LinearGradient(0, 0, w, h * 0.6f,
                        new int[]{((int) (150 * i) << 24) | 0x00ffb45a, 0x00000000},
                        new float[]{0f, 0.75f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, postPaint);
                postPaint.setShader(null);
                break;
            }
            case LENS_FLARE: {
                float cx = w * 0.68f, cy = h * 0.28f, r = Math.min(w, h) * 0.42f;
                postPaint.setShader(new RadialGradient(cx, cy, r,
                        new int[]{((int) (200 * i) << 24) | 0x00fff6d0,
                                  ((int) (70 * i) << 24) | 0x00ffd08a, 0x00000000},
                        new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, postPaint);
                postPaint.setShader(null);
                break;
            }
            case FILM_GRAIN:
            case SUBTLE_NOISE:
            case DUST: {
                int step = Math.max(3, w / 240);
                int alpha = t == EffectType.SUBTLE_NOISE ? (int) (34 * i) : (int) (60 * i);
                postPaint.setColor(0x00ffffff | (alpha << 24));
                // Deterministic pseudo-random from the frame clock so preview
                // and export produce the same grain for the same timestamp.
                long seed = (long) (timeSec * 24f);
                int n = (w / (step * 2)) * (h / (step * 2));
                for (int q = 0; q < n; q++) {
                    long r1 = (q * 2654435761L + seed * 40503L) & 0x7fffffffL;
                    int x = (int) ((r1 % 997) * (w / 997.0));
                    int y = (int) (((r1 / 997) % 991) * (h / 991.0));
                    canvas.drawRect(x, y, x + step, y + 1, postPaint);
                }
                break;
            }
            case PARTICLES: {
                int count = Math.max(8, (int) (26 * i));
                postPaint.setColor(((int) (120 * i) << 24) | 0x00cfe8ff);
                for (int q = 0; q < count; q++) {
                    float ph = (q * 0.6180339887f) % 1f;
                    float drift = (timeSec * (0.05f + ph * 0.07f)) % 1f;
                    float px = ((q * 0.381966f) % 1f) * w;
                    float py = ((ph + drift) % 1f) * h;
                    float rad = Math.max(1f, w / 340f) * (0.6f + ph);
                    canvas.drawCircle(px, py, rad, postPaint);
                }
                break;
            }
            case FILM_FLICKER: {
                // Deterministic brightness wobble at ~6 Hz, subtle by design.
                float f = (float) (Math.sin(timeSec * 37.7f) * 0.5f + Math.sin(timeSec * 13.3f) * 0.5f);
                int a = (int) (Math.abs(f) * 22f * i);
                postPaint.setColor(f > 0 ? (a << 24 | 0x00ffffff) : (a << 24));
                canvas.drawRect(0, 0, w, h, postPaint);
                break;
            }
            default:
                break;
        }
    }

    // --------------------------------------------------------- spatial helpers

    /**
     * Softening-halo strength for blur-like effects, 0..1. The caller draws one
     * extra low-alpha, slightly enlarged copy of the frame to fake a real blur
     * without a RenderScript/RenderEffect dependency (which is not available on
     * minSdk 26 for offscreen bitmaps).
     */
    public float blurStrengthFor(EffectType t, float intensity) {
        float i = clamp01(intensity);
        if (t == null) return 0f;
        switch (t) {
            case BLUR:             return 0.55f * i;
            case GAUSSIAN_BLUR:    return 0.85f * i;
            case SOFT_FOCUS:       return 0.35f * i;
            case DREAM:            return 0.35f * i;
            case DREAM_GLOW:       return 0.30f * i;
            case MOTION_BLUR:      return 0.50f * i;
            case DIRECTIONAL_BLUR: return 0.55f * i;
            default:               return 0f;
        }
    }

    /**
     * Horizontal/vertical sample offset in canvas fractions for the channel
     * split effects, or 0 when the effect does not split channels.
     */
    public float channelShift(EffectType t, float intensity) {
        float i = clamp01(intensity);
        switch (t == null ? EffectType.NONE : t) {
            case CHROMATIC_ABERRATION: return 0.006f * i;
            case RGB_SHIFT:            return 0.012f * i;
            default:                   return 0f;
        }
    }

    /** Per-channel colour filter for the split passes of a channel effect. */
    public ColorMatrixColorFilter channelFilter(EffectType t, int channel) {
        float[] m = new float[20];
        m[18] = 1f;
        switch (channel) {
            case 0: m[0] = 1f; break;
            case 1: m[6] = 1f; break;
            default: m[12] = 1f; break;
        }
        return new ColorMatrixColorFilter(new ColorMatrix(m));
    }

    /** Draws a directionally smeared copy of the frame (motion/directional blur). */
    public void drawDirectionalSmear(Canvas canvas, Bitmap src, RectF dst, float rotation,
                                     float px, float py, float strength, float alpha) {
        if (src == null || src.isRecycled() || strength <= 0.01f || alpha <= 0.01f) return;
        fillPaint.reset();
        fillPaint.setAntiAlias(true);
        fillPaint.setFilterBitmap(true);
        fillPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.LIGHTEN));
        int steps = 4;
        for (int s = 1; s <= steps; s++) {
            float f = s / (float) steps;
            fillPaint.setAlpha((int) (48 * strength * alpha * (1f - f * 0.6f)));
            RectF r = new RectF(dst);
            r.offset(px * f, py * f);
            canvas.save();
            canvas.rotate(rotation, dst.centerX(), dst.centerY());
            canvas.drawBitmap(src, null, r, fillPaint);
            canvas.restore();
        }
        fillPaint.setXfermode(null);
    }

    public static EffectType[] all() { return EffectType.values(); }

    public static String label(EffectType t) {
        if (t == null) return "None";
        switch (t) {
            case NONE: return "None";
            case BLACK_WHITE: return "B&W";
            case MOTION_BLUR: return "Motion Blur";
            case GAUSSIAN_BLUR: return "Gaussian Blur";
            case DIRECTIONAL_BLUR: return "Directional Blur";
            case SOFT_FOCUS: return "Soft Focus";
            case SOFT_GLOW: return "Soft Glow";
            case CINEMATIC_GLOW: return "Cinematic Glow";
            case DREAM_GLOW: return "Dream Glow";
            case HIGHLIGHT_GLOW: return "Highlight Glow";
            case FILM_GRAIN: return "Film Grain";
            case FILM_FLICKER: return "Film Flicker";
            case COLOR_BOOST: return "Color Boost";
            case LIGHT_LEAK: return "Light Leak";
            case LENS_FLARE: return "Lens Flare";
            case SUBTLE_NOISE: return "Subtle Noise";
            case CHROMATIC_ABERRATION: return "Chromatic Aberration";
            case RGB_SHIFT: return "RGB Shift";
            case CINEMATIC_SHADOWS: return "Cinematic Shadows";
            default: {
                String s = t.name().toLowerCase().replace('_', ' ');
                return Character.toUpperCase(s.charAt(0)) + s.substring(1);
            }
        }
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
