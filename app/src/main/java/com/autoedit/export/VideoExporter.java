package com.autoedit.export;

import android.content.*;import android.graphics.Bitmap;import android.media.*;import android.net.Uri;import android.util.Log;
import com.autoedit.model.*;import java.io.*;import java.nio.ByteBuffer;

public class VideoExporter {
    public interface Listener { void onProgress(ExportProgress p); boolean isCancelled(); }
    private static final String TAG="AutoEditExport";
    private final Context context;
    public VideoExporter(Context c){ context=c.getApplicationContext(); }
    public void export(EditProject project, ExportOptions opts, Listener listener) throws Exception {
        File out = opts.outputPath == null ? null : new File(opts.outputPath); File parent = out == null ? context.getExternalFilesDir(null) : out.getParentFile(); if(parent!=null) parent.mkdirs();
        long est = StorageGuard.estimateBytes(project.totalDurationSec(), opts.bitrate); if(!StorageGuard.hasSpace(parent==null?context.getCacheDir():parent, est)) throw new IOException("Insufficient storage for estimated export size");
        DiskBitmapCache diskCache = new DiskBitmapCache(context);
        diskCache.predecodeProject(project, opts, listener);
        MediaCodec encoder=null; MediaMuxer muxer=null; FrameRenderer renderer=new FrameRenderer(context);
        try {
            MediaCodecInfo codecInfo = selectCodec(MediaFormat.MIMETYPE_VIDEO_AVC); if(codecInfo==null) throw new IOException("Encoder unavailable: AVC/H.264");
            int colorFormat = selectColorFormat(codecInfo, MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, opts.width, opts.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, opts.bitrate); format.setInteger(MediaFormat.KEY_FRAME_RATE, opts.fps); format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            encoder = MediaCodec.createByCodecName(codecInfo.getName()); encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start();
            muxer = opts.outputFileDescriptor != null ? new MediaMuxer(opts.outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) : new MediaMuxer(opts.outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack=-1; boolean muxerStarted=false; MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(); long frameIndex=0,total=project.totalFrames(); byte[] yuv=new byte[opts.width*opts.height*3/2];
            for(int ci=0; ci<project.clips.size(); ci++){
                TimelineClip clip=project.clips.get(ci); int frames=Math.max(1, Math.round(clip.durationSec*opts.fps));
                for(int f=0; f<frames; f++){
                    if(listener!=null && listener.isCancelled()) throw new InterruptedIOException("Export cancelled");
                    float prog=frames==1?1f:f/(float)(frames-1); Bitmap frame=renderer.renderAtTime(project, frameIndex/(float)opts.fps, opts.width, opts.height, opts.fitMode);
                    Yuv420Converter.bitmapToYuv420(frame,yuv,colorFormat);
                    boolean submitted=false; while(!submitted){ int in=encoder.dequeueInputBuffer(10_000); if(in>=0){ ByteBuffer buf=encoder.getInputBuffer(in); if(buf==null) throw new IOException("Encoder input buffer unavailable"); buf.clear(); buf.put(yuv); encoder.queueInputBuffer(in,0,yuv.length,frameIndex*1_000_000L/opts.fps,0); submitted=true; } DrainState pre=drainState(encoder,muxer,info,false,videoTrack,muxerStarted); if(pre.format!=null && !muxerStarted){ videoTrack=muxer.addTrack(pre.format); muxer.start(); muxerStarted=true; } }
                    DrainState ds=drainState(encoder,muxer,info,false,videoTrack,muxerStarted); if(ds.format!=null && !muxerStarted){ videoTrack=muxer.addTrack(ds.format); muxer.start(); muxerStarted=true; }
                    frameIndex++; if(listener!=null && frameIndex%Math.max(1,opts.fps/2)==0){ ExportProgress p=new ExportProgress(); p.currentClip=ci+1; p.currentFrame=frameIndex; p.totalFrames=total; p.percent=(int)Math.min(99, 10 + frameIndex*89/Math.max(1,total)); p.message="Rendering clip "+(ci+1)+" / "+project.clips.size(); listener.onProgress(p); }
                }
            }
            int in=encoder.dequeueInputBuffer(10_000); if(in>=0) encoder.queueInputBuffer(in,0,0,frameIndex*1_000_000L/opts.fps,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            while(true){ DrainState ds=drainState(encoder,muxer,info,true,videoTrack,muxerStarted); if(ds.format!=null && !muxerStarted){ videoTrack=muxer.addTrack(ds.format); muxer.start(); muxerStarted=true; } if(ds.eos) break; }
            if(project.audioUri!=null) Log.i(TAG,"Audio URI is stored in project. Compatible audio pass-through can be added before muxer start for production extensions: "+project.audioUri);
            if(listener!=null){ ExportProgress p=new ExportProgress(); p.percent=100; p.currentFrame=total; p.totalFrames=total; p.currentClip=project.clips.size(); p.message="Export complete"; listener.onProgress(p); }
        } catch(Exception e){ Log.e(TAG,"Export failed",e); if(out != null && out.exists()) //noinspection ResultOfMethodCallIgnored
                out.delete(); throw e; }
        finally { renderer.release(); if(encoder!=null){ try{encoder.stop();}catch(Exception ignored){} encoder.release(); } if(muxer!=null){ try{muxer.stop();}catch(Exception ignored){} try{muxer.release();}catch(Exception ignored){} } }
    }
    private static class DrainState{ boolean eos; MediaFormat format; }
    private DrainState drainState(MediaCodec enc, MediaMuxer mux, MediaCodec.BufferInfo info, boolean end, int track, boolean started){ DrainState st=new DrainState(); while(true){ int out=enc.dequeueOutputBuffer(info,0); if(out==MediaCodec.INFO_TRY_AGAIN_LATER){ if(!end) break; } else if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){ st.format=enc.getOutputFormat(); break; } else if(out>=0){ ByteBuffer buf=enc.getOutputBuffer(out); if(buf!=null && info.size>0 && started){ buf.position(info.offset); buf.limit(info.offset+info.size); mux.writeSampleData(track,buf,info); } if((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) st.eos=true; enc.releaseOutputBuffer(out,false); if(st.eos) break; } } return st; }
    private void drain(MediaCodec e,MediaMuxer m,MediaCodec.BufferInfo i,boolean end,int t,boolean s){ drainState(e,m,i,end,t,s); }
    private MediaFormat tryOutputFormat(MediaCodec e){ try { return e.getOutputFormat(); } catch(Exception ex){ return null; } }
    private static MediaCodecInfo selectCodec(String mime){ MediaCodecList list=new MediaCodecList(MediaCodecList.ALL_CODECS); for(MediaCodecInfo info:list.getCodecInfos()){ if(!info.isEncoder()) continue; for(String t:info.getSupportedTypes()) if(t.equalsIgnoreCase(mime)) return info; } return null; }
    private static int selectColorFormat(MediaCodecInfo info,String mime){ MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(mime); for(int c:caps.colorFormats) if(c==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return c; for(int c:caps.colorFormats) if(c==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return c; for(int c:caps.colorFormats) if(c==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return c; return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible; }
}
