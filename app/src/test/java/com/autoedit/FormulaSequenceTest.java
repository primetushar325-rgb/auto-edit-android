package com.autoedit;

import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.*;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * CORE CONTRACT: a Formula is a REPEATING PER-CLIP MOTION PATTERN.
 *   clip i -> step (i % patternLength); ONE clip = ONE primary motion lerped
 *   start->end over that clip's whole duration. Never multiple motions/clip.
 */
public class FormulaSequenceTest {
    private final FormulaEngine e = new FormulaEngine();

    private Formula buildPattern(String... motionIds) {
        Formula f = new Formula("T", "test", "test", new KeyframeState(0, 0, 1f, 0, 1), new KeyframeState(0, 0, 1f, 0, 1));
        f.steps = new ArrayList<>();
        for (String mid : motionIds) f.steps.add(new FormulaStep(e.byId(mid)));
        return f;
    }

    @Test public void atLeastTwentyPatternFormulasRegistered() {
        int count = 0;
        for (Formula f : e.all()) if (f.isPattern()) count++;
        assertTrue("expected >=20 pattern formulas, got " + count, count >= 20);
        assertTrue(e.byId("F01").isPattern());
        assertEquals("Cinematic Travel", e.byId("F01").name);
    }

    @Test public void clipIndexWrapsModuloPatternLength() {
        Formula f = buildPattern("06", "07", "02", "04");
        for (int i = 0; i < 100; i++) {
            KeyframeState st = e.stateForClip(f, i, 0.5f);
            KeyframeState expected = e.stateForClip(f, i % 4, 0.5f);
            assertEquals(expected.x, st.x, 1e-6f);
            assertEquals(expected.y, st.y, 1e-6f);
            assertEquals(expected.scale, st.scale, 1e-6f);
            assertEquals(expected.rotation, st.rotation, 1e-6f);
        }
    }

    @Test public void oneClipOneMotion_startAndEndBelongToThatStep() {
        Formula f = buildPattern("06", "07", "02", "04");
        Formula m0 = e.motionForClip(f, 0);
        assertEquals(m1id(e, f), e.motionForClip(f, 5).id); // 5 % 4 == 1
        KeyframeState s0 = e.stateForClip(f, 0, 0f);
        KeyframeState e0 = e.stateForClip(f, 0, 1f);
        assertEquals(m0.start.scale, s0.scale, 1e-6f);
        assertEquals(m0.end.scale,   e0.scale, 1e-6f);
        Formula m2 = e.motionForClip(f, 2);
        assertNotSame(m2.id, m0.id);
        KeyframeState e2 = e.stateForClip(f, 2, 1f);
        assertEquals(m2.end.x, e2.x, 1e-6f);
        assertNotEquals(m0.end.scale, e2.scale, 0.005f);
    }

    private String m1id(FormulaEngine engine, Formula f) { return engine.motionForClip(f, 1).id; }

    @Test public void motionSpansTheWholeClip() {
        Formula f = buildPattern("06", "07");
        KeyframeState a = e.stateForClip(f, 0, 0f);
        KeyframeState b = e.stateForClip(f, 0, 0.5f);
        KeyframeState c = e.stateForClip(f, 0, 1f);
        assertTrue(a.scale < b.scale);
        assertTrue(b.scale < c.scale);
    }

    @Test public void normalizedTimingIsClipRelative() {
        Formula f = buildPattern("06");
        KeyframeState k3 = e.stateForClip(f, 0, 0.5f);
        KeyframeState k8 = e.stateForClip(f, 0, 0.5f);
        assertEquals(k3.scale, k8.scale, 1e-6f);
        f.steps.get(0).motionEndProgress = 0.4f;
        f.steps.get(0).holdUntilProgress = 1.0f;
        KeyframeState held = e.stateForClip(f, 0, 0.8f);
        KeyframeState end = e.stateForClip(f, 0, 1f);
        assertEquals(end.scale, held.scale, 1e-6f);
        f.steps.get(0).motionStartProgress = 0.2f;
        KeyframeState before = e.stateForClip(f, 0, 0.1f);
        assertEquals(f.steps.get(0).motion.start.scale, before.scale, 1e-6f);
    }

    @Test public void perStepEffectAndTransitionResolve() {
        Formula f = buildPattern("06", "07");
        f.steps.get(1).effect = EffectType.CINEMATIC;
        f.steps.get(1).effectIntensity = 0.9f;
        f.steps.get(0).transition = TransitionType.FADE;
        assertNull(e.effectForClip(f, 0));
        assertSame(EffectType.CINEMATIC, e.effectForClip(f, 1));
        assertEquals(0.9f, e.effectIntensityForClip(f, 1, 0.6f), 0f);
        assertEquals(0.6f, e.effectIntensityForClip(f, 0, 0.6f), 0f);
        assertSame(TransitionType.FADE, e.transitionForClip(f, 0));
        assertSame(TransitionType.NONE, e.transitionForClip(f, 1));
    }

    @Test public void classicSingleMotionAppliesToEveryClip() {
        Formula zi = e.byId("06");
        assertFalse(zi.isPattern());
        for (int i = 0; i < 5; i++) {
            assertEquals(1.04f, e.stateForClip(zi, i, 0f).scale, 0.01f);
            assertEquals(1.16f, e.stateForClip(zi, i, 1f).scale, 0.01f);
        }
        assertNull(e.effectForClip(zi, 0));
    }

    @Test public void noneFormulaIsStaticEveryClip() {
        Formula none = e.byId("00");
        for (int i = 0; i < 6; i++) {
            KeyframeState a = e.stateForClip(none, i, 0f);
            KeyframeState b = e.stateForClip(none, i, 1f);
            assertEquals(a.scale, b.scale, 1e-6f);
            assertEquals(a.x, b.x, 1e-6f);
            assertEquals(a.y, b.y, 1e-6f);
        }
    }

    @Test public void clonesAreIndependent() {
        Formula a = e.byId("F01");
        Formula b = e.byId("F01");
        a.steps.get(0).transition = TransitionType.ZOOM;
        assertEquals(TransitionType.NONE, b.steps.get(0).transition);
    }

    @Test public void applyingPatternReplacesAndNoneRemoves() {
        TimelineClip c = new TimelineClip("uri", 1, e.defaultFormula());
        c.formula = e.byId("F01");
        assertTrue(c.formula.isPattern());
        c.formula = e.byId("F03");
        assertEquals("F03", c.formula.id);
        c.formula = e.byId("00");
        assertFalse(c.formula.isPattern());
    }
}
