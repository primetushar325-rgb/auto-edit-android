package com.autoedit.model;

import org.json.JSONObject;
import org.json.JSONException;

/**
 * MASTER BORDER / FRAME EFFECT — per-clip state.
 *
 * Each TimelineClip can have one border/frame (distinct from Transition).
 * Stored as lightweight metadata only (preset id + overrides), so 1000 clips
 * cost ~metadata, not bitmaps. Rendered procedurally via {@link com.autoedit.engine.BorderRenderer}
 * on both preview (FrameComposer) and export.
 *
 * Spec § Master Border Engine:
 * - presetId -> BorderEffectRegistry (≥20 real effects)
 * - intensity/opacity/speed/thickness/glow/corner/direction
 * - primary/secondary colors (override)
 * - duration: entire clip (startMs==0 && endMs==0) or custom range
 * - persistence: JSON via TimelineClip + ProjectStore
 */
public class BorderEffectConfig {
    public String presetId;
    public float intensity = 1f;   // 0..1 overall strength
    public float opacity = 1f;     // 0..1 border opacity
    public float speed = 0.7f;     // 0..1 animation speed
    public float thickness = 8f;   // dp 1..20
    public float glow = 6f;        // 0..12 blur/glow radius
    public int direction = 0;      // 0 cw, 1 ccw, 2 alternate
    public float cornerRadius = 12f; // dp 0..32
    public int primaryColor = 0;   // 0 = use preset
    public int secondaryColor = 0; // 0 = use preset
    /** Custom range within clip: 0 means start of clip, 0 means until end. In ms. */
    public long startMs = 0;
    public long endMs = 0;

    public BorderEffectConfig() {}
    public BorderEffectConfig(String presetId) { this.presetId = presetId; }

    public BorderEffectConfig copy() {
        BorderEffectConfig c = new BorderEffectConfig();
        c.presetId = this.presetId;
        c.intensity = this.intensity;
        c.opacity = this.opacity;
        c.speed = this.speed;
        c.thickness = this.thickness;
        c.glow = this.glow;
        c.direction = this.direction;
        c.cornerRadius = this.cornerRadius;
        c.primaryColor = this.primaryColor;
        c.secondaryColor = this.secondaryColor;
        c.startMs = this.startMs;
        c.endMs = this.endMs;
        return c;
    }

    /** Whether this border should be drawn at localSec within clip duration. */
    public boolean isActiveAt(float localSec, float clipDurationSec) {
        if (presetId == null || presetId.isEmpty()) return false;
        if (startMs == 0 && endMs == 0) return true;
        float s = startMs / 1000f;
        float e = endMs <= 0 ? clipDurationSec : endMs / 1000f;
        return localSec >= s && localSec <= e;
    }

    public boolean isWholeClip() { return startMs == 0 && endMs == 0; }

    public static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("presetId", presetId == null ? "" : presetId);
        o.put("intensity", clamp01(intensity));
        o.put("opacity", clamp01(opacity));
        o.put("speed", clamp01(speed));
        o.put("thickness", Math.max(1f, Math.min(20f, thickness)));
        o.put("glow", Math.max(0f, Math.min(12f, glow)));
        o.put("direction", direction);
        o.put("cornerRadius", Math.max(0f, Math.min(32f, cornerRadius)));
        if (primaryColor != 0) o.put("primaryColor", primaryColor);
        if (secondaryColor != 0) o.put("secondaryColor", secondaryColor);
        if (startMs != 0) o.put("startMs", startMs);
        if (endMs != 0) o.put("endMs", endMs);
        return o;
    }

    public static BorderEffectConfig fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            String id = o.optString("presetId", "");
            if (id.isEmpty()) id = o.optString("effectId", "");
            if (id.isEmpty()) return null;
            BorderEffectConfig c = new BorderEffectConfig(id);
            c.intensity = (float) o.optDouble("intensity", 1.0);
            c.opacity = (float) o.optDouble("opacity", 1.0);
            if (o.has("alpha")) c.opacity = (float) o.optDouble("alpha", c.opacity);
            c.speed = (float) o.optDouble("speed", 0.7);
            c.thickness = (float) o.optDouble("thickness", 8);
            c.glow = (float) o.optDouble("glow", 6);
            if (o.has("blur")) c.glow = (float) o.optDouble("blur", c.glow);
            c.direction = o.optInt("direction", 0);
            c.cornerRadius = (float) o.optDouble("cornerRadius", 12);
            if (o.has("corner")) c.cornerRadius = (float) o.optDouble("corner", c.cornerRadius);
            c.primaryColor = o.optInt("primaryColor", 0);
            if (o.has("color")) c.primaryColor = o.optInt("color", c.primaryColor);
            c.secondaryColor = o.optInt("secondaryColor", 0);
            c.startMs = o.optLong("startMs", 0);
            c.endMs = o.optLong("endMs", 0);
            if (o.has("effectStart")) c.startMs = o.optLong("effectStart", c.startMs);
            if (o.has("effectEnd")) c.endMs = o.optLong("effectEnd", c.endMs);
            c.intensity = clamp01(c.intensity);
            c.opacity = clamp01(c.opacity);
            c.speed = clamp01(c.speed);
            return c;
        } catch (Exception e) { return null; }
    }
}
