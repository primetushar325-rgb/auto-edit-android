package com.autoedit.engine;

import android.graphics.*;
import com.autoedit.model.BorderEffectConfig;

/**
 * Procedural border/frame renderer — every preset is REAL.
 * Uses Canvas/Gradient/blur/animated paths — no heavy deps, offline.
 * Called by FrameComposer (preview==export) and by PreviewView for live temp preview.
 *
 * Performance: no bitmap allocation per frame, paints created locally but small.
 * Canvas-aligned: draws in canvas space 0,0,w,h (not motion-transformed).
 * Order: Background → Image/Motion → Filters → Border → Text/Overlay (spec § Layers).
 */
public class BorderRenderer {

    private static float clamp01(float v){ return v<0?0:(v>1?1:v); }

    /** Entry — draws border if config active at localSec (or whole clip if isWholeClip). */
    public static void draw(Canvas canvas, int w, int h, float timeSec, BorderEffectConfig cfg, float clipLocalSec, float clipDuration) {
        if (canvas==null || w<=0 || h<=0 || cfg==null || cfg.presetId==null) return;
        BorderEffectRegistry.Preset preset = BorderEffectRegistry.byId(cfg.presetId);
        if (preset==null) return;
        if (!cfg.isActiveAt(clipLocalSec, clipDuration)) return;
        float intensity = clamp01(cfg.intensity);
        float opacity = clamp01(cfg.opacity);
        if (intensity<=0.01f || opacity<=0.01f) return;
        int prim = cfg.primaryColor !=0 ? cfg.primaryColor : preset.primaryColor;
        int sec  = cfg.secondaryColor!=0 ? cfg.secondaryColor : preset.secondaryColor;
        float thicknessPx = Math.max(2f, cfg.thickness * w / 360f * intensity); // scale with canvas
        if (thicknessPx<1.5f) thicknessPx=1.5f;
        float glowPx = cfg.glow * w / 360f;
        float speedEff = clamp01(cfg.speed);
        int dir = cfg.direction;
        float cornerPx = cfg.cornerRadius * w / 360f;

        // alpha from opacity*intensity
        int alpha = (int)(255*opacity* (0.7f+0.3f*intensity));
        alpha = Math.max(0, Math.min(255, alpha));

        // Dispatch by preset id / category
        String id = preset.id;
        String cat = preset.category;
        float t = timeSec * (0.5f + speedEff*1.5f);

        if (id.equals("cinematic_black") || id.equals("cinematic_scope")) {
            drawCinematicBars(canvas,w,h,thicknessPx,alpha,prim, id.equals("cinematic_scope"));
            return;
        }
        if (cat.equals("Cinematic")) {
            drawCinematicFrame(canvas,w,h,thicknessPx,glowPx,alpha,prim,sec,cornerPx);
            return;
        }
        if (cat.equals("Electric")) {
            drawElectric(canvas,w,h,thicknessPx,glowPx,alpha,prim,sec,t,dir);
            return;
        }
        if (id.equals("special_vignette")) { drawVignette(canvas,w,h,alpha, intensity); return; }
        if (id.equals("special_scanline")) { drawScanline(canvas,w,h,thicknessPx,alpha,prim, t); return; }
        if (id.equals("special_starfield")||id.equals("special_stars")) { drawStars(canvas,w,h,thicknessPx,alpha,prim, t); return; }
        if (cat.equals("Flow") || id.startsWith("flow_")) {
            drawFlowSweep(canvas,w,h,thicknessPx,glowPx,alpha,prim,sec,t,dir, id.equals("flow_rainbow"));
            return;
        }
        if (cat.equals("Glow")) {
            drawGlowPulse(canvas,w,h,thicknessPx,glowPx,alpha,prim,sec,t,speedEff, id);
            return;
        }
        if (cat.equals("Neon") || cat.equals("Dual")) {
            // Dual and Neon share sweep logic but with different colors
            drawNeonSweep(canvas,w,h,thicknessPx,glowPx,alpha,prim,sec,t,dir, id, cornerPx);
            return;
        }
        if (cat.equals("Special")) {
            drawSpecial(canvas,w,h,thicknessPx,glowPx,alpha,prim,sec,t,dir,id,cornerPx);
            return;
        }
        // fallback neon
        drawNeonSweep(canvas,w,h,thicknessPx,glowPx,alpha,prim,sec,t,dir,id,cornerPx);
    }

