package com.autoedit;

import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.*;
import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;

/**
 * Custom-formula pattern contract (Parts 3, 16, 31): a custom formula built
 * from per-clip steps is a REPEATING per-clip pattern, and has no 4-step cap.
 * This mirrors the result of CustomFormulaStore.toFormula(steps[]) without
 * touching Android (org.json is not mocked in JVM tests), using the same
 * FormulaStep(motion) shape the store produces.
 */
public class CustomFormulaStorePatternTest {

    /** Builds a custom pattern from per-clip motion ids + optional meta. */
    private Formula custom(String... motionIds) {
        FormulaEngine e = new FormulaEngine();
        Formula f = new Formula("Ctest", "Custom", "Custom",
                new KeyframeState(0, 0, 1f, 0, 1), new KeyframeState(0, 0, 1f, 0, 1));
        f.steps = new ArrayList<>();
        for (String mid : motionIds) f.steps.add(new FormulaStep(e.byId(mid)));
        return f;
    }

    @Test public void stepsArePerClipPattern() {
        FormulaEngine e = new FormulaEngine();
        // Zoom Out, Pan Left, Pan Down, Zoom In
        Formula f = custom("07", "02", "01", "06");
        f.steps.get(0).transition = TransitionType.FADE;
        f.steps.get(1).effect = EffectType.CINEMATIC;
        f.steps.get(1).effectIntensity = 0.7f;

        assertTrue(f.isPattern());
        assertEquals(4, f.patternSize());

        // clip i uses ONLY motion (i % 4)
        assertEquals("07", e.motionForClip(f, 0).id);
        assertEquals("02", e.motionForClip(f, 1).id);
        assertEquals("01", e.motionForClip(f, 2).id);
        assertEquals("06", e.motionForClip(f, 3).id);
        assertEquals("07", e.motionForClip(f, 4).id); // repeats

        // per-step transition/effect meta resolves per clip
        assertSame(TransitionType.FADE, e.transitionForClip(f, 0));
        assertSame(EffectType.CINEMATIC, e.effectForClip(f, 1));
        assertNull(e.effectForClip(f, 0));
        assertEquals(0.7f, e.effectIntensityForClip(f, 1, 0.5f), 1e-6f);
        assertEquals(0.5f, e.effectIntensityForClip(f, 0, 0.5f), 1e-6f);

        // a clip's one motion spans its whole duration (start..end of that motion)
        Formula m0 = e.motionForClip(f, 0);
        assertEquals(m0.start.scale, e.stateForClip(f, 0, 0f).scale, 1e-6f);
        assertEquals(m0.end.scale,   e.stateForClip(f, 0, 1f).scale, 1e-6f);
    }

    @Test public void unlimitedSteps_noFourCap() {
        String[] many = new String[10];
        for (int i = 0; i < 10; i++) many[i] = (i % 2 == 0) ? "06" : "07";
        Formula f = custom(many);
        assertEquals(10, f.patternSize());
        FormulaEngine e = new FormulaEngine();
        // clip 9 -> step 9 (07 Zoom Out); clip 10 -> step 0 (06 Zoom In)
        assertEquals("07", e.motionForClip(f, 9).id);
        assertEquals("06", e.motionForClip(f, 10).id);
    }
}
