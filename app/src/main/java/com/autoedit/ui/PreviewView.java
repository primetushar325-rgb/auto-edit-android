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
 * Live preview. For every frame it resolves the SAME (clipIndex, clipProgress)
 * the export uses: FormulaEngine.stateForClip(formula, clipIndex, progress) —
 * one motion per clip. Cover math comes from SafeTransform (shared with
 * FrameRenderer), so pans/slides never reveal an edge.
 */
public class PreviewView extends View {
    private static final String TAG = "AutoEditPreview";
    public EditProject project;
    private final FormulaEngine formulas = new FormulaEngine();
    private final EffectEngine effects = new EffectEngine();
    private final TransitionEngine transitions = new TransitionEngine();
    private final LruCache<String, Bitmap> cache;
    private long startMs = 0; private float baseTimeSec = 0f;
    public boolean playing = false;
    private final Set<String> toastedErrors = new HashSet<>();
    private long lastErrorToastMs = 0;
    private final Paint sharedPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

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

    private int viewW() { return Math.max(1, getWidth()); }
    private int viewH() { return Math.max(1, getHeight()); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint bg = sharedPaint;
        bg.setColor(0xff071422);
        canvas.drawRoundRect(8, 8, getWidth() - 8f, getHeight() - 8f, 24, 24, bg);
        if (project == null || project.clips.isEmpty()) {
            drawCentered(canvas, "Tap  + IMAGES  to add photos\nThey will appear here", 0xff8dcaff);
            if (onFrame != null) onFrame.onTime(0f, -1, 0f);
            return;
        }
        float total = Math.max(.001f, project.totalDurationSec());
        float t = currentTimeSec();
        ClipAtTime active = findClip(t);
        try {
            drawClip(canvas, active.clip, active.index, active.progress, 1f, 1f, 0f, 0f, 0f);

            float td = transitionDurationAt(active.clip, active.index);
            if (active.index < project.clips.size() - 1 && td > 0
                    && active.localTime > active.clip.durationSec - td) {
                float mix = Math.min(1f, (active.localTime - (active.clip.durationSec - td)) / td);
                TransitionType tt = junctionTransition(active.clip, active.index);
                if (tt != TransitionType.NONE) {
                    TransitionEngine.Transform in = transitions.incoming(tt, mix);
                    TransitionEngine.Transform out = transitions.outgoing(tt, mix);
                    if (transitions.fadesThroughBackground(tt)) {
                        bg.setColor(0xff071422);
                        bg.setAlpha((int) (255 * in.alpha));
                        canvas.drawRect(0, 0, getWidth(), getHeight(), bg);
                    }
                    if (out.dx != 0f || out.dy != 0f || out.scale != 1f || transitions.outgoingAlpha(tt, mix) < 1f) {
                        drawClip(canvas, active.clip, active.index, active.progress,
                                transitions.outgoingAlpha(tt, mix), out.scale, out.dx, out.dy, 0f);
                    }
                    TimelineClip next = project.clips.get(active.index + 1);
                    drawIncoming(canvas, next, active.index + 1, in);
                    if (transitions.flashes(tt)) {
                        int fa = (int) (255 * (1f - Math.abs(mix - 0.5f) * 2f));
                        bg.setColor(0xFFFFFFFF); bg.setAlpha(fa);
                        canvas.drawRect(0, 0, getWidth(), getHeight(), bg);
                    }
                }
            }
            drawTexts(canvas, t);
            drawHud(canvas, t, active, total);
        } catch (Throwable e) {
            Log.e(TAG, "Preview render failed uri=" + (active.clip == null ? "none" : active.clip.uri)
                    + " clip=" + (active.clip == null ? "none" : active.clip.index) + " time=" + t, e);
            drawErrorState(canvas);
            debugErrorToast(active);
        }
        if (onFrame != null) onFrame.onTime(t, active.index, total);
        if (playing) postInvalidateDelayed(33);
    }

    private static class ClipAtTime { TimelineClip clip; int index; float localTime; float progress; }

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

    private TransitionType junctionTransition(TimelineClip clip, int clipIndex) {
        TransitionType pattern = formulas.transitionForClip(clip.formula, clipIndex);
        if (pattern != null && pattern != TransitionType.NONE) return pattern;
        return clip.transition == null ? TransitionType.NONE : clip.transition;
    }

    private float transitionDurationAt(TimelineClip clip, int clipIndex) {
        if (junctionTransition(clip, clipIndex) == TransitionType.NONE) return 0f;
        return Math.min(clip.transitionDurationSec, clip.durationSec / 2f);
    }

