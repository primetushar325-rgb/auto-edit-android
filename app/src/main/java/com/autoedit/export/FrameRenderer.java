package com.autoedit.export;

import android.content.*;
import android.graphics.*;
import android.util.LruCache;
import java.io.*;
import com.autoedit.model.*;
import com.autoedit.engine.*;

/**
 * Export frame renderer. Uses the EXACT same (clipIndex, clipProgress)
 * resolution as PreviewView: stateForClip, SafeTransform cover math,
 * TransitionEngine (incoming+outgoing+masks) and EffectEngine — preview == MP4.
 */
public class FrameRenderer {
    private final FormulaEngine formulas = new FormulaEngine();
    private final EffectEngine effects = new EffectEngine();
    private final TransitionEngine transitions = new TransitionEngine();
    private final DiskBitmapCache diskCache;
    private final LruCache<String, Bitmap> memoryCache;
    private Bitmap frameBitmap;
    private Canvas frameCanvas;

    public FrameRenderer(Context c) {
        diskCache = new DiskBitmapCache(c.getApplicationContext());
        int maxKb = (int) Math.min(96 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 4);
        memoryCache = new LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
        };
    }

    private static class At { int index; TimelineClip clip; float local, progress; }

    private At atTime(EditProject project, float timeSec) {
        At r = new At();
        r.clip = project.clips.get(0);
        float start = 0;
        for (int i = 0; i < project.clips.size(); i++) {
            TimelineClip c = project.clips.get(i);
            if (timeSec < start + c.durationSec || i == project.clips.size() - 1) {
                r.index = i; r.clip = c;
                r.local = Math.max(0, timeSec - start);
                r.progress = Math.min(1f, r.local / Math.max(.001f, c.durationSec));
                return r;
            }
            start += c.durationSec;
        }
        return r;
    }

    public Bitmap renderAtTime(EditProject project, float timeSec, int width, int height, FitMode fitMode) throws IOException {
        ensure(width, height);
        frameCanvas.drawColor(0xff020409);
        if (project.clips.isEmpty()) return frameBitmap;

        At at = atTime(project, timeSec);
        renderClip(at.clip, at.index, at.progress, width, height, fitMode, 1f, 1f, 0f, 0f, 0f);

        float td = transitionDurationAt(at.clip, at.index);
        if (td > 0 && at.index < project.clips.size() - 1 && at.local > at.clip.durationSec - td) {
            float mix = Math.min(1f, (at.local - (at.clip.durationSec - td)) / td);
            TransitionType tt = junctionTransition(at.clip, at.index);
            if (tt != TransitionType.NONE) {
                TransitionEngine.Transform in = transitions.incoming(tt, mix);
                TransitionEngine.Transform out = transitions.outgoing(tt, mix);
                if (transitions.fadesThroughBackground(tt)) {
                    Paint bg = new Paint(); bg.setColor(0xff020409); bg.setAlpha((int) (255 * in.alpha));
                    frameCanvas.drawRect(0, 0, width, height, bg);
                }
                if (out.dx != 0f || out.dy != 0f || out.scale != 1f || transitions.outgoingAlpha(tt, mix) < 1f) {
                    renderClip(at.clip, at.index, at.progress, width, height, fitMode,
                            transitions.outgoingAlpha(tt, mix), out.scale, out.dx, out.dy, 0f);
                }
                TimelineClip next = project.clips.get(at.index + 1);
                renderIncoming(next, at.index + 1, in, width, height, fitMode);
                if (transitions.flashes(tt)) {
                    int fa = (int) (255 * (1f - Math.abs(mix - .5f) * 2f));
                    Paint fl = new Paint(); fl.setColor(0xFFFFFFFF); fl.setAlpha(fa);
                    frameCanvas.drawRect(0, 0, width, height, fl);
                }
            }
        }
        drawTexts(project, timeSec, width, height);
        return frameBitmap;
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

    private void ensure(int width, int height) {
        if (frameBitmap == null || frameBitmap.getWidth() != width || frameBitmap.getHeight() != height) {
            if (frameBitmap != null) frameBitmap.recycle();
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frameCanvas = new Canvas(frameBitmap);
        }
    }

    private void renderClip(TimelineClip clip, int clipIndex, float progress, int w, int h,
                            FitMode fitMode, float alpha, float exScale, float dx, float dy, float blur) throws IOException {
        KeyframeState st = formulas.stateForClip(clip.formula, clipIndex, progress);
        KeyframeState s0 = motionStart(clip, clipIndex);
        KeyframeState s1 = motionEnd(clip, clipIndex);
        if (exScale != 1f) st.scale *= exScale;
        EffectType eff = effectFor(clip, clipIndex);
        float inten = intensityFor(clip, clipIndex);
        renderState(clip, st, s0, s1, w, h, fitMode, alpha, eff, inten, dx, dy, blur);
    }

    private void renderIncoming(TimelineClip clip, int clipIndex, TransitionEngine.Transform tr,
                                int w, int h, FitMode fitMode) throws IOException {
        KeyframeState st = formulas.stateForClip(clip.formula, clipIndex, 0f);
        KeyframeState s0 = motionStart(clip, clipIndex);
        KeyframeState s1 = motionEnd(clip, clipIndex);
        if (tr.scale != 1f) st.scale *= tr.scale;
        EffectType eff = effectFor(clip, clipIndex);
        float inten = intensityFor(clip, clipIndex);

        int saved = frameCanvas.save();
        boolean masked = tr.revealRadius > 0f && tr.revealRadius < 1f;
        if (masked) {
            Path path = new Path();
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
            frameCanvas.clipPath(path);
        }
        renderState(clip, st, s0, s1, w, h, fitMode, tr.alpha, eff, inten, tr.dx, tr.dy, tr.blurAmount);
        frameCanvas.restoreToCount(saved);
    }

    private KeyframeState motionStart(TimelineClip clip, int idx) {
        Formula m = formulas.motionForClip(clip.formula, idx);
        return m != null && m.start != null ? m.start : new KeyframeState(0, 0, 1f, 0, 1);
    }
    private KeyframeState motionEnd(TimelineClip clip, int idx) {
        Formula m = formulas.motionForClip(clip.formula, idx);
        return m != null && m.end != null ? m.end : new KeyframeState(0, 0, 1f, 0, 1);
    }
    private EffectType effectFor(TimelineClip clip, int idx) {
        EffectType e = formulas.effectForClip(clip.formula, idx);
        return e == null ? clip.effect : e;
    }
    private float intensityFor(TimelineClip clip, int idx) {
        return formulas.effectIntensityForClip(clip.formula, idx, clip.effectIntensity);
    }

    private void renderState(TimelineClip clip, KeyframeState st, KeyframeState s0, KeyframeState s1,
                             int w, int h, FitMode fitMode, float alpha,
                             EffectType eff, float intensity, float dx, float dy, float blur) throws IOException {
        Bitmap src = getBitmap(clip.uri, w, h);
        if (src == null) throw new IOException("Invalid source image: " + clip.uri);
        if (fitMode == FitMode.FIT) drawFitBackground(src, w, h, alpha);
        RectF dst = fitMode == FitMode.FIT
                ? SafeTransform.fitRect(src.getWidth(), src.getHeight(), w, h, st)
                : SafeTransform.fillRect(src.getWidth(), src.getHeight(), w, h, st, s0, s1);
        if (dx != 0f || dy != 0f) dst.offset(dx * w, dy * h);

        Paint p = effects.paintFor(eff, intensity);
        p.setAlpha((int) (255 * clamp01(alpha) * st.opacity));
        float pivotX = w / 2f + dx * w, pivotY = h / 2f + dy * h;
        frameCanvas.save();
        frameCanvas.rotate(st.rotation, pivotX, pivotY);
        frameCanvas.drawBitmap(src, null, dst, p);
        frameCanvas.restore();

        float blurStrength = blur;
        if (eff == EffectType.BLUR) blurStrength = Math.max(blurStrength, .55f * intensity);
        if (eff == EffectType.MOTION_BLUR) blurStrength = Math.max(blurStrength, .5f * intensity);
        if (eff == EffectType.SOFT_FOCUS || eff == EffectType.DREAM) blurStrength = Math.max(blurStrength, .35f * intensity);
        if (blurStrength > .02f && alpha > .05f)
            drawSoftened(src, dst, pivotX, pivotY, st.rotation, Math.min(1f, blurStrength), clamp01(alpha) * st.opacity);

        effects.drawPost(frameCanvas, w, h, eff, intensity * alpha);
    }

    private void drawSoftened(Bitmap src, RectF dst, float px, float py, float rot, float strength, float alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setAlpha((int) (64 * strength * alpha));
        float grow = 1f + .05f * strength;
        RectF big = new RectF(dst);
        big.inset(-dst.width() * (grow - 1) / 2f, -dst.height() * (grow - 1) / 2f);
        frameCanvas.save();
        frameCanvas.rotate(rot, px, py);
        frameCanvas.drawBitmap(src, null, big, p);
        frameCanvas.restore();
    }

    private void drawFitBackground(Bitmap src, int w, int h, float alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setAlpha((int) (120 * alpha));
        RectF fill = SafeTransform.fillRect(src.getWidth(), src.getHeight(), w, h,
                new KeyframeState(0, 0, 1.15f, 0, 1), new KeyframeState(0, 0, 1.15f, 0, 1), new KeyframeState(0, 0, 1.15f, 0, 1));
        frameCanvas.drawBitmap(src, null, fill, p);
        p.setColor(0xaa020409); p.setAlpha((int) (170 * alpha));
        frameCanvas.drawRect(0, 0, w, h, p);
    }

    private Bitmap getBitmap(String uri, int w, int h) throws IOException {
        String key = uri + "_" + w + "x" + h;
        Bitmap b = memoryCache.get(key);
        if (b != null && !b.isRecycled()) return b;
        b = diskCache.decodeForRender(uri, w, h);
        if (b != null) memoryCache.put(key, b);
        return b;
    }

    private void drawTexts(EditProject p, float time, int w, int h) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextAlign(Paint.Align.CENTER);
        for (TextOverlay o : p.texts) {
            if (time < o.startSec || time > o.endSec) continue;
            paint.setColor(o.color); paint.setTextSize(o.size); paint.setFakeBoldText(o.bold);
            paint.setAlpha((int) (255 * o.opacity));
            frameCanvas.drawText(o.text, o.x * w, o.y * h, paint);
        }
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    public void release() {
        if (frameBitmap != null) { frameBitmap.recycle(); frameBitmap = null; }
        memoryCache.evictAll();
    }
}
