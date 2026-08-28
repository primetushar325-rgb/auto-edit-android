package com.autoedit.export;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import com.autoedit.model.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class DiskBitmapCache {
    private final Context context;
    private final File dir;

    public DiskBitmapCache(Context context) {
        this.context = context.getApplicationContext();
        this.dir = new File(context.getCacheDir(), "scaled_export_images");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public void predecodeProject(EditProject project, ExportOptions options, VideoExporter.Listener listener) throws Exception {
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ArrayList<Future<?>> futures = new ArrayList<>();
        final int[] done = {0};
        for (TimelineClip clip : project.clips) {
            futures.add(pool.submit(() -> {
                try { ensureCached(clip.uri, options.width, options.height); }
                catch (IOException e) { throw new RuntimeException(e); }
                synchronized (done) {
                    done[0]++;
                    if (listener != null) {
                        ExportProgress p = new ExportProgress();
                        p.stage = ExportStage.OPTIMIZING;
                        p.percent = ExportStage.OPTIMIZING.percent(done[0] / (float) Math.max(1, project.clips.size()));
                        p.currentClip = done[0];
                        p.totalFrames = project.totalFrames();
                        p.message = "Optimizing images " + done[0] + " / " + project.clips.size();
                        listener.onProgress(p);
                    }
                }
            }));
        }
        pool.shutdown();
        for (Future<?> f : futures) {
            if (listener != null && listener.isCancelled()) throw new InterruptedIOException("Export cancelled");
            try { f.get(); }
            catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException && c.getCause() instanceof IOException) throw (IOException)c.getCause();
                throw new IOException("Image optimization failed", c);
            }
        }
    }

    public Bitmap decodeForRender(String uri, int targetW, int targetH) throws IOException {
        File cached = cacheFile(uri, targetW, targetH);
        if (cached.exists() && cached.length() > 0) return BitmapFactory.decodeFile(cached.getAbsolutePath());
        ensureCached(uri, targetW, targetH);
        return BitmapFactory.decodeFile(cached.getAbsolutePath());
    }

    private void ensureCached(String uri, int targetW, int targetH) throws IOException {
        File out = cacheFile(uri, targetW, targetH);
        if (out.exists() && out.length() > 0) return;
        Bitmap src = decodeSampled(Uri.parse(uri), targetW * 2, targetH * 2);
        if (src == null) throw new IOException("Unsupported or corrupt image: " + uri);
        Bitmap scaled = scaleDownIfNeeded(src, targetW * 2, targetH * 2);
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) { scaled.compress(Bitmap.CompressFormat.JPEG, 98, fos); }
        if (scaled != src) scaled.recycle();
        src.recycle();
        if (!tmp.renameTo(out)) throw new IOException("Unable to finalize cached image");
    }

    private Bitmap decodeSampled(Uri uri, int reqW, int reqH) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(is, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = sample(bounds.outWidth, bounds.outHeight, reqW, reqH);
        try (InputStream is = context.getContentResolver().openInputStream(uri)) { return BitmapFactory.decodeStream(is, null, opts); }
    }

    private Bitmap scaleDownIfNeeded(Bitmap src, int maxW, int maxH) {
        float ratio = Math.min(maxW / (float) src.getWidth(), maxH / (float) src.getHeight());
        if (ratio >= 1f) return src;
        int w = Math.max(2, Math.round(src.getWidth() * ratio));
        int h = Math.max(2, Math.round(src.getHeight() * ratio));
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    private int sample(int w, int h, int tw, int th) { int s = 1; while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2; return Math.max(1, s); }
    private File cacheFile(String uri, int w, int h) { return new File(dir, sha(uri + "_" + w + "x" + h) + ".jpg"); }
    private String sha(String s) { try { MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = md.digest(s.getBytes("UTF-8")); StringBuilder sb = new StringBuilder(); for (int i=0;i<16;i++) sb.append(String.format(Locale.US, "%02x", b[i])); return sb.toString(); } catch(Exception e) { return String.valueOf(s.hashCode()); } }
}
