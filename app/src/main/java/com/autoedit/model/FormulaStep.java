package com.autoedit.model;

/**
 * ONE step of a repeating per-clip Formula pattern. Clip i uses step
 * i % patternSize. {@link #motion} is a single start->end keyframe move over
 * THAT clip's whole duration (normalized 0..1). Timing fields are normalized:
 * motionStartProgress (0) -> motionEndProgress (1), holdUntilProgress (1).
 * {@link #transition} on step k defines the junction between clip k and k+1.
 */
public class FormulaStep {
    public float motionStartProgress = 0f;
    public float motionEndProgress = 1f;
    public float holdUntilProgress = 1f;
    public float zoomAmount = 0f, panX = 0f, panY = 0f, rotation = 0f, opacity = 1f;
    public Easing easing = Easing.EASE_IN_OUT;
    public EffectType effect = EffectType.NONE;
    public float effectIntensity = 0f;
    public Formula motion;
    public TransitionType transition = TransitionType.NONE;
    public float startSec, durationSec;

    public FormulaStep(Formula motion) { this.motion = motion; this.startSec = 0f; this.durationSec = 1f; }

    public FormulaStep(float startSec, float durationSec, Formula motion) {
        this.startSec = startSec; this.durationSec = durationSec; this.motion = motion;
    }

    public float endSec() { return startSec + durationSec; }
}
