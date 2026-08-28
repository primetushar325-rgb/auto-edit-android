package com.autoedit.model;

import java.util.ArrayList;

/**
 * A Formula is a REPEATING, CLIP-BY-CLIP MOTION PATTERN.
 *   clip index i -> pattern step (i % steps.size())
 * Each step holds ONE primary motion for one clip: a single start->end
 * keyframe move spanning THAT clip's whole duration (normalized 0..1).
 * steps == null = classic single-motion formula (every clip gets the move).
 */
public class Formula {
    public String id, name, direction;
    public String description = "";
    public KeyframeState start, end;
    public float speed = 1f, zoomAmount = .08f, smoothness = 1f;
    public Easing easing = Easing.EASE_IN_OUT;
    public String category = "Motion";
    public ArrayList<FormulaStep> steps = null;
    public float motionStartProgress = 0f;
    public float motionEndProgress = 1f;
    public float holdUntilProgress = 1f;

    public Formula(String id, String name, String direction, KeyframeState start, KeyframeState end) {
        this.id = id; this.name = name; this.direction = direction; this.start = start; this.end = end;
    }

    public boolean isPattern() { return steps != null && steps.size() > 0; }
    public boolean isSequence() { return isPattern(); }
    public int patternSize() { return isPattern() ? steps.size() : 1; }
    public float totalDurationSec() { return 1f; }
}
