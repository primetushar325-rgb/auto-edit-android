package com.autoedit.project;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decodes a track's peaks OFF the main thread for the timeline waveform
 * track (v1.8). One in-flight decode per URI (in-flight guard), one
 * in-memory result after that. The decode is a best-effort PCM scan with a
 * hard sample cap, so a 5-minute voice-over costs a bounded amount of CPU
 * and can never block the UI thread.
 *
 * Peak array contract: exactly {@link #BUCKETS} values, 0..1, where bucket
 * i covers source seconds {@code i * srcSec / BUCKETS}.
 */
public class WaveformCache {

    /** Number of peak buckets — the waveform track samples at this density. */
    public static final int BUCKETS = 240;

    private static final Map<String, float[]> MEM = new HashMap<>();
    private static final Set<String> INFLIGHT = Collections.synchronizedSet(new HashSet<String>());

    public interface Callback {
        /** Runs on the UI handler. {@code peaks} is empty when decode failed. */
        void onPeaks(String uri, float[] peaks);
    }

    /** Synchronous cache lookup: memory hit or null. */
    public static float[] get(String uri) {
        if (uri == null) return null;
        synchronized (MEM) {
            return MEM.get(uri);
        }
    }

    /**
     * Returns cached peaks immediately, starts a single background decode
     * when missing (later callers for the same URI just wait for the first
     * decode's callback).
     */
    public static void ensure(final Context ctx, final String uri, final Handler ui, final Callback cb) {
        if (uri == null || cb == null) return;
        final Context app = ctx.getApplicationContext();
        synchronized (MEM) {
            float[] hit = MEM.get(uri);
            if (hit != null) { ui.post(new Runnable() { public void run() { cb.onPeaks(uri, hit); } }); return; }
        }
        synchronized (INFLIGHT) {
            if (INFLIGHT.contains(uri)) return;
            INFLIGHT.add(uri);
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                float[] peaks = decode(app, uri);
                synchronized (INFLIGHT) { INFLIGHT.remove(uri); }
                if (peaks != null) {
                    synchronized (MEM) { MEM.put(uri, peaks); }
                }
                final float[] r = peaks == null ? new float[0] : peaks;
                ui.post(new Runnable() { public void run() { cb.onPeaks(uri, r); } });
            }
        }, "waveform-decode");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // -------------------------------------------------------------- decode

    /**
     * Scans up to 180s of decoded PCM and records the peak amplitude per
     * bucket. Returns null on any failure (unsupported format, no decoder,
     * unreadable URI, silence) — the caller treats that as "no waveform".
     */
    private static float[] decode(Context ctx, String uriString) {
        Uri uri = Uri.parse(uriString);
        File local = null;
        if ("file".equals(uri.getScheme())) {
            local = new File(uri.getPath());
            if (!local.exists()) return null;
        }
        MediaExtractor ex = null;
        MediaCodec dec = null;
        try {
            ex = new MediaExtractor();
            if (local != null) ex.setDataSource(local.getAbsolutePath());
            else ex.setDataSource(ctx, uri, null);
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; break; }
            }
            if (track < 0) return null;
            MediaFormat in = ex.getTrackFormat(track);
            ex.selectTrack(track);
            String mime = in.getString(MediaFormat.KEY_MIME);
            String codecName = pickCodec(mime);
            if (codecName == null) return null;

            dec = MediaCodec.createByCodecName(codecName);
            dec.configure(in, null, null, 0);
            dec.start();

            // Cap the scan: long sources never burn unbounded CPU.
            long scanUs = 180_000_000L;
            long d = durationUs(ctx, uri, local);
            if (d > 0) scanUs = Math.min(d, 180_000_000L);

            float[] sums = new float[BUCKETS];
            int channels = in.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? in.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long scannedUs = 0;
            boolean inputEos = false, outputEos = false;
            int idle = 0;

            while (!outputEos && scannedUs < scanUs) {
                if (!inputEos) {
                    int inIdx = dec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        java.nio.ByteBuffer inBuf = dec.getInputBuffer(inIdx);
                        long t0 = ex.getSampleTime();
                        int sz = ex.readSampleData(inBuf, 0);
                        ex.advance();
                        long durUs = Math.max(1L, ex.getSampleTime() - t0);
                        if (sz < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else if (scannedUs + durUs > scanUs) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            scannedUs += durUs;
                            dec.queueInputBuffer(inIdx, 0, sz, t0, 0);
                        }
                        idle = 0;
                    } else {
                        idle++;
                    }
                }
                if (idle > 60) break; // stuck on both sides: give up
                int outIdx = dec.dequeueOutputBuffer(info, 10_000);
                if (outIdx < 0) continue;
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0) {
                    java.nio.ByteBuffer outBuf = dec.getOutputBuffer(outIdx);
                    if (outBuf != null) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        // PCM16LE (AAC/MP3 decoders output this). Relative
                        // amplitude is what the waveform needs.
                        int sampleUs = 2000; // 1 sample ≈ 2000µs at 500Hz; we
                        // bucket by progress, not absolute time, below.
                        long totalSamples = (scannedUs / 2000) * channels;
                        long sampleIdx = 0;
                        while (outBuf.remaining() >= channels * 2) {
                            short v = outBuf.getShort();
                            float amp = Math.abs(v) / 32768f;
                            int bucket;
                            if (totalSamples <= 1) bucket = 0;
                            else {
                                bucket = (int) (sampleIdx * (float) BUCKETS / totalSamples);
                                if (bucket >= BUCKETS) bucket = BUCKETS - 1;
                            }
                            if (amp > sums[bucket]) sums[bucket] = amp;
                            sampleIdx++;
                        }
                    }
                }
                dec.releaseOutputBuffer(outIdx, false);
                if (eos) outputEos = true;
            }

            float[] peaks = new float[BUCKETS];
            float max = 0f;
            for (int i = 0; i < BUCKETS; i++) if (sums[i] > max) max = sums[i];
            if (max <= 0.001f) return null; // silence/undecodable
            for (int i = 0; i < BUCKETS; i++) peaks[i] = sums[i] / max;
            return peaks;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (dec != null) dec.stop(); } catch (Throwable ignored) {}
            try { if (dec != null) dec.release(); } catch (Throwable ignored) {}
            try { if (ex != null) ex.release(); } catch (Throwable ignored) {}
        }
    }

    private static long durationUs(Context ctx, Uri uri, File local) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (local != null) r.setDataSource(local.getAbsolutePath());
            else r.setDataSource(ctx, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1L : Long.parseLong(d) * 1000L;
        } catch (Throwable t) {
            return -1L;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private static String pickCodec(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo ci : list.getCodecInfos()) {
            if (ci.isEncoder()) continue;
            for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return ci.getName();
        }
        return null;
    }
}
