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
 * Live preview. Reads ONLY from the single timeline source of truth
 * (project.clips[].durationMs) — no separate playback math.
 * Emits onFrame(time, activeClip, total) every drawn frame so the transport
 * bar, ruler playhead and timeline highlight can follow playback.
 */
public class PreviewView extends View {
    private static final String TAG = "AutoEditPreview";
    public EditProject project;
    private final FormulaEngine formulas = new FormulaEngine();
    private final EffectEngine effects = new EffectEngine();
    private final TransitionEngine transitions = new TransitionEngine();
    private final LruCache<String, Bitmap> cache;
    private long startMs = 0; private float baseTimeSec = 0f; private long lastDebugLogMs = 0;
    public boolean playing = false;
    private final Set<String> toastedErrors = new HashSet<>();
    private long lastErrorToastMs = 0;

    /** Called on the UI thread for every drawn frame (paused frames included, once per invalidate). */
    public interface OnFrame { void onTime(float timeSec, int activeClip, float totalSec); }
    public OnFrame onFrame;

    public PreviewView(Context c) {
        super(c);
        setBackgroundColor(0xff020409);
        int maxKb = (int) Math.min(64 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 5);
        cache = new LruCache<String, Bitmap>(maxKb) { @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; } };
    }

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

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff071422);
        canvas.drawRoundRect(8, 8, getWidth() - 8f, getHeight() - 8f, 24, 24, p);
        if (project == null || project.clips.isEmpty()) {
            drawCentered(canvas, "Tap  + IMAGES  to add photos\nThey will appear here", 0xff8dcaff);
            if (onFrame != null) onFrame.onTime(0f, -1, 0f);
            return;
        }
        float total = Math.max(.001f, project.totalDurationSec());
        float t = currentTimeSec();
        ClipAtTime active = findClip(t);
        try {
            drawClip(canvas, active.clip, active.progress, 1f, 1f, 0f, 0f);
            // transition window: last td seconds of the active clip reveal the next clip
            float td = Math.min(active.clip.transitionDurationSec, active.clip.durationSec / 2f);
            if (active.index < project.clips.size() - 1 && active.clip.transition != TransitionType.NONE && td > 0 && active.localTime > active.clip.durationSec - td) {
                float mix = Math.min(1f, (active.localTime - (active.clip.durationSec - td)) / td);
                TimelineClip next = project.clips.get(active.index + 1);
                TransitionEngine.Transform tr = transitions.incoming(active.clip.transition, mix);
                if (transitions.fadesThroughBackground(active.clip.transition)) {
                    Paint bg = new Paint();
                    bg.setColor(0xff071422);
                    bg.setAlpha((int) (255 * transitions.incoming(active.clip.transition, mix).alpha));
                    canvas.drawRect(8, 8, getWidth() - 8f, getHeight() - 8f, bg);
                }
                drawClip(canvas, next, 0f, tr.alpha, tr.scale, tr.dx, tr.dy);
            }
            drawTexts(canvas, t);
            logPreview(t, active, true, null);
            drawHud(canvas, t, active, total);
        } catch (Throwable e) {
            // Never swallow: log exact exception + failing URI + active clip, show a useful state.
            logPreview(t, active, false, e);
            Log.e(TAG, "Preview render failed uri=" + (active.clip == null ? "none" : active.clip.uri)
                    + " clip=" + (active.clip == null ? "none" : active.clip.index) + " time=" + t, e);
            drawErrorState(canvas, active, e);
            debugErrorToast(active, e);
        }
        if (onFrame != null) onFrame.onTime(t, active.index, total);
        if (playing) postInvalidateDelayed(33);
    }

    private static class ClipAtTime { TimelineClip clip; int index; float localTime; float progress; }

    /** One timeline model, shared by preview, ruler, export. */
    private ClipAtTime findClip(float time) {
        ClipAtTime r = new ClipAtTime();
        if (project == null || project.clips.isEmpty()) return r;
        float acc = 0;
        for (int i = 0; i < project.clips.size(); i++) {
            TimelineClip c = project.clips.get(i);
            if (time < acc + c.durationSec || i == project.clips.size() - 1) {
                r.clip = c; r.index = i;
                r.localTime = Math.min(Math.max(0, time - acc), c.durationSec);
                r.progress = Math.min(1, r.localTime / Math.max(.001f, c.durationSec));
                return r;
            }
            acc += c.durationSec;
        }
        return r;
    }

    /**
     * Draws one clip. exScale/dx/dy are the extra transition transform for
     * the incoming clip (identity for the main clip).
     */
    private void drawClip(Canvas canvas, TimelineClip clip, float progress, float alphaMul, float exScale, float dx, float dy) throws IOException {
        Bitmap b = getBitmap(clip.uri);
        if (b == null) throw new IOException("Decode returned null for: " + clip.uri);
        KeyframeState st = formulas.stateAt(clip.formula, progress);
        if (exScale != 1f) st.scale *= exScale;
        RectF dst = project.fitMode == FitMode.FIT ? fitInside(b.getWidth(), b.getHeight(), getWidth(), getHeight(), st)
                : fill(b.getWidth(), b.getHeight(), getWidth(), getHeight(), st);
        if (dx != 0f || dy != 0f) dst.offset(dx * getWidth(), dy * getHeight());
        if (project.fitMode == FitMode.FIT) drawFitBars(canvas, b, alphaMul);
        Paint paint = effects.paintFor(clip.effect, clip.effectIntensity);
        paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, alphaMul)) * st.opacity));
        canvas.save();
        canvas.rotate(st.rotation, getWidth() / 2f + dx * getWidth(), getHeight() / 2f + dy * getHeight());
        canvas.drawBitmap(b, null, dst, paint);
        canvas.restore();
        effects.drawPost(canvas, getWidth(), getHeight(), clip.effect, clip.effectIntensity * alphaMul);
    }

    private void drawHud(Canvas canvas, float t, ClipAtTime active, float total) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xccffffff); p.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        String s = String.format(Locale.US, "Clip %02d  %ds   %s / %s   %s",
                active.clip.index, Math.round(active.clip.durationSec), fmt(t), fmt(total),
                active.clip.formula == null ? "static" : active.clip.formula.name);
        canvas.drawText(s, 16, getHeight() - 14, p);
    }

    private void drawErrorState(Canvas canvas, ClipAtTime active, Throwable e) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xffffb4a6); p.setTextSize(16f * getResources().getDisplayMetrics().scaledDensity); p.setFakeBoldText(true);
        canvas.drawText("Image unavailable", getWidth() / 2f, getHeight() / 2f - 44, p);
        p.setFakeBoldText(false);
        p.setColor(0xff9eb8cc); p.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        String uri = active.clip == null ? "?" : active.clip.uri;
        if (uri.length() > 46) uri = uri.substring(0, 46) + "…";
        canvas.drawText("Clip " + (active.clip == null ? "?" : active.clip.index) + " • " + uri, getWidth() / 2f, getHeight() / 2f - 16, p);
        String reason = e == null ? "unknown" : e.getClass().getSimpleName();
        if (e != null && e.getMessage() != null && e.getMessage().length() < 60) reason += " " + e.getMessage();
        canvas.drawText("Reason: " + reason, getWidth() / 2f, getHeight() / 2f + 6, p);
        canvas.drawText("Re-import this image or check permissions, then retry.", getWidth() / 2f, getHeight() / 2f + 28, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void debugErrorToast(ClipAtTime active, Throwable e) {
        long now = System.currentTimeMillis();
        if (active.clip == null) return;
        if (!toastedErrors.add(active.clip.uri) || now - lastErrorToastMs < 3000) return;
        lastErrorToastMs = now;
        Toast.makeText(getContext(), "Preview: cannot show clip " + active.clip.index + " (" + e.getClass().getSimpleName() + ")", Toast.LENGTH_SHORT).show();
    }

    /** Dev-mode logging (preview debug mode). */
    private void logPreview(float time, ClipAtTime active, boolean success, Throwable error) {
        long now = System.currentTimeMillis();
        if (now - lastDebugLogMs < 1000 && success) return;
        lastDebugLogMs = now;
        TimelineClip c = active.clip;
        String msg = "currentTime=" + fmt(time)
                + " activeClip=" + (c == null ? "none" : c.index)
                + " clipUri=" + (c == null ? "none" : c.uri)
                + " clipDuration=" + (c == null ? 0 : c.durationMs)
                + " motion=" + (c == null || c.formula == null ? "none" : c.formula.id)
                + " formula=" + (c == null || c.formula == null ? "none" : c.formula.name)
                + " transition=" + (c == null ? "none" : c.transition)
                + " bitmapLoaded=" + success
                + " renderSuccess=" + success
                + (error == null ? "" : " error=" + error.getClass().getSimpleName() + ":" + error.getMessage());
        Log.d(TAG, msg);
    }

    private Bitmap getBitmap(String uri) throws IOException {
        String key = uri + "@" + getWidth() + "x" + getHeight();
        Bitmap b = cache.get(key);
        if (b != null && !b.isRecycled()) return b;
        b = decode(Uri.parse(uri), Math.max(1, getWidth()), Math.max(1, getHeight()));
        if (b != null) cache.put(key, b);
        return b;
    }

    private void drawFitBars(Canvas c, Bitmap b, float alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setAlpha((int) (90 * alpha));
        c.drawBitmap(b, null, fill(b.getWidth(), b.getHeight(), getWidth(), getHeight(), new KeyframeState(0, 0, 1.12f, 0, 1)), p);
        p.setColor(0xff071422); p.setAlpha((int) (170 * alpha));
        c.drawRect(0, 0, getWidth(), getHeight(), p);
    }

    private void drawTexts(Canvas c, float time) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextAlign(Paint.Align.CENTER);
        for (TextOverlay o : project.texts) {
            if (time < o.startSec || time > o.endSec) continue;
            paint.setColor(o.color); paint.setTextSize(o.size);
            paint.setFakeBoldText(o.bold); paint.setAlpha((int) (255 * o.opacity));
            c.drawText(o.text, o.x * getWidth(), o.y * getHeight(), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawCentered(Canvas c, String s, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setTextSize(15f * getResources().getDisplayMetrics().scaledDensity);
        p.setTextAlign(Paint.Align.CENTER);
        String[] lines = s.split("\n");
        float lh = 24f * getResources().getDisplayMetrics().scaledDensity;
        float y = getHeight() / 2f - (lines.length - 1) * lh / 2f;
        for (String line : lines) { c.drawText(line, getWidth() / 2f, y, p); y += lh; }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private RectF fill(int sw, int sh, int w, int h, KeyframeState st) {
        float scale = Math.max(w / (float) sw, h / (float) sh) * st.scale;
        return rect(sw, sh, w, h, st, scale);
    }

    private RectF fitInside(int sw, int sh, int w, int h, KeyframeState st) {
        float scale = Math.min(w / (float) sw, h / (float) sh) * st.scale;
        return rect(sw, sh, w, h, st, scale);
    }

    private RectF rect(int sw, int sh, int w, int h, KeyframeState st, float scale) {
        float dw = sw * scale, dh = sh * scale, cx = w / 2f + st.x * w, cy = h / 2f + st.y * h;
        return new RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2);
    }

    private Bitmap decode(Uri uri, int tw, int th) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = open(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int s = 1;
        while (bounds.outWidth / (s * 2) >= tw * 2 && bounds.outHeight / (s * 2) >= th * 2) s *= 2;
        opts.inSampleSize = Math.max(1, s);
        try (InputStream is = open(uri)) {
            return BitmapFactory.decodeStream(is, null, opts);
        }
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream is = getContext().getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open URI (permission lost or item removed): " + uri);
        return is;
    }

    private String fmt(float sec) { int s = Math.round(sec); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }
}
