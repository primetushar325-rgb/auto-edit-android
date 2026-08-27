package com.autoedit.engine;

import com.autoedit.model.*;
import java.util.*;

public class FormulaEngine {
    private final ArrayList<Formula> formulas = new ArrayList<>();

    public FormulaEngine(){ build(); }

    private void build(){
        // ---- existing single-motion formulas (unchanged ids/behavior) ----
        add("00","None","Static",0,0,1.0f,0,0,1.0f);
        add("01","Top → Bottom","Vertical",0,-.10f,1.0f,0,.10f,1.08f);
        add("02","Right → Left","Horizontal",.12f,0,1.05f,-.12f,0,1.05f);
        add("03","Top → Bottom Zoom","Vertical Zoom",0,-.12f,1.0f,0,.12f,1.12f);
        add("04","Left → Right","Horizontal",-.12f,0,1.05f,.12f,0,1.05f);
        add("05","Bottom → Top","Vertical",0,.10f,1.0f,0,-.10f,1.08f);
        add("06","Zoom In","Zoom",0,0,1.0f,0,0,1.14f);
        add("07","Zoom Out","Zoom",0,0,1.14f,0,0,1.0f);
        add("08","Center → Left","Cinematic",0,0,1.08f,-.10f,0,1.08f);
        add("09","Center → Right","Cinematic",0,0,1.08f,.10f,0,1.08f);
        add("10","Diagonal TL → BR","Diagonal",-.08f,-.08f,1.08f,.08f,.08f,1.10f);
        add("11","Diagonal TR → BL","Diagonal",.08f,-.08f,1.08f,-.08f,.08f,1.10f);
        add("12","Diagonal BL → TR","Diagonal",-.08f,.08f,1.08f,.08f,-.08f,1.10f);
        add("13","Diagonal BR → TL","Diagonal",.08f,.08f,1.08f,-.08f,-.08f,1.10f);
        add("14","Slow Push In","Cinematic",0,0,1.02f,0,0,1.10f);
        add("15","Slow Pull Out","Cinematic",0,0,1.10f,0,0,1.02f);
        add("16","Pan + Zoom","Combo",-.10f,-.03f,1.03f,.10f,.03f,1.12f);
        add("17","Ken Burns","Classic",-.06f,.02f,1.04f,.06f,-.02f,1.12f);
        add("18","Soft Floating","Subtle",-.025f,-.015f,1.04f,.025f,.015f,1.06f);
        add("19","Focus Zoom","Focus",0,0,1.0f,0,0,1.18f);
        add("20","Random Cinematic","Random",0,0,1f,0,0,1.08f);
        // ---- v1.0.6 multi-motion Formula sequences (built from the motions above) ----
        addSequence("S1","Cinematic Travel","Travel","06","07","02","04");
        addSequence("S2","Story Flow","Story","14","04","15");
        addSequence("S3","Dynamic Portrait","Portrait","06","02","07","04");
        addSequence("S4","Smooth Documentary","Documentary","14","05","01","15");
        addSequence("S5","Vertical Flow","Vertical","05","01","06","07");
    }

    private void add(String id,String name,String dir,float sx,float sy,float ss,float ex,float ey,float es){ formulas.add(new Formula(id,name,dir,new KeyframeState(sx,sy,ss,0,1),new KeyframeState(ex,ey,es,0,1))); }

    /**
     * Builds a multi-motion sequence from existing single-motion ids.
     * Each step occupies 2 seconds of the formula's own timeline.
     */
    private void addSequence(String id, String name, String category, String... motionIds){
        Formula f = new Formula(id, name, category, new KeyframeState(0,0,1f,0,1), new KeyframeState(0,0,1f,0,1));
        f.category = category;
        f.steps = new ArrayList<>();
        float t = 0;
        for (String mid : motionIds) {
            Formula motion = lookup(mid);
            FormulaStep s = new FormulaStep(t, 2f, motion);
            f.steps.add(s);
            t += 2f;
        }
        formulas.add(f);
    }

    private Formula lookup(String id){ for(Formula f:formulas) if(f.id.equals(id)) return f; return formulas.get(0); }

    public List<Formula> all(){ return Collections.unmodifiableList(formulas); }

    public List<Formula> sequences(){
        List<Formula> out = new ArrayList<>();
        for (Formula f : formulas) if (f.isSequence()) out.add(f);
        return Collections.unmodifiableList(out);
    }

    public Formula byId(String id){ for(Formula f:formulas) if(f.id.equals(id)) return cloneFormula(f); return cloneFormula(formulas.get(16)); }
    public Formula defaultFormula(){ return byId("17"); }
    public Formula randomFor(int index){ if(index<0) index=0; return byId(String.format(Locale.US, "%02d", (index*7 % 19)+1)); }