    private void drawClip(Canvas canvas, TimelineClip clip, int clipIndex, float progress,
                          float alphaMul, float exScale, float dx, float dy, float blur) throws IOException {
        KeyframeState st = formulas.stateForClip(clip.formula, clipIndex, progress);
        KeyframeState s0 = motionStart(clip, clipIndex);
        KeyframeState s1 = motionEnd(clip, clipIndex);
        if (exScale != 1f) st.scale *= exScale;
        EffectType eff = effectiveEffect(clip, clipIndex);
        float inten = effectiveIntensity(clip, clipIndex);
        drawState(canvas, clip, st, s0, s1, alphaMul, eff, inten, dx, dy, blur);
    }

    private void drawIncoming(Canvas canvas, TimelineClip clip, int clipIndex,
                              TransitionEngine.Transform tr) throws IOException {
        KeyframeState st = formulas.stateForClip(clip.formula, clipIndex, 0f);
        KeyframeState s0 = motionStart(clip, clipIndex);
        KeyframeState s1 = motionEnd(clip, clipIndex);
        if (tr.scale != 1f) st.scale *= tr.scale;
        EffectType eff = effectiveEffect(clip, clipIndex);
        float inten = effectiveIntensity(clip, clipIndex);

        boolean masked = tr.revealRadius > 0f && tr.revealRadius < 1f;
        int saved = -1;
        if (masked) {
            saved = canvas.save();
            Path path = new Path();
            float w = getWidth(), h = getHeight();
            if (tr.circleReveal) {
                float maxR = (float) Math.hypot(w, h) / 2f;
                path.addCircle(w / 2f + tr.dx * w, h / 2f + tr.dy * h, Math.max(1f, maxR * tr.revealRadius), Path.Direction.CW);
            } else {
                float cover = Math.max(w, h) * 1.2f, ext = cover * tr.revealRadius, cx = w/2f, cy = h/2f;
                RectF r = tr.wipeAxis == 1
                        ? new RectF(cx - (tr.wipeSign < 0 ? ext : 0), -cover, cx + (tr.wipeSign > 0 ? ext : 0), h + cover)
                        : new RectF(-cover, cy - (tr.wipeSign < 0 ? ext : 0), w + cover, cy + (tr.wipeSign > 0 ? ext : 0));
                path.addRect(r, Path.Direction.CW);
            }
            canvas.clipPath(path);
        }
        drawState(canvas, clip, st, s0, s1, tr.alpha, eff, inten, tr.dx, tr.dy, tr.blurAmount);
        if (masked && saved >= 0) canvas.restoreToCount(saved);
    }

    private KeyframeState motionStart(TimelineClip clip, int clipIndex) {
        Formula m = formulas.motionForClip(clip.formula, clipIndex);
        return m != null && m.start != null ? m.start : new KeyframeState(0, 0, 1f, 0, 1);
    }
    private KeyframeState motionEnd(TimelineClip clip, int clipIndex) {
        Formula m = formulas.motionForClip(clip.formula, clipIndex);
        return m != null && m.end != null ? m.end : new KeyframeState(0, 0, 1f, 0, 1);
    }

    private EffectType effectiveEffect(TimelineClip clip, int clipIndex) {
        EffectType e = formulas.effectForClip(clip.formula, clipIndex);
        return e == null ? clip.effect : e;
    }
    private float effectiveIntensity(TimelineClip clip, int clipIndex) {
        return formulas.effectIntensityForClip(clip.formula, clipIndex, clip.effectIntensity);
    }

