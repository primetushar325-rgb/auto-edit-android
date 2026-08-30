package com.autoedit.engine;

import android.graphics.*;
import com.autoedit.model.*;
import java.io.IOException;
import java.util.List;

/**
 * ONE frame composer, used by BOTH the live preview and the MP4 exporter
 * (spec §16, §37). Preview and export cannot diverge because they do not have
 * separate implementations — they call this.
 *
 * <h3>Render order (spec §37)</h3>
 * <pre>
 *   1. BACKGROUND LAYER   the clip's own image, cover-scaled, no pan. Always
 *                         fills the canvas, so a black wedge is impossible.
 *   2. FOREGROUND         FIT/FILL/CROP rect + motion transform + rotation.
 *   3. EFFECTS            ordered effect stack: colour pass, smear, channel
 *                         split, post overlays.
 *   4. TRANSITION         outgoing clip transform, then the incoming clip with
 *                         its reveal/wipe mask, then dips/flash/light leak.
 *   5. TEXT / OVERLAY     TextOverlay tracks.
 * </pre>
 *
 * <h3>No black gaps (spec §7)</h3>
 * The background layer is painted first and always covers the canvas, and the
 * foreground cover scale comes from {@link SafeTransform}'s exact geometry over
 * the whole motion path. Together those two make an exposed edge structurally
 * impossible rather than "tuned away".
 */
public class FrameComposer {

    /** Supplies decoded bitmaps; preview and export plug in their own caches. */
    public interface BitmapSource {
        Bitmap get(String uri, int targetW, int targetH) throws IOException;
    }

    private final FormulaEngine formulas;
    private final EffectEngine effects;
    private final TransitionEngine transitions;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint softPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF work = new RectF();
    private float textScale = 1f;

    public FrameComposer() { this(new FormulaEngine(), new EffectEngine(), new TransitionEngine()); }

    public FrameComposer(FormulaEngine f, EffectEngine e, TransitionEngine t) {
        this.formulas = f; this.effects = e; this.transitions = t;
    }

    public FormulaEngine formulas() { return formulas; }

    /**
     * Renders the frame for {@code timeSec}.
     *
     * @param canvas any canvas (a View's during preview, a Bitmap's during export)
     * @param w,h    the pixel size of that canvas
     */
    public void compose(EditProject project, float timeSec, Canvas canvas, int w, int h,
                        BitmapSource source) throws IOException {
        canvas.drawColor(0xff020409);
        if (project == null || project.clips.isEmpty()) return;
        compose(project, timeSec, canvas, w, h, source, 1f);
    }

    /** @param textScale multiplies TextOverlay font sizes so preview text matches export text. */
    public void compose(EditProject project, float timeSec, Canvas canvas, int w, int h,
                        BitmapSource source, float textScale) throws IOException {
        this.textScale = textScale;
        Timeline.Point at = Timeline.resolve(project, timeSec);
        if (at.clip == null) return;

        drawClip(canvas, at.clip, at.clipIndex, at.progress, w, h, project.fitMode, source,
                1f, 1f, 0f, 0f, 0f, 0f, null, timeSec);

        float td = transitionDurationFor(at.clip, at.clipIndex);
        if (td > 0f && at.clipIndex < project.clips.size() - 1
                && at.localSec > at.clip.durationSec - td) {
            float mix = Math.min(1f, (at.localSec - (at.clip.durationSec - td)) / td);
            drawTransition(project, canvas, at, mix, w, h, source, timeSec);
        }
        drawTexts(project, canvas, timeSec, w, h);
    }

    // ------------------------------------------------------------- transition

