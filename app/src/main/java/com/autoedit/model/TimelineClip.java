package com.autoedit.model;

import org.json.*;
import java.util.ArrayList;

/**
 * ONE imported image on the timeline.
 *
 * Pure editing state: a URI plus the decisions made about it. The pixels are
 * never decoded here and the original file is never written to.
 *
 * ONE CLIP = ONE PRIMARY MOTION (spec §1). {@link #formula} is either a single
 * motion (every clip gets it) or a repeating per-clip pattern where this clip
 * plays step {@code index % steps.size()} for its WHOLE duration.
 */
public class TimelineClip {
    public String uri;
    public int index;
    public float durationSec = 5f;
    public long durationMs = 5000L;
    public Formula formula;
    /** The junction AFTER this clip (spec §46). Never inside the clip. */
    public TransitionType transition = TransitionType.CROSS_DISSOLVE;
    public float transitionDurationSec = .5f;
    /** CapCut library preset id (TransitionRegistry); null for legacy raw-enum transitions. */
    public String transitionPresetId = null;

    /**
     * Legacy single-effect fields. Kept as real fields for compatibility and
     * always mirrored to/from {@link #effectLayers} position 0.
     */
    public EffectType effect = EffectType.NONE;
    public float effectIntensity = .6f;

    /** Ordered effect stack (spec §10). Empty falls back to {@link #effect}. */
    public ArrayList<EffectLayer> effectLayers = new ArrayList<>();

    public TimelineClip(String uri, int index, Formula formula) {
        this.uri = uri; this.index = index; this.formula = formula;
    }

    // -------------------------------------------------------------- duration

    /**
     * Project-defined safe minimum clip duration. The timeline resize handles
     * clamp to this, so a clip can never be dragged to zero and the timeline
     * geometry (playhead, split, audio sync) stays stable.
     */
    public static final long MIN_DURATION_MS = 500L;
    public static final long MAX_DURATION_MS = 60_000L;

    public void setDurationSeconds(float seconds) { setDurationMs(Math.round(seconds * 1000f)); }

    public void setDurationMs(long ms) {
        long clamped = Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, ms));
        durationMs = clamped;
        durationSec = clamped / 1000f;
    }

    public long startTimeMsIn(EditProject project) {
        long t = 0;
        for (TimelineClip c : project.clips) { if (c == this) break; t += c.durationMs; }
        return t;
    }

    // -------------------------------------------------------------- effects

    /** Replaces the whole stack with one effect (used by "apply to clip"). */
    public void setSingleEffect(EffectType type, float intensity) {
        effect = type == null ? EffectType.NONE : type;
        effectIntensity = EffectLayer.clamp01(intensity);
        effectLayers.clear();
        if (effect != EffectType.NONE) effectLayers.add(new EffectLayer(effect, effectIntensity));
    }

    /** Adds (or replaces by type) one layer on top of the existing stack. */
    public void addEffectLayer(EffectType type, float intensity) {
        if (type == null || type == EffectType.NONE) return;
        for (EffectLayer l : effectLayers) if (l.type == type) { l.intensity = EffectLayer.clamp01(intensity); syncPrimary(); return; }
        effectLayers.add(new EffectLayer(type, intensity));
        syncPrimary();
    }

    public void removeEffectLayer(EffectType type) {
        for (int i = 0; i < effectLayers.size(); i++)
            if (effectLayers.get(i).type == type) { effectLayers.remove(i); break; }
        syncPrimary();
    }

    public void clearEffects() { effectLayers.clear(); syncPrimary(); }

    /** Keeps the legacy fields pointing at layer 0 so old readers stay correct. */
    private void syncPrimary() {
        if (effectLayers.isEmpty()) { effect = EffectType.NONE; return; }
        effect = effectLayers.get(0).type;
        effectIntensity = effectLayers.get(0).intensity;
    }

    /** Layers to render, in order. Never empty-NPEs; may be an empty list. */
    public ArrayList<EffectLayer> resolvedLayers() {
        if (effectLayers.isEmpty()) {
            ArrayList<EffectLayer> out = new ArrayList<>();
            if (effect != null && effect != EffectType.NONE) out.add(new EffectLayer(effect, effectIntensity));
            return out;
        }
        return effectLayers;
    }

    // ------------------------------------------------------------ serialize

    public JSONObject toJson() throws JSONException {
        setDurationSeconds(durationSec);
        JSONObject o = new JSONObject();
        o.put("uri", uri);
        o.put("index", index);
        o.put("duration", durationSec);
        o.put("durationMs", durationMs);
        o.put("formula", formula.id);
        o.put("transition", transition.name());
        if (transitionPresetId != null) o.put("transitionPreset", transitionPresetId);
        o.put("transitionDuration", transitionDurationSec);
        o.put("effect", effect.name());
        o.put("effectIntensity", effectIntensity);
        JSONArray layers = new JSONArray();
        for (EffectLayer l : effectLayers) layers.put(l.toJson());
        o.put("effectLayers", layers);
        return o;
    }
}
