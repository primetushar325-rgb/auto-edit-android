package com.autoedit.engine;

import android.graphics.*;
import com.autoedit.model.EffectType;

public class EffectEngine {
    public Paint paintFor(EffectType type, float intensity) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        if (type == null || type == EffectType.NONE) return p;
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
            case FADE: p.setAlpha((int)(255*(1f-i*.45f))); return p;
            default: cm.setSaturation(type==EffectType.SOFT_FOCUS || type==EffectType.DREAM ? .82f : 1f); break;
        }
        p.setColorFilter(new ColorMatrixColorFilter(cm)); return p;
    }
    public void drawPost(Canvas canvas, int w, int h, EffectType type, float intensity) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); float i = Math.max(0, Math.min(1, intensity));
        if (type == EffectType.VIGNETTE || type == EffectType.CINEMATIC || type == EffectType.FILM || type == EffectType.VINTAGE) {
            RadialGradient g = new RadialGradient(w/2f,h/2f,Math.max(w,h)*.65f,new int[]{0x00000000,0x66000000},new float[]{.55f,1f},Shader.TileMode.CLAMP);
            p.setShader(g); p.setAlpha((int)(180*i)); canvas.drawRect(0,0,w,h,p); p.setShader(null);
        }
        if (type == EffectType.GLOW || type == EffectType.SOFT_GLOW || type == EffectType.DREAM) { p.setColor(0x2249A8FF); canvas.drawRect(0,0,w,h,p); }
        if (type == EffectType.FILM_GRAIN || type == EffectType.FILM) { p.setColor(0x18FFFFFF); for(int y=0;y<h;y+=6) canvas.drawLine(0,y,w,y,p); }
    }
}
