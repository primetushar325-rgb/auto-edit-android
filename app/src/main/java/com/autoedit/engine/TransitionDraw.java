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
import com.autoedit.model.TransitionType;

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

    // ================================================================
    //  v1.8 multi-panel GALLERY renderers
    // ================================================================
    //  These draw BOTH clip bitmaps (out = outgoing clip, in = incoming
    //  clip) into multiple panels — the family the single-transform
    //  in/out path cannot express. FrameComposer short-circuits to this
    //  method when the junction type isGallery(), so preview and export
    //  run the identical code.
    //
    //  Depth is 2.5D: Camera 3D perspective is used only where it stays
    //  stable on low-end GPUs (filmstrip scroll, space planes, carousel
    //  ring); everything else is Canvas 2D. Panel imagery is cover-cropped
    //  (center) so any source aspect fills its panel with no letterbox.
    // ----------------------------------------------------------------

    /**
     * Draws the whole gallery transition for mix {@code m} (0 → outgoing
     * alone, 1 → incoming alone). The outgoing clip is already on the
     * canvas; panels paint over it.
     *
     * @param outBmp outgoing clip decoded at target canvas size (may be null)
     * @param inBmp  incoming clip decoded at target canvas size (may be null)
     * @param direction preset direction ("left"/"right"/"down"/"" — mirrors engine)
     * @param intensity 0..1 preset strength (nudges travel distance)
     * @param seed deterministic seed (clip index based) so the look is stable
     */
    public static void drawGallery(Canvas c, float w, float h, float m,
                                   TransitionType type, String direction, float intensity,
                                   Bitmap outBmp, Bitmap inBmp, float seed) {
        // per-call paint: preview (main thread) and export (worker) never share one
        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean right = "right".equals(direction);
        boolean down = "down".equals(direction);
        float k = 0.6f + 0.4f * clamp01(intensity); // intensity scales travel
        switch (type) {
            case GALLERY_MOTION:   gMotion(c, w, h, m, outBmp, inBmp, right, k, seed, gp); break;
            case GALLERY_WALL:     gWall(c, w, h, m, outBmp, inBmp, k, seed, false, gp); break;
            case GALLERY_WALL_V:   gWall(c, w, h, m, outBmp, inBmp, k, seed, down, gp); break;
            case GALLERY_SCROLL_3D: gScroll3D(c, w, h, m, outBmp, inBmp, right, k, gp); break;
            case GALLERY_ALIGN:    gAlign(c, w, h, m, outBmp, inBmp, seed, gp); break;
            case GALLERY_SOCIAL:   gSocial(c, w, h, m, outBmp, inBmp, k, gp); break;
            case GALLERY_FRAME:    gFrame(c, w, h, m, outBmp, inBmp, gp); break;
            case GALLERY_CAM:      gCam(c, w, h, m, outBmp, inBmp, gp); break;
            case GALLERY_SPACE:    gSpace(c, w, h, m, outBmp, inBmp, gp); break;
            case GALLERY_PREVIEW:  gPreview(c, w, h, m, outBmp, inBmp, gp); break;
            case GALLERY_GRID:     gGrid(c, w, h, m, outBmp, inBmp, seed, gp); break;
            case GALLERY_MESSY:    gMessy(c, w, h, m, outBmp, inBmp, seed, k, gp); break;
            case GALLERY_MORPH:    gMorph(c, w, h, m, outBmp, inBmp, gp); break;
            case GALLERY_CAROUSEL: gCarousel(c, w, h, m, outBmp, inBmp, gp); break;
            case GALLERY_COLUMNS:  gColumns(c, w, h, m, outBmp, inBmp, down, k, gp); break;
            default: break;
        }
    }

    /** Cover-cropped panel: draws bmp filling rect (px,py,pw,ph), centre-cropped. */
    private static void panel(Canvas c, Bitmap b, float px, float py, float pw, float ph,
                              float alpha, Paint p) {
        if (b == null || pw <= 1f || ph <= 1f) return;
        float s = Math.max(pw / b.getWidth(), ph / b.getHeight());
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        RectF dst = new RectF(px + (pw - dw) / 2f, py + (ph - dh) / 2f,
                              px + (pw - dw) / 2f + dw, py + (ph - dh) / 2f + dh);
        p.setAlpha((int) (255 * clamp01(alpha)));
        c.drawBitmap(b, null, dst, p);
    }

    /** Rounded-card panel (clip + draw) for social/preview families. */
    private static void card(Canvas c, Bitmap b, float px, float py, float pw, float ph,
                             float radius, float alpha, Paint p) {
        if (b == null || pw <= 1f || ph <= 1f) return;
        Path path = new Path();
        path.addRoundRect(new RectF(px, py, px + pw, py + ph), radius, radius, Path.Direction.CW);
        int save = c.save();
        c.clipPath(path);
        panel(c, b, px, py, pw, ph, alpha, p);
        c.restoreToCount(save);
    }

    private static float gm(float m, float start, float span) {
        return clamp01((m - start) / Math.max(0.001f, span));
    }

    /** Motion Gallery: staggered panels drift in as the outgoing set drifts out. */
    private static void gMotion(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB,
                                boolean right, float k, float seed, Paint gp) {
        int cols = 4;
        float cw = w / (float) cols;
        float dirX = (right ? -1f : 1f);
        for (int i = 0; i < cols; i++) {
            float phase = i / (float) cols;
            float mo = gm(m, phase * 0.25f, 0.55f);            // outgoing panel exit
            float mi = gm(m, 0.35f + phase * 0.25f, 0.55f);    // incoming panel enter
            float oy = (pseudo(seed, i, 1) - 0.5f) * h * 0.16f;
            panel(c, outB, dirX * cw * mo * k * 1.5f + i * cw, oy, cw + 1, h, 1f - mo, gp);
            float ix = (right ? -cw : w) + i * cw + dirX * cw * (1f - mi) * k * 1.5f;
            panel(c, inB, ix, -oy, cw + 1, h, mi, gp);
        }
    }

    /** Wall Gallery: tiled wall, diagonal (or vertical) wave. */
    private static void gWall(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB,
                              float k, float seed, boolean vertical, Paint gp) {
        int cols = 3, rows = 2;
        float gap = w * 0.012f;
        float cw = (w - gap * (cols + 1)) / cols, ch = (h - gap * (rows + 1)) / rows;
        for (int r = 0; r < rows; r++) for (int col = 0; col < cols; col++) {
            int wave = vertical ? r * 3 + col : r + col;
            float mo = gm(m, wave * 0.09f, 0.55f);
            float mi = gm(m, 0.45f + wave * 0.09f, 0.55f);
            float px = gap + col * (cw + gap), py = gap + r * (ch + gap);
            float off = (vertical ? (r - 0.5f) : (col - 1f)) * h * 0.06f * k;
            panel(c, outB, px, py + (vertical ? -off * mo : 0), cw, ch, 1f - mo, gp);
            panel(c, inB, px, py + (vertical ? off * (1f - mi) * 2f : 0), cw, ch, mi, gp);
        }
    }

    /** 3D Gallery Scroll: perspective filmstrip (real Camera), direction aware. */
    private static void gScroll3D(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB,
                                  boolean right, float k, Paint gp) {
        float dirSign = right ? -1f : 1f;
        float travel = (1f - m * 2f) * w * 1.6f * k * dirSign;
        int n = 7;
        float cw = w * 0.55f;
        Camera cam = new Camera();
        cam.rotateY(dirSign * (14f - 28f * m));           // gentle yaw while scrolling
        cam.translate(0f, 0f, -h * 0.10f);
        Matrix mat = new Matrix();
        cam.getMatrix(mat);
        mat.preTranslate(-w / 2f, -h / 2f);
        mat.postTranslate(w / 2f, h / 2f);
        int save = c.save();
        c.concat(mat);
        for (int i = 0; i < n; i++) {
            float x = w / 2f + (i - n / 2f) * cw * 0.72f + travel;
            Bitmap b = (i % 2 == 0) ? outB : inB;
            float a = 1f - 0.5f * Math.abs(x - w / 2f) / (w * 0.8f);
            panel(c, b, x - cw / 2f, h * 0.18f, cw, h * 0.64f, clamp01(a) * 0.95f, gp);
        }
        c.restoreToCount(save);
    }

    /** Gallery Alignment: scattered panels glide into a 3x3 grid. */
    private static void gAlign(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB,
                               float seed, Paint gp) {
        float e = m * m * (3f - 2f * m);
        int cols = 3, rows = 3;
        float gap = w * 0.015f;
        float cw = (w - gap * (cols + 1)) / cols, ch = (h - gap * (rows + 1)) / rows;
        for (int r = 0; r < rows; r++) for (int col = 0; col < cols; col++) {
            int i = r * cols + col;
            float px = gap + col * (cw + gap), py = gap + r * (ch + gap);
            if (m < 0.5f) {
                // outgoing set: grid → scattered
                float sx = px + (pseudo(seed, i, 2) - 0.5f) * w * 1.4f * e;
                float sy = py + (pseudo(seed, i, 3) - 0.5f) * h * 1.4f * e;
                float rot = (pseudo(seed, i, 4) - 0.5f) * 50f * e;
                c.save(); c.rotate(rot, sx + cw / 2f, sy + ch / 2f);
                panel(c, outB, sx, sy, cw, ch, 1f - e * 0.9f, gp);
                c.restore();
            } else {
                // incoming set: scattered → grid
                float f = (e - 0.5f) * 2f;
                float sx = px + (pseudo(seed + 40f, i, 2) - 0.5f) * w * 1.4f * (1f - f);
                float sy = py + (pseudo(seed + 40f, i, 3) - 0.5f) * h * 1.4f * (1f - f);
                float rot = (pseudo(seed + 40f, i, 4) - 0.5f) * 50f * (1f - f);
                c.save(); c.rotate(rot, sx + cw / 2f, sy + ch / 2f);
                panel(c, inB, sx, sy, cw, ch, f, gp);
                c.restore();
            }
        }
    }

    /** Social Gallery: feed cards stagger in from the bottom. */
    private static void gSocial(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB,
                                float k, Paint gp) {
        float cw = w * 0.86f, ch = h * 0.62f;
        // outgoing card leaves upward
        float mo = gm(m, 0f, 0.55f);
        card(c, outB, (w - cw) / 2f, -ch * mo * k, cw, ch, w * 0.04f, 1f - mo, gp);
        // two incoming cards stagger up from below
        for (int i = 0; i < 2; i++) {
            float mi = gm(m, 0.35f + i * 0.18f, 0.6f);
            float y = h + ch * (1f - mi) * k - i * ch * 0.28f * mi;
            card(c, inB, (w - cw) / 2f + (i == 0 ? 0f : w * 0.05f), y,
                    cw * (i == 0 ? 1f : 0.92f), ch * (i == 0 ? 1f : 0.9f),
                    w * 0.04f, mi, gp);
        }
    }

    /** Gallery Frame: framed centre panel crossfades with a settle. */
    private static void gFrame(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB, Paint gp) {
        float border = w * 0.05f;
        float pw = w - border * 2, ph = h * 0.62f;
        float px = border, py = (h - ph) / 2f;
        float e = m * m * (3f - 2f * m);
        // frame plate
        Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        plate.setColor(0xFF101318);
        c.drawRoundRect(new RectF(px - w * 0.015f, py - w * 0.015f, px + pw + w * 0.015f, py + ph + w * 0.015f),
                w * 0.02f, w * 0.02f, plate);
        float so = 1f + 0.12f * (1f - e);
        float si = 1f + 0.12f * (1f - e);
        c.save();
        c.scale(so, so, w / 2f, py + ph / 2f);
        panel(c, outB, px, py, pw, ph, 1f - e, gp);
        c.restore();
        c.save();
        c.scale(si, si, w / 2f, py + ph / 2f);
        panel(c, inB, px, py, pw, ph, e, gp);
        c.restore();
    }

    /** Cam Gallery: viewfinder brackets + zoom settle on both clips. */
    private static void gCam(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB, Paint gp) {
        float e = m * m * (3f - 2f * m);
        float so = 1.18f - 0.18f * e;
        float si = 1.18f - 0.18f * e;
        c.save(); c.scale(so, so, w / 2f, h / 2f);
        panel(c, outB, 0, 0, w, h, 1f - e, gp);
        c.restore();
        c.save(); c.scale(si, si, w / 2f, h / 2f);
        panel(c, inB, 0, 0, w, h, e, gp);
        c.restore();
        // viewfinder brackets
        Paint br = new Paint(Paint.ANTI_ALIAS_FLAG);
        br.setColor(0xE6FFFFFF);
        br.setStrokeWidth(Math.max(2f, w * 0.006f));
        float mgn = w * 0.09f, L = w * 0.07f;
        float[][] cs = {{mgn, mgn, 1, 1}, {w - mgn, mgn, -1, 1}, {mgn, h - mgn, 1, -1}, {w - mgn, h - mgn, -1, -1}};
        for (float[] q : cs) {
            c.drawLine(q[0], q[1], q[0] + L * q[2], q[1], br);
            c.drawLine(q[0], q[1], q[0], q[1] + L * q[3], br);
        }
        // centre crosshair
        float cx = w / 2f, cy = h / 2f, cl = w * 0.03f;
        br.setStrokeWidth(Math.max(1.5f, w * 0.004f));
        c.drawLine(cx - cl, cy, cx + cl, cy, br);
        c.drawLine(cx, cy - cl, cx, cy + cl, br);
    }

    /** Space Gallery: three depth planes fly through Camera 3D space. */
    private static void gSpace(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB, Paint gp) {
        // plane order: out (front) → in (centre) → out (far); mix slides the focus
        for (int i = 0; i < 3; i++) {
            float focus = i / 2f;                       // 0, .5, 1
            float d = (m - focus);                      // >0: plane passes the camera
            float z = d * w * 1.1f;
            float a = clamp01(1f - Math.abs(d) * 2.2f);
            if (a <= 0.01f) continue;
            Bitmap b = (i == 1) ? inB : outB;
            Camera cam = new Camera();
            cam.translate(0f, 0f, z);
            Matrix mat = new Matrix();
            cam.getMatrix(mat);
            mat.preTranslate(-w / 2f, -h / 2f);
            mat.postTranslate(w / 2f, h / 2f);
            int save = c.save();
            c.concat(mat);
            float s = 1f - Math.min(0.6f, Math.abs(z) / (w * 1.6f));
            float pw = w * s, ph = h * s;
            panel(c, b, (w - pw) / 2f, (h - ph) / 2f, pw, ph, a, gp);
            c.restoreToCount(save);
        }
    }

    /** Gallery Preview: editor card with a moving scrub line. */
    private static void gPreview(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB, Paint gp) {
        float cw = w * 0.88f, ch = h * 0.52f;
        float px = (w - cw) / 2f, py = (h - ch) / 2f;
        Paint bg = new Paint();
        bg.setColor(0xFF0B0D12);
        c.drawRect(0, 0, w, h, bg);
        // card: left half outgoing, right half incoming, split sweeps with m
        float split = clamp01(m) * cw;
        Path lp = new Path();
        lp.addRect(px, py, px + Math.max(1f, split), py + ch, Path.Direction.CW);
        int save = c.save(); c.clipPath(lp);
        card(c, outB, px, py, cw, ch, w * 0.03f, 1f, gp);
        c.restoreToCount(save);
        Path rp = new Path();
        rp.addRect(px + split, py, px + cw, py + ch, Path.Direction.CW);
        save = c.save(); c.clipPath(rp);
        card(c, inB, px, py, cw, ch, w * 0.03f, 1f, gp);
        c.restoreToCount(save);
        // card outline + scrub line + progress bar
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStrokeWidth(Math.max(1.5f, w * 0.004f));
        line.setColor(0x88FFFFFF);
        c.drawRoundRect(new RectF(px, py, px + cw, py + ch), w * 0.03f, w * 0.03f, line);
        line.setColor(0xFFFFFFFF);
        c.drawLine(px + split, py - w * 0.02f, px + split, py + ch + w * 0.02f, line);
        Paint bar = new Paint();
        bar.setColor(0x33FFFFFF);
        c.drawRect(px, py + ch + w * 0.05f, px + cw, py + ch + w * 0.05f + w * 0.012f, bar);
        bar.setColor(0xFF49A8FF);
        c.drawRect(px, py + ch + w * 0.05f, px + cw * m, py + ch + w * 0.05f + w * 0.012f, bar);
    }

    /** Gallery Grid: 3x3 checkerboard cascade. */
    private static void gGrid(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB,
                              float seed, Paint gp) {
        int n = 3, gap = Math.round(w * 0.012f);
        float cw = (w - gap * (n + 1)) / n, ch = (h - gap * (n + 1)) / n;
        for (int r = 0; r < n; r++) for (int col = 0; col < n; col++) {
            int i = r * n + col;
            float mi = gm(m, i * 0.07f, 0.6f);
            float e = mi * mi * (3f - 2f * mi);
            float px = gap + col * (cw + gap), py = gap + r * (ch + gap);
            Bitmap b = ((r + col) % 2 == 0) ? inB : outB;
            Bitmap other = ((r + col) % 2 == 0) ? outB : inB;
            float s = 0.7f + 0.3f * e;
            float dw = cw * s, dh = ch * s;
            c.save();
            c.rotate(90f * (1f - e) * ((i % 3 == 1) ? -1f : 1f) * pseudo(seed, i, 5), px + cw / 2f, py + ch / 2f);
            panel(c, other, px + (cw - dw) / 2f, py + (ch - dh) / 2f, dw, dh, 1f - e, gp);
            panel(c, b, px, py, cw, ch, e, gp);
            c.restore();
        }
    }

    /** Messy Gallery: irregular seeded panels swap with parallax. */
    private static void gMessy(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB,
                               float seed, float k, Paint gp) {
        int n = 7;
        float e = m * m * (3f - 2f * m);
        for (int i = 0; i < n; i++) {
            float bw = (0.3f + pseudo(seed, i, 1) * 0.4f) * w;
            float bh = (0.22f + pseudo(seed, i, 2) * 0.3f) * h;
            float bx = pseudo(seed, i, 3) * (w - bw);
            float by = pseudo(seed, i, 4) * (h - bh);
            float rot = (pseudo(seed, i, 5) - 0.5f) * 30f;
            float par = (pseudo(seed, i, 6) - 0.5f) * h * 0.08f * k;
            c.save();
            c.rotate(rot, bx + bw / 2f, by + bh / 2f);
            panel(c, outB, bx + par * e, by - par * e, bw, bh, (1f - e) * 0.9f, gp);
            c.restore();
            float bx2 = pseudo(seed + 90f, i, 3) * (w - bw);
            float by2 = pseudo(seed + 90f, i, 4) * (h - bh);
            float rot2 = (pseudo(seed + 90f, i, 5) - 0.5f) * 30f;
            c.save();
            c.rotate(rot2, bx2 + bw / 2f, by2 + bh / 2f);
            panel(c, inB, bx2 - par * (1f - e), by2 + par * (1f - e), bw, bh, e * 0.95f, gp);
            c.restore();
        }
    }

    /** Gallery Morph: quadrant matrix rotates/scales out and in. */
    private static void gMorph(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB, Paint gp) {
        float e = m * m * (3f - 2f * m);
        float q = w * 0.02f; // gap between quadrants
        for (int r = 0; r < 2; r++) for (int col = 0; col < 2; col++) {
            float qx = col * (w / 2f), qy = r * (h / 2f);
            float cx = qx + w / 4f, cy = qy + h / 4f;
            float ang = 90f * (1f - e) * ((r + col) % 2 == 0 ? 1f : -1f);
            float s = 1f - 0.45f * e;
            c.save();
            c.rotate(ang, cx, cy);
            c.scale(s, s, cx, cy);
            panel(c, outB, qx, qy, w / 2f - q, h / 2f - q, 1f - e, gp);
            c.restore();
            float ang2 = -90f * (1f - e) * ((r + col) % 2 == 0 ? 1f : -1f);
            float s2 = 0.55f + 0.45f * e;
            c.save();
            c.rotate(ang2, cx, cy);
            c.scale(s2, s2, cx, cy);
            panel(c, inB, qx, qy, w / 2f - q, h / 2f - q, e, gp);
            c.restore();
        }
    }

    /** Gallery Carousel: 3D ring swing (real Camera perspective). */
    private static void gCarousel(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB, Paint gp) {
        int n = 8;
        float ring = (0.5f - m) * (float) Math.PI;       // half turn across the mix
        float R = w * 0.85f;
        float pw = w * 0.62f, ph = h * 0.42f;
        // far panels first so near ones paint on top
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        float[] depth = new float[n];
        for (int i = 0; i < n; i++) depth[i] = (float) Math.cos(i * 2 * Math.PI / n + ring);
        java.util.Arrays.sort(order, (a, b) -> Float.compare(depth[a], depth[b]));
        for (int idx : order) {
            float a = idx * 2f * (float) Math.PI / n + ring;
            float z = (float) Math.cos(a) * R;
            float x = (float) Math.sin(a) * R * 0.55f;
            float aAlpha = clamp01(0.25f + 0.75f * (z / R));
            Camera cam = new Camera();
            cam.translate(0f, 0f, z * 0.55f);
            Matrix mat = new Matrix();
            cam.getMatrix(mat);
            mat.preTranslate(-w / 2f, -h / 2f);
            mat.postTranslate(w / 2f, h / 2f);
            int save = c.save();
            c.concat(mat);
            Bitmap b = (idx % 2 == 0) ? outB : inB;
            panel(c, b, w / 2f - pw / 2f + x, h / 2f - ph / 2f, pw, ph, aAlpha, gp);
            c.restoreToCount(save);
        }
    }

    /** Gallery Columns: vertical columns reveal, staggered. */
    private static void gColumns(Canvas c, float w, float h, float m, Bitmap outB, Bitmap inB,
                                 boolean down, float k, Paint gp) {
        int n = 5;
        float cw = w / (float) n;
        for (int i = 0; i < n; i++) {
            float mo = gm(m, i * 0.08f, 0.55f);
            float mi = gm(m, 0.4f + i * 0.08f, 0.6f);
            float dirY = (down ? 1f : -1f);
            c.save();
            c.clipRect(i * cw, 0, (i + 1) * cw, h);
            panel(c, outB, 0, -h * mo * k, w, h, 1f - mo, gp);
            panel(c, inB, 0, dirY * h * (1f - mi) * k, w, h, mi, gp);
            c.restore();
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
