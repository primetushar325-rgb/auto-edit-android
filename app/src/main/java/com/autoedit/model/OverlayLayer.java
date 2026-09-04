package com.autoedit.model;

import org.json.JSONObject;
import org.json.JSONException;

/**
 * One independent OVERLAY LAYER on the project (v1.8 layer system, spec §10b).
 *
 * Overlays sit ABOVE the clip, its effects and the transition, but text
 * blocks (TextOverlay) stay a separate, older system — both are drawn in the
 * same order in preview AND export (FrameComposer), so what you see is what
 * gets muxed.
 *
 * Geometry is normalised (0..1 of the canvas) so the same layer renders at
 * the same relative spot on any export size. Timing: {@code startSec} to
 * {@code endSec}; {@code endSec < 0} means "until the project ends".
 *
 * Corner presets (logo/watermark placement): the corner string + margin are
 * stored so the user can re-apply the same placement to another overlay;
 * when the corner is not empty the x/y values were derived from it.
 */
public class OverlayLayer {

    public enum Kind { IMAGE, TEXT }

    public String id = java.util.UUID.randomUUID().toString();
    public Kind kind = Kind.IMAGE;

    // ---- IMAGE
    public String uri = null;

    // ---- TEXT
    public String text = "Text";
    public int color = 0xffffffff;
    public boolean bold = true;
    /** Base font size in px at a 1080px-wide canvas (scaled to the real canvas). */
    public float textSize = 64f;

    // ---- geometry (normalised canvas fractions, centre-based)
    public float x = 0.5f;
    public float y = 0.5f;
    /** 1.0 = the layer is 45% of the canvas width. */
    public float scale = 1f;
    /** Degrees, clockwise. */
    public float rotation = 0f;
    /** 0..1 */
    public float opacity = 1f;

    // ---- timing (seconds on the project timeline)
    public float startSec = 0f;
    /** &lt; 0 → until the project ends. */
    public float endSec = -1f;

    // ---- UI state
    public boolean locked = false;
    public boolean hidden = false;

    // ---- corner preset
    /** "" | top-left | top-right | bottom-left | bottom-right | center */
    public String corner = "";
    /** Fraction of the canvas min side from each edge. */
    public float cornerMargin = 0.06f;

    /** End time resolved against the project (endSec<0 → project end). */
    public float endResolvedSec(EditProject p) {
        float total = p == null ? 0f : p.totalDurationSec();
        return endSec < 0f ? total : Math.min(endSec, total);
    }

    /** Layer length in seconds on the given project. Never below 1ms. */
    public float durationSec(EditProject p) {
        return Math.max(0.001f, endResolvedSec(p) - Math.max(0f, startSec));
    }

    /** True when the layer is visible at absolute time t of project p. */
    public boolean activeAt(float t, EditProject p) {
        if (hidden) return false;
        if (t < startSec) return false;
        return t < endResolvedSec(p);
    }

    /**
     * Places this layer's centre according to the stored corner preset.
     * margin = fraction of the canvas min side from each edge.
     */
    public void applyCornerPreset(String cornerName, float margin) {
        this.corner = cornerName == null ? "" : cornerName;
        this.cornerMargin = Math.max(0.01f, margin);
        if (this.corner.isEmpty()) return;
        float m = this.cornerMargin;
        // half-width of the layer as a canvas fraction (width-based, 45% at scale 1)
        float hw = 0.225f * Math.max(0.05f, scale);
        float hh = 0.225f * Math.max(0.05f, scale);
        switch (this.corner) {
            case "top-left":     x = m + hw; y = m + hh; break;
            case "top-right":    x = 1f - m - hw; y = m + hh; break;
            case "bottom-left":  x = m + hw; y = 1f - m - hh; break;
            case "bottom-right": x = 1f - m - hw; y = 1f - m - hh; break;
            case "center":       x = 0.5f; y = 0.5f; break;
            default: break;
        }
    }

    // ------------------------------------------------------------ serialize

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("kind", kind.name());
        if (uri != null) o.put("uri", uri);
        if (kind == Kind.TEXT) {
            o.put("text", text);
            o.put("color", color);
            o.put("bold", bold);
            o.put("textSize", textSize);
        }
        o.put("x", x);
        o.put("y", y);
        o.put("scale", scale);
        o.put("rotation", rotation);
        o.put("opacity", opacity);
        o.put("startSec", startSec);
        o.put("endSec", endSec);
        o.put("locked", locked);
        o.put("hidden", hidden);
        if (corner != null && !corner.isEmpty()) {
            o.put("corner", corner);
            o.put("cornerMargin", cornerMargin);
        }
        return o;
    }

    /** Defensive parse: bad values fall back to defaults, never throws. */
    public static OverlayLayer fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            return parse(o);
        } catch (Throwable t) {
            // Corrupt entry: keep the layer alive with safe defaults.
            OverlayLayer l = new OverlayLayer();
            try {
                String keep = o.optString("id", null);
                if (keep != null && !keep.isEmpty() && !"null".equals(keep)) l.id = keep;
            } catch (Throwable ignored) {}
            return l;
        }
    }

    private static OverlayLayer parse(JSONObject o) {
        OverlayLayer l = new OverlayLayer();
        String id = o.optString("id", null);
        if (id != null && !id.isEmpty() && !"null".equals(id)) l.id = id;
        try { l.kind = Kind.valueOf(o.optString("kind", Kind.IMAGE.name())); }
        catch (Exception e) { l.kind = Kind.IMAGE; }
        String u = o.optString("uri", null);
        if (u != null && !u.isEmpty() && !"null".equals(u)) l.uri = u;
        l.text = o.optString("text", l.text);
        l.color = (int) (o.optDouble("color", l.color));
        l.bold = o.optBoolean("bold", l.bold);
        l.textSize = (float) o.optDouble("textSize", l.textSize);
        l.x = (float) o.optDouble("x", l.x);
        l.y = (float) o.optDouble("y", l.y);
        l.scale = (float) o.optDouble("scale", l.scale);
        l.rotation = (float) o.optDouble("rotation", l.rotation);
        l.opacity = (float) o.optDouble("opacity", l.opacity);
        l.startSec = (float) o.optDouble("startSec", l.startSec);
        l.endSec = (float) o.optDouble("endSec", l.endSec);
        l.locked = o.optBoolean("locked", false);
        l.hidden = o.optBoolean("hidden", false);
        l.corner = o.optString("corner", "");
        l.cornerMargin = (float) o.optDouble("cornerMargin", l.cornerMargin);
        // sanitize so a corrupt entry can never break the composer
        l.x = clamp01(l.x);
        l.y = clamp01(l.y);
        l.scale = Math.max(0.05f, Math.min(l.scale, 3f));
        l.opacity = clamp01(l.opacity);
        l.textSize = Math.max(8f, Math.min(l.textSize, 400f));
        if (l.kind == Kind.IMAGE && (l.uri == null || l.uri.isEmpty())) l.kind = Kind.TEXT;
        return l;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
