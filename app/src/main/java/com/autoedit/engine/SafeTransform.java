package com.autoedit.engine;

import com.autoedit.model.Easing;
import com.autoedit.model.KeyframeState;

/**
 * Safe cover-scale math, shared by PreviewView and FrameRenderer.
 *
 * <h3>The rule this class enforces</h3>
 * A clip must NEVER reveal an empty (near-black) wedge while it pans, zooms,
 * rotates or overshoots. The required scale is derived EXACTLY from the drawn
 * geometry instead of a blind multiplier:
 *
 * <pre>
 *   The image rect (half-size hw x hh) is centred at the canvas centre plus the
 *   pan offset (x*W, y*H) and rotated by `rotation` about the canvas centre.
 *   Rotating the canvas corners into the image's local frame gives the exact
 *   coverage requirement:
 *
 *     needW = (W/2 + |x|*W)*|cos t| + (H/2 + |y|*H)*|sin t|
 *     needH = (W/2 + |x|*W)*|sin t| + (H/2 + |y|*H)*|cos t|
 *
 *     hw = srcW*scale/2 &gt;= needW   and   hh = srcH*scale/2 &gt;= needH
 * </pre>
 *
 * {@link #coverScale} evaluates that over the whole motion path (both keyframes
 * plus the easing's overshoot band, for Back curves) and returns the worst
 * case, so every intermediate frame is covered too.
 *
 * <h3>Background layer</h3>
 * {@link #backgroundRect} returns a static, pan-free rect that always covers
 * the canvas. Renderers draw it FIRST so that even in Fit/letterbox modes, or
 * if a caller applies an extra transition offset, the pixels behind the frame
 * are the image's own colours rather than black. This is the safety net that
 * makes an accidental gap impossible by construction.
 */
public final class SafeTransform {

    /**
     * A destination rectangle in canvas pixels.
     *
     * This class is deliberately plain Java (no {@code android.graphics}) so
     * the whole cover-scale calculation is unit-testable on the JVM — which is
     * how "no black gaps" is proved rather than eyeballed.
     */
    public static final class Box {
        public final float left, top, right, bottom;
        public Box(float left, float top, float right, float bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public float width() { return right - left; }
        public float height() { return bottom - top; }
        public float centerX() { return (left + right) / 2f; }
        public float centerY() { return (top + bottom) / 2f; }
        public Box offset(float dx, float dy) { return new Box(left + dx, top + dy, right + dx, bottom + dy); }
        /** Grows (or shrinks, when negative) by the given insets. */
        public Box inset(float dx, float dy) { return new Box(left + dx, top + dy, right - dx, bottom - dy); }
        public boolean covers(float w, float h) {
            return left <= 0.5f && top <= 0.5f && right >= w - 0.5f && bottom >= h - 0.5f;
        }
        public android.graphics.RectF toRectF() { return new android.graphics.RectF(left, top, right, bottom); }
        @Override public String toString() {
            return "[" + left + "," + top + "," + right + "," + bottom + "]";
        }
    }

    /** Small extra margin above the exact geometric requirement. */
    public static final float SAFETY_MARGIN = 1.02f;
    /** Samples along the motion path used to find the worst-case coverage. */
    private static final int PATH_SAMPLES = 9;

    private SafeTransform() {}

    // ------------------------------------------------------------------ exact

    /**
     * Exact scale multiplier (relative to the plain cover scale) needed for the
     * source to fully cover the canvas at this single state.
     *
     * @return a value &gt;= 1
     */
    public static float coverScaleAt(int srcW, int srcH, int canvasW, int canvasH, KeyframeState st) {
        if (srcW <= 0 || srcH <= 0 || canvasW <= 0 || canvasH <= 0) return 1f;
        float t = (float) Math.toRadians(st.rotation);
        float cos = Math.abs((float) Math.cos(t));
        float sin = Math.abs((float) Math.sin(t));
        float halfX = canvasW / 2f + Math.abs(st.x) * canvasW;
        float halfY = canvasH / 2f + Math.abs(st.y) * canvasH;
        float needW = halfX * cos + halfY * sin;
        float needH = halfX * sin + halfY * cos;
        float sW = 2f * needW / srcW;
        float sH = 2f * needH / srcH;
        float base = Math.max(canvasW / (float) srcW, canvasH / (float) srcH);
        if (base <= 0f) return 1f;
        return Math.max(1f, Math.max(sW, sH) / base);
    }

    /** Plain cover scale (source exactly fills the canvas, no pan allowance). */
    public static float baseCoverScale(int srcW, int srcH, int canvasW, int canvasH) {
        if (srcW <= 0 || srcH <= 0) return 1f;
        return Math.max(canvasW / (float) srcW, canvasH / (float) srcH);
    }