    /**
     * Keyframe state for a formula at clip progress (0..1).
     * Classic formulas: single lerp (existing behavior).
     * Sequences: find the active step by normalized time, then lerp that
     * step's motion keyframes with the step's own easing.
     */
    public KeyframeState stateAt(Formula formula, float clipProgress){
        if (formula == null) return new KeyframeState(0,0,1f,0,1);
        float p = Math.max(0f, Math.min(1f, clipProgress));
        if (!formula.isSequence()) {
            float eased = formula.easing.apply(p);
            return KeyframeState.lerp(formula.start, formula.end, eased);
        }
        FormulaStep s = stepAtTime(formula, p * formula.totalDurationSec());
        float local = Math.max(0f, Math.min(1f, (p * formula.totalDurationSec() - s.startSec) / Math.max(.001f, s.durationSec)));
        float eased = s.easing.apply(local);
        return KeyframeState.lerp(s.motion.start, s.motion.end, eased);
    }

    /**
     * Step whose time window contains tSec (null-safe).
     * Windows are [start, end]: at an exact boundary the previous step's end
     * state is returned (guarantees the end keyframe is shown for one frame).
     */
    public FormulaStep stepAtTime(Formula f, float tSec){
        if (f == null || !f.isSequence()) return null;
        float t = Math.max(0f, Math.min(tSec, f.totalDurationSec() - .0001f));
        for (FormulaStep s : f.steps) if (t <= s.endSec()) return s;
        return f.steps.get(f.steps.size() - 1);
    }

    /** Effect active at tSec of a sequence (null = use the clip's own effect). */
    public EffectType effectAt(Formula f, float tSec){
        FormulaStep s = stepAtTime(f, tSec);
        if (s == null) return null;
        return s.effect == EffectType.NONE ? null : s.effect;
    }

    public float stepEffectIntensity(Formula f, float tSec, float clipIntensity){
        FormulaStep s = stepAtTime(f, tSec);
        return (s != null && s.effectIntensity > 0f) ? s.effectIntensity : clipIntensity;
    }

    /**
     * Crossfade mix (0..1) at step boundaries when the current step defines a
     * transition into the next step. 0 = no blend. Same math in preview/export.
     */
    public float stepTransitionMix(Formula f, float tSec){
        if (f == null || !f.isSequence() || f.steps.size() < 2) return 0f;
        FormulaStep s = stepAtTime(f, tSec);
        if (s == null || s.transition == TransitionType.NONE) return 0f;
        float td = Math.min(0.3f, s.durationSec / 3f);
        float end = s.endSec();
        if (tSec < end - td) return 0f;
        return Math.min(1f, (tSec - (end - td)) / td);
    }

    /** Transition type the current step plays into the next step. */
    public TransitionType stepTransitionAt(Formula f, float tSec){
        FormulaStep s = stepAtTime(f, tSec);
        return s == null ? TransitionType.NONE : s.transition;
    }

    /** Keyframe state of the NEXT step at its start (incoming side of a step blend). */
    public KeyframeState nextStepStateAt(Formula f, float tSec){
        if (f == null || !f.isSequence()) return new KeyframeState(0,0,1f,0,1);
        FormulaStep s = stepAtTime(f, tSec);
        if (s == null) return new KeyframeState(0,0,1f,0,1);
        int i = f.steps.indexOf(s);
        if (i < 0 || i + 1 >= f.steps.size()) return s.motion.end.copy();
        return f.steps.get(i + 1).motion.start.copy();
    }

    private Formula cloneFormula(Formula f){
        Formula n = new Formula(f.id,f.name,f.direction,f.start.copy(),f.end.copy());
        n.speed=f.speed; n.zoomAmount=f.zoomAmount; n.smoothness=f.smoothness; n.easing=f.easing;
        n.category = f.category;
        if (f.steps != null) {
            n.steps = new ArrayList<>();
            for (FormulaStep s : f.steps) {
                FormulaStep ns = new FormulaStep(s.startSec, s.durationSec, cloneFormula(s.motion));
                ns.easing = s.easing; ns.effect = s.effect; ns.effectIntensity = s.effectIntensity;
                ns.transition = s.transition; ns.zoomAmount = s.zoomAmount; ns.panX = s.panX; ns.panY = s.panY;
                ns.rotation = s.rotation; ns.opacity = s.opacity;
                n.steps.add(ns);
            }
        }
        return n;
    }
}
