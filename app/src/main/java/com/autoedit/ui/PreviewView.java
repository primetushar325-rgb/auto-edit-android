package com.autoedit.ui;

import android.content.*;import android.graphics.*;import android.net.Uri;import android.util.Log;import android.util.LruCache;import android.view.*;import com.autoedit.model.*;import com.autoedit.engine.*;import java.io.*;import java.util.Locale;

public class PreviewView extends View {
    private static final String TAG="AutoEditPreview";
    public EditProject project;
    private final FormulaEngine formulas=new FormulaEngine();
    private final EffectEngine effects=new EffectEngine();
    private final LruCache<String,Bitmap> cache;
    private long startMs=0; private float baseTimeSec=0f; public boolean playing=false;

    public PreviewView(Context c){
        super(c); setBackgroundColor(0xff020409);
        int maxKb=(int)Math.min(48*1024, Runtime.getRuntime().maxMemory()/1024/6);
        cache=new LruCache<String,Bitmap>(maxKb){ @Override protected int sizeOf(String key,Bitmap value){ return value.getByteCount()/1024; } };
    }

    public void play(){ playing=true; startMs=System.currentTimeMillis(); invalidate(); }
    public void pause(){ if(playing) baseTimeSec=currentTimeSec(); playing=false; invalidate(); }
    public void seekTo(float seconds){ baseTimeSec=Math.max(0,seconds); startMs=System.currentTimeMillis(); invalidate(); }