    /** Overload for preview temp — uses clipLocal =0 and duration large so isActive true */
    public static void draw(Canvas canvas, int w, int h, float timeSec, BorderEffectConfig cfg){
        draw(canvas,w,h,timeSec,cfg,0f, 999f);
    }

    // ──────────────────── helpers ────────────────────

    private static void drawCinematicBars(Canvas c, int w,int h,float thick,int alpha,int color, boolean scope){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setAlpha(alpha);
        p.setStyle(Paint.Style.FILL);
        float barH = scope ? h*0.14f : h*0.08f;
        c.drawRect(0,0,w,barH,p);
        c.drawRect(0,h-barH,w,h,p);
        // subtle inner line
        p.setColor(0x22FFFFFF); p.setAlpha((int)(alpha*0.15f));
        c.drawRect(0,barH-1,w,barH,p);
        c.drawRect(0,h-barH,w,h-barH+1,p);
    }

    private static void drawCinematicFrame(Canvas canvas,int w,int h,float thick,float glow,int alpha,int prim,int sec,float corner){
        RectF r=new RectF(thick/2,thick/2,w-thick/2,h-thick/2);
        // glow
        if(glow>1){
            Paint gp=new Paint(Paint.ANTI_ALIAS_FLAG);
            gp.setStyle(Paint.Style.STROKE);
            gp.setStrokeWidth(thick+glow*2);
            gp.setColor(prim); gp.setAlpha((int)(alpha*0.18f));
            gp.setStrokeCap(Paint.Cap.ROUND); gp.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawRoundRect(r,corner,corner,gp);
        }
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(thick);
        p.setColor(prim); p.setAlpha(alpha);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawRoundRect(r,corner,corner,p);
        // inner highlight line for bevel-like
        Paint inner=new Paint(Paint.ANTI_ALIAS_FLAG);
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(Math.max(1, thick*0.25f));
        inner.setColor(0x55FFFFFF); inner.setAlpha((int)(alpha*0.35f));
        RectF r2=new RectF(thick+2, thick+2, w-thick-2, h-thick-2);
        canvas.drawRoundRect(r2, Math.max(0,corner-4), Math.max(0,corner-4), inner);
    }

    private static void drawGlowPulse(Canvas canvas,int w,int h,float thick,float glow,int alpha,int prim,int sec,float t,float speed, String id){
        float pulse = 0.65f + 0.35f * (float)Math.sin(t*2.2f);
        float t2 = 0.55f + 0.45f * (float)Math.sin(t*1.6f + 1.1f);
        RectF r=new RectF(thick/2+2, thick/2+2, w-thick/2-2, h-thick/2-2);
        // outer halo
        Paint halo=new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(thick + glow*2.2f * pulse);
        halo.setColor(prim); halo.setAlpha((int)(alpha*0.22f*pulse));
        halo.setStrokeJoin(Paint.Join.ROUND); halo.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawRoundRect(r, 18,18, halo);
        // main glow
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(thick*1.1f);
        // For rainbow pulse, sweep
        if(id.equals("glow_rainbow_pulse")){
            int[] cols=new int[]{0xFFFF0080,0xFFFFD700,0xFF00FF88,0xFF00D4FF,0xFF9D00FF,0xFFFF0080};
            SweepGradient sg=new SweepGradient(w/2f,w/2f, cols, null);
            Matrix m=new Matrix(); m.setRotate((t*40)%360, w/2f, h/2f); sg.setLocalMatrix(m);
            p.setShader(sg);
            p.setAlpha((int)(alpha*t2));
        } else {
            p.setColor(prim); p.setAlpha((int)(alpha*0.95f));
        }
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawRoundRect(r, 18,18, p);
        // inner soft fill glow
        if(glow>4){
            Paint soft=new Paint(Paint.ANTI_ALIAS_FLAG);
            soft.setStyle(Paint.Style.FILL);
            soft.setColor(prim); soft.setAlpha((int)(alpha*0.06f*pulse));
            RectF inner=new RectF(thick+6, thick+6, w-thick-6, h-thick-6);
            canvas.drawRoundRect(inner, 14,14, soft);
        }
    }