    /**
     * Worst-case multiplier over the whole motion from {@code a} to {@code b},
     * including the easing's overshoot band so BACK_* curves cannot expose an
     * edge. Never below 1.
     */
    public static float safeScaleMultiplier(int srcW, int srcH, int canvasW, int canvasH,
                                            KeyframeState a, KeyframeState b, Easing easing) {
        if (a == null || b == null) return 1f;
        float over = easing == null ? 0f : easing.overshootAmount();
        float worst = Math.max(coverScaleAt(srcW, srcH, canvasW, canvasH, a),
                               coverScaleAt(srcW, srcH, canvasW, canvasH, b));
        for (int i = 1; i < PATH_SAMPLES; i++) {
            float t = -over + (1f + 2f * over) * (i / (float) PATH_SAMPLES);
            KeyframeState s = KeyframeState.lerp(a, b, t);
            worst = Math.max(worst, coverScaleAt(srcW, srcH, canvasW, canvasH, s));
        }
        return Math.max(1f, worst);
    }

    /** Backwards-compatible entry point used by the unit tests and callers
     *  that do not have the source pixel size at hand. */
    public static float safeScaleMultiplier(float srcAspect, float canvasAspect,
                                            KeyframeState a, KeyframeState b) {
        return safeScaleMultiplier(1000, Math.round(1000f / Math.max(0.01f, srcAspect)),
                1000, Math.round(1000f / Math.max(0.01f, canvasAspect)), a, b, null) * SAFETY_MARGIN;
    }

    // ------------------------------------------------------------------ rects

    /**
     * Cover rect for the FOREGROUND image: base cover * motion-safe multiplier
     * * the keyframe's own zoom, positioned by the pan offset. Guaranteed to
     * cover the canvas for the entire motion.
     */
    public static Box fillRect(int srcW, int srcH, int canvasW, int canvasH,
                               KeyframeState st, KeyframeState start, KeyframeState end) {
        return fillRect(srcW, srcH, canvasW, canvasH, st, start, end, null, SAFETY_MARGIN);
    }

    public static Box fillRect(int srcW, int srcH, int canvasW, int canvasH,
                               KeyframeState st, KeyframeState start, KeyframeState end,
                               Easing easing, float margin) {
        float base = baseCoverScale(srcW, srcH, canvasW, canvasH);
        float safe = safeScaleMultiplier(srcW, srcH, canvasW, canvasH, start, end, easing);
        float scale = base * safe * Math.max(0.01f, st.scale) * Math.max(0.5f, margin);
        return rect(srcW, srcH, canvasW, canvasH, st, scale);
    }

    /**
     * Tight cover rect: minimum overscan, so the subject is framed as closely
     * as the pan allows. Used by {@code FitMode.CROP}.
     */
    public static Box cropRect(int srcW, int srcH, int canvasW, int canvasH,
                               KeyframeState st, KeyframeState start, KeyframeState end,
                               Easing easing) {
        return fillRect(srcW, srcH, canvasW, canvasH, st, start, end, easing, 1f);
    }

    /**
     * Always-covering BACKGROUND rect: no pan, no zoom animation, a fixed
     * generous margin so it fills the canvas regardless of source aspect.
     * Drawn beneath the foreground by both renderers.
     */
    public static Box backgroundRect(int srcW, int srcH, int canvasW, int canvasH, float extraCover) {
        float base = baseCoverScale(srcW, srcH, canvasW, canvasH);
        float scale = base * Math.max(1f, extraCover);
        KeyframeState centre = new KeyframeState(0f, 0f, 1f, 0f, 1f);
        return rect(srcW, srcH, canvasW, canvasH, centre, scale);
    }

    /** Contain rect (letterbox). Pan/zoom still apply on top of the contain. */
    public static Box fitRect(int srcW, int srcH, int canvasW, int canvasH, KeyframeState st) {
        if (srcW <= 0 || srcH <= 0) return new Box(0, 0, canvasW, canvasH);
        float base = Math.min(canvasW / (float) srcW, canvasH / (float) srcH);
        float scale = base * Math.max(1f, st.scale);
        return rect(srcW, srcH, canvasW, canvasH, st, scale);
    }

    private static Box rect(int srcW, int srcH, int w, int h, KeyframeState st, float scale) {
        float dw = srcW * scale;
        float dh = srcH * scale;
        float cx = w / 2f + st.x * w;
        float cy = h / 2f + st.y * h;
        return new Box(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f);
    }

    // ------------------------------------------------------------ diagnostics

    /**
     * True when {@code dst} fully covers the canvas (allowing a 0.5 px
     * tolerance). Used by unit tests to prove there are no black gaps.
     */
    public static boolean coversCanvas(Box dst, int canvasW, int canvasH) {
        return dst != null && dst.covers(canvasW, canvasH);
    }
}
