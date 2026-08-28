package com.autoedit.ui;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.util.Log;
import android.util.LruCache;
import android.view.*;
import android.widget.Toast;
import com.autoedit.model.*;
import com.autoedit.engine.*;
import java.io.*;
import java.util.*;
import java.util.Locale;

/**
 * Live preview.
 *
 * This view owns NO rendering logic of its own: it resolves the playback clock
 * and hands every frame to {@link FrameComposer}, the same composer the MP4
 * exporter uses. That is what makes "preview == export" a structural guarantee
 * instead of a promise (spec §16, audit findings C2/C3).
 *
 * Decoding goes through a size-keyed LruCache with {@code inSampleSize}, so a
 * 1000-image project never holds full-resolution originals in memory (§38).
 */
public class PreviewView extends View {
    private static final String TAG = "AutoEditPreview";
    public EditProject project;

    private final FrameComposer composer = new FrameComposer();
    private final LruCache<String, Bitmap> cache;
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Set<String> toastedErrors = new HashSet<>();

    private long startMs = 0;
    private float baseTimeSec = 0f;
    public boolean playing = false;
    private long lastErrorToastMs = 0;

    /** Extra scale so TextOverlay sizes authored for the export canvas read
     *  correctly on the (usually smaller) preview canvas. */
    private float textScale = 1f;

    public interface OnFrame { void onTime(float timeSec, int activeClip, float totalSec); }
    public OnFrame onFrame;

    private final FrameComposer.BitmapSource source = new FrameComposer.BitmapSource() {
        @Override public Bitmap get(String uri, int w, int h) throws IOException {
            return getBitmap(uri, w, h);
        }
    };

    public PreviewView(Context c) {
        super(c);
        setBackgroundColor(0xff020409);
        int maxKb = (int) Math.min(64 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 5);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
        };
    }

    // ------------------------------------------------------------------ clock

    public void play() { if (playing) return; playing = true; startMs = System.currentTimeMillis(); invalidate(); }
    public void pause() { if (playing) baseTimeSec = currentTimeSec(); playing = false; invalidate(); }
    public void seekTo(float seconds) { baseTimeSec = Math.max(0, seconds); startMs = System.currentTimeMillis(); invalidate(); }

    public float currentTimeSec() {
        if (project == null) return 0f;
        float total = Math.max(.001f, project.totalDurationSec());
        float t = baseTimeSec;
        if (playing) t += (System.currentTimeMillis() - startMs) / 1000f;
        return t % total;
    }

    /** Text authored for the export canvas is rescaled to this view. */
    public void setExportCanvas(int exportW, int exportH) {
        if (exportW <= 0 || exportH <= 0) { textScale = 1f; return; }
        float vh = Math.max(1, getHeight());
        textScale = Math.max(0.2f, Math.min(4f, vh / (float) exportH));
    }

    private int viewW() { return Math.max(1, getWidth()); }
    private int viewH() { return Math.max(1, getHeight()); }

    // ------------------------------------------------------------------ draw

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        hudPaint.reset();
        hudPaint.setAntiAlias(true);
        hudPaint.setColor(0xff071422);
        canvas.drawRoundRect(8, 8, getWidth() - 8f, getHeight() - 8f, 24, 24, hudPaint);

        if (project == null || project.clips.isEmpty()) {
            drawCentered(canvas, "Tap  + IMAGES  to add photos\nThey will appear here", 0xff8dcaff);
            if (onFrame != null) onFrame.onTime(0f, -1, 0f);
            return;
        }

        float total = Math.max(.001f, project.totalDurationSec());
        float t = currentTimeSec();
        Timeline.Point active = Timeline.resolve(project, t);
        try {
            composer.compose(project, t, canvas, viewW(), viewH(), source, textScale);
        } catch (Throwable e) {
            Log.e(TAG, "Preview render failed uri=" + (active.clip == null ? "none" : active.clip.uri)
                    + " clip=" + (active.clip == null ? "none" : active.clip.index) + " time=" + t, e);
            drawErrorState(canvas);
            debugErrorToast(active);
        }
        drawHud(canvas, t, active, total);

        if (onFrame != null) onFrame.onTime(t, active.clipIndex, total);
        if (playing) postInvalidateDelayed(33);
    }

    // ------------------------------------------------------------------- hud

    private void drawHud(Canvas canvas, float t, Timeline.Point active, float total) {
        hudPaint.reset();
        hudPaint.setAntiAlias(true);
        hudPaint.setTextAlign(Paint.Align.LEFT);
        hudPaint.setColor(0xccffffff);
        hudPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        String motionName = "static";
        if (active.clip != null) {
            Formula m = composer.formulas().motionForClip(active.clip.formula, active.clipIndex);
            if (m != null) motionName = m.name;
        }
        int idx = active.clip == null ? 0 : active.clip.index;
        int dur = active.clip == null ? 0 : Math.round(active.clip.durationSec);
        canvas.drawText(String.format(Locale.US, "Clip %02d  %ds   %s / %s   %s",
                idx, dur, fmt(t), fmt(total), motionName), 16, getHeight() - 14, hudPaint);
    }

    private void drawCentered(Canvas canvas, String text, int color) {
        hudPaint.reset();
        hudPaint.setAntiAlias(true);
        hudPaint.setColor(color);
        hudPaint.setTextAlign(Paint.Align.CENTER);
        hudPaint.setTextSize(15f * getResources().getDisplayMetrics().scaledDensity);
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], getWidth() / 2f, getHeight() / 2f + i * 22, hudPaint);
    }

    private void drawErrorState(Canvas canvas) {
        hudPaint.reset();
        hudPaint.setAntiAlias(true);
        hudPaint.setTextAlign(Paint.Align.CENTER);
        hudPaint.setColor(0xffffb4a6);
        hudPaint.setTextSize(16f * getResources().getDisplayMetrics().scaledDensity);
        hudPaint.setFakeBoldText(true);
        canvas.drawText("Image unavailable", getWidth() / 2f, getHeight() / 2f - 44, hudPaint);
        hudPaint.setFakeBoldText(false);
        hudPaint.setColor(0xff9eb8cc);
        hudPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText("Tap the clip again or re-import the photo.", getWidth() / 2f, getHeight() / 2f - 18, hudPaint);
    }

    private void debugErrorToast(Timeline.Point active) {
        String uri = active.clip == null ? "none" : active.clip.uri;
        long now = System.currentTimeMillis();
        if (!toastedErrors.contains(uri) && now - lastErrorToastMs > 4000) {
            toastedErrors.add(uri); lastErrorToastMs = now;
            Toast.makeText(getContext(), "Couldn't load one image", Toast.LENGTH_SHORT).show();
        }
    }

    // --------------------------------------------------------------- caching

    private Bitmap getBitmap(String uri, int tw, int th) throws IOException {
        String key = uri + "_" + tw + "x" + th;
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap b = decode(Uri.parse(uri), tw, th);
        if (b == null) throw new IOException("Decode returned null for: " + uri);
        cache.put(key, b);
        return b;
    }

    private Bitmap decode(Uri uri, int tw, int th) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = open(uri)) { BitmapFactory.decodeStream(is, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int s = 1;
        while (bounds.outWidth / (s * 2) >= tw * 2 && bounds.outHeight / (s * 2) >= th * 2) s *= 2;
        opts.inSampleSize = Math.max(1, s);
        try (InputStream is = open(uri)) { return BitmapFactory.decodeStream(is, null, opts); }
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream is = getContext().getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open URI: " + uri);
        return is;
    }

    private String fmt(float sec) { int s = Math.round(sec); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }
}
