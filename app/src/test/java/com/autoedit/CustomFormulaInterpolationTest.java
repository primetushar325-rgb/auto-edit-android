package com.autoedit;

import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.*;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Validates the Custom Formula keyframe → step conversion that
 * CustomFormulaStore.toFormula performs (mirrored here in pure Java so the
 * logic runs on the JVM). A custom formula stores N keyframes; the store
 * builds N-1 steps whose motion keyframes are exactly the adjacent keyframe
 * states, and FormulaEngine.stateAt interpolates between them with the
 * chosen easing. This is the exact path used by editor preview and export.
 */
public class CustomFormulaInterpolationTest {
    private final FormulaEngine e = new FormulaEngine();

    /** Mirrors CustomFormulaStore.toFormula: keyframes → Formula sequence. */
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

    @Test public void keyframesBecomeSteps_withCorrectDurations() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1.0f, 0, 1),
                new KeyframeState(0, 0, 1.3f, 0, 1),
                new KeyframeState(50 / 100f, 0, 1.0f, 0, 1),
                new KeyframeState(0, 0, 1.14f, 0, 1),
        };
        Easing[] es = {Easing.EASE_IN_OUT, Easing.LINEAR, Easing.EASE_OUT};
        float[] times = {0f, 2f, 4f, 6f};
        Formula f = buildFromKeyframes(kfs, es, times);

        assertTrue(f.isSequence());
        assertEquals(3, f.steps.size());
        assertEquals(2f, f.steps.get(0).durationSec, 0.001f);
        assertEquals(6f, f.totalDurationSec(), 0.001f);
        // chronological windows
        for (int i = 1; i < f.steps.size(); i++) {
            assertTrue(f.steps.get(i).startSec >= f.steps.get(i - 1).endSec() - 0.001f);
        }
    }

    @Test public void interpolatesSmoothly_scaleZoom1to1_3() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1.0f, 0, 1),
                new KeyframeState(0, 0, 1.3f, 0, 1),
        };
        Easing[] es = {Easing.LINEAR};
        float[] times = {0f, 2f};
        Formula f = buildFromKeyframes(kfs, es, times);

        KeyframeState start = e.stateAt(f, 0f);
        KeyframeState mid = e.stateAt(f, 0.5f);
        KeyframeState end = e.stateAt(f, 1f);
        assertEquals(1.0f, start.scale, 0.001f);
        assertEquals(1.15f, mid.scale, 0.001f);   // exact midpoint lerp
        assertEquals(1.3f, end.scale, 0.001f);
    }

    @Test public void interpolatesPan_andEases() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1f, 0, 1),
                new KeyframeState(0.4f, 0, 1f, 0, 1),
        };
        Easing[] es = {Easing.EASE_OUT};
        float[] times = {0f, 4f};
        Formula f = buildFromKeyframes(kfs, es, times);

        KeyframeState mid = e.stateAt(f, 0.5f); // progress 0.5 → eased 0.75
        assertEquals(0.3f, mid.x, 0.001f);
        // EASE_OUT at t=0.5 → 1-(1-.5)^2 = 0.75 → x = 0.4*0.75 = 0.3
    }

    @Test public void noJumpingBetweenKeyframes_stateIsContinuous() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1.0f, 0, 1),
                new KeyframeState(0, 0, 1.3f, 0, 1),
                new KeyframeState(-0.3f, 0.1f, 0.9f, 5f, 0.7f),
                new KeyframeState(0.2f, -0.2f, 1.2f, 0, 1),
        };
        Easing[] es = {Easing.EASE_IN_OUT, Easing.EASE_IN_OUT, Easing.EASE_IN_OUT};
        float[] times = {0f, 2f, 4f, 6f};
        Formula f = buildFromKeyframes(kfs, es, times);

        // sample densely across the whole sequence — consecutive samples must
        // move in small steps per parameter (no teleporting between keyframes)
        KeyframeState prev = e.stateAt(f, 0f);
        for (int i = 1; i <= 200; i++) {
            KeyframeState s = e.stateAt(f, i / 200f);
            float dScale = Math.abs(s.scale - prev.scale);
            float dX = Math.abs(s.x - prev.x);
            float dY = Math.abs(s.y - prev.y);
            float dRot = Math.abs(s.rotation - prev.rotation);
            float dOp = Math.abs(s.opacity - prev.opacity);
            assertTrue("step " + i + " scale jumped by " + dScale, dScale < 0.05f);
            assertTrue("step " + i + " panX jumped by " + dX, dX < 0.05f);
            assertTrue("step " + i + " panY jumped by " + dY, dY < 0.05f);
            assertTrue("step " + i + " rotation jumped by " + dRot, dRot < 0.16f);
            assertTrue("step " + i + " opacity jumped by " + dOp, dOp < 0.05f);
            prev = s;
        }
        // boundaries land exactly on the keyframe states
        KeyframeState at2 = e.stateAt(f, 2f / 6f);
        assertEquals(1.3f, at2.scale, 0.001f);
        assertEquals(0f, at2.x, 0.001f);
    }

    @Test public void transitionAndEffectMetaSurviveOnSteps() {
        KeyframeState[] kfs = {
                new KeyframeState(0, 0, 1f, 0, 1),
                new KeyframeState(0, 0, 1.2f, 0, 1),
                new KeyframeState(0, 0, 1f, 0, 1),
        };
        Easing[] es = {Easing.EASE_IN_OUT, Easing.EASE_IN_OUT};
        float[] times = {0f, 2f, 4f};
        Formula f = buildFromKeyframes(kfs, es, times);

        f.steps.get(0).transition = TransitionType.CROSS_DISSOLVE;
        f.steps.get(0).effect = EffectType.GLOW;
        assertSame(TransitionType.CROSS_DISSOLVE, e.stepTransitionAt(f, 1f));
        assertSame(EffectType.GLOW, e.effectAt(f, 1f));
        assertSame(TransitionType.NONE, e.stepTransitionAt(f, 3f));
    }

    @Test public void oldSingleMotionFormulasStillWork() {
        Formula classic = e.byId("06"); // Zoom In
        assertFalse(classic.isSequence());
        KeyframeState mid = e.stateAt(classic, 0.5f);
        assertEquals(1.07f, mid.scale, 0.01f);
        KeyframeState end = e.stateAt(classic, 1f);
        assertEquals(1.14f, end.scale, 0.01f);
    }
}
