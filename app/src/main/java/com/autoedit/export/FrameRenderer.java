package com.autoedit.export;

import android.content.*;import android.graphics.*;import android.net.Uri;import java.io.*;
import com.autoedit.model.*;import com.autoedit.engine.*;

public class FrameRenderer {
    private final Context context; private final FormulaEngine formulas = new FormulaEngine(); private final EffectEngine effects = new EffectEngine();
    private Bitmap frameBitmap; private Canvas frameCanvas;
    public FrameRenderer(Context c){ context=c.getApplicationContext(); }
    public Bitmap renderAtTime(EditProject project, float timeSec, int width, int height) throws IOException {
        ensure(width,height); frameCanvas.drawColor(0xff020409);
        if(project.clips.isEmpty()) return frameBitmap;
        int ci=0; float start=0; TimelineClip clip=project.clips.get(0);
        for(int i=0;i<project.clips.size();i++){ TimelineClip c=project.clips.get(i); if(timeSec < start+c.durationSec || i==project.clips.size()-1){ ci=i; clip=c; break;} start+=c.durationSec; }
        float local=Math.max(0, timeSec-start); float progress=Math.min(1, local/Math.max(.001f,clip.durationSec));
        renderClip(clip, progress, width, height, 1f);
        float td=Math.min(clip.transitionDurationSec, clip.durationSec/2f);
        if(clip.transition!=TransitionType.NONE && ci<project.clips.size()-1 && td>0 && local>clip.durationSec-td){ float tp=(local-(clip.durationSec-td))/td; renderClip(project.clips.get(ci+1), 0f, width, height, tp); }
        drawTexts(project, timeSec, width, height); return frameBitmap;
    }
    public Bitmap render(EditProject project, TimelineClip clip, float progress, int width, int height) throws IOException { ensure(width,height); frameCanvas.drawColor(0xff020409); renderClip(clip,progress,width,height,1f); drawTexts(project, projectTimeBefore(project,clip)+progress*clip.durationSec,width,height); return frameBitmap; }
    private void ensure(int width,int height){ if(frameBitmap==null || frameBitmap.getWidth()!=width || frameBitmap.getHeight()!=height){ if(frameBitmap!=null) frameBitmap.recycle(); frameBitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); frameCanvas=new Canvas(frameBitmap); } }
    private void renderClip(TimelineClip clip, float progress, int width, int height, float alpha) throws IOException { Bitmap src = decodeForFrame(Uri.parse(clip.uri), width, height); if(src==null) throw new IOException("Invalid source image: "+clip.uri); KeyframeState st = formulas.stateAt(clip.formula, progress); RectF dst = computeFitFill(src.getWidth(),src.getHeight(),width,height,st); Paint p = effects.paintFor(clip.effect, clip.effectIntensity); p.setAlpha((int)(255*st.opacity*alpha)); frameCanvas.save(); frameCanvas.rotate(st.rotation, width/2f, height/2f); frameCanvas.drawBitmap(src,null,dst,p); frameCanvas.restore(); effects.drawPost(frameCanvas,width,height,clip.effect,clip.effectIntensity*alpha); src.recycle(); }
    private float projectTimeBefore(EditProject p, TimelineClip c){ float t=0; for(TimelineClip x:p.clips){ if(x==c) break; t+=x.durationSec;} return t; }
    private void drawTexts(EditProject p, float time, int w, int h){ Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextAlign(Paint.Align.CENTER); for(TextOverlay o:p.texts){ if(time<o.startSec || time>o.endSec) continue; paint.setColor(o.color); paint.setTextSize(o.size); paint.setFakeBoldText(o.bold); paint.setAlpha((int)(255*o.opacity)); frameCanvas.drawText(o.text,o.x*w,o.y*h,paint); } }
    private RectF computeFitFill(int sw,int sh,int w,int h,KeyframeState st){ float scale=Math.max(w/(float)sw,h/(float)sh)*st.scale; float dw=sw*scale, dh=sh*scale; float cx=w/2f+st.x*w, cy=h/2f+st.y*h; return new RectF(cx-dw/2f,cy-dh/2f,cx+dw/2f,cy+dh/2f); }
    private Bitmap decodeForFrame(Uri uri,int targetW,int targetH) throws IOException { BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true; try(InputStream is=context.getContentResolver().openInputStream(uri)){ BitmapFactory.decodeStream(is,null,bounds); } if(bounds.outWidth<=0||bounds.outHeight<=0) return null; BitmapFactory.Options opts=new BitmapFactory.Options(); opts.inPreferredConfig=Bitmap.Config.ARGB_8888; opts.inSampleSize=sample(bounds.outWidth,bounds.outHeight,targetW*2,targetH*2); try(InputStream is=context.getContentResolver().openInputStream(uri)){ return BitmapFactory.decodeStream(is,null,opts); } }
    private int sample(int w,int h,int tw,int th){ int s=1; while(w/(s*2)>=tw && h/(s*2)>=th) s*=2; return Math.max(1,s); }
    public void release(){ if(frameBitmap!=null){ frameBitmap.recycle(); frameBitmap=null; } }
}
