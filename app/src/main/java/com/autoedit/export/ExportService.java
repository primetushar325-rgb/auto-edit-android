package com.autoedit.export;

import android.app.*;import android.content.*;import android.os.*;import com.autoedit.model.*;import com.autoedit.project.ProjectStore;

public class ExportService extends Service {
    public static final String ACTION_START="com.autoedit.START_EXPORT", ACTION_CANCEL="com.autoedit.CANCEL_EXPORT", ACTION_PROGRESS="com.autoedit.PROGRESS";
    private volatile boolean cancelled=false;
    @Override public int onStartCommand(Intent intent,int flags,int startId){ if(intent!=null && ACTION_CANCEL.equals(intent.getAction())){ cancelled=true; stopSelf(); return START_NOT_STICKY; } if(intent!=null && ACTION_START.equals(intent.getAction())){ cancelled=false; String out=intent.getStringExtra("out"); int w=intent.getIntExtra("w",1920), h=intent.getIntExtra("h",1080), fps=intent.getIntExtra("fps",30); new Thread(()->runExport(out,w,h,fps),"AutoEditExportThread").start(); } return START_NOT_STICKY; }
    private void runExport(String out,int w,int h,int fps){ try{ EditProject p=new ProjectStore(this).load(); ExportOptions o=new ExportOptions(); o.outputPath=out; o.width=w; o.height=h; o.fps=fps; o.bitrate=w>=3840?35_000_000:w>=2560?18_000_000:w>=1920?8_000_000:4_000_000; new VideoExporter(this).export(p,o,new VideoExporter.Listener(){ public void onProgress(ExportProgress pr){ Intent i=new Intent(ACTION_PROGRESS); i.setPackage(getPackageName()); i.putExtra("percent",pr.percent); i.putExtra("frame",pr.currentFrame); i.putExtra("total",pr.totalFrames); i.putExtra("clip",pr.currentClip); i.putExtra("message",pr.message); sendBroadcast(i); } public boolean isCancelled(){ return cancelled; }}); }catch(Exception e){ Intent i=new Intent(ACTION_PROGRESS); i.setPackage(getPackageName()); i.putExtra("percent",-1); i.putExtra("message",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()); sendBroadcast(i); } finally { stopSelf(); } }
    @Override public IBinder onBind(Intent intent){ return null; }
}
