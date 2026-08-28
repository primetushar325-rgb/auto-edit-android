package com.autoedit;

import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.*;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Custom formulas (from CustomFormulaStore.toFormula) are per-clip patterns:
 * each adjacent keyframe pair becomes ONE step = one clip's motion.
 * FormulaEngine.stateForClip interpolates that clip's motion start->end.
 */
public class CustomFormulaInterpolationTest {
    private final FormulaEngine e = new FormulaEngine();

    private Formula buildFromKeyframes(KeyframeState[] kfs, Easing[] easings, float[] times) {
        Formula f = new Formula("Ctest", "Custom", "Custom",
                new KeyframeState(0, 0, 1f, 0, 1), new KeyframeState(0, 0, 1f, 0, 1));
        f.steps = new ArrayList<>();
        for (int i = 0; i < kfs.length - 1; i++) {
            float dur = Math.max(0.05f, times[i + 1] - times[i]);
            Formula m = new Formula("K" + i, "Custom", "Custom", kfs[i].copy(), kfs[i + 1].copy());
            m.easing = easings[i];
            FormulaStep st = new FormulaStep(times[i], dur, m);
            st.easing = easings[i];
            f.steps.add(st);
        }
        return f;
    }

    @Test public void keyframesBecomeSteps_onePerClip() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1.0f, 0, 1),
                new KeyframeState(0, 0, 1.3f, 0, 1),
                new KeyframeState(0.5f, 0, 1.0f, 0, 1),
                new KeyframeState(0, 0, 1.14f, 0, 1),
        };
        Easing[] es = {Easing.EASE_IN_OUT, Easing.LINEAR, Easing.EASE_OUT};
        float[] times = {0f, 2f, 4f, 6f};
        Formula f = buildFromKeyframes(kfs, es, times);
        assertTrue(f.isPattern());
        assertEquals(3, f.patternSize());
        assertEquals(e.motionForClip(f, 0).id, e.motionForClip(f, 3).id);
    }

    @Test public void clipMotionInterpolatesStartToEnd() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1.0f, 0, 1),
                new KeyframeState(0, 0, 1.3f, 0, 1),
        };
        Formula f = buildFromKeyframes(kfs, new Easing[]{Easing.LINEAR}, new float[]{0f, 2f});
        KeyframeState start = e.stateForClip(f, 0, 0f);
        KeyframeState mid = e.stateForClip(f, 0, 0.5f);
        KeyframeState end = e.stateForClip(f, 0, 1f);
        assertEquals(1.0f, start.scale, 0.001f);
        assertEquals(1.15f, mid.scale, 0.001f);
        assertEquals(1.3f, end.scale, 0.001f);
        assertEquals(end.scale, e.stateForClip(f, 1, 1f).scale, 1e-6f);
    }

    @Test public void easingAppliedWithinClip() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1f, 0, 1),
                new KeyframeState(0.4f, 0, 1f, 0, 1),
        };
        Formula f = buildFromKeyframes(kfs, new Easing[]{Easing.EASE_OUT}, new float[]{0f, 4f});
        KeyframeState mid = e.stateForClip(f, 0, 0.5f);
        assertEquals(0.3f, mid.x, 0.001f);
    }

    @Test public void noJumpingWithinAClip_stateIsContinuous() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1.0f, 0, 1),
                new KeyframeState(0, 0, 1.3f, 0, 1),
                new KeyframeState(-0.3f, 0.1f, 0.9f, 5f, 0.7f),
                new KeyframeState(0.2f, -0.2f, 1.2f, 0, 1),
        };
        Formula f = buildFromKeyframes(kfs,
                new Easing[]{Easing.EASE_IN_OUT, Easing.EASE_IN_OUT, Easing.EASE_IN_OUT},
                new float[]{0f, 2f, 4f, 6f});
        KeyframeState prev = e.stateForClip(f, 0, 0f);
        for (int i = 1; i <= 200; i++) {
            KeyframeState s = e.stateForClip(f, 0, i / 200f);
            assertTrue("scale jumped " + Math.abs(s.scale - prev.scale), Math.abs(s.scale - prev.scale) < 0.05f);
            assertTrue("x jumped", Math.abs(s.x - prev.x) < 0.05f);
            assertTrue("y jumped", Math.abs(s.y - prev.y) < 0.05f);
            assertTrue("rotation jumped", Math.abs(s.rotation - prev.rotation) < 0.16f);
            assertTrue("opacity jumped", Math.abs(s.opacity - prev.opacity) < 0.05f);
            prev = s;
        }
        assertEquals(1.3f, e.stateForClip(f, 0, 1f).scale, 0.001f);
        assertEquals(0.2f, e.stateForClip(f, 2, 1f).x, 0.001f);
    }

    @Test public void effectAndTransitionMetaSurviveOnSteps() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1f, 0, 1),
                new KeyframeState(0, 0, 1.2f, 0, 1),
                new KeyframeState(0, 0, 1f, 0, 1),
        };
        Formula f = buildFromKeyframes(kfs,
                new Easing[]{Easing.EASE_IN_OUT, Easing.EASE_IN_OUT}, new float[]{0f, 2f, 4f});
        f.steps.get(0).transition = TransitionType.CROSS_DISSOLVE;
        f.steps.get(0).effect = EffectType.GLOW;
        assertSame(TransitionType.CROSS_DISSOLVE, e.transitionForClip(f, 0));
        assertSame(EffectType.GLOW, e.effectForClip(f, 0));
        assertNull(e.effectForClip(f, 1));
        assertSame(TransitionType.NONE, e.transitionForClip(f, 1));
        assertSame(TransitionType.CROSS_DISSOLVE, e.transitionForClip(f, 2));
        assertSame(TransitionType.NONE, e.transitionForClip(f, 3));
    }

    @Test public void classicSingleMotionStillWorks() {
        Formula classic = e.byId("06");
        assertFalse(classic.isPattern());
        assertEquals(1.04f, e.stateForClip(classic, 0, 0f).scale, 0.01f);
        assertEquals(1.10f, e.stateForClip(classic, 0, 0.5f).scale, 0.02f);
        assertEquals(1.16f, e.stateForClip(classic, 0, 1f).scale, 0.01f);
    }
}
