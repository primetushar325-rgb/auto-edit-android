package com.autoedit.export;

import android.content.*;import android.graphics.*;import android.util.LruCache;import java.io.*;
import com.autoedit.model.*;import com.autoedit.engine.*;

public class FrameRenderer {
    private final FormulaEngine formulas = new FormulaEngine();
    private final EffectEngine effects = new EffectEngine();
    private final TransitionEngine transitions = new TransitionEngine();
    private final DiskBitmapCache diskCache;
    private final LruCache<String, Bitmap> memoryCache;
    private Bitmap frameBitmap; private Canvas frameCanvas;

    public FrameRenderer(Context c){
        diskCache = new DiskBitmapCache(c.getApplicationContext());
        int maxKb = (int)Math.min(96 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 4);
        memoryCache = new LruCache<String, Bitmap>(maxKb){ @Override protected int sizeOf(String key, Bitmap value){ return value.getByteCount()/1024; } };
    }

    public Bitmap renderAtTime(EditProject project, float timeSec, int width, int height, FitMode fitMode) throws IOException {
        ensure(width,height); frameCanvas.drawColor(0xff020409);
        if(project.clips.isEmpty()) return frameBitmap;
        int ci=0; float start=0; TimelineClip clip=project.clips.get(0);
        for(int i=0;i<project.clips.size();i++){ TimelineClip c=project.clips.get(i); if(timeSec < start+c.durationSec || i==project.clips.size()-1){ ci=i; clip=c; break;} start+=c.durationSec; }
        float local=Math.max(0, timeSec-start); float progress=Math.min(1, local/Math.max(.001f,clip.durationSec));
        renderClip(clip, progress, width, height, fitMode, 1f, 1f, 0f, 0f);
        // multi-motion formula: crossfade at step boundaries (same math as the live preview)
        float sm=formulas.stepTransitionMix(clip.formula, local);
        if(sm>0f){
            TransitionType stT=formulas.stepTransitionAt(clip.formula, local);
            if(transitions.fadesThroughBackground(stT)){
                Paint bg=new Paint(); bg.setColor(0xff020409); bg.setAlpha((int)(255*sm));
                frameCanvas.drawRect(0,0,width,height,bg);
            }
            renderClipWithState(clip, formulas.nextStepStateAt(clip.formula, local), width, height, fitMode, sm, 0f, 0f);
        }
        float td=Math.min(clip.transitionDurationSec, clip.durationSec/2f);
        if(clip.transition!=TransitionType.NONE && ci<project.clips.size()-1 && td>0 && local>clip.durationSec-td){
            float tp=(local-(clip.durationSec-td))/td;
            // Same transition math as the live preview (TransitionEngine): what you see is what you get.
            TransitionEngine.Transform tr = transitions.incoming(clip.transition, tp);
            if(transitions.fadesThroughBackground(clip.transition)){
                Paint bg = new Paint(); bg.setColor(0xff020409); bg.setAlpha((int)(255*tr.alpha));
                frameCanvas.drawRect(0,0,width,height,bg);
            }
            renderClip(project.clips.get(ci+1), 0f, width, height, fitMode, tr.alpha, tr.scale, tr.dx, tr.dy);
        }
        drawTexts(project, timeSec, width, height); return frameBitmap;
    }

    public Bitmap render(EditProject project, TimelineClip clip, float progress, int width, int height) throws IOException {
        ensure(width,height); frameCanvas.drawColor(0xff020409); renderClip(clip,progress,width,height,project.fitMode,1f,1f,0f,0f); drawTexts(project, projectTimeBefore(project,clip)+progress*clip.durationSec,width,height); return frameBitmap;
    }

