package com.autoedit.engine;

import android.graphics.*;
import com.autoedit.model.EffectType;
import java.util.HashMap;

public class EffectEngine {
    // v1.0.7 perf: the ColorMatrix + ColorMatrixColorFilter are the expensive
    // part and are identical whenever (type, quantized intensity) repeats —
    // which is every frame during export. Cache them; Paints are still created
    // per call because callers mutate alpha on the returned Paint.
    private final HashMap<Integer, ColorMatrixColorFilter> filterCache = new HashMap<>();

    private int key(EffectType type, float intensity) {
        float i = Math.max(0, Math.min(1, intensity));
        return (type.ordinal() + 1) * 101 + Math.round(i * 100f);
    }

    public Paint paintFor(EffectType type, float intensity) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        if (type == null || type == EffectType.NONE) return p;
        if (type == EffectType.FADE) { float i = Math.max(0, Math.min(1, intensity)); p.setAlpha((int)(255*(1f-i*.45f))); return p; }
        ColorMatrixColorFilter f = filterCache.get(key(type, intensity));
        if (f == null) {
            ColorMatrix cm = new ColorMatrix();
            float i = Math.max(0, Math.min(1, intensity));
            switch (type) {
                case BLACK_WHITE: cm.setSaturation(0); break;
                case SEPIA: cm.set(new float[]{.393f,.769f,.189f,0,0,.349f,.686f,.168f,0,0,.272f,.534f,.131f,0,0,0,0,0,1,0}); break;
                case CINEMATIC: cm.set(new float[]{1.05f,0,0,0,-8,0,1.02f,0,0,3,0,0,1.18f,0,10,0,0,0,1,0}); break;
                case VINTAGE: cm.set(new float[]{1.08f,.04f,.02f,0,8,.02f,.95f,.02f,0,2,.04f,.03f,.82f,0,-4,0,0,0,1,0}); break;
                case BRIGHTNESS: cm.set(new float[]{1,0,0,0,70*i,0,1,0,0,70*i,0,0,1,0,70*i,0,0,0,1,0}); break;
                case CONTRAST: float c=1+i*.45f; float t=128*(1-c); cm.set(new float[]{c,0,0,0,t,0,c,0,0,t,0,0,c,0,t,0,0,0,1,0}); break;
                case SATURATION: cm.setSaturation(1f + i*.8f); break;
                default: cm.setSaturation(type==EffectType.SOFT_FOCUS || type==EffectType.DREAM ? .82f : 1f); break;
            }
            f = new ColorMatrixColorFilter(cm);
            filterCache.put(key(type, intensity), f);
        }
        p.setColorFilter(f); return p;
    }

    // v1.0.7 perf: drawPost allocates a Paint (+ RadialGradient for vignette
    // styles) every frame; dimensions are constant for the life of an export,
    // so cache per (type, w, h). The paint is only used transiently inside
    // drawPost (state is fully reset on each call), so reuse is safe.
    private static class Post { Paint paint; RadialGradient vignette; }
    private final HashMap<Integer, Post> postCache = new HashMap<>();

    private Post postFor(EffectType type, int w, int h) {
        int k = type.ordinal() * 10_000_000 + w * 1000 + h;
        Post pc = postCache.get(k);
        if (pc != null) return pc;
        pc = new Post();
        pc.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (type == EffectType.VIGNETTE || type == EffectType.CINEMATIC || type == EffectType.FILM || type == EffectType.VINTAGE) {
            pc.vignette = new RadialGradient(w/2f,h/2f,Math.max(w,h)*.65f,new int[]{0x00000000,0x66000000},new float[]{.55f,1f},Shader.TileMode.CLAMP);
        }
        postCache.put(k, pc);
        return pc;
    }

    public void drawPost(Canvas canvas, int w, int h, EffectType type, float intensity) {
        if (type == null) return;
        float i = Math.max(0, Math.min(1, intensity));
        Paint p = postFor(type, w, h).paint;
        if (type == EffectType.VIGNETTE || type == EffectType.CINEMATIC || type == EffectType.FILM || type == EffectType.VINTAGE) {
            p.setShader(postFor(type, w, h).vignette); p.setAlpha((int)(180*i)); canvas.drawRect(0,0,w,h,p); p.setShader(null);
        }
        if (type == EffectType.GLOW || type == EffectType.SOFT_GLOW || type == EffectType.DREAM) { p.setColor(0x2249A8FF); canvas.drawRect(0,0,w,h,p); }
        if (type == EffectType.FILM_GRAIN || type == EffectType.FILM) { p.setColor(0x18FFFFFF); for(int y=0;y<h;y+=6) canvas.drawLine(0,y,w,y,p); }
    }
}
