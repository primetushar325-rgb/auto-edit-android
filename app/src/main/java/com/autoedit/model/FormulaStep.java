package com.autoedit.model;

/**
 * One timed step inside a multi-motion Formula sequence.
 *
 * A step occupies [startSec, startSec+durationSec) of the formula's own
 * timeline (seconds, relative to the formula total). When the formula is
 * mapped onto a clip, the step timeline is normalized to the clip duration,
 * so a sequence always fills the clip exactly — same mapping in preview and
 * export.
 *
 * The motion is stored both as raw parameters (panX/panY/zoomAmount/rotation/
 * opacity/easing) and as a resolved {@link Formula} (keyframes) that the
 * existing FormulaEngine interpolates — no new engine, no new renderer.
 */
public class FormulaStep {
    public float startSec;
    public float durationSec;

    // raw parameters (kept for UI / tests / future keyframe editor)
    public float zoomAmount = 0f;
    public float panX = 0f;
    public float panY = 0f;
    public float rotation = 0f;
    public float opacity = 1f;
    public Easing easing = Easing.EASE_IN_OUT;

    // optional per-step overlay applied while this step is active
    public EffectType effect = EffectType.NONE;
    public float effectIntensity = 0f; // 0 = use the clip's own intensity

    // the resolved motion (existing single-motion Formula with keyframes)
    public Formula motion;

    // transition played at the END of this step (into the next step), if any
    public TransitionType transition = TransitionType.NONE;

    public FormulaStep(float startSec, float durationSec, Formula motion) {
        this.startSec = startSec;
        this.durationSec = durationSec;
        this.motion = motion;
    }

    public float endSec() { return startSec + durationSec; }
}