    private void ensure(int width,int height){ if(frameBitmap==null || frameBitmap.getWidth()!=width || frameBitmap.getHeight()!=height){ if(frameBitmap!=null) frameBitmap.recycle(); frameBitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); frameCanvas=new Canvas(frameBitmap); } }

    private void renderClip(TimelineClip clip, float progress, int width, int height, FitMode fitMode, float alpha, float exScale, float dx, float dy) throws IOException {
        Bitmap src = getBitmap(clip.uri, width, height); if(src==null) throw new IOException("Invalid source image: "+clip.uri);
        if(fitMode == FitMode.FIT && exScale == 1f) drawFitBackground(src, width, height, alpha);
        KeyframeState st = formulas.stateAt(clip.formula, progress);
        if(exScale != 1f) st.scale *= exScale;
        EffectType eff = effectiveEffect(clip, progress * clip.durationSec);
        float intensity = effectiveIntensity(clip, progress * clip.durationSec);
        RectF dst = fitMode == FitMode.FIT ? computeFit(src.getWidth(),src.getHeight(),width,height,st) : computeFill(src.getWidth(),src.getHeight(),width,height,st);
        if(dx != 0f || dy != 0f) dst.offset(dx*width, dy*height);
        Paint p = effects.paintFor(eff, intensity); p.setAlpha((int)(255*Math.max(0,Math.min(1,alpha))*st.opacity));
        frameCanvas.save(); frameCanvas.rotate(st.rotation, width/2f+dx*width, height/2f+dy*height); frameCanvas.drawBitmap(src,null,dst,p); frameCanvas.restore();
        effects.drawPost(frameCanvas,width,height,eff,intensity*alpha);
    }

    private void renderClipWithState(TimelineClip clip, KeyframeState st, int width, int height, FitMode fitMode, float alpha, float dx, float dy) throws IOException {
        Bitmap src = getBitmap(clip.uri, width, height); if(src==null) throw new IOException("Invalid source image: "+clip.uri);
        EffectType eff = clip.effect;
        RectF dst = fitMode == FitMode.FIT ? computeFit(src.getWidth(),src.getHeight(),width,height,st) : computeFill(src.getWidth(),src.getHeight(),width,height,st);
        if(dx != 0f || dy != 0f) dst.offset(dx*width, dy*height);
        Paint p = effects.paintFor(eff, clip.effectIntensity); p.setAlpha((int)(255*Math.max(0,Math.min(1,alpha))*st.opacity));
        frameCanvas.save(); frameCanvas.rotate(st.rotation, width/2f+dx*width, height/2f+dy*height); frameCanvas.drawBitmap(src,null,dst,p); frameCanvas.restore();
    }

    private EffectType effectiveEffect(TimelineClip clip, float tSec){
        EffectType e = formulas.effectAt(clip.formula, tSec);
        return e == null ? clip.effect : e;
    }
    private float effectiveIntensity(TimelineClip clip, float tSec){
        return formulas.stepEffectIntensity(clip.formula, tSec, clip.effectIntensity);
    }

    private Bitmap getBitmap(String uri, int w, int h) throws IOException {
        String key = uri + "_" + w + "x" + h;
        Bitmap b = memoryCache.get(key);
        if (b != null && !b.isRecycled()) return b;
        b = diskCache.decodeForRender(uri, w, h);
        if (b != null) memoryCache.put(key, b);
        return b;
    }

    private void drawFitBackground(Bitmap src, int w, int h, float alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); p.setAlpha((int)(120*alpha));
        RectF fill = computeFill(src.getWidth(), src.getHeight(), w, h, new KeyframeState(0,0,1.15f,0,1));
        frameCanvas.drawBitmap(src, null, fill, p);
        p.setColor(0xaa020409); p.setAlpha((int)(170*alpha)); frameCanvas.drawRect(0,0,w,h,p);
    }

    private float projectTimeBefore(EditProject p, TimelineClip c){ float t=0; for(TimelineClip x:p.clips){ if(x==c) break; t+=x.durationSec;} return t; }
    private void drawTexts(EditProject p, float time, int w, int h){ Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextAlign(Paint.Align.CENTER); for(TextOverlay o:p.texts){ if(time<o.startSec || time>o.endSec) continue; paint.setColor(o.color); paint.setTextSize(o.size); paint.setFakeBoldText(o.bold); paint.setAlpha((int)(255*o.opacity)); frameCanvas.drawText(o.text,o.x*w,o.y*h,paint); } }
    private RectF computeFill(int sw,int sh,int w,int h,KeyframeState st){ float scale=Math.max(w/(float)sw,h/(float)sh)*st.scale; return rect(sw,sh,w,h,st,scale); }
    private RectF computeFit(int sw,int sh,int w,int h,KeyframeState st){ float scale=Math.min(w/(float)sw,h/(float)sh)*st.scale; return rect(sw,sh,w,h,st,scale); }
    private RectF rect(int sw,int sh,int w,int h,KeyframeState st,float scale){ float dw=sw*scale, dh=sh*scale; float cx=w/2f+st.x*w, cy=h/2f+st.y*h; return new RectF(cx-dw/2f,cy-dh/2f,cx+dw/2f,cy+dh/2f); }
    public void release(){ if(frameBitmap!=null){ frameBitmap.recycle(); frameBitmap=null; } memoryCache.evictAll(); }
}
