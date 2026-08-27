package com.autoedit.export;

import android.content.*;import android.graphics.*;import android.net.Uri;import android.provider.OpenableColumns;import java.io.*;
import com.autoedit.model.*;import com.autoedit.engine.*;

public class FrameRenderer {
    private final Context context; private final FormulaEngine formulas = new FormulaEngine(); private final EffectEngine effects = new EffectEngine();
    private Bitmap frameBitmap; private Canvas frameCanvas;
    public FrameRenderer(Context c){ context=c.getApplicationContext(); }
    public Bitmap render(EditProject project, TimelineClip clip, float progress, int width, int height) throws IOException {
        if(frameBitmap==null || frameBitmap.getWidth()!=width || frameBitmap.getHeight()!=height){ if(frameBitmap!=null) frameBitmap.recycle(); frameBitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); frameCanvas=new Canvas(frameBitmap); }
        frameCanvas.drawColor(0xff020409);
        Bitmap src = decodeForFrame(Uri.parse(clip.uri), width, height);
        if(src==null) throw new IOException("Invalid source image: "+clip.uri);
        KeyframeState st = formulas.stateAt(clip.formula, progress);
        RectF dst = computeFitFill(src.getWidth(),src.getHeight(),width,height,st);
        Paint p = effects.paintFor(clip.effect, clip.effectIntensity); p.setAlpha((int)(255*st.opacity));
        frameCanvas.save(); frameCanvas.rotate(st.rotation, width/2f, height/2f); frameCanvas.drawBitmap(src,null,dst,p); frameCanvas.restore();
        effects.drawPost(frameCanvas,width,height,clip.effect,clip.effectIntensity);
        drawTexts(project, projectTimeBefore(project, clip)+progress*clip.durationSec, width, height);
        src.recycle(); return frameBitmap;
    }
    private float projectTimeBefore(EditProject p, TimelineClip c){ float t=0; for(TimelineClip x:p.clips){ if(x==c) break; t+=x.durationSec;} return t; }
    private void drawTexts(EditProject p, float time, int w, int h){ Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextAlign(Paint.Align.CENTER); for(TextOverlay o:p.texts){ if(time<o.startSec || time>o.endSec) continue; paint.setColor(o.color); paint.setTextSize(o.size); paint.setFakeBoldText(o.bold); paint.setAlpha((int)(255*o.opacity)); frameCanvas.drawText(o.text,o.x*w,o.y*h,paint); } }
    private RectF computeFitFill(int sw,int sh,int w,int h,KeyframeState st){ float scale=Math.max(w/(float)sw,h/(float)sh)*st.scale; float dw=sw*scale, dh=sh*scale; float cx=w/2f+st.x*w, cy=h/2f+st.y*h; return new RectF(cx-dw/2f,cy-dh/2f,cx+dw/2f,cy+dh/2f); }
    private Bitmap decodeForFrame(Uri uri,int targetW,int targetH) throws IOException { BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true; try(InputStream is=context.getContentResolver().openInputStream(uri)){ BitmapFactory.decodeStream(is,null,bounds); }
        if(bounds.outWidth<=0||bounds.outHeight<=0) return null; BitmapFactory.Options opts=new BitmapFactory.Options(); opts.inPreferredConfig=Bitmap.Config.ARGB_8888; opts.inSampleSize=sample(bounds.outWidth,bounds.outHeight,targetW*2,targetH*2); try(InputStream is=context.getContentResolver().openInputStream(uri)){ return BitmapFactory.decodeStream(is,null,opts); } }
    private int sample(int w,int h,int tw,int th){ int s=1; while(w/(s*2)>=tw && h/(s*2)>=th) s*=2; return Math.max(1,s); }
    public void release(){ if(frameBitmap!=null){ frameBitmap.recycle(); frameBitmap=null; } }
}
