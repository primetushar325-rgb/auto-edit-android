package com.autoedit.engine;

import com.autoedit.model.*;
import java.util.*;

/**
 * THE single authoritative formula/motion resolver used by BOTH live preview
 * and export. A Formula is a REPEATING PER-CLIP MOTION PATTERN: clip index i
 * uses pattern step (i % patternLength). ONE CLIP = ONE PRIMARY MOTION, lerped
 * start->end over that clip's whole duration via normalized progress 0..1.
 * Timing is normalized to each clip's own duration (no hardcoded seconds).
 */
public class FormulaEngine {
    private final ArrayList<Formula> formulas = new ArrayList<>();

    public FormulaEngine() { build(); }

    private void build() {
        for (Formula m : MotionCatalog.all()) formulas.add(m);
        for (Formula p : FormulaCatalog.all()) formulas.add(p);
    }

    public List<Formula> all() { return Collections.unmodifiableList(formulas); }

    public List<Formula> motions() {
        List<Formula> out = new ArrayList<>();
        for (Formula f : formulas) if (!f.isPattern()) out.add(f);
        return Collections.unmodifiableList(out);
    }

    public List<Formula> sequences() {
        List<Formula> out = new ArrayList<>();
        for (Formula f : formulas) if (f.isPattern()) out.add(f);
        return Collections.unmodifiableList(out);
    }

    public List<Formula> patterns() { return sequences(); }

    public Formula byId(String id) {
        if (id != null) for (Formula f : formulas) if (f.id.equals(id)) return cloneFormula(f);
        return cloneFormula(MotionCatalog.byId("17"));
    }

    public Formula defaultFormula() { return byId("17"); }

    public Formula randomFor(int index) {
        if (index < 0) index = 0;
        List<Formula> m = motions();
        int pick = 1 + (index * 7) % Math.max(1, m.size() - 1);
        return cloneFormula(m.get(Math.min(pick, m.size() - 1)));
    }

    public FormulaStep patternStepForClip(Formula formula, int clipIndex) {
        if (formula == null || !formula.isPattern()) return null;
        int n = formula.steps.size();
        if (n == 0) return null;
        int idx = ((clipIndex % n) + n) % n;
        return formula.steps.get(idx);
    }

    public Formula motionForClip(Formula formula, int clipIndex) {
        if (formula == null) return null;
        if (!formula.isPattern()) return formula;
        FormulaStep s = patternStepForClip(formula, clipIndex);
        return s == null ? null : s.motion;
    }

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

        float mStart = formula.isPattern() ? step.motionStartProgress : formula.motionStartProgress;
        float mEnd   = formula.isPattern() ? step.motionEndProgress   : formula.motionEndProgress;
        float hold   = formula.isPattern() ? step.holdUntilProgress   : formula.holdUntilProgress;
        if (mEnd <= mStart) mEnd = 1f;
        Easing easing = motion.easing != null ? motion.easing : Easing.EASE_IN_OUT;

        float local;
        if (pc <= mStart) local = 0f;
        else if (pc >= mEnd) local = 1f;
        else local = (pc - mStart) / (mEnd - mStart);
        if (pc >= mEnd && pc <= Math.max(hold, mEnd)) local = 1f;
        float eased = easing.apply(local);
        return KeyframeState.lerp(motion.start, motion.end, eased);
    }

    public KeyframeState stateAt(Formula formula, float clipProgress) {
        return stateForClip(formula, 0, clipProgress);
    }

    public EffectType effectForClip(Formula formula, int clipIndex) {
        FormulaStep s = patternStepForClip(formula, clipIndex);
        if (s == null || s.effect == null || s.effect == EffectType.NONE) return null;
        return s.effect;
    }

    public float effectIntensityForClip(Formula formula, int clipIndex, float clipIntensity) {
        FormulaStep s = patternStepForClip(formula, clipIndex);
        return (s != null && s.effectIntensity > 0f) ? s.effectIntensity : clipIntensity;
    }

    public TransitionType transitionForClip(Formula formula, int clipIndex) {
        FormulaStep s = patternStepForClip(formula, clipIndex);
        return s == null ? TransitionType.NONE : s.transition;
    }

    // ---- legacy sequence-style accessors (backward compatibility) ----
    public FormulaStep stepAtTime(Formula f, float tSec) {
        if (f == null || !f.isSequence()) return null;
        float t = Math.max(0f, tSec);
        int n = f.steps.size();
        int i = (int) Math.floor(t / Math.max(1e-4f, f.totalDurationSec() / Math.max(1, n)));
        return f.steps.get(Math.max(0, Math.min(n - 1, i)));
    }

    public EffectType effectAt(Formula f, float tSec) {
        FormulaStep s = stepAtTime(f, tSec);
        return (s == null || s.effect == null || s.effect == EffectType.NONE) ? null : s.effect;
    }

    public float stepEffectIntensity(Formula f, float tSec, float clipIntensity) {
        FormulaStep s = stepAtTime(f, tSec);
        return (s != null && s.effectIntensity > 0f) ? s.effectIntensity : clipIntensity;
    }

    public TransitionType stepTransitionAt(Formula f, float tSec) {
        FormulaStep s = stepAtTime(f, tSec);
        return s == null ? TransitionType.NONE : s.transition;
    }

    public float stepTransitionMix(Formula f, float tSec) { return 0f; }

    public KeyframeState nextStepStateAt(Formula f, float tSec) {
        FormulaStep s = stepAtTime(f, tSec);
        if (s == null || s.motion == null) return new KeyframeState(0, 0, 1f, 0, 1);
        return s.motion.end.copy();
    }

    public Formula cloneFormula(Formula f) {
        Formula n = new Formula(f.id, f.name, f.direction,
                f.start == null ? new KeyframeState(0, 0, 1f, 0, 1) : f.start.copy(),
                f.end   == null ? new KeyframeState(0, 0, 1f, 0, 1) : f.end.copy());
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
