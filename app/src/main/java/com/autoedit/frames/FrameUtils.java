package com.autoedit.frames;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pure-local frame utilities: aspect-ratio cropping (never stretches the
 * frame), a lightweight "smart crop" heuristic, image encoding (JPG/PNG/WEBP)
 * and ZIP creation. No network, no external APIs.
 */
public final class FrameUtils {

    public enum Aspect { ORIGINAL, R16_9, R9_16, R1_1, R4_5, R4_3, R3_4, R3_2, R2_3, CUSTOM }
    public enum Crop { CENTER, TOP, BOTTOM, SMART }

    private FrameUtils() {}

    public static String aspectLabel(Aspect a) {
        switch (a) {
            case ORIGINAL: return "Original";
            case R16_9: return "16:9";
            case R9_16: return "9:16";
            case R1_1: return "1:1";
            case R4_5: return "4:5";
            case R4_3: return "4:3";
            case R3_4: return "3:4";
            case R3_2: return "3:2";
            case R2_3: return "2:3";
            default: return "Custom";
        }
    }

    /** Target output dims for an aspect, given the source frame dims. */
    public static int[] targetDims(Aspect a, int srcW, int srcH, int customW, int customH) {
        switch (a) {
            case ORIGINAL: return new int[]{srcW, srcH};
            case R16_9: return dimsFor(srcW, srcH, 16f / 9f, 1920);
            case R9_16: return dimsFor(srcW, srcH, 9f / 16f, 1080);
            case R1_1: return dimsFor(srcW, srcH, 1f, 1080);
            case R4_5: return dimsFor(srcW, srcH, 4f / 5f, 1080);
            case R4_3: return dimsFor(srcW, srcH, 4f / 3f, 1440);
            case R3_4: return dimsFor(srcW, srcH, 3f / 4f, 1080);
            case R3_2: return dimsFor(srcW, srcH, 3f / 2f, 1620);
            case R2_3: return dimsFor(srcW, srcH, 2f / 3f, 1080);
            default: return new int[]{customW, customH};
        }
    }

    /** Output dims at the target ratio, capped at the source's useful scale
     *  (never upscale beyond ~1920 for a phone workflow, always even). */
    private static int[] dimsFor(int srcW, int srcH, float ratio, int capLong) {
        int w, h;
        if (ratio >= 1f) { // landscape/square-ish: width is the long side
            h = Math.min(srcH, capLong);
            w = (int) (h * ratio);
            if (w > 1920) { w = 1920; h = (int) (w / ratio); }
        } else {           // portrait: height is the long side
            w = Math.min(srcW, capLong);
            h = (int) (w / ratio);
            if (h > 1920) { h = 1920; w = (int) (h * ratio); }
        }
        w -= w % 2; h -= h % 2;
        return new int[]{Math.max(2, w), Math.max(2, h)};
    }

    /**
     * Crops the frame to `ratio` WITHOUT stretching: selects a window of the
     * source and scales it to the target dims. CENTER / TOP / BOTTOM are
     * deterministic; SMART picks the window with the highest detail energy
     * (gradient magnitude on a downsampled copy) — a real local heuristic,
     * no ML, no distortion.
     */
    /**
     * Crops {@code src} to the region the user framed with pinch-zoom and drag
     * in the preview (spec §9, §10).
     *
     * <p>{@code zoom} is the preview magnification (1 = whole frame) and
     * {@code panX}/{@code panY} are normalised offsets in [-1, 1] where -1 means
     * pushed fully to the left/top edge and +1 fully to the right/bottom. The
     * crop window is clamped to the source, so panning can never expose empty
     * space - which is what lets the user deliberately push part of a 16:9 video
     * outside the frame and still get a full frame out.
     *
     * <p>Pure arithmetic over pixel bounds, so it is unit-testable without a
     * device. Applied BEFORE {@link #fitToAspect} so the existing aspect/crop
     * pipeline is untouched: preview and extraction share this one transform,
     * which is what makes the saved frame match what the user saw.
     *
     * @return the cropped bitmap, or {@code src} itself when zoom is 1
     */
    public static Bitmap cropZoomPan(Bitmap src, float zoom, float panX, float panY) {
        if (src == null) return null;
        float z = zoom < 1f ? 1f : (zoom > 8f ? 8f : zoom);
        if (z <= 1.0001f) return src;

        int sw = src.getWidth(), sh = src.getHeight();
        int cw = Math.max(1, Math.round(sw / z));
        int ch = Math.max(1, Math.round(sh / z));

        // Travel available on each axis, then map the normalised pan onto it.
        float travelX = sw - cw, travelY = sh - ch;
        float px = panX < -1f ? -1f : (panX > 1f ? 1f : panX);
        float py = panY < -1f ? -1f : (panY > 1f ? 1f : panY);
        int x = Math.round((px + 1f) * 0.5f * travelX);
        int y = Math.round((py + 1f) * 0.5f * travelY);
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x + cw > sw) x = sw - cw;
        if (y + ch > sh) y = sh - ch;
        if (x < 0) x = 0;
        if (y < 0) y = 0;