    private static void drawFlowSweep(Canvas canvas,int w,int h,float thick,float glow,int alpha,int prim,int sec,float t,int dir, boolean rainbow){
        RectF r=new RectF(thick/2,thick/2,w-thick/2,h-thick/2);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(thick);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        int[] cols;
        if(rainbow){
            cols=new int[]{0xFFFF0000,0xFFFFA500,0xFFFFFF00,0xFF00FF00,0xFF00FFFF,0xFF0000FF,0xFFFF00FF,0xFFFF0000};
        } else if(prim==0xFFFF4500 || sec==0xFFFFD700){
            cols=new int[]{prim, sec, prim};
        } else {
            cols=new int[]{prim, sec, prim};
        }
        SweepGradient sg=new SweepGradient(w/2f,h/2f, cols, null);
        Matrix m=new Matrix();
        float rot=(dir==1? -t*55 : t*55)%360;
        m.setRotate(rot, w/2f, h/2f); sg.setLocalMatrix(m);
        p.setShader(sg);
        p.setAlpha(alpha);
        // glow under
        if(glow>1){
            Paint g=new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setStyle(Paint.Style.STROKE);
            g.setStrokeWidth(thick+glow*1.5f);
            g.setColor(prim); g.setAlpha((int)(alpha*0.18f));
            g.setStrokeCap(Paint.Cap.ROUND); g.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawRoundRect(r, 16,16, g);
        }
        canvas.drawRoundRect(r, 16,16, p);
    }

