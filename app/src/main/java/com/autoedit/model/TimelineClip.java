package com.autoedit.model;

import org.json.*;

public class TimelineClip {
    public String uri;
    public int index;
    public float durationSec = 3f;
    public Formula formula;
    public TransitionType transition = TransitionType.CROSS_DISSOLVE;
    public float transitionDurationSec = .5f;
    public EffectType effect = EffectType.NONE;
    public float effectIntensity = .6f;
    public TimelineClip(String uri, int index, Formula formula) { this.uri = uri; this.index = index; this.formula = formula; }
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject(); o.put("uri", uri); o.put("index", index); o.put("duration", durationSec); o.put("formula", formula.id); o.put("transition", transition.name()); o.put("transitionDuration", transitionDurationSec); o.put("effect", effect.name()); o.put("effectIntensity", effectIntensity); return o;
    }
}