    private float currentTimeSec(){ if(project==null) return 0f; float total=Math.max(.001f,project.totalDurationSec()); float t=baseTimeSec; if(playing) t += (System.currentTimeMillis()-startMs)/1000f; return t % total; }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0xff071422); canvas.drawRoundRect(10,10,getWidth()-10,getHeight()-10,28,28,p);
        if(project==null||project.clips.isEmpty()){ drawCentered(canvas,"Add images to preview",0xff8dcaff); return; }
        float t=currentTimeSec(); ClipAtTime active=findClip(t); if(active.clip==null){ drawCentered(canvas,"No active clip",0xffff8080); return; }
        try{
            drawClip(canvas, active.clip, active.progress, 1f);
            float td=Math.min(active.clip.transitionDurationSec, active.clip.durationSec/2f);
            if(active.index<project.clips.size()-1 && active.clip.transition!=TransitionType.NONE && td>0 && active.localTime>active.clip.durationSec-td){
                float mix=(active.localTime-(active.clip.durationSec-td))/td; drawClip(canvas, project.clips.get(active.index+1), 0f, mix);
            }
            drawTexts(canvas,t);
        }catch(Throwable e){ Log.e(TAG,"Preview render failed",e); drawCentered(canvas,"Preview image unavailable",0xffff8080); }
        p.setColor(0xffffffff); p.setTextSize(28); canvas.drawText(String.format(Locale.US,"Clip %02d  %.1fs   %s / %s",active.clip.index,active.clip.durationSec,fmt(t),fmt(project.totalDurationSec())),28,getHeight()-28,p);
        if(playing) postInvalidateDelayed(33);
    }

    private static class ClipAtTime{ TimelineClip clip; int index; float localTime; float progress; }
    private ClipAtTime findClip(float time){ ClipAtTime r=new ClipAtTime(); float acc=0; for(int i=0;i<project.clips.size();i++){ TimelineClip c=project.clips.get(i); if(time < acc+c.durationSec || i==project.clips.size()-1){ r.clip=c; r.index=i; r.localTime=Math.max(0,time-acc); r.progress=Math.min(1,r.localTime/Math.max(.001f,c.durationSec)); return r; } acc+=c.durationSec; } return r; }

    private void drawClip(Canvas canvas, TimelineClip clip, float progress, float alpha) throws IOException{
        Bitmap b=getBitmap(clip.uri); if(b==null) throw new IOException("Decode returned null");
        KeyframeState st=formulas.stateAt(clip.formula,progress); RectF dst=project.fitMode==FitMode.FIT?fitInside(b.getWidth(),b.getHeight(),getWidth(),getHeight(),st):fill(b.getWidth(),b.getHeight(),getWidth(),getHeight(),st);
        if(project.fitMode==FitMode.FIT) drawFitBars(canvas,b,alpha);
        Paint paint=effects.paintFor(clip.effect,clip.effectIntensity); paint.setAlpha((int)(255*alpha*st.opacity)); canvas.save(); canvas.rotate(st.rotation,getWidth()/2f,getHeight()/2f); canvas.drawBitmap(b,null,dst,paint); canvas.restore(); effects.drawPost(canvas,getWidth(),getHeight(),clip.effect,clip.effectIntensity*alpha);
    }

    private Bitmap getBitmap(String uri) throws IOException{ String key=uri+"@"+getWidth()+"x"+getHeight(); Bitmap b=cache.get(key); if(b!=null&&!b.isRecycled()) return b; b=decode(Uri.parse(uri),Math.max(1,getWidth()),Math.max(1,getHeight())); if(b!=null) cache.put(key,b); return b; }
    private void drawFitBars(Canvas c,Bitmap b,float alpha){ Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG); p.setAlpha((int)(90*alpha)); c.drawBitmap(b,null,fill(b.getWidth(),b.getHeight(),getWidth(),getHeight(),new KeyframeState(0,0,1.12f,0,1)),p); p.setColor(0xaa020409); p.setAlpha((int)(170*alpha)); c.drawRect(0,0,getWidth(),getHeight(),p); }
    private void drawTexts(Canvas c,float time){ Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextAlign(Paint.Align.CENTER); for(TextOverlay o:project.texts){ if(time<o.startSec||time>o.endSec) continue; paint.setColor(o.color); paint.setTextSize(o.size); paint.setFakeBoldText(o.bold); paint.setAlpha((int)(255*o.opacity)); c.drawText(o.text,o.x*getWidth(),o.y*getHeight(),paint); } }
    private void drawCentered(Canvas c,String s,int color){ Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setTextSize(34); p.setTextAlign(Paint.Align.CENTER); c.drawText(s,getWidth()/2f,getHeight()/2f,p); }
    private RectF fill(int sw,int sh,int w,int h,KeyframeState st){ float scale=Math.max(w/(float)sw,h/(float)sh)*st.scale; return rect(sw,sh,w,h,st,scale); }
    private RectF fitInside(int sw,int sh,int w,int h,KeyframeState st){ float scale=Math.min(w/(float)sw,h/(float)sh)*st.scale; return rect(sw,sh,w,h,st,scale); }
    private RectF rect(int sw,int sh,int w,int h,KeyframeState st,float scale){ float dw=sw*scale,dh=sh*scale,cx=w/2f+st.x*w,cy=h/2f+st.y*h; return new RectF(cx-dw/2,cy-dh/2,cx+dw/2,cy+dh/2); }
    private Bitmap decode(Uri uri,int tw,int th) throws IOException{ BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true; try(InputStream is=getContext().getContentResolver().openInputStream(uri)){ BitmapFactory.decodeStream(is,null,bounds); } if(bounds.outWidth<=0||bounds.outHeight<=0) return null; BitmapFactory.Options opts=new BitmapFactory.Options(); opts.inPreferredConfig=Bitmap.Config.ARGB_8888; int s=1; while(bounds.outWidth/(s*2)>=tw*2 && bounds.outHeight/(s*2)>=th*2) s*=2; opts.inSampleSize=Math.max(1,s); try(InputStream is=getContext().getContentResolver().openInputStream(uri)){ return BitmapFactory.decodeStream(is,null,opts); } }
    private String fmt(float sec){ int s=Math.round(sec); return String.format(Locale.US,"%02d:%02d",s/60,s%60); }
}