        Bitmap out = Bitmap.createBitmap(src, x, y, cw, ch);
        if (out != src && out != null) src.recycle();
        return out;
    }

    /** The crop rect {@link #cropZoomPan} would take, as {x, y, w, h}. */
    public static int[] zoomPanRect(int sw, int sh, float zoom, float panX, float panY) {
        float z = zoom < 1f ? 1f : (zoom > 8f ? 8f : zoom);
        if (z <= 1.0001f) return new int[]{0, 0, sw, sh};
        int cw = Math.max(1, Math.round(sw / z));
        int ch = Math.max(1, Math.round(sh / z));
        float px = panX < -1f ? -1f : (panX > 1f ? 1f : panX);
        float py = panY < -1f ? -1f : (panY > 1f ? 1f : panY);
        int x = Math.round((px + 1f) * 0.5f * (sw - cw));
        int y = Math.round((py + 1f) * 0.5f * (sh - ch));
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x + cw > sw) x = sw - cw;
        if (y + ch > sh) y = sh - ch;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        return new int[]{x, y, cw, ch};
    }

    public static Bitmap fitToAspect(Bitmap src, float ratio, Crop crop, int outW, int outH) {
        int sw = src.getWidth(), sh = src.getHeight();
        float srcRatio = sw / (float) sh;
        int cw = sw, ch = sh, cx = 0, cy = 0;
        if (Math.abs(srcRatio - ratio) > 0.001f) {
            if (srcRatio > ratio) {           // source wider → crop left/right
                cw = Math.max(2, (int) (sh * ratio));
                ch = sh;
                if (crop == Crop.TOP || crop == Crop.BOTTOM) cx = (sw - cw) / 2;      // vertical bias n/a
                else if (crop == Crop.SMART) cx = smartWindow(src, cw, ch, true, 64);
                else cx = (sw - cw) / 2;
            } else {                          // source taller → crop top/bottom
                ch = Math.max(2, (int) (sw / ratio));
                cw = sw;
                if (crop == Crop.TOP) cy = 0;
                else if (crop == Crop.BOTTOM) cy = sh - ch;
                else if (crop == Crop.SMART) cy = smartWindow(src, cw, ch, false, 64);
                else cy = (sh - ch) / 2;
            }
        }
        Bitmap cropBmp = Bitmap.createBitmap(src, cx, cy, cw, ch);
        if (cropBmp.getWidth() == outW && cropBmp.getHeight() == outH) return cropBmp;
        Bitmap scaled = Bitmap.createScaledBitmap(cropBmp, outW, outH, true);
        if (scaled != cropBmp) cropBmp.recycle();
        return scaled;
    }

    /** Window with max gradient energy (x/y step). step = sample grid size. */
    private static int smartWindow(Bitmap src, int winW, int winH, boolean horizontal, int step) {
        int sw = src.getWidth(), sh = src.getHeight();
        // downsample for speed
        int dw = Math.max(16, Math.min(sw / step, 96));
        int dh = Math.max(16, Math.min(sh / step, 96));
        Bitmap small = Bitmap.createScaledBitmap(src, dw, dh, false);
        int[] px = new int[dw * dh];
        small.getPixels(px, 0, dw, 0, 0, dw, dh);
        float wWin = dw * winW / (float) sw;
        float hWin = dh * winH / (float) sh;
        int best = 0;
        double bestE = -1;
        int count = horizontal ? dw - (int) wWin : dh - (int) hWin;
        for (int p = 0; p < count; p++) {
            double e = 0;
            int ws = horizontal ? p : 0;
            int hs = horizontal ? 0 : p;
            int we = horizontal ? p + (int) wWin : dw;
            int he = horizontal ? dh : p + (int) hWin;
            for (int y = hs; y < he - 1; y++) {
                for (int x = ws; x < we - 1; x++) {
                    int c1 = px[y * dw + x], c2 = px[y * dw + x + 1], c3 = px[(y + 1) * dw + x];
                    int dr = Math.abs(((c1 >> 16) & 0xff) - ((c2 >> 16) & 0xff)) + Math.abs(((c1 >> 16) & 0xff) - ((c3 >> 16) & 0xff));
                    int dg = Math.abs(((c1 >> 8) & 0xff) - ((c2 >> 8) & 0xff)) + Math.abs(((c1 >> 8) & 0xff) - ((c3 >> 8) & 0xff));
                    int db = Math.abs((c1 & 0xff) - (c2 & 0xff)) + Math.abs((c1 & 0xff) - (c3 & 0xff));
                    e += dr + dg + db;
                }
            }
            if (e > bestE) { bestE = e; best = p; }
        }
        small.recycle();
        return horizontal ? best * sw / dw : best * sh / dh;
    }

    /** Encodes a bitmap to the requested format/quality. */
    public static void encode(Bitmap bmp, File out, String format, int quality) throws IOException {
        Bitmap.CompressFormat cf;
        switch (format.toLowerCase(Locale.US)) {
            case "png": cf = Bitmap.CompressFormat.PNG; break;
            case "webp": cf = Bitmap.CompressFormat.WEBP; break;
            default: cf = Bitmap.CompressFormat.JPEG;
        }
        try (OutputStream os = new FileOutputStream(out)) {
            if (!bmp.compress(cf, quality, os)) throw new IOException("Image encoding failed");
        }
    }

    public static String frameName(int seq, double timeSec, String ext) {
        int m = (int) (timeSec / 60);
        int s = (int) (timeSec % 60);
        return String.format(Locale.US, "frame_%04d_%02dm%02ds.%s", seq, m, s, ext);
    }

    /** Creates a ZIP with the given files (flat entries, original names). */
    public static void zip(List<File> files, File out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zos.setLevel(6);
            for (File f : files) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    zos.putNextEntry(new ZipEntry(f.getName()));
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                    zos.closeEntry();
                }
            }
        }
    }

    /** Rough per-frame JPEG byte estimate used for the storage warning. */
    public static long estimateBytes(int outW, int outH, String format, int quality, long frames) {
        double px = (double) outW * outH;
        double per;
        if ("png".equals(format)) per = px * 0.9;                       // lossless, rough
        else if ("webp".equals(format)) per = px * 0.35 * (quality / 90.0);
        else per = px * 0.30 * (quality / 90.0);                        // jpeg ~0.25-0.5 bpp
        return (long) (per * frames);
    }

    public static String fmtTime(double sec) {
        int s = (int) Math.round(sec);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /** Parses "mm:ss" or "ss" → seconds; returns null when invalid. */
    public static Double parseTime(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            if (t.contains(":")) {
                String[] p = t.split(":");
                if (p.length > 3) return null;
                double v = 0;
                for (String part : p) {
                    if (part.isEmpty()) return null;
                    v = v * 60 + Integer.parseInt(part);
                }
                return v;
            }
            double d = Double.parseDouble(t);
            return d >= 0 ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    public static Bitmap decodeSampledFile(File f, int maxDim) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes a directory tree. */
    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Copies a file to a stream (used for SAF save/share flows). */
    public static void copy(File src, OutputStream dst) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) dst.write(buf, 0, n);
        }
    }
}
