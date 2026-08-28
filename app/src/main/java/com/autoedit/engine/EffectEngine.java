package com.autoedit.engine;

import android.graphics.*;
import com.autoedit.model.EffectType;
import java.util.HashMap;
import java.util.Map;

/**
 * Effect rendering shared by preview and export. Color effects are a cached
 * ColorMatrixColorFilter keyed by (type, intensity); spatial/temporal effects
 * (vignette, glow, grain) are drawn by drawPost; blur is handled by the caller.
 */
public class EffectEngine {

    private final Map<Integer, Paint> paintCache = new HashMap<>();
    private final Paint postPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Paint paintFor(EffectType type, float intensity) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (type == null || type == EffectType.NONE) return p;
        float i = clamp01(intensity);
        int key = type.ordinal() * 101 + Math.round(i * 100);
        Paint cached = paintCache.get(key);
        if (cached != null) { p.setColorFilter(cached.getColorFilter()); return p; }

        ColorMatrix cm = matrixFor(type, i);
        if (cm != null) {
            ColorMatrixColorFilter cf = new ColorMatrixColorFilter(cm);
            p.setColorFilter(cf);
            Paint store = new Paint(); store.setColorFilter(cf);
            paintCache.put(key, store);
        } else if (type == EffectType.FADE) {
            p.setAlpha((int) (255 * (1f - 0.35f * i)));
        }
        return p;
    }

    private ColorMatrix matrixFor(EffectType t, float i) {
        float k;
        switch (t) {
            case BRIGHTNESS: {
                k = i * 80f;
                return new ColorMatrix(new float[]{
                        1,0,0,0,k,  0,1,0,0,k,  0,0,1,0,k,  0,0,0,1,0});
            }
            case CONTRAST: {
                float s = 1f + i * 0.8f;
                float off = 128f * (1f - s);
                return new ColorMatrix(new float[]{
                        s,0,0,0,off,  0,s,0,0,off,  0,0,s,0,off,  0,0,0,1,0});
            }
            case SATURATION: {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(1f + i * 1.6f);
                return cm;
            }
            case EXPOSURE: {
                float e = 1f + i * 0.7f;
                ColorMatrix cm = new ColorMatrix();
                cm.setScale(e, e, e, 1f);
                return cm;
            }
            case BLACK_WHITE: {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0f);
                return cm;
            }
            case SEPIA: {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(1f - 0.6f * i);
                cm.postConcat(new ColorMatrix(new float[]{
                        .393f,.769f,.189f,0,0,  .349f,.686f,.168f,0,0,
                        .272f,.534f,.131f,0,0,  0,0,0,1,0}));
                return cm;
            }
            case CINEMATIC: {
                ColorMatrix cm = new ColorMatrix(new float[]{
                        1.05f,0,0,0,8,  0,1.02f,0,0,4,  0,0,0.92f,0,6,  0,0,0,1,0});
                cm.setSaturation(1f - 0.18f * i);
                return cm;
            }
            case VINTAGE: {
                return new ColorMatrix(new float[]{
                        0.9f,0.05f,0,0,18,  0,0.85f,0.05f,0,14,  0,0.05f,0.8f,0,10,  0,0,0,1,0});
            }
            case FILM: {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(1f - 0.25f * i);
                cm.postConcat(new ColorMatrix(new float[]{
                        1.04f,0,0,0,6,  0,1.0f,0,0,6,  0,0,0.96f,0,10,  0,0,0,1,0}));
                return cm;
            }
            case DREAM: {
                return new ColorMatrix(new float[]{
                        1.05f,0.08f,0.08f,0,10,  0.05f,1.05f,0.1f,0,8,  0.05f,0.1f,1.08f,0,12,  0,0,0,1,0});
            }
            case TEMPERATURE: {
                return new ColorMatrix(new float[]{
                        1f+0.12f*i,0,0,0, 10f*i,  0,1f,0,0, 4f*i,  0,0,1f-0.1f*i,0, -6f*i,  0,0,0,1,0});
            }
            case HIGHLIGHTS: {
                k = i * 30f;
                return new ColorMatrix(new float[]{
                        1,0,0,0,k,  0,1,0,0,k,  0,0,1,0,k*0.6f,  0,0,0,1,0});
            }
            case SHADOWS: {
                float s = 1f + i * 0.25f;
                return new ColorMatrix(new float[]{
                        s,0,0,0,0,  0,s,0,0,0,  0,0,s,0,0,  0,0,0,1,0});
            }
            case SHARPEN:
                return new ColorMatrix(new float[]{
                        1.08f,0,0,0,-8,  0,1.08f,0,0,-8,  0,0,1.08f,0,-8,  0,0,0,1,0});
            default:
                return null;
        }
    }

    public void drawPost(Canvas canvas, int w, int h, EffectType t, float intensity) {
        if (t == null) return;
        float i = clamp01(intensity);
        switch (t) {
            case VIGNETTE: {
                postPaint.setColorFilter(null);
                float cx = w / 2f, cy = h / 2f;
                float r = Math.max(w, h) * 0.75f;
                RadialGradient g = new RadialGradient(cx, cy, r,
                        new int[]{0x00000000, (int) (180 * i) << 24},
                        new float[]{0.55f, 1f}, Shader.TileMode.CLAMP);
                postPaint.setShader(g);
                canvas.drawRect(0, 0, w, h, postPaint);
                postPaint.setShader(null);
                break;
            }
            case GLOW:
            case SOFT_GLOW:
            case BLOOM: {
                postPaint.setColorFilter(null);
                postPaint.setColor(t == EffectType.BLOOM ? 0x55ffd9a0 : 0x33fff4d6);
                postPaint.setAlpha((int) (90 * i));
                canvas.drawRect(0, 0, w, h, postPaint);
                postPaint.setAlpha(255);
                break;
            }
            case FILM_GRAIN: {
                postPaint.setColorFilter(null);
                postPaint.setColor(0x22ffffff & 0x00ffffff | ((int) (60 * i) << 24));
                int step = Math.max(3, w / 240);
                for (int y = 0; y < h; y += step * 2)
                    for (int x = 0; x < w; x += step * 2)
                        canvas.drawRect(x, y, x + step, y + 1, postPaint);
                break;
            }
            default:
                break;
        }
    }

    public static EffectType[] all() { return EffectType.values(); }

    public static String label(EffectType t) {
        switch (t) {
            case NONE: return "None";
            case BLACK_WHITE: return "B&W";
            case MOTION_BLUR: return "Motion Blur";
            case SOFT_FOCUS: return "Soft Focus";
            case SOFT_GLOW: return "Soft Glow";
            case FILM_GRAIN: return "Film Grain";
            default: {
                String s = t.name().toLowerCase().replace('_', ' ');
                return Character.toUpperCase(s.charAt(0)) + s.substring(1);
            }
        }
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
