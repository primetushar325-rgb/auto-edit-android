package com.autoedit.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ONE effect on a clip, with its own intensity (spec §10, §45).
 *
 * A clip holds an ORDERED list of these and the renderer applies them in order,
 * so "Glow → Vignette → Film Grain" is three real passes, not one merged
 * approximation. The list is state; the source image is never modified.
 */
public class EffectLayer {
    public EffectType type = EffectType.NONE;
    /** 0..1 blend strength handed to {@code EffectEngine}. */
    public float intensity = 0.6f;

    public EffectLayer() {}

    public EffectLayer(EffectType type, float intensity) {
        this.type = type == null ? EffectType.NONE : type;
        this.intensity = clamp01(intensity);
    }

    public boolean isActive() { return type != null && type != EffectType.NONE; }

    public static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    public EffectLayer copy() { return new EffectLayer(type, intensity); }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type.name());
        o.put("intensity", intensity);
        return o;
    }

    public static EffectLayer fromJson(JSONObject o) {
        if (o == null) return null;
        EffectLayer l = new EffectLayer();
        try { l.type = EffectType.valueOf(o.optString("type", EffectType.NONE.name())); }
        catch (Exception e) { l.type = EffectType.NONE; }
        l.intensity = clamp01((float) o.optDouble("intensity", 0.6d));
        return l;
    }
}