    private static void drawNeonSweep(Canvas canvas,int w,int h,float thick,float glow,int alpha,int prim,int sec,float t,int dir,String id,float corner){
        RectF r=new RectF(thick/2+1, thick/2+1, w-thick/2-1, h-thick/2-1);
        float rot=(dir==1? -t*70 : t*70)%360;
        // For neon_flow use dual sweep blue→green continuously rotating
        int[] cols;
        if(id.equals("neon_flow")){
            cols=new int[]{0xFF00D4FF, 0xFF00FF88, 0xFF00D4FF};
        } else if(id.equals("dual_sunset")||id.equals("dual_ocean")||id.startsWith("dual_")){
            cols=new int[]{prim, sec, prim};
            rot = (dir==1? -t*45 : t*45)%360;
        } else {
            // standard neon: primary with slight secondary tint, sweeping highlight
            cols=new int[]{prim, sec!=0? sec: 0xFFFFFFFF, prim};
        }
        // glow underlay
        Paint glowP=new Paint(Paint.ANTI_ALIAS_FLAG);
        glowP.setStyle(Paint.Style.STROKE);
        glowP.setStrokeWidth(thick+glow*2f);
        glowP.setColor(prim); glowP.setAlpha((int)(alpha*0.20f));
        glowP.setStrokeCap(Paint.Cap.ROUND); glowP.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawRoundRect(r, corner, corner, glowP);
        // main sweep stroke
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(thick);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        SweepGradient sg=new SweepGradient(w/2f,h/2f, cols, null);
        Matrix m=new Matrix(); m.setRotate(rot, w/2f, h/2f); sg.setLocalMatrix(m);
        p.setShader(sg);
        p.setAlpha(alpha);
        canvas.drawRoundRect(r, corner, corner, p);
        // traveling highlight dot — white glint racing around
        float perim=2*(w+h);
        float dist=(t*300 + (dir==1? -t*100:0)) % perim;
        float hx=0,hy=0;
        if(dist < w){ hx=dist; hy=0; }
        else if(dist < w+h){ hx=w; hy=dist-w; }
        else if(dist < 2*w+h){ hx=w-(dist-(w+h)); hy=h; }
        else { hx=0; hy=h-(dist-(2*w+h)); }
        Paint dot=new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFFFFFFF); dot.setAlpha((int)(alpha*0.95f));
        float rad=thick*1.1f;
        // clamp inside corners
        hx=Math.max(thick, Math.min(w-thick, hx));
        hy=Math.max(thick, Math.min(h-thick, hy));
        canvas.drawCircle(hx,hy, rad, dot);
        Paint dotGlow=new Paint(Paint.ANTI_ALIAS_FLAG);
        dotGlow.setColor(prim); dotGlow.setAlpha((int)(alpha*0.35f));
        canvas.drawCircle(hx,hy, rad*2.2f, dotGlow);
    }

    private static void drawElectric(Canvas canvas,int w,int h,float thick,float glow,int alpha,int prim,int sec,float t,int dir){
        // jagged electric frame — polyline with per-segment jitter + flicker
        float inset=thick/2+2;
        Path path=new Path();
        float flicker=0.70f+0.30f*(float)Math.sin(t*14f);
        float jitter = thick*0.9f;
        int segments=28;
        // top edge
        path.moveTo(inset, inset);
        for(int i=0;i<segments;i++){
            float x=inset + (w-2*inset)*i/segments;
            float y=inset + ((i%2==0)? -jitter*0.6f : jitter*0.6f)*(float)Math.sin(t*8 + i*0.7f);
            if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
        }
        // right edge
        for(int i=0;i<segments;i++){
            float x=w-inset + ((i%2==0)? -jitter*0.6f : jitter*0.6f)*(float)Math.cos(t*9 + i);
            float y=inset + (h-2*inset)*i/segments;
            path.lineTo(x,y);
        }
        // bottom
        for(int i=segments;i>=0;i--){
            float x=inset + (w-2*inset)*i/segments;
            float y=h-inset + ((i%2==0)? -jitter*0.6f : jitter*0.6f)*(float)Math.sin(t*7 + i*1.1f);
            path.lineTo(x,y);
        }
        for(int i=segments;i>=0;i--){
            float x=inset + ((i%2==0)? -jitter*0.6f : jitter*0.6f)*(float)Math.cos(t*8 + i*0.9f);
            float y=inset + (h-2*inset)*i/segments;
            path.lineTo(x,y);
        }
        path.close();
        // glow
        Paint gp=new Paint(Paint.ANTI_ALIAS_FLAG);
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(thick+glow);
        gp.setColor(prim); gp.setAlpha((int)(alpha*0.18f*flicker));
        gp.setStrokeCap(Paint.Cap.ROUND); gp.setStrokeJoin(Paint.Join.ROUND);
        gp.setPathEffect(new CornerPathEffect(6));
        canvas.drawPath(path,gp);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(thick*0.85f);
        p.setColor(prim); p.setAlpha((int)(alpha*flicker));
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        p.setPathEffect(new CornerPathEffect(4));
        canvas.drawPath(path,p);
        // bright core thin
        Paint core=new Paint(Paint.ANTI_ALIAS_FLAG);
        core.setStyle(Paint.Style.STROKE);
        core.setStrokeWidth(Math.max(1, thick*0.35f));
        core.setColor(0xFFFFFFFF); core.setAlpha((int)(alpha*0.75f*flicker));
        canvas.drawPath(path,core);
        // occasional flash overlay
        if(Math.sin(t*3.7f)>0.85f){
            Paint flash=new Paint();
            flash.setColor(prim); flash.setAlpha((int)(alpha*0.06f));
            canvas.drawRect(0,0,w,h, flash);
        }
    }

    private static void drawVignette(Canvas canvas,int w,int h,int alpha,float intensity){
        // radial vignette darkening edges
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        RadialGradient rg=new RadialGradient(w/2f,h/2f, Math.max(w,h)*0.75f,
                new int[]{0x00000000, 0x90000000}, new float[]{0.55f,1f}, Shader.TileMode.CLAMP);
        p.setShader(rg);
        p.setAlpha((int)(alpha*0.85f*clamp01(intensity)));
        canvas.drawRect(0,0,w,h,p);
        // subtle inner border
        Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(w*0.018f);
        border.setColor(0xFF000000); border.setAlpha((int)(alpha*0.5f));
        canvas.drawRect(w*0.015f, h*0.015f, w*0.985f, h*0.985f, border);
    }

    private static void drawScanline(Canvas canvas,int w,int h,float thick,int alpha,int prim,float t){
        // CRT scanlines inside frame + border
        Paint bg=new Paint(); bg.setColor(0xFF001100); bg.setAlpha((int)(alpha*0.35f));
        canvas.drawRect(0,0,w,h,bg);
        Paint line=new Paint();
        line.setColor(prim); line.setAlpha((int)(alpha*0.35f));
        line.setStrokeWidth(1.5f);
        float offset=(t*60)% (thick*4+8);
        for(float y=offset; y<h; y+=6){
            canvas.drawLine(0,y,w,y, line);
        }
        // moving scan bar
        float barY=(t*120)%h;
        Paint bar=new Paint();
        bar.setColor(0xFF00FF88); bar.setAlpha((int)(alpha*0.18f));
        canvas.drawRect(0,barY,w,barY+18, bar);
        // border
        Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(thick);
        border.setColor(prim); border.setAlpha(alpha);
        canvas.drawRect(thick/2, thick/2, w-thick/2, h-thick/2, border);
    }

    private static void drawStars(Canvas canvas,int w,int h,float thick,int alpha,int prim,float t){
        // star speckles along border path
        RectF r=new RectF(thick/2, thick/2, w-thick/2, h-thick/2);
        Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(thick*0.7f);
        border.setColor(prim); border.setAlpha((int)(alpha*0.45f));
        canvas.drawRoundRect(r, 8,8, border);
        Paint star=new Paint(Paint.ANTI_ALIAS_FLAG);
        star.setColor(0xFFFFFFFF);
        star.setAlpha((int)(alpha));
        // deterministic pseudo-random stars along perimeter
        int count=18;
        float perim=2*(w+h);
        for(int i=0;i<count;i++){
            float frac=(i/(float)count + (t*0.03f)%1f) %1f;
            float dist=frac*perim;
            float sx=0,sy=0;
            if(dist<w){ sx=dist; sy=thick; }
            else if(dist<w+h){ sx=w-thick; sy=dist-w; }
            else if(dist<2*w+h){ sx=w-(dist-(w+h)); sy=h-thick; }
            else { sx=thick; sy=h-(dist-(2*w+h)); }
            float tw=(float)Math.sin(t*2 + i*1.3f)*0.4f+0.6f;
            float sz=thick*0.55f*tw;
            // 4-point star
            Path starPath=new Path();
            starPath.moveTo(sx, sy-sz);
            starPath.lineTo(sx+sz*0.3f, sy-sz*0.3f);
            starPath.lineTo(sx+sz, sy);
            starPath.lineTo(sx+sz*0.3f, sy+sz*0.3f);
            starPath.lineTo(sx, sy+sz);
            starPath.lineTo(sx-sz*0.3f, sy+sz*0.3f);
            starPath.lineTo(sx-sz, sy);
            starPath.lineTo(sx-sz*0.3f, sy-sz*0.3f);
            starPath.close();
            star.setAlpha((int)(alpha*tw));
            canvas.drawPath(starPath, star);
        }
    }

    private static void drawSpecial(Canvas canvas,int w,int h,float thick,float glow,int alpha,int prim,int sec,float t,int dir,String id,float corner){
        if(id.equals("special_neon_corners")){
            drawNeonCorners(canvas,w,h,thick,glow,alpha,prim,sec,t,dir);
            return;
        }
        if(id.equals("special_bevel")){
            drawBevel(canvas,w,h,thick,alpha,prim,sec);
            return;
        }
        if(id.equals("special_double_line")){
            RectF r=new RectF(thick/2, thick/2, w-thick/2, h-thick/2);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(thick*0.6f);
            p.setColor(prim); p.setAlpha(alpha);
            canvas.drawRoundRect(r, corner, corner, p);
            RectF r2=new RectF(thick*1.8f, thick*1.8f, w-thick*1.8f, h-thick*1.8f);
            Paint p2=new Paint(Paint.ANTI_ALIAS_FLAG);
            p2.setStyle(Paint.Style.STROKE); p2.setStrokeWidth(thick*0.45f);
            p2.setColor(sec!=0?sec:prim); p2.setAlpha((int)(alpha*0.85f));
            canvas.drawRoundRect(r2, Math.max(0, corner*0.7f), Math.max(0, corner*0.7f), p2);
            return;
        }
        // default fallback: simple frame
        drawCinematicFrame(canvas,w,h,thick,glow,alpha,prim,sec,corner);
    }

    private static void drawNeonCorners(Canvas canvas,int w,int h,float thick,float glow,int alpha,int prim,int sec,float t,int dir){
        float len=Math.min(w,h)*0.22f;
        float inset=thick+4;
        Paint glowP=new Paint(Paint.ANTI_ALIAS_FLAG);
        glowP.setStyle(Paint.Style.STROKE);
        glowP.setStrokeWidth(thick+glow);
        glowP.setColor(prim); glowP.setAlpha((int)(alpha*0.18f));
        glowP.setStrokeCap(Paint.Cap.ROUND);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(thick);
        p.setColor(prim); p.setAlpha(alpha);
        p.setStrokeCap(Paint.Cap.ROUND);
        float pulse=0.8f+0.2f*(float)Math.sin(t*3.5f);
        p.setAlpha((int)(alpha*pulse));
        glowP.setAlpha((int)(alpha*0.18f*pulse));
        // 4 corners - each L shape
        float[][] pts={
                {inset,inset, inset+len, inset, inset,inset+len},
                {w-inset,w-inset-len, w-inset,w-inset-len, w-inset,inset},
                {inset,inset, inset+len, inset, inset,inset+len}, // reused
        };
        // top-left
        Path tl=new Path(); tl.moveTo(inset+len, inset); tl.lineTo(inset, inset); tl.lineTo(inset, inset+len);
        Path tr=new Path(); tr.moveTo(w-inset-len, inset); tr.lineTo(w-inset, inset); tr.lineTo(w-inset, inset+len);
        Path bl=new Path(); bl.moveTo(inset, h-inset-len); bl.lineTo(inset, h-inset); bl.lineTo(inset+len, h-inset);
        Path br=new Path(); br.moveTo(w-inset, h-inset-len); br.lineTo(w-inset, h-inset); br.lineTo(w-inset-len, h-inset);
        for(Path path: new Path[]{tl,tr,bl,br}){
            canvas.drawPath(path, glowP);
            canvas.drawPath(path, p);
        }
        // subtle crosshair center flicker for neon feel
        if(Math.sin(t*2.1f)>0.6f){
            Paint flash=new Paint(); flash.setColor(prim); flash.setAlpha((int)(alpha*0.04f));
            canvas.drawRect(inset, inset, w-inset, h-inset, flash);
        }
    }

    private static void drawBevel(Canvas canvas,int w,int h,float thick,int alpha,int prim,int sec){
        // 3D bevel: outer highlight / inner shadow
        RectF outer=new RectF(0,0,w,h);
        Paint bg=new Paint(); bg.setColor(0xFF2A2A2A); bg.setAlpha((int)(alpha*0.55f));
        canvas.drawRect(outer, bg);
        RectF inner=new RectF(thick, thick, w-thick, h-thick);
        // highlight top/left
        Paint hl=new Paint(); hl.setColor(0x55FFFFFF); hl.setStyle(Paint.Style.FILL);
        Path hlPath=new Path();
        hlPath.moveTo(0,0); hlPath.lineTo(w,0); hlPath.lineTo(w-thick, thick); hlPath.lineTo(thick, thick); hlPath.lineTo(thick, h-thick); hlPath.lineTo(0,h); hlPath.close();
        canvas.drawPath(hlPath, hl);
        // shadow bottom/right
        Paint sh=new Paint(); sh.setColor(0x66000000);
        Path shPath=new Path();
        shPath.moveTo(w,0); shPath.lineTo(w,h); shPath.lineTo(0,h); shPath.lineTo(thick, h-thick); shPath.lineTo(w-thick, h-thick); shPath.lineTo(w-thick, thick); shPath.close();
        canvas.drawPath(shPath, sh);
        // inner border
        Paint frame=new Paint(Paint.ANTI_ALIAS_FLAG);
        frame.setStyle(Paint.Style.STROKE); frame.setStrokeWidth(2);
        frame.setColor(sec!=0? sec: prim); frame.setAlpha(alpha);
        canvas.drawRect(thick/2, thick/2, w-thick/2, h-thick/2, frame);
    }
}
