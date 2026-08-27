package com.autoedit.export;

import android.app.*;import android.content.*;import android.os.*;import com.autoedit.model.*;import com.autoedit.project.ProjectStore;

public class ExportService extends Service {
    public static final String ACTION_START="com.autoedit.START_EXPORT", ACTION_CANCEL="com.autoedit.CANCEL_EXPORT", ACTION_PROGRESS="com.autoedit.PROGRESS";
    private volatile boolean cancelled=false;

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && ACTION_CANCEL.equals(intent.getAction())){ cancelled=true; stopSelf(); return START_NOT_STICKY; }
        if(intent!=null && ACTION_START.equals(intent.getAction())){
            cancelled=false;
            int w=intent.getIntExtra("w",1920), h=intent.getIntExtra("h",1080), fps=intent.getIntExtra("fps",30);
            String fit=intent.getStringExtra("fitMode");
            new Thread(()->runExport(w,h,fps,fit),"AutoEditExportThread").start();
        }
        return START_NOT_STICKY;
    }

    private void runExport(int w,int h,int fps,String fit){
        ExportDestination destination = null;
        try{
            EditProject p=new ProjectStore(this).load();
            p.width=w; p.height=h; p.fps=fps;
            if(fit!=null) try{ p.fitMode=FitMode.valueOf(fit); }catch(Exception ignored){}
            String fileName="AutoEdit_"+System.currentTimeMillis()+".mp4";
            destination = ExportDestination.create(this, fileName);
            ExportOptions o=new ExportOptions();
            o.outputFileDescriptor=destination.fileDescriptor();
            o.outputPath=destination.file!=null ? destination.file.getAbsolutePath() : null;
            o.width=w; o.height=h; o.fps=fps; o.fitMode=p.fitMode;
            o.bitrate=w>=3840?35_000_000:w>=2560?18_000_000:w>=1920?8_000_000:4_000_000;
            ExportDestination finalDestination = destination;
            new VideoExporter(this).export(p,o,new VideoExporter.Listener(){
                public void onProgress(ExportProgress pr){ sendProgress(pr.percent, pr.currentFrame, pr.totalFrames, pr.currentClip, pr.message); }
                public boolean isCancelled(){ return cancelled; }
            });
            finalDestination.markSuccess();
            sendProgress(100, p.totalFrames(), p.totalFrames(), p.clips.size(), "Saved to Gallery: Movies/AutoEdit/" + fileName);
        }catch(Exception e){
            String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            sendProgress(-1,0,0,0,categorize(msg));
        } finally {
            if(destination!=null) destination.publishOrDelete();
            stopSelf();
        }
    }

    private String categorize(String msg){
        String m=msg==null?"":msg.toLowerCase();
        if(m.contains("storage") || m.contains("space")) return "Insufficient storage: "+msg;
        if(m.contains("encoder")) return "Encoder unavailable: "+msg;
        if(m.contains("permission") || m.contains("denied")) return "Permission problem: "+msg;
        if(m.contains("unsupported") || m.contains("corrupt") || m.contains("invalid source")) return "Unsupported/invalid media: "+msg;
        if(m.contains("cancel")) return "Export cancelled";
        return "Rendering error: "+msg;
    }

    private void sendProgress(int percent,long frame,long total,int clip,String message){
        Intent i=new Intent(ACTION_PROGRESS); i.setPackage(getPackageName()); i.putExtra("percent",percent); i.putExtra("frame",frame); i.putExtra("total",total); i.putExtra("clip",clip); i.putExtra("message",message); sendBroadcast(i);
    }
    @Override public IBinder onBind(Intent intent){ return null; }
}
