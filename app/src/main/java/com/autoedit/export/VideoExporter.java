package com.autoedit.export;

import android.content.*;
import android.graphics.Bitmap;
import android.media.*;
import android.util.Log;

import com.autoedit.engine.*;
import com.autoedit.model.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The production export pipeline (spec §17, §20).
 *
 * <pre>
 *   1. validate the project                     → ExportStage.PREPARING
 *   2. check free storage                       → ExportStage.PREPARING
 *   3. pre-scale every source to the disk cache → ExportStage.OPTIMIZING
 *   4. decode + mix + AAC-encode the audio      → ExportStage.AUDIO
 *   5. render every frame with FrameComposer    → ExportStage.RENDERING
 *   6. H.264 encode, interleaving audio samples → ExportStage.ENCODING
 *   7. flush the encoder, close the muxer       → ExportStage.FINALIZING
 *   8. hand back to the caller to publish        → ExportStage.SAVING
 * </pre>
 *
 * There is no "video-only" placeholder here. When the project has audio the
 * MP4 really contains an AAC track, and {@link #verifyContainer} re-opens the
 * finished file to prove both streams are present before anything is published.
 *
 * <h3>Frame schedule</h3>
 * Frames are driven by {@link Timeline#totalFrames} / {@link Timeline#frameTime},
 * the same schedule the preview uses, so the file duration is exactly
 * {@code totalFrames / fps} and no boundary frame is written twice
 * (audit findings C3 and C4).
 */
public class VideoExporter {
    private static final String TAG = "AutoEditExport";

    public interface Listener {
        void onProgress(ExportProgress p);
        boolean isCancelled();
    }

    /** What the caller needs in order to publish and verify the result. */
    public static class Result {
        public long frames;
        public long durationUs;
        public boolean hasAudio;
        public String audioError;
    }

    private final Context context;

    public VideoExporter(Context c) { this.context = c.getApplicationContext(); }

    public Result export(EditProject project, ExportOptions opts, Listener listener) throws Exception {
        File out = opts.outputPath == null ? null : new File(opts.outputPath);
        File parent = out == null ? context.getExternalFilesDir(null) : out.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();

        // ---- 1/2. validate + storage -----------------------------------------
        report(listener, new ExportProgress(ExportStage.PREPARING, 0f, "Checking the project"));
        String err = validate(project);
        if (err != null) throw new IOException(err);
        long est = StorageGuard.estimateBytes(project.totalDurationSec(), opts.bitrate);
        if (!StorageGuard.hasSpace(parent == null ? context.getCacheDir() : parent, est))
            throw new IOException("Not enough storage space to export this video (needs about "
                    + (est / (1024 * 1024)) + " MB).");

        DiskBitmapCache diskCache = new DiskBitmapCache(context);
        FrameRenderer renderer = new FrameRenderer(context);
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        AudioMixer.Result audio = null;
        File audioFile = null;
        RandomAccessFile audioIn = null;

        try {
            // ---- 3. pre-scale sources ----------------------------------------
            diskCache.predecodeProject(project, opts, listener);

            // ---- 4. audio pass ------------------------------------------------
            if (project.hasAudio()) {
                report(listener, new ExportProgress(ExportStage.AUDIO, 0f, "Decoding audio"));
                AudioMixer mixer = new AudioMixer(context);
                try {
                    audio = mixer.render(project.activeAudio(), project.totalDurationSec(),
                            44100, listener::isCancelled);
                } catch (Exception e) {
                    // A broken audio file must not silently produce a silent
                    // video the user believes has sound (spec §48).
                    Log.e(TAG, "Audio render failed", e);
                    throw new IOException("Audio could not be decoded: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
                }
                if (audio != null && audio.present) {
                    audioFile = audio.file;
                    audioIn = new RandomAccessFile(audioFile, "r");
                }
                report(listener, new ExportProgress(ExportStage.AUDIO, 1f, "Audio ready"));
            }

            // ---- 5/6. video pass ---------------------------------------------
            MediaCodecInfo codecInfo = selectCodec(MediaFormat.MIMETYPE_VIDEO_AVC);
            if (codecInfo == null) throw new IOException("This device has no H.264 encoder.");
            int colorFormat = selectColorFormat(codecInfo, MediaFormat.MIMETYPE_VIDEO_AVC);

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, opts.width, opts.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, opts.bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, opts.fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            encoder = MediaCodec.createByCodecName(codecInfo.getName());
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = opts.outputFileDescriptor != null
                    ? new MediaMuxer(opts.outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    : new MediaMuxer(opts.outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            final long total = Timeline.totalFrames(project);
            byte[] yuv = new byte[opts.width * opts.height * 3 / 2];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int videoTrack = -1, audioTrack = -1;
            boolean muxerStarted = false;
            int audioCursor = 0;
            long lastReported = -1;

            for (long frameIndex = 0; frameIndex < total; frameIndex++) {
                if (listener != null && listener.isCancelled()) throw new InterruptedIOException("Export cancelled");

                float t = Timeline.frameTime(project, frameIndex);
                Bitmap frame = renderer.renderAtTime(project, t, opts.width, opts.height, opts.fitMode);
                Yuv420Converter.bitmapToYuv420(frame, yuv, colorFormat);

                long pts = Timeline.framePtsUs(project, frameIndex);
                queueVideoFrame(encoder, yuv, pts, frameIndex == total - 1);

                // Drain video; start the muxer as soon as BOTH formats are known.
                while (true) {
                    int o = encoder.dequeueOutputBuffer(info, 10_000);
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat vf = encoder.getOutputFormat();
                        videoTrack = muxer.addTrack(vf);
                        if (audio != null && audio.present && audio.format != null)
                            audioTrack = muxer.addTrack(audio.format);
                        muxer.start();
                        muxerStarted = true;
                        continue;
                    }
                    if (o < 0) break;
                    ByteBuffer buf = encoder.getOutputBuffer(o);
                    if (buf != null && muxerStarted && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        // Keep the streams interleaved: flush every audio sample
                        // that belongs before this video sample.
                        if (audioIn != null && audioTrack >= 0)
                            audioCursor = writeAudioUpTo(muxer, audioIn, audio, audioTrack, audioCursor,
                                    info.presentationTimeUs);
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrack, buf, info);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(o, false);
                    if (eos) break;
                }

                long pctStep = Math.max(1, total / 100);
                if (frameIndex % pctStep == 0 || frameIndex == total - 1) {
                    float frac = (frameIndex + 1) / (float) total;
                    int pct = frac < 0.85f
                            ? ExportStage.RENDERING.percent(frac / 0.85f)
                            : ExportStage.ENCODING.percent((frac - 0.85f) / 0.15f);
                    if (pct != lastReported) {
                        lastReported = pct;
                        ExportProgress p = new ExportProgress();
                        p.percent = pct;
                        p.stage = frac < 0.85f ? ExportStage.RENDERING : ExportStage.ENCODING;
                        p.currentFrame = frameIndex + 1;
                        p.totalFrames = total;
                        p.currentClip = Timeline.resolve(project, t).clipIndex + 1;
                        p.message = "Frame " + (frameIndex + 1) + " of " + total;
                        report(listener, p);
                    }
                }
            }

            // ---- 7. flush ------------------------------------------------------
            report(listener, new ExportProgress(ExportStage.FINALIZING, 0.2f, "Flushing the encoder"));
            flushVideo(encoder, muxer, info, videoTrack, muxerStarted,
                    audioIn, audio, audioTrack);

            if (audioIn != null && audioTrack >= 0)
                writeAudioUpTo(muxer, audioIn, audio, audioTrack, audioCursor, Long.MAX_VALUE);

            report(listener, new ExportProgress(ExportStage.FINALIZING, 1f, "Closing the container"));
            muxer.stop();
            muxerStarted = false;
            muxer.release();
            muxer = null;

            Result r = new Result();
            r.frames = total;
            r.durationUs = total * 1_000_000L / Math.max(1, opts.fps);
            r.hasAudio = audio != null && audio.present;
            return r;
        } catch (Exception e) {
            Log.e(TAG, "Export failed", e);
            if (out != null && out.exists()) //noinspection ResultOfMethodCallIgnored
                out.delete();
            throw e;
        } finally {
            renderer.release();
            if (audioIn != null) try { audioIn.close(); } catch (IOException ignored) {}
            if (audio != null) audio.close();
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                encoder.release();
            }
            if (muxer != null) {
                try { muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    // ------------------------------------------------------------- validation

    /** Returns a user-facing error, or null when the project can be exported. */
    public static String validate(EditProject project) {
        if (project == null) return "No project to export.";
        if (project.clips.isEmpty()) return "No images to export — add photos first.";
        for (TimelineClip c : project.clips) {
            if (c.uri == null || c.uri.isEmpty()) return "One clip is missing its source image.";
            c.setDurationSeconds(c.durationSec);
            if (c.durationSec <= 0.05f) return "A clip has an invalid duration.";
        }
        if (project.totalDurationSec() <= 0.05f) return "The project has no duration.";
        return null;
    }

    /**
     * Re-opens the finished file and reports which streams it really contains.
     * Called after the muxer is closed, before the file is published, so a
     * half-written container can never be handed to the user (spec §17).
     */
    public static ContainerInfo verifyContainer(Context ctx, java.io.FileDescriptor fd) {
        return verifyContainer(ctx, fd, -1L);
    }

    /**
     * Reads the finished container back and reports which tracks it holds.
     *
     * <p>{@code length} is passed through to
     * {@link MediaExtractor#setDataSource(java.io.FileDescriptor, long, long)}
     * when it is known. The single-argument overload is unreliable here:
     * MediaMuxer writes the MP4 {@code moov} box last, so the extractor has to
     * know the exact extent of the file to seek to it. Passing an explicit
     * length removes that guesswork.
     */
    public static ContainerInfo verifyContainer(Context ctx, java.io.FileDescriptor fd, long length) {
        ContainerInfo info = new ContainerInfo();
        MediaExtractor ex = new MediaExtractor();
        try {
            if (length > 0L) ex.setDataSource(fd, 0L, length);
            else ex.setDataSource(fd);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    info.hasVideo = true;
                    info.durationUs = Math.max(info.durationUs, f.getLong(MediaFormat.KEY_DURATION));
                } else if (mime.startsWith("audio/")) {
                    info.hasAudio = true;
                }
            }
        } catch (Exception e) {
            info.error = e.getMessage();
            Log.e(TAG, "Container verification failed", e);
        } finally {
            ex.release();
        }
        return info;
    }

    public static class ContainerInfo {
        public boolean hasVideo;
        public boolean hasAudio;
        public long durationUs;
        public String error;
    }

    // -------------------------------------------------------------- encoder io

    private void queueVideoFrame(MediaCodec encoder, byte[] yuv, long ptsUs, boolean last) {
        while (true) {
            int in = encoder.dequeueInputBuffer(20_000);
            if (in >= 0) {
                ByteBuffer buf = encoder.getInputBuffer(in);
                if (buf == null) { encoder.queueInputBuffer(in, 0, 0, ptsUs, 0); return; }
                buf.clear();
                buf.put(yuv);
                encoder.queueInputBuffer(in, 0, yuv.length, ptsUs,
                        last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                return;
            }
            // Encoder is full; the caller's drain loop will free a buffer.
            MediaCodec.BufferInfo ignore = new MediaCodec.BufferInfo();
            int o = encoder.dequeueOutputBuffer(ignore, 10_000);
            if (o >= 0) encoder.releaseOutputBuffer(o, false);
        }
    }

    private void flushVideo(MediaCodec encoder, MediaMuxer muxer, MediaCodec.BufferInfo info,
                            int videoTrack, boolean muxerStarted, RandomAccessFile audioIn,
                            AudioMixer.Result audio, int audioTrack) throws IOException {
        int in = encoder.dequeueInputBuffer(20_000);
        if (in >= 0) encoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        while (true) {
            int o = encoder.dequeueOutputBuffer(info, 20_000);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (o < 0) continue;
            ByteBuffer buf = encoder.getOutputBuffer(o);
            if (buf != null && muxerStarted && info.size > 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                if (audioIn != null && audioTrack >= 0)
                    writeAudioUpTo(muxer, audioIn, audio, audioTrack, Integer.MAX_VALUE / 2,
                            info.presentationTimeUs);
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                muxer.writeSampleData(videoTrack, buf, info);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            encoder.releaseOutputBuffer(o, false);
            if (eos) break;
        }
    }

    /**
     * Writes every audio sample whose presentation time is at or before
     * {@code ptsUs}, keeping the MP4 properly interleaved.
     *
     * @return the new cursor into the audio sample index
     */
    private int writeAudioUpTo(MediaMuxer muxer, RandomAccessFile in, AudioMixer.Result audio,
                               int audioTrack, int cursor, long ptsUs) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (cursor < audio.index.size()) {
            long[] e = audio.index.get(cursor);
            if (e[0] > ptsUs) break;
            byte[] chunk = new byte[(int) e[2]];
            in.seek(e[1]);
            in.readFully(chunk);
            ByteBuffer bb = ByteBuffer.wrap(chunk);
            info.set(0, chunk.length, e[0], (int) e[3]);
            muxer.writeSampleData(audioTrack, bb, info);
            cursor++;
        }
        return cursor;
    }

    // ---------------------------------------------------------------- helpers

    private static void report(Listener l, ExportProgress p) { if (l != null) l.onProgress(p); }

    private static MediaCodecInfo selectCodec(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return info;
        }
        return null;
    }

    private static int selectColorFormat(MediaCodecInfo info, String mime) {
        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
        for (int c : caps.colorFormats) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return c;
        for (int c : caps.colorFormats) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return c;
        for (int c : caps.colorFormats) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return c;
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    }
}
