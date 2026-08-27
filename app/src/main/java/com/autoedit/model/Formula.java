package com.autoedit.model;

import java.util.ArrayList;

public class Formula {
    public String id, name, direction;
    public KeyframeState start, end;
    public float speed = 1f, zoomAmount = .08f, smoothness = 1f;
    public Easing easing = Easing.EASE_IN_OUT;

    // --- v1.0.6 additions (backward compatible: null steps = classic single motion)
    public String category = "Motion";
    public ArrayList<FormulaStep> steps = null; // multi-motion sequence, or null

    public Formula(String id, String name, String direction, KeyframeState start, KeyframeState end) {
        this.id = id; this.name = name; this.direction = direction; this.start = start; this.end = end;
    }

    public boolean isSequence() { return steps != null && steps.size() > 0; }

    /** Total sequence duration in seconds (1.0 for classic single-motion formulas). */
    public float totalDurationSec() {
        if (!isSequence()) return 1f;
        float t = 0;
        for (FormulaStep s : steps) t += s.durationSec;
        return t;
    }
}
