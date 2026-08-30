package com.autoedit.model;

import java.util.Arrays;
import java.util.List;

/**
 * One entry in the CapCut-style transition library. A preset is a *UI item*
 * (name, category, metadata, parameters) that points at a TransitionType
 * renderer. Many presets can share a renderer (e.g. direction/colour variants);
 * {@link #direction} and {@link #tint} feed TransitionEngine so the same math
 * covers them. Adding a transition = adding one register(...) call; the UI,
 * search, recents and renderers all read it from the registry.
 */
public class TransitionPreset {
    public final String id;
    public final String name;
    public final TransitionCategory category;
    public final TransitionType type;     // renderer / source of truth
    public final float defaultDuration;   // seconds
    public final float minDuration;
    public final float maxDuration;
    public final String direction;        // "" | left|right|up|down|cw|ccw|in|out|center
    public final int tint;                // 0 = none, else ARGB overlay tint
    public final float intensity;         // default parameter strength 0..1
    public final boolean supportsDirection;
    public final boolean supportsIntensity;
    public final boolean isPremium;
    public final boolean isNew;
    public final boolean isTrending;
    public final String[] tags;
    public final String description;

    public TransitionPreset(String id, String name, TransitionCategory cat, TransitionType type,
                            float defDur, float minDur, float maxDur, String direction, int tint,
                            float intensity, boolean dirSupport, boolean intenSupport,
                            boolean premium, boolean isNew, boolean trending,
                            String[] tags, String description) {
        this.id = id; this.name = name; this.category = cat; this.type = type;
        this.defaultDuration = defDur; this.minDuration = minDur; this.maxDuration = maxDur;
        this.direction = direction == null ? "" : direction;
        this.tint = tint; this.intensity = intensity;
        this.supportsDirection = dirSupport; this.supportsIntensity = intenSupport;
        this.isPremium = premium; this.isNew = isNew; this.isTrending = trending;
        this.tags = tags == null ? new String[0] : tags;
        this.description = description == null ? "" : description;
    }

    public boolean hasTag(String needle) {
        if (needle == null) return false;
        String n = needle.toLowerCase();
        if (name.toLowerCase().contains(n)) return true;
        if (category.label.toLowerCase().contains(n)) return true;
        if (id.toLowerCase().contains(n)) return true;
        for (String t : tags) if (t.toLowerCase().contains(n)) return true;
        return false;
    }

    public List<String> tagList() { return Arrays.asList(tags); }

    /** Clamp a chosen duration to what neighbouring clips allow and this preset's range. */
    public float clampDuration(float seconds) {
        float v = Math.max(minDuration, Math.min(maxDuration, seconds));
        return Math.max(0.1f, v);
    }

    /** Compact builder for the many presets. */
    public static class B {
        String id, name, dir = "", desc = ""; TransitionCategory cat; TransitionType type;
        float def = 0.5f, min = 0.2f, max = 2.0f, inten = 0.6f; int tint = 0;
        boolean dirSup = false, intenSup = false, premium = false, neu = false, trend = false;
        String[] tags = new String[0];
        public B(String id, String name, TransitionCategory cat, TransitionType type) {
            this.id = id; this.name = name; this.cat = cat; this.type = type;
        }
        public B dur(float def, float min, float max) { this.def = def; this.min = min; this.max = max; return this; }
        public B dir(String d) { this.dir = d; this.dirSup = true; return this; }
        public B tint(int c) { this.tint = c; return this; }
        public B intensity(float v) { this.inten = v; this.intenSup = true; return this; }
        public B premium() { this.premium = true; return this; }
        public B neu() { this.neu = true; return this; }
        public B trend() { this.trend = true; return this; }
        public B tags(String... t) { this.tags = t; return this; }
        public B desc(String d) { this.desc = d; return this; }
        public TransitionPreset build() {
            return new TransitionPreset(id, name, cat, type, def, min, max, dir, tint, inten,
                    dirSup, intenSup, premium, neu, trend, tags, desc);
        }
    }
}
