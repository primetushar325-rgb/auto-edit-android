package com.autoedit.engine;

import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Bitmap;

/**
 * Stateless drawing helpers that apply a {@link TransitionEngine.Transform} to
 * a canvas IDENTICALLY in the live monitor, the animated transition cards and
 * the export renderer. Keeping the clip / perspective-matrix / mask / overlay
 * logic here (not duplicated across three renderers) is what guarantees the
 * preview and the MP4 perform the same motion.
 *
 * 3D transitions use {@link android.graphics.Camera} — real perspective
 * rotation around X/Y at a camera z-distance — not fake scaling.
 */
public final class TransitionDraw {
    private TransitionDraw() {}

    /** Save + apply 2D/3D transform. Returns a save level for restoreToCount. */
    public static int apply(Canvas c, float cx, float cy, TransitionEngine.Transform t) {
        int level = c.save();
        c.translate(t.dx + t.shakeX, t.dy + t.shakeY);
        if (Math.abs(t.rotX) > 0.5f || Math.abs(t.rotY) > 0.5f) {
            Camera cam = new Camera();
            cam.translate(0f, 0f, -Math.max(0f, (Math.abs(t.rotX) + Math.abs(t.rotY)) * 0.55f));
            cam.rotateX(t.rotX);
            cam.rotateY(t.rotY);
            cam.rotateZ(t.rotZ * 0.3f);
            Matrix m = new Matrix();
            cam.getMatrix(m);
            m.preTranslate(-cx, -cy);
            m.postTranslate(cx, cy);
            c.concat(m);
        } else if (Math.abs(t.rotZ) > 0.5f) {
            c.rotate(t.rotZ, cx, cy);
        }
        return level;
    }

    /** Single-axis squeeze around centre (squeezeX/squeezeY default 1). */
    public static void applySqueeze(Canvas c, float cx, float cy, TransitionEngine.Transform t) {
        if (Math.abs(t.squeezeX - 1f) > 0.001f || Math.abs(t.squeezeY - 1f) > 0.001f)
            c.scale(t.squeezeX, t.squeezeY, cx, cy);
    }

    /**
     * Clip to the incoming reveal mask (circle/rect/linear/shape with feather
     * already supported by renderers' feather handling). Returns save level or
     * -1 when there is no mask.
     */
    public static int clipReveal(Canvas c, float w, float h, TransitionEngine.Transform t) {
        boolean masked = t.revealRadius > 0f && t.revealRadius < 1f;
        if (!masked) return -1;
        int save = c.save();
        Path path = new Path();
        float cx = w / 2f, cy = h / 2f;
        float r = t.revealInverse ? 1f - t.revealRadius : t.revealRadius;
        if (t.shape != null && !t.shape.isEmpty() && !t.shape.equals("shape")) {
            shapePath(path, t.shape, cx, cy, w, h, r);
        } else if (t.circleReveal) {
            float maxR = (float) Math.hypot(w, h) / 2f;
            path.addCircle(cx + t.dx * w, cy + t.dy * h, Math.max(1f, maxR * r), Path.Direction.CW);
        } else {
            float cover = Math.max(w, h) * 1.5f;
            float ext = cover * r;
            if (t.wipeAxis == 2) {
                path.moveTo(t.wipeSign < 0 ? w + ext : -ext, -cover);
                path.lineTo(t.wipeSign < 0 ? w - ext : ext, -cover);
                path.lineTo(t.wipeSign < 0 ? -ext : w + ext, h + cover);
                path.lineTo(t.wipeSign < 0 ? ext : w - ext, h + cover);
                path.close();
            } else if (t.wipeSign == 0f) {
                if (t.wipeAxis == 1) path.addRect(cx - ext, -cover, cx + ext, h + cover, Path.Direction.CW);
                else path.addRect(-cover, cy - ext, w + cover, cy + ext, Path.Direction.CW);
            } else {
                RectF rr = t.wipeAxis == 1
                        ? new RectF(cx - (t.wipeSign < 0 ? ext : 0), -cover, cx + (t.wipeSign > 0 ? ext : 0), h + cover)
                        : new RectF(-cover, cy - (t.wipeSign < 0 ? ext : 0), w + cover, cy + (t.wipeSign > 0 ? ext : 0));
                path.addRect(rr, Path.Direction.CW);
            }
        }
        c.clipPath(path);
        return save;
    }

