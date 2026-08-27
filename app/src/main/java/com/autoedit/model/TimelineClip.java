package com.autoedit.model;

import org.json.*;

public class TimelineClip {
    public String uri;
    public int index;
    public float durationSec = 5f;
    public long durationMs = 5000L;
    public Formula formula;
    public TransitionType transition = TransitionType.CROSS_DISSOLVE;
    public float transitionDurationSec = .5f;
    public EffectType effect = EffectType.NONE;
    public float effectIntensity = .6f;
    public TimelineClip(String uri, int index, Formula formula) { this.uri = uri; this.index = index; this.formula = formula; }
    public void setDurationSeconds(float seconds) { setDurationMs(Math.round(seconds * 1000f)); }
    public void setDurationMs(long ms) { long clamped = Math.max(3000L, Math.min(8000L, ms)); durationMs = clamped; durationSec = clamped / 1000f; }
    public long startTimeMsIn(EditProject project) { long t = 0; for (TimelineClip c : project.clips) { if (c == this) break; t += c.durationMs; } return t; }
    public JSONObject toJson() throws JSONException {
        setDurationSeconds(durationSec); JSONObject o = new JSONObject(); o.put("uri", uri); o.put("index", index); o.put("duration", durationSec); o.put("durationMs", durationMs); o.put("formula", formula.id); o.put("transition", transition.name()); o.put("transitionDuration", transitionDurationSec); o.put("effect", effect.name()); o.put("effectIntensity", effectIntensity); return o;
    }
}
