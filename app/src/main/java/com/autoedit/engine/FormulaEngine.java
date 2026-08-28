package com.autoedit.engine;

import com.autoedit.model.*;
import java.util.*;

/**
 * THE single authoritative formula/motion resolver used by BOTH live preview
 * and export (spec §16).
 *
 * <h3>The rule</h3>
 * <pre>
 *   ONE CLIP = ONE PRIMARY MOTION.
 *   A Formula is a REPEATING PER-CLIP PATTERN.
 *
 *   clip i  →  step (i % steps.size())  →  that ONE motion, played from its
 *              start keyframe to its end keyframe across the clip's WHOLE
 *              normalized duration (0..1).
 * </pre>
 * A formula NEVER runs several motions inside one clip. The old intra-clip
 * "sequence" API ({@code stepAtTime}, {@code nextStepStateAt},
 * {@code stepTransitionMix}, ...) has been REMOVED — it was the source of the
 * multi-motion-per-image bug (audit finding C1) and nothing referenced it.
 *
 * <h3>Timing</h3>
 * All timing is normalized to each clip's own duration, so the same pattern
 * behaves identically on a 3 s clip and an 8 s clip.
 */
public class FormulaEngine {
    private final ArrayList<Formula> formulas = new ArrayList<>();

    public FormulaEngine() { build(); }

    private void build() {
        for (Formula m : MotionCatalog.all()) formulas.add(m);
        for (Formula p : FormulaCatalog.all()) formulas.add(p);
    }

    public List<Formula> all() { return Collections.unmodifiableList(formulas); }

    /** Single motions (one move applied to every clip). */
    public List<Formula> motions() {
        List<Formula> out = new ArrayList<>();
        for (Formula f : formulas) if (!f.isPattern()) out.add(f);
        return Collections.unmodifiableList(out);
    }

    /** Repeating per-clip patterns. */
    public List<Formula> sequences() {
        List<Formula> out = new ArrayList<>();
        for (Formula f : formulas) if (f.isPattern()) out.add(f);
        return Collections.unmodifiableList(out);
    }

    public List<Formula> patterns() { return sequences(); }

    /** Never returns null; unknown ids fall back to the default motion. */
    public Formula byId(String id) {
        if (id != null) for (Formula f : formulas) if (f.id.equals(id)) return cloneFormula(f);
        return cloneFormula(MotionCatalog.byId("17"));
    }

    public Formula defaultFormula() { return byId("17"); }

    /** Deterministic pick used by "Auto Edit" so the result is reproducible. */
    public Formula randomFor(int index) {
        if (index < 0) index = 0;
        List<Formula> m = motions();
        int pick = 1 + (index * 7) % Math.max(1, m.size() - 1);
        return cloneFormula(m.get(Math.min(pick, m.size() - 1)));
    }

    // ------------------------------------------------------- pattern resolution

    /**
     * The pattern step clip {@code clipIndex} plays. This is the whole cyclic
     * rule in one place: {@code i % patternSize}, made positive-safe so a
     * negative index (which should never happen) still resolves.
     */
    public FormulaStep patternStepForClip(Formula formula, int clipIndex) {
        if (formula == null || !formula.isPattern()) return null;
        int n = formula.steps.size();
        if (n == 0) return null;
        int idx = ((clipIndex % n) + n) % n;
        return formula.steps.get(idx);
    }

    /** The ONE motion this clip plays for its entire duration. */
    public Formula motionForClip(Formula formula, int clipIndex) {
        if (formula == null) return null;
        if (!formula.isPattern()) return formula;
        FormulaStep s = patternStepForClip(formula, clipIndex);
        return s == null ? null : s.motion;
    }

    /**
     * Resolved transform state for clip {@code clipIndex} at normalized
     * progress {@code p} (0 = first frame of the clip, 1 = last).
     */
    public KeyframeState stateForClip(Formula formula, int clipIndex, float p) {
        float pc = clamp01(p);
        if (formula == null) return new KeyframeState(0, 0, 1f, 0, 1);
        Formula motion;
        FormulaStep step = null;
        if (formula.isPattern()) {
            step = patternStepForClip(formula, clipIndex);
            motion = step == null ? null : step.motion;
        } else {
            motion = formula;
        }
        if (motion == null) return new KeyframeState(0, 0, 1f, 0, 1);

        float mStart = step != null ? step.motionStartProgress : formula.motionStartProgress;
        float mEnd = step != null ? step.motionEndProgress : formula.motionEndProgress;
        if (mEnd <= mStart) mEnd = 1f;
        Easing easing = easingForClip(formula, clipIndex);

        float local;
        if (pc <= mStart) local = 0f;
        else if (pc >= mEnd) local = 1f;
        else local = (pc - mStart) / (mEnd - mStart);
        float eased = easing.apply(local);
        return KeyframeState.lerp(motion.start, motion.end, eased);
    }