    private static void shapePath(Path path, String shape, float cx, float cy, float w, float h, float r) {
        float maxR = Math.max(2f, Math.min(w, h) * 0.75f * r);
        switch (shape) {
            case "diamond":
                path.moveTo(cx, cy - maxR); path.lineTo(cx + maxR, cy);
                path.lineTo(cx, cy + maxR); path.lineTo(cx - maxR, cy); path.close();
                break;
            case "triangle":
                path.moveTo(cx, cy - maxR); path.lineTo(cx + maxR, cy + maxR * 0.8f);
                path.lineTo(cx - maxR, cy + maxR * 0.8f); path.close();
                break;
            case "heart": {
                float s = maxR / 2f;
                path.moveTo(cx, cy + s * 1.6f);
                path.cubicTo(cx - 2.2f * s, cy + 0.2f * s, cx - 1.6f * s, cy - 1.4f * s, cx, cy - 0.4f * s);
                path.cubicTo(cx + 1.6f * s, cy - 1.4f * s, cx + 2.2f * s, cy + 0.2f * s, cx, cy + s * 1.6f);
                path.close();
                break;
            }
            case "star": {
                for (int i = 0; i < 10; i++) {
                    double ang = -Math.PI / 2 + i * (Math.PI / 5);
                    float rad = (i % 2 == 0) ? maxR : maxR * 0.45f;
                    float x = cx + (float) Math.cos(ang) * rad, y = cy + (float) Math.sin(ang) * rad;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                break;
            }
            case "hexagon": {
                for (int i = 0; i < 6; i++) {
                    double ang = Math.PI / 3 * i + Math.PI / 6;
                    float x = cx + (float) Math.cos(ang) * maxR, y = cy + (float) Math.sin(ang) * maxR;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                break;
            }
            case "roundrect":
                path.addRoundRect(new RectF(cx - maxR, cy - maxR, cx + maxR, cy + maxR),
                        maxR * 0.35f, maxR * 0.35f, Path.Direction.CW);
                break;
            case "rect":
            default:
                path.addRect(cx - maxR, cy - maxR, cx + maxR, cy + maxR, Path.Direction.CW);
                break;
        }
    }

    /** Full-frame colour wash (flash/light/dip) on top of both layers. */
    public static void drawOverlay(Canvas c, float w, float h, TransitionEngine.Transform t) {
        if (t.overlayColor == 0 || t.overlayAlpha <= 0.01f) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(t.overlayColor);
        p.setAlpha((int) (255 * clamp01(t.overlayAlpha)));
        c.drawRect(0, 0, w, h, p);
    }

    /** RGB-split / chromatic aberration: red/blue channel ghosts offset. */
    public static void drawChromaSplit(Canvas c, RectF dst, Bitmap src, float chroma, float rot, float px, float py) {
        if (chroma <= 0.01f || src == null) return;
        float off = chroma * dst.width() * 0.02f;
        Paint pr = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        pr.setColorFilter(new PorterDuffColorFilter(0xFFFF0000, PorterDuff.Mode.SCREEN));
        pr.setAlpha((int) (170 * chroma));
        Paint pb = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        pb.setColorFilter(new PorterDuffColorFilter(0xFF0055FF, PorterDuff.Mode.SCREEN));
        pb.setAlpha((int) (170 * chroma));
        c.save(); c.rotate(rot, px, py);
        c.drawBitmap(src, null, off(dst, -off, 0), pr);
        c.drawBitmap(src, null, off(dst, off, 0), pb);
        c.restore();
    }

    /** Cheap deterministic grain speckle. */
    public static void drawGrain(Canvas c, float w, float h, float grain, float seed) {
        if (grain <= 0.02f) return;
        Paint p = new Paint();
        int count = (int) (40 * grain);
        for (int i = 0; i < count; i++) {
            float rx = pseudo(seed, i, 1) * w, ry = pseudo(seed, i, 2) * h;
            p.setColor(pseudo(seed, i, 3) > 0.5f ? 0x33FFFFFF : 0x22000000);
            c.drawRect(rx, ry, rx + 3, ry + 3, p);
        }
    }

    private static RectF off(RectF r, float dx, float dy) {
        return new RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy);
    }
    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static float pseudo(float seed, int i, int k) {
        float s = (float) Math.sin(seed * 127.1f + i * 311.7f + k * 74.7f) * 43758.5453f;
        return s - (float) Math.floor(s);
    }
}