    private void drawTransition(EditProject project, Canvas canvas, Timeline.Point at,
                                float mix, int w, int h, BitmapSource source, float timeSec)
            throws IOException {
        TransitionType tt;
        TransitionPreset preset;
        try {
            preset = junctionPreset(at.clip, at.clipIndex);
            tt = preset != null ? preset.type : junctionTransition(at.clip, at.clipIndex);
        } catch (Throwable bad) {
            // never let a bad transition crash render/export — fall back to Fade.
            preset = TransitionRegistry.fallback();
            tt = preset.type;
        }
        if (tt == TransitionType.NONE || tt == TransitionType.CUT) return;

        TransitionEngine.Transform in = preset != null ? transitions.incoming(preset, mix) : transitions.incoming(tt, mix);
        TransitionEngine.Transform out = preset != null ? transitions.outgoing(preset, mix) : transitions.outgoing(tt, mix);

        if (transitions.fadesThroughBackground(tt)) {
            overlayPaint.reset();
            overlayPaint.setColor(tt == TransitionType.DIP_TO_WHITE ? 0xFFFFFFFF : 0xFF000000);
            overlayPaint.setAlpha((int) (255 * clamp01(in.alpha)));
            canvas.drawRect(0, 0, w, h, overlayPaint);
        }

        // Outgoing clip: redraw when the transform moved/faded it.
        if (out.alpha < 0.999f || out.dx != 0f || out.dy != 0f || out.scale != 1f
                || out.blurAmount > 0f || out.rotZ != 0f || out.rotX != 0f || out.rotY != 0f
                || out.squeezeX != 1f || out.squeezeY != 1f || out.chroma > 0f || out.shakeX != 0f || out.shakeY != 0f) {
            drawClipT(canvas, at.clip, at.clipIndex, at.progress, w, h, project.fitMode, source, out, timeSec);
        }

        // Incoming clip, with its reveal / wipe / shape mask.
        TimelineClip next = project.clips.get(at.clipIndex + 1);
        drawIncoming(canvas, next, at.clipIndex + 1, in, w, h, project.fitMode, source, timeSec);

        // Full-frame washes (flash/light/dip) + grain.
        TransitionDraw.drawOverlay(canvas, w, h, out);
        TransitionDraw.drawOverlay(canvas, w, h, in);
        if (in.grain > 0.02f || out.grain > 0.02f)
            TransitionDraw.drawGrain(canvas, w, h, Math.max(in.grain, out.grain) * 0.7f, in.seed + mix);
        if (transitions.flashes(tt)) {
            int fa = (int) (255 * (1f - Math.abs(mix - .5f) * 2f));
            if (fa > 0 && in.overlayAlpha <= 0.001f && out.overlayAlpha <= 0.001f) {
                overlayPaint.reset();
                overlayPaint.setColor(transitions.flashColor(tt));
                overlayPaint.setAlpha(fa);
                canvas.drawRect(0, 0, w, h, overlayPaint);
            }
        }
    }

    /** Library preset stored on the clip that owns the junction (null → raw enum). */
    private TransitionPreset junctionPreset(TimelineClip clip, int clipIndex) {
        TransitionType pattern = formulas.transitionForClip(clip.formula, clipIndex);
        if (pattern != null && pattern != TransitionType.NONE && pattern != TransitionType.CUT) return null;
        if (clip.transitionPresetId != null && !clip.transitionPresetId.isEmpty())
            return TransitionRegistry.byId(clip.transitionPresetId);
        return null;
    }

    private void drawIncoming(Canvas canvas, TimelineClip clip, int clipIndex,
                              TransitionEngine.Transform tr, int w, int h, FitMode fitMode,
                              BitmapSource source, float timeSec) throws IOException {
        int maskSave = TransitionDraw.clipReveal(canvas, w, h, tr);
        drawClipT(canvas, clip, clipIndex, 0f, w, h, fitMode, source, tr, timeSec);
        if (maskSave >= 0) canvas.restoreToCount(maskSave);
    }

    /** Draws one transition layer (outgoing or incoming) with the full Transform. */
    private void drawClipT(Canvas canvas, TimelineClip clip, int clipIndex, float progress,
                           int w, int h, FitMode fitMode, BitmapSource source,
                           TransitionEngine.Transform tr, float timeSec) throws IOException {
        float[] smear = tr.blurDirection != 0f ? new float[]{tr.blurDirection, 0f} : null;
        int layer = TransitionDraw.apply(canvas, w / 2f, h / 2f, tr);
        TransitionDraw.applySqueeze(canvas, w / 2f, h / 2f, tr);
        drawClip(canvas, clip, clipIndex, progress, w, h, fitMode, source,
                tr.alpha, tr.scale, tr.dx + tr.shakeX, tr.dy + tr.shakeY,
                0f, tr.blurAmount, smear, tr.chroma, timeSec);
        canvas.restoreToCount(layer);
    }

    // ---------------------------------------------------------------- one clip

    /**
     * Draws one clip. This is the single place a source bitmap becomes pixels.
     *
     * @param alphaMul   transition alpha multiplier
     * @param exScale    extra scale from a transition
     * @param dx,dy      transition offset, in canvas fractions
     * @param extraBlur  blur amount handed down by a transition
     * @param smearDir   {x,y} unit direction for directional smearing, or null
     */
    private void drawClip(Canvas canvas, TimelineClip clip, int clipIndex, float progress,
                          int w, int h, FitMode fitMode, BitmapSource source,
                          float alphaMul, float exScale, float dx, float dy,
                          float extraBlur, float transitionBlur, float[] smearDir, float timeSec)
            throws IOException {
        drawClip(canvas, clip, clipIndex, progress, w, h, fitMode, source,
                alphaMul, exScale, dx, dy, extraBlur, transitionBlur, smearDir, 0f, timeSec);
    }

