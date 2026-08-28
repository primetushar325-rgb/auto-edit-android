package com.autoedit.frames;

import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.IBinder;

import java.io.File;

/**
 * Local, offline frame extraction service.
 *
 * Runs the whole extraction on a background thread: opens the video once with
 * MediaMetadataRetriever, walks the requested time range at the chosen
 * interval, crops/resizes each frame (never stretching), encodes it to the
 * chosen format and writes it into an app-private temp dir — one frame in
 * memory at a time. Progress is broadcast with the same pattern as
 * ExportService (percent / framesDone / total / current time / message).
 * No network. No cloud. Release + cancel are safe.
 */
public class FrameExtractService extends Service {
    public static final String ACTION_START = "com.autoedit.frames.START";
    public static final String ACTION_CANCEL = "com.autoedit.frames.CANCEL";
    public static final String ACTION_PROGRESS = "com.autoedit.frames.PROGRESS";

    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_INTERVAL = "interval";
    public static final String EXTRA_START = "startSec";
    public static final String EXTRA_END = "endSec";
    public static final String EXTRA_ASPECT = "aspect";
    public static final String EXTRA_CROP = "crop";
    public static final String EXTRA_OUT_W = "outW";
    public static final String EXTRA_OUT_H = "outH";
    /** Preview zoom the user framed with pinch gestures (spec §9/§10). */
    public static final String EXTRA_ZOOM = "zoom";
    public static final String EXTRA_PAN_X = "panX";
    public static final String EXTRA_PAN_Y = "panY";
    public static final String EXTRA_FORMAT = "format";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_DIR = "dir";

    private volatile boolean cancelled = false;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            cancelled = false;
            final Intent copy = new Intent(intent);
            new Thread(() -> runExtract(copy), "AutoEditFrameExtract").start();
        }
        return START_NOT_STICKY;
    }

    private void runExtract(Intent i) {
        String uriStr = i.getStringExtra(EXTRA_URI);
        double interval = i.getDoubleExtra(EXTRA_INTERVAL, 2);
        double startSec = i.getDoubleExtra(EXTRA_START, 0);
        double endSec = i.getDoubleExtra(EXTRA_END, 0);
        FrameUtils.Aspect aspect = FrameUtils.Aspect.valueOf(i.getStringExtra(EXTRA_ASPECT));
        FrameUtils.Crop crop = FrameUtils.Crop.valueOf(i.getStringExtra(EXTRA_CROP));
        int outW = i.getIntExtra(EXTRA_OUT_W, 0), outH = i.getIntExtra(EXTRA_OUT_H, 0);
        // The zoom/pan the user set in the preview. Frames must come out with
        // the same composition the user saw, never the untouched source.
        final float zoom = i.getFloatExtra(EXTRA_ZOOM, 1f);
        final float panX = i.getFloatExtra(EXTRA_PAN_X, 0f);
        final float panY = i.getFloatExtra(EXTRA_PAN_Y, 0f);
        String format = i.getStringExtra(EXTRA_FORMAT);
        int quality = i.getIntExtra(EXTRA_QUALITY, 90);
        File dir = new File(i.getStringExtra(EXTRA_DIR));
        String ext = "png".equals(format) ? "png" : "webp".equals(format) ? "webp" : "jpg";
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, Uri.parse(uriStr));
            String durS = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String wS = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hS = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (durS == null) throw new IllegalStateException("This video format/codec is not supported on this device.");
            double durationMs = Double.parseDouble(durS);
            double durationSec = durationMs / 1000.0;
            int srcW = wS == null ? 0 : Integer.parseInt(wS);
            int srcH = hS == null ? 0 : Integer.parseInt(hS);
            if (srcW <= 0 || srcH <= 0) {
                // Some muxers omit size; fall back to the first frame's size.
                Bitmap probe = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (probe == null) throw new IllegalStateException("This video format/codec is not supported on this device.");
                srcW = probe.getWidth();
                srcH = probe.getHeight();
                probe.recycle();
            }
            if (endSec <= 0 || endSec > durationSec) endSec = durationSec;
            if (startSec < 0) startSec = 0;
            if (startSec >= endSec - 0.001) throw new IllegalStateException("Start time must be before End time.");
            long frames = (long) ((endSec - startSec) / interval) + 1;
            if (frames < 1) frames = 1;
            if (frames > 20000) throw new IllegalStateException("Too many frames for this interval — choose a larger interval.");

            int[] dims = FrameUtils.targetDims(aspect, srcW, srcH, outW, outH);
            float ratio = dims[0] / (float) dims[1];

            if (!dir.exists()) dir.mkdirs();
            long seq = 0;
            for (double t = startSec; t <= endSec + 0.0001 && !cancelled; t += interval) {
                long us = (long) (t * 1_000_000L);
                if (us > durationMs * 1000) us = (long) (durationMs * 1000);
                Bitmap frame = null;
                Bitmap out = null;
                try {
                    frame = mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) {
                        // tolerate a dead tail (few decoders fail on the very last ms)
                        if (seq > 0 && t > durationSec - interval) break;
                        throw new IllegalStateException("Could not decode a frame at " + FrameUtils.fmtTime(t));
                    }
                    frame = FrameUtils.cropZoomPan(frame, zoom, panX, panY);
                    if (frame == null) continue;
                    if (aspect == FrameUtils.Aspect.ORIGINAL && dims[0] == frame.getWidth() && dims[1] == frame.getHeight()) {
                        out = frame;
                    } else {
                        out = FrameUtils.fitToAspect(frame, ratio, crop, dims[0], dims[1]);
                        if (out != frame) frame.recycle();
                    }
                    seq++;
                    File f = new File(dir, FrameUtils.frameName((int) seq, t, ext));
                    FrameUtils.encode(out, f, format, quality);
                    if (seq % 5 == 0 || seq == frames) {
                        sendProgress((int) (100 * seq / (double) frames), seq, frames, t,
                                "Frames created: " + seq + " / " + frames);
                    }
                } catch (OutOfMemoryError oom) {
                    sendError("Device memory limit reached at frame " + (seq + 1) + " — the frame was too large for this device. Try a smaller output size.");
                    return;
                } finally {
                    if (out != null) out.recycle();
                }
            }
            if (cancelled) {
                sendProgress(-1, seq, frames, 0, "Cancelled");
                FrameUtils.deleteTree(dir);
            } else {
                sendProgress(100, seq, frames, endSec, "Complete: " + seq + " frames");
            }
        } catch (Exception e) {
            FrameUtils.deleteTree(dir);
            sendError(categorize(e));
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
            stopSelf();
        }
    }

    private String categorize(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String low = m.toLowerCase();
        if (low.contains("not supported") || low.contains("unsupported")) return "This video format/codec is not supported on this device.";
        if (low.contains("memory")) return m;
        if (low.contains("denied") || low.contains("permission")) return "Permission problem: " + m;
        return m;
    }

    private void sendProgress(int percent, long done, long total, double tSec, String msg) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra("percent", percent);
        i.putExtra("framesDone", done);
        i.putExtra("framesTotal", total);
        i.putExtra("currentTime", tSec);
        i.putExtra("message", msg);
        sendBroadcast(i);
    }

    private void sendError(String msg) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra("percent", -1);
        i.putExtra("framesDone", 0L);
        i.putExtra("framesTotal", 0L);
        i.putExtra("currentTime", 0d);
        i.putExtra("message", msg);
        sendBroadcast(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
