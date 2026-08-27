package com.autoedit;

import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.*;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class FormulaSequenceTest {
    private final FormulaEngine e = new FormulaEngine();

    private Formula seq(String id) { return e.byId(id); }

    /** Helper: build an ad-hoc sequence from motion ids (2s steps each). */
    private Formula buildSeq(String... motionIds) {
        Formula f = new Formula("T", "test", "test", new KeyframeState(0, 0, 1f, 0, 1), new KeyframeState(0, 0, 1f, 0, 1));
        f.steps = new ArrayList<>();
        float t = 0;
        for (String mid : motionIds) {
            f.steps.add(new FormulaStep(t, 2f, e.byId(mid)));
            t += 2f;
        }
        return f;
    }

    @Test public void sequencesAreRegistered() {
        assertEquals(5, e.sequences().size());
        Formula s1 = seq("S1");
        assertTrue(s1.isSequence());
        assertEquals("Cinematic Travel", s1.name);
        assertEquals(8f, s1.totalDurationSec(), 0.001f);
        assertEquals(4, s1.steps.size());
    }

    @Test public void cinematicTravelAppliesStepsInOrder() {
        // 0-2s Zoom In (06): 1.00 -> 1.14
        // 2-4s Zoom Out (07): 1.14 -> 1.00
        // 4-6s Pan Left (02): x +0.12 -> -0.12, scale 1.05
        // 6-8s Pan Right (04): x -0.12 -> +0.12, scale 1.05
        Formula f = seq("S1");
        KeyframeState a = e.stateAt(f, 0f);
        assertEquals(1.0f, a.scale, 0.01f);
        assertEquals(0f, a.x, 0.01f);

        KeyframeState b = e.stateAt(f, 0.25f); // end of step 1
        assertEquals(1.14f, b.scale, 0.01f);

        KeyframeState c = e.stateAt(f, 0.5f); // end of step 2
        assertEquals(1.0f, c.scale, 0.01f);

        KeyframeState d = e.stateAt(f, 0.75f); // end of step 3 (Pan Left end)
        assertEquals(-0.12f, d.x, 0.01f);
        assertEquals(1.05f, d.scale, 0.01f);

        KeyframeState eState = e.stateAt(f, 1f); // end of step 4 (Pan Right end)
        assertEquals(0.12f, eState.x, 0.01f);
        assertEquals(1.05f, eState.scale, 0.01f);
    }

    @Test public void storyFlowSequence() {
        // 14 Slow Push In, 04 Pan Right, 15 Slow Pull Out (6s total)
        Formula f = seq("S2");
        assertEquals(6f, f.totalDurationSec(), 0.001f);
        assertEquals(3, f.steps.size());
        assertEquals(1.02f, e.stateAt(f, 0f).scale, 0.01f);
        assertEquals(1.10f, e.stateAt(f, 1f / 3f).scale, 0.01f); // end push-in
        assertEquals(0.12f, e.stateAt(f, 2f / 3f).x, 0.01f);   // end pan right
        assertEquals(1.02f, e.stateAt(f, 1f).scale, 0.01f);     // end pull out
    }

    @Test public void dynamicPortraitSequence() {
        Formula f = seq("S3");
        // 06 -> 02 -> 07 -> 04
        assertEquals(1.14f, e.stateAt(f, 0.25f).scale, 0.01f);
        assertEquals(-0.12f, e.stateAt(f, 0.5f).x, 0.01f);
        assertEquals(1.0f, e.stateAt(f, 0.75f).scale, 0.01f);
        assertEquals(0.12f, e.stateAt(f, 1f).x, 0.01f);
    }

    @Test public void verticalFlowSequence() {
        // 05 Pan Up, 01 Pan Down, 06 Zoom In, 07 Zoom Out
        Formula f = seq("S5");
        assertEquals(-0.10f, e.stateAt(f, 0.25f).y, 0.01f); // pan up end (y negative)
        assertEquals(0.10f, e.stateAt(f, 0.5f).y, 0.01f);   // pan down end
        assertEquals(1.14f, e.stateAt(f, 0.75f).scale, 0.01f);
        assertEquals(1.0f, e.stateAt(f, 1f).scale, 0.01f);
    }

    @Test public void smoothDocumentarySequence() {
        Formula f = seq("S4");
        // 14 Slow Push In, 05 Pan Up, 01 Pan Down, 15 Slow Pull Out
        assertEquals(1.10f, e.stateAt(f, 0.25f).scale, 0.01f);
        assertEquals(-0.10f, e.stateAt(f, 0.5f).y, 0.01f);
        assertEquals(0.10f, e.stateAt(f, 0.75f).y, 0.01f);
        assertEquals(1.02f, e.stateAt(f, 1f).scale, 0.01f);
    }

    @Test public void backwardCompatibleClassicFormulas() {
        // classic single-motion behavior must be unchanged
        Formula zi = seq("06");
        assertFalse(zi.isSequence());
        assertEquals(1f, zi.totalDurationSec(), 0.001f);
        assertEquals(1.0f, e.stateAt(zi, 0f).scale, 0.001f);
        assertEquals(1.14f, e.stateAt(zi, 1f).scale, 0.001f);
        assertEquals(1.07f, e.stateAt(zi, 0.5f).scale, 0.02f);
        assertNull(e.effectAt(zi, 0.5f)); // classic -> clip's own effect
    }

    @Test public void stepAtTimeAndClamping() {
        Formula f = seq("S1");
        assertEquals(0f, f.steps.indexOf(e.stepAtTime(f, 0f)), 0);
        assertEquals(1f, f.steps.indexOf(e.stepAtTime(f, 2.5f)), 0);
        assertEquals(2f, f.steps.indexOf(e.stepAtTime(f, 4.5f)), 0);
        assertEquals(3f, f.steps.indexOf(e.stepAtTime(f, 7.9999f)), 0);
        // clamped beyond total
        assertNotNull(e.stepAtTime(f, 99f));
        // null-safe
        assertNull(e.stepAtTime(null, 1f));
        assertNull(e.stepAtTime(seq("06"), 1f));
    }

    @Test public void stepTransitionMixAtBoundaries() {
        Formula f = buildSeq("06", "07");
        // default: no step transitions
        assertEquals(0f, e.stepTransitionMix(f, 1.9f), 0.001f);
        // set a transition at the end of step 1 (into step 2)
        f.steps.get(0).transition = TransitionType.CROSS_DISSOLVE;
        assertEquals(0f, e.stepTransitionMix(f, 1.0f), 0.001f);
        assertTrue(e.stepTransitionMix(f, 1.85f) > 0f);
        assertEquals(1f, e.stepTransitionMix(f, 2.0f), 0.001f);
        assertEquals(0f, e.stepTransitionMix(f, 2.5f), 0.001f);
        // next-step state is step 2's start (Zoom Out start = scale 1.14)
        KeyframeState ns = e.nextStepStateAt(f, 1.9f);
        assertEquals(1.14f, ns.scale, 0.01f);
    }

    @Test public void stepEffectOverride() {
        Formula f = buildSeq("06", "07");
        f.steps.get(1).effect = EffectType.CINEMATIC;
        f.steps.get(1).effectIntensity = 0.9f;
        assertNull(e.effectAt(f, 1f));          // step 1: none -> clip effect
        assertEquals(EffectType.CINEMATIC, e.effectAt(f, 3f)); // step 2
        assertEquals(0.6f, e.stepEffectIntensity(f, 1f, 0.6f), 0.001f); // clip intensity
        assertEquals(0.9f, e.stepEffectIntensity(f, 3f, 0.6f), 0.001f); // step intensity
    }

    @Test public void applyingSequenceReplacesAndNoneRemoves() {
        // simulate: apply S1 to a clip, then replace with S3, then None
        TimelineClip c = new TimelineClip("uri", 1, e.defaultFormula());
        c.formula = seq("S1");
        assertTrue(c.formula.isSequence());
        c.formula = seq("S3");
        assertEquals("S3", c.formula.id);
        c.formula = seq("00"); // None
        assertFalse(c.formula.isSequence());
        // None is static: no motion over the whole clip
        KeyframeState a = e.stateAt(c.formula, 0f);
        KeyframeState b = e.stateAt(c.formula, 1f);
        assertEquals(a.scale, b.scale, 0.0001f);
        assertEquals(a.x, b.x, 0.0001f);
        assertEquals(a.y, b.y, 0.0001f);
    }

    @Test public void sequenceClonesAreIndependent() {
        Formula a = seq("S1");
        Formula b = seq("S1");
        a.steps.get(0).transition = TransitionType.ZOOM;
        assertNotSame(a.steps, b.steps);
        assertEquals(TransitionType.NONE, b.steps.get(0).transition);
    }
}