    private void drawClip(Canvas canvas, TimelineClip clip, int clipIndex, float progress,
                          int w, int h, FitMode fitMode, BitmapSource source,
                          float alphaMul, float exScale, float dx, float dy,
                          float extraBlur, float transitionBlur, float[] smearDir,
                          float extraChroma, float timeSec)
            throws IOException {
        Bitmap b = source.get(clip.uri, w, h);
        if (b == null || b.isRecycled()) throw new IOException("Invalid source image: " + clip.uri);
        int sw = b.getWidth(), sh = b.getHeight();

        KeyframeState st = formulas.stateForClip(clip.formula, clipIndex, progress);
        KeyframeState s0 = formulas.motionStartForClip(clip.formula, clipIndex);
        KeyframeState s1 = formulas.motionEndForClip(clip.formula, clipIndex);
        Easing easing = formulas.easingForClip(clip.formula, clipIndex);
        if (exScale != 1f) st.scale *= exScale;
        float alpha = clamp01(alphaMul) * st.opacity;

        // ---- 1. background layer: always covers the canvas --------------------
        drawBackgroundLayer(canvas, b, sw, sh, w, h, fitMode, alpha);

        // ---- 2. foreground rect ----------------------------------------------
        SafeTransform.Box box;
        FitMode mode = FitMode.orDefault(fitMode);
        switch (mode) {
            case FIT:
            case BLUR_BG:
            case SOLID_BG:
                box = SafeTransform.fitRect(sw, sh, w, h, st);
                break;
            case CROP:
                box = SafeTransform.cropRect(sw, sh, w, h, st, s0, s1, easing);
                break;
            case FILL:
            default:
                box = SafeTransform.fillRect(sw, sh, w, h, st, s0, s1, easing, SafeTransform.SAFETY_MARGIN);
                break;
        }
        if (dx != 0f || dy != 0f) box = box.offset(dx * w, dy * h);
        RectF dst = box.toRectF();

        float pivotX = w / 2f + dx * w, pivotY = h / 2f + dy * h;
        List<EffectLayer> layers = formulas.effectLayersForClip(clip, clipIndex);

        // ---- 3. effects: colour pass -----------------------------------------
        float blur = Math.max(extraBlur, transitionBlur);
        float channelShift = extraChroma * 0.9f;   // glitch/RGB-split transitions
        boolean drawn = false;
        for (int i = 0; i < layers.size(); i++) {
            EffectLayer l = layers.get(i);
            blur = Math.max(blur, effects.blurStrengthFor(l.type, l.intensity));
            float cs = effects.channelShift(l.type, l.intensity);
            if (cs > channelShift) channelShift = cs;
        }

        if (channelShift > 0f) {
            drawn = drawChannelSplit(canvas, b, dst, pivotX, pivotY, st.rotation, channelShift,
                    alpha, layers);
        }
        if (!drawn) {
            Paint paint = combinedPaint(layers);
            paint.setAlpha((int) (255 * alpha));
            canvas.save();
            canvas.rotate(st.rotation, pivotX, pivotY);
            canvas.drawBitmap(b, null, dst, paint);
            canvas.restore();
        }

        // ---- softening halo (blur family) ------------------------------------
        if (blur > 0.02f && alpha > 0.05f)
            drawSoftened(canvas, b, dst, pivotX, pivotY, st.rotation, Math.min(1f, blur), alpha);

        // ---- directional smear -----------------------------------------------
        if (smearDir != null && transitionBlur > 0.02f)
            effects.drawDirectionalSmear(canvas, b, dst, st.rotation,
                    smearDir[0] * w * 0.03f * transitionBlur, smearDir[1] * h * 0.03f * transitionBlur,
                    transitionBlur, alpha);

        // ---- 4. post overlays --------------------------------------------------
        for (EffectLayer l : layers) effects.drawPost(canvas, w, h, l.type, l.intensity * alpha, timeSec);
    }

    /** Draws R/G/B as three slightly offset passes (chromatic aberration). */
    private boolean drawChannelSplit(Canvas canvas, Bitmap b, RectF dst, float px, float py,
                                     float rot, float shift, float alpha, List<EffectLayer> layers) {
        float off = shift * Math.max(dst.width(), dst.height());
        if (off < 0.6f) return false;
        Paint base = combinedPaint(layers);
        float[] dxs = {-off, 0f, off};
        for (int c = 0; c < 3; c++) {
            Paint p = new Paint(base);
            p.setColorFilter(effects.channelFilter(EffectType.RGB_SHIFT, c));
            p.setAlpha((int) (255 * alpha * (c == 1 ? 1f : 0.85f)));
            canvas.save();
            canvas.rotate(rot, px, py);
            work.set(dst);
            work.offset(dxs[c], 0f);
            canvas.drawBitmap(b, null, work, p);
            canvas.restore();
        }
        return true;
    }