    private void drawState(Canvas canvas, TimelineClip clip, KeyframeState st,
                           KeyframeState startKf, KeyframeState endKf, float alphaMul,
                           EffectType eff, float intensity, float dx, float dy, float blur) throws IOException {
        Bitmap b = getBitmap(clip.uri);
        if (b == null) throw new IOException("Decode returned null for: " + clip.uri);
        int w = viewW(), h = viewH();
        RectF dst = project.fitMode == FitMode.FIT
                ? SafeTransform.fitRect(b.getWidth(), b.getHeight(), w, h, st)
                : SafeTransform.fillRect(b.getWidth(), b.getHeight(), w, h, st, startKf, endKf);
        if (dx != 0f || dy != 0f) dst.offset(dx * w, dy * h);
        if (project.fitMode == FitMode.FIT) { sharedPaint.setColorFilter(null); sharedPaint.setColor(0xff020409); canvas.drawRect(0,0,w,h,sharedPaint); }

        Paint paint = effects.paintFor(eff, intensity);
        paint.setAlpha((int) (255 * clamp01(alphaMul) * st.opacity));
        float pivotX = w / 2f + dx * w, pivotY = h / 2f + dy * h;
        canvas.save();
        canvas.rotate(st.rotation, pivotX, pivotY);
        canvas.drawBitmap(b, null, dst, paint);
        canvas.restore();

        float blurStrength = blur;
        if (eff == EffectType.BLUR) blurStrength = Math.max(blurStrength, 0.55f * intensity);
        if (eff == EffectType.MOTION_BLUR) blurStrength = Math.max(blurStrength, 0.5f * intensity);
        if (eff == EffectType.SOFT_FOCUS || eff == EffectType.DREAM) blurStrength = Math.max(blurStrength, 0.35f * intensity);
        if (blurStrength > 0.02f && alphaMul > 0.05f)
            drawSoftened(canvas, b, dst, pivotX, pivotY, st.rotation, Math.min(1f, blurStrength), clamp01(alphaMul) * st.opacity);

        effects.drawPost(canvas, w, h, eff, intensity * alphaMul);
    }

    private void drawSoftened(Canvas canvas, Bitmap b, RectF dst, float px, float py, float rot, float strength, float alpha) {
        Paint p = sharedPaint;
        p.setColorFilter(null);
        p.setAlpha((int) (70 * strength * alpha));
        float grow = 1f + 0.06f * strength;
        RectF big = new RectF(dst);
        big.inset(-dst.width() * (grow - 1) / 2f, -dst.height() * (grow - 1) / 2f);
        canvas.save();
        canvas.rotate(rot, px, py);
        canvas.drawBitmap(b, null, big, p);
        canvas.restore();
    }

    private void drawTexts(Canvas canvas, float t) {
        Paint p = sharedPaint; p.setColorFilter(null);
        p.setTextAlign(Paint.Align.CENTER);
        for (TextOverlay o : project.texts) {
            if (t < o.startSec || t > o.endSec) continue;
            p.setColor(o.color); p.setTextSize(o.size); p.setFakeBoldText(o.bold);
            p.setAlpha((int) (255 * o.opacity));
            canvas.drawText(o.text, o.x * getWidth(), o.y * getHeight(), p);
        }
        p.setFakeBoldText(false);
    }

    private void drawHud(Canvas canvas, float t, ClipAtTime active, float total) {
        Paint p = sharedPaint; p.setColorFilter(null);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(0xccffffff); p.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        String motionName = "static";
        if (active.clip != null) { Formula m = formulas.motionForClip(active.clip.formula, active.index); if (m != null) motionName = m.name; }
        canvas.drawText(String.format(Locale.US, "Clip %02d  %ds   %s / %s   %s",
                active.clip.index, Math.round(active.clip.durationSec), fmt(t), fmt(total), motionName), 16, getHeight() - 14, p);
    }

    private void drawCentered(Canvas canvas, String text, int color) {
        Paint p = sharedPaint; p.setColorFilter(null);
        p.setColor(color); p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(15f * getResources().getDisplayMetrics().scaledDensity);
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], getWidth() / 2f, getHeight() / 2f + i * 22, p);
    }

    private void drawErrorState(Canvas canvas) {
        Paint p = sharedPaint; p.setColorFilter(null);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xffffb4a6); p.setTextSize(16f * getResources().getDisplayMetrics().scaledDensity); p.setFakeBoldText(true);
        canvas.drawText("Image unavailable", getWidth() / 2f, getHeight() / 2f - 44, p);
        p.setFakeBoldText(false);
        p.setColor(0xff9eb8cc); p.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText("Tap the clip again or re-import the photo.", getWidth() / 2f, getHeight() / 2f - 18, p);
    }

    private void debugErrorToast(ClipAtTime active) {
        String uri = active.clip == null ? "none" : active.clip.uri;
        long now = System.currentTimeMillis();
        if (!toastedErrors.contains(uri) && now - lastErrorToastMs > 4000) {
            toastedErrors.add(uri); lastErrorToastMs = now;
            Toast.makeText(getContext(), "Couldn't load one image", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap getBitmap(String uri) throws IOException {
        Bitmap cached = cache.get(uri);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap b = decode(Uri.parse(uri), viewW(), viewH());
        if (b != null) cache.put(uri, b);
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

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private String fmt(float sec) { int s = Math.round(sec); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }
}
