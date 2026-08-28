package com.autoedit.engine;

import android.graphics.RectF;
import com.autoedit.model.KeyframeState;

/**
 * Safe cover-scale math, shared by PreviewView and FrameRenderer.
 *
 * Instead of a fixed multiplier (old 1.10x), the extra scale is computed from
 * the ACTUAL keyframe extents of the one motion the clip plays: max pan offset
 * on each axis + half the pan travel, a rotation term from cos/sin of the max
 * rotation, times a small SAFETY_MARGIN, never below 1.
 * Priority: NO BLACK EDGE > exact requested pan distance.
 */
public final class SafeTransform {
    public static final float SAFETY_MARGIN = 1.02f;
    private SafeTransform() {}

    public static float safeScaleMultiplier(float srcAspect, float canvasAspect,
                                            KeyframeState a, KeyframeState b) {
        float maxPanX = Math.max(Math.abs(a.x), Math.abs(b.x));
        float maxPanY = Math.max(Math.abs(a.y), Math.abs(b.y));
        float travelX = Math.abs(b.x - a.x);
        float travelY = Math.abs(b.y - a.y);
        float needX = maxPanX + travelX * 0.5f;
        float needY = maxPanY + travelY * 0.5f;
        float kPanX = 1f + 2f * needX;
        float kPanY = 1f + 2f * needY;
        float kPan = Math.max(kPanX, kPanY);

        float kRot = 1f;
        float maxRot = (float) Math.toRadians(Math.max(Math.abs(a.rotation), Math.abs(b.rotation)));
        if (maxRot > 0.0001f) {
            float cos = Math.abs((float) Math.cos(maxRot));
            float sin = Math.abs((float) Math.sin(maxRot));
            float aspect = Math.max(srcAspect, canvasAspect) / Math.min(srcAspect, canvasAspect);
            kRot = cos + sin * (1f + 1f / Math.max(1f, aspect));
            kRot = Math.max(kRot, 1f / (cos + 0.0001f));
        }
        float k = Math.max(kPan, kRot) * SAFETY_MARGIN;
        return Math.max(1f, k);
    }

    public static RectF fillRect(int srcW, int srcH, int canvasW, int canvasH,
                                 KeyframeState st, KeyframeState start, KeyframeState end) {
        float srcAspect = srcW / (float) Math.max(1, srcH);
        float canvasAspect = canvasW / (float) Math.max(1, canvasH);
        float base = Math.max(canvasW / (float) srcW, canvasH / (float) srcH);
        float safe = safeScaleMultiplier(srcAspect, canvasAspect, start, end);
        float scale = base * safe * st.scale;
        return rect(srcW, srcH, canvasW, canvasH, st, scale);
    }

    public static RectF fitRect(int srcW, int srcH, int canvasW, int canvasH, KeyframeState st) {
        float base = Math.min(canvasW / (float) srcW, canvasH / (float) srcH);
        float scale = base * Math.max(1f, st.scale);
        return rect(srcW, srcH, canvasW, canvasH, st, scale);
    }

    private static RectF rect(int srcW, int srcH, int w, int h, KeyframeState st, float scale) {
        float dw = srcW * scale;
        float dh = srcH * scale;
        float cx = w / 2f + st.x * w;
        float cy = h / 2f + st.y * h;
        return new RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f);
    }
}