    /** One Paint carrying the composed colour filter of the whole stack. */
    private Paint combinedPaint(List<EffectLayer> layers) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (layers.isEmpty()) return p;
        ColorMatrixColorFilter cf = effects.stackFilter(layers);
        if (cf != null) p.setColorFilter(cf);
        float a = effects.stackAlphaScale(layers);
        if (a < 1f) p.setAlpha((int) (255 * a));
        return p;
    }

    /**
     * Background layer: the clip's own image, cover-scaled with no pan, painted
     * beneath the foreground. In FILL/CROP modes the foreground hides it
     * completely; in FIT/BLUR_BG modes it fills the letterbox bars; and in every
     * mode it guarantees no black wedge if anything above ever under-covers.
     */
    private void drawBackgroundLayer(Canvas canvas, Bitmap b, int sw, int sh, int w, int h,
                                     FitMode mode, float alpha) {
        FitMode m = FitMode.orDefault(mode);
        boolean bars = m == FitMode.BLUR_BG || m == FitMode.SOLID_BG || m == FitMode.FIT;
        if (!bars) {
            // Safety net only: a static full-cover pass. Cheap, and it makes an
            // exposed edge impossible even mid-transition.
            bgPaint.reset();
            bgPaint.setFilterBitmap(true);
            bgPaint.setAlpha((int) (255 * clamp01(alpha)));
            RectF fill = SafeTransform.backgroundRect(sw, sh, w, h, 1.0f).toRectF();
            canvas.drawBitmap(b, null, fill, bgPaint);
            return;
        }
        bgPaint.reset();
        bgPaint.setFilterBitmap(true);
        if (m == FitMode.SOLID_BG) {
            bgPaint.setColor(0xff071422);
            bgPaint.setAlpha((int) (255 * clamp01(alpha)));
            canvas.drawRect(0, 0, w, h, bgPaint);
            bgPaint.setColor(0);
            return;
        }
        RectF fill = SafeTransform.backgroundRect(sw, sh, w, h, 1.18f).toRectF();
        bgPaint.setAlpha((int) (m == FitMode.FIT ? 96 * clamp01(alpha) : 210 * clamp01(alpha)));
        canvas.drawBitmap(b, null, fill, bgPaint);
        // Darken the bars so the foreground reads clearly on top.
        bgPaint.setColor(0xff020409);
        bgPaint.setAlpha((int) ((m == FitMode.FIT ? 150 : 96) * clamp01(alpha)));
        canvas.drawRect(0, 0, w, h, bgPaint);
        bgPaint.setColor(0);
    }

    private void drawSoftened(Canvas canvas, Bitmap src, RectF dst, float px, float py,
                              float rot, float strength, float alpha) {
        softPaint.reset();
        softPaint.setFilterBitmap(true);
        softPaint.setAntiAlias(true);
        softPaint.setAlpha((int) (70 * strength * alpha));
        float grow = 1f + 0.06f * strength;
        RectF big = new RectF(dst);
        big.inset(-dst.width() * (grow - 1) / 2f, -dst.height() * (grow - 1) / 2f);
        canvas.save();
        canvas.rotate(rot, px, py);
        canvas.drawBitmap(src, null, big, softPaint);
        canvas.restore();
    }

    // ------------------------------------------------------------------ texts

    private void drawTexts(EditProject p, Canvas canvas, float time, int w, int h) {
        textPaint.reset();
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        for (TextOverlay o : p.texts) {
            if (time < o.startSec || time > o.endSec) continue;
            textPaint.setColor(o.color);
            textPaint.setTextSize(o.size * textScale);
            textPaint.setFakeBoldText(o.bold);
            textPaint.setAlpha((int) (255 * o.opacity));
            canvas.drawText(o.text, o.x * w, o.y * h, textPaint);
        }
        textPaint.setFakeBoldText(false);
    }

    // -------------------------------------------------------------- junctions

    /** The transition for the junction AFTER this clip. Pattern wins over clip. */
    public TransitionType junctionTransition(TimelineClip clip, int clipIndex) {
        TransitionType pattern = formulas.transitionForClip(clip.formula, clipIndex);
        if (pattern != null && pattern != TransitionType.NONE && pattern != TransitionType.CUT) return pattern;
        return clip.transition == null ? TransitionType.NONE : clip.transition;
    }

    /**
     * Safe junction length: never more than half of either neighbouring clip,
     * so a short clip can never be swallowed by its own transition (spec §9).
     */
    public float transitionDurationFor(TimelineClip clip, int clipIndex) {
        TransitionType t = junctionTransition(clip, clipIndex);
        if (t == TransitionType.NONE || t == TransitionType.CUT) return 0f;
        return Math.max(0f, Math.min(clip.transitionDurationSec, clip.durationSec / 2f));
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
