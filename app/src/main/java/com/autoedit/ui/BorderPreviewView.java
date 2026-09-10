package com.autoedit.ui;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import com.autoedit.engine.BorderEffectRegistry;
import com.autoedit.engine.BorderRenderer;
import com.autoedit.model.BorderEffectConfig;

/**
 * Live thumbnail for a border preset — animated 1.5s loop.
 * Uses the SAME BorderRenderer as the main preview/export (preview == export).
 * Lightweight, no bitmap, 30fps invalidates when attached.
 */
public class BorderPreviewView extends View {
    private BorderEffectRegistry.Preset preset;
    private BorderEffectConfig cfg;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long startMs = 0;
    private boolean attached = false;

    public BorderPreviewView(Context c) { super(c); init(); }
    public BorderPreviewView(Context c, AttributeSet a){ super(c,a); init(); }

    private void init(){
        setWillNotDraw(false);
        bgPaint.setColor(0xFF0F1724);
        textPaint.setColor(0x66FFFFFF);
        textPaint.setTextSize(10f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setPreset(BorderEffectRegistry.Preset p){
        this.preset = p;
        if(p!=null){
            cfg = new BorderEffectConfig(p.id);
            cfg.thickness = p.defaultThickness;
            cfg.glow = p.defaultGlow;
            cfg.speed = p.defaultSpeed;
            cfg.intensity = 1f; cfg.opacity = 1f;
            cfg.cornerRadius = 10f;
        } else cfg=null;
        startMs = System.currentTimeMillis();
        invalidate();
    }

    public void setConfig(BorderEffectConfig c){
        this.cfg = c == null ? null : c.copy();
        if(c!=null) preset = BorderEffectRegistry.byId(c.presetId);
        else preset=null;
        startMs = System.currentTimeMillis();
        invalidate();
    }

    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();
        attached=true; startMs=System.currentTimeMillis();
        postInvalidateDelayed(33);
    }
    @Override protected void onDetachedFromWindow(){
        attached=false; super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        int w=getWidth(), h=getHeight();
        if(w<=0||h<=0) return;
        // background
        float r=18f;
        RectF bg=new RectF(0,0,w,h);
        canvas.drawRoundRect(bg, r,r, bgPaint);
        // inner image placeholder gradient
        Paint img=new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient lg=new LinearGradient(0,0,w,h, 0xFF152033, 0xFF223047, Shader.TileMode.CLAMP);
        img.setShader(lg);
        RectF inner=new RectF(8,8,w-8,h-8);
        canvas.drawRoundRect(inner, 12,12, img);
        // border
        if(cfg!=null){
            float t=(System.currentTimeMillis()-startMs)/1000f;
            int save=canvas.save();
            // clip to preview bounds so border doesn't draw outside
            canvas.clipRect(0,0,w,h);
            BorderRenderer.draw(canvas, w,h, t, cfg, 0f, 999f);
            canvas.restoreToCount(save);
        }
        if(attached) postInvalidateDelayed(33);
    }
}