    public KeyframeState stateAt(Formula formula, float clipProgress) {
        return stateForClip(formula, 0, clipProgress);
    }

    /**
     * Effective easing for the motion this clip plays. A pattern step's easing
     * wins over the motion's own default; nothing is ever null.
     */
    public Easing easingForClip(Formula formula, int clipIndex) {
        if (formula == null) return Easing.DEFAULT;
        if (formula.isPattern()) {
            FormulaStep s = patternStepForClip(formula, clipIndex);
            if (s != null && s.easing != null) return s.easing;
            Formula m = s == null ? null : s.motion;
            if (m != null && m.easing != null) return m.easing;
            return Easing.DEFAULT;
        }
        return formula.easing != null ? formula.easing : Easing.DEFAULT;
    }

    /** Start keyframe of the motion this clip plays (needed for safe-scale). */
    public KeyframeState motionStartForClip(Formula formula, int clipIndex) {
        Formula m = motionForClip(formula, clipIndex);
        return m != null && m.start != null ? m.start : new KeyframeState(0, 0, 1f, 0, 1);
    }

    /** End keyframe of the motion this clip plays. */
    public KeyframeState motionEndForClip(Formula formula, int clipIndex) {
        Formula m = motionForClip(formula, clipIndex);
        return m != null && m.end != null ? m.end : new KeyframeState(0, 0, 1f, 0, 1);
    }

    // ------------------------------------------------- per-step effect/transition

    /** The effect the pattern assigns to this clip, or null for "use the clip's own". */
    public EffectType effectForClip(Formula formula, int clipIndex) {
        FormulaStep s = patternStepForClip(formula, clipIndex);
        if (s == null || s.effect == null || s.effect == EffectType.NONE) return null;
        return s.effect;
    }

    public float effectIntensityForClip(Formula formula, int clipIndex, float clipIntensity) {
        FormulaStep s = patternStepForClip(formula, clipIndex);
        return (s != null && s.effectIntensity > 0f) ? s.effectIntensity : clipIntensity;
    }

    /**
     * Effect layers to render for this clip, in order (spec §10, §45).
     * The pattern's effect (if any) comes first, then the clip's own stack.
     */
    public List<EffectLayer> effectLayersForClip(TimelineClip clip, int clipIndex) {
        List<EffectLayer> out = new ArrayList<>();
        if (clip == null) return out;
        EffectType patternEffect = effectForClip(clip.formula, clipIndex);
        if (patternEffect != null) {
            out.add(new EffectLayer(patternEffect, effectIntensityForClip(clip.formula, clipIndex, clip.effectIntensity)));
        }
        for (EffectLayer l : clip.resolvedLayers()) {
            if (!l.isActive()) continue;
            if (patternEffect != null && l.type == patternEffect) continue; // avoid double-applying
            out.add(l);
        }
        return out;
    }

    /**
     * The transition the pattern assigns to the junction AFTER this clip.
     * A transition always lives BETWEEN clips (spec §46).
     */
    public TransitionType transitionForClip(Formula formula, int clipIndex) {
        FormulaStep s = patternStepForClip(formula, clipIndex);
        return s == null ? TransitionType.NONE : s.transition;
    }

    // ------------------------------------------------------------------ cloning

    public Formula cloneFormula(Formula f) {
        Formula n = new Formula(f.id, f.name, f.direction,
                f.start == null ? new KeyframeState(0, 0, 1f, 0, 1) : f.start.copy(),
                f.end == null ? new KeyframeState(0, 0, 1f, 0, 1) : f.end.copy());
        n.speed = f.speed; n.zoomAmount = f.zoomAmount; n.smoothness = f.smoothness;
        n.easing = f.easing; n.category = f.category; n.description = f.description;
        n.motionStartProgress = f.motionStartProgress;
        n.motionEndProgress = f.motionEndProgress;
        n.holdUntilProgress = f.holdUntilProgress;
        if (f.steps != null) {
            n.steps = new ArrayList<>();
            for (FormulaStep s : f.steps) {
                Formula m = s.motion == null ? null : cloneFormula(s.motion);
                FormulaStep ns = new FormulaStep(m);
                ns.startSec = s.startSec; ns.durationSec = s.durationSec;
                ns.motionStartProgress = s.motionStartProgress;
                ns.motionEndProgress = s.motionEndProgress;
                ns.holdUntilProgress = s.holdUntilProgress;
                ns.easing = s.easing; ns.effect = s.effect; ns.effectIntensity = s.effectIntensity;
                ns.transition = s.transition;
                ns.zoomAmount = s.zoomAmount; ns.panX = s.panX; ns.panY = s.panY;
                ns.rotation = s.rotation; ns.opacity = s.opacity;
                n.steps.add(ns);
            }
        }
        return n;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
