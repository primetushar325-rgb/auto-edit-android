package com.autoedit;

import com.autoedit.engine.*;
import com.autoedit.model.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * THE most important rule in the product (spec §1, §39, TEST B/C/D):
 *
 * <pre>
 *   ONE FORMULA STEP = ONE CLIP.
 *   NOT: ONE CLIP = MULTIPLE FORMULA STEPS.
 * </pre>
 *
 * Every test here asserts the cyclic assignment across clips and asserts that
 * within a single clip the motion never changes identity — a clip's motion is
 * the same from p=0 to p=1.
 */
public class FormulaCyclicPatternTest {

    /** Builds the exact formula from the spec: Zoom Out, Slide Left, Pan Down, Zoom In. */
    private Formula specFormula() {
        Formula f = new Formula("Fspec", "Spec Cycle", "Cinematic",
                new KeyframeState(0, 0, 1f, 0, 1), new KeyframeState(0, 0, 1f, 0, 1));
        f.steps = new ArrayList<>();
        FormulaEngine e = new FormulaEngine();
        f.steps.add(new FormulaStep(e.byId("07"))); // Zoom Out
        f.steps.add(new FormulaStep(e.byId("02"))); // Pan (slide) Left
        f.steps.add(new FormulaStep(e.byId("01"))); // Pan Down
        f.steps.add(new FormulaStep(e.byId("06"))); // Zoom In
        return f;
    }

    // ---------------------------------------------------------------- TEST C

    @Test public void eightClipsWithFourStepsRepeatExactlyTwice() {
        FormulaEngine e = new FormulaEngine();
        Formula f = specFormula();
        String[] expected = {"07", "02", "01", "06", "07", "02", "01", "06"};
        for (int i = 0; i < 8; i++)
            assertEquals("clip " + i, expected[i], e.motionForClip(f, i).id);
    }

    /** The literal example from the spec: 10 clips against a 4-step formula. */
    @Test public void tenClipsMatchTheSpecExampleVerbatim() {
        FormulaEngine e = new FormulaEngine();
        Formula f = specFormula();
        String[] spec = {"07", "02", "01", "06", "07", "02", "01", "06", "07", "02"};
        for (int i = 0; i < spec.length; i++)
            assertEquals("clip " + (i + 1), spec[i], e.motionForClip(f, i).id);
    }

    // ---------------------------------------------------------------- TEST D

    @Test public void hundredClipsStayCyclicAndResolveFast() {
        FormulaEngine e = new FormulaEngine();
        Formula f = specFormula();
        EditProject p = new EditProject();
        for (int i = 0; i < 100; i++) p.clips.add(new TimelineClip("content://img/" + i, i + 1, f));

        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertEquals("clip " + i, specFormula().steps.get(i % 4).motion.id, e.motionForClip(f, i).id);
            // and the state really resolves, not just the id
            assertNotNull(e.stateForClip(f, i, 0.5f));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue("resolving 100 clips took " + ms + "ms", ms < 500);
    }

    @Test public void fewerClipsThanStepsUsesOnlyTheNeededSteps() {
        FormulaEngine e = new FormulaEngine();
        Formula f = specFormula();
        // One clip: only step 0 is ever reached, and it still spans 0..1.
        assertEquals("07", e.motionForClip(f, 0).id);
        assertEquals(f.steps.get(0).motion.start.scale, e.stateForClip(f, 0, 0f).scale, 1e-6f);
        assertEquals(f.steps.get(0).motion.end.scale, e.stateForClip(f, 0, 1f).scale, 1e-6f);
    }

    @Test public void twoAndThreeClipProjectsResolve() {
        FormulaEngine e = new FormulaEngine();
        Formula f = specFormula();
        assertEquals("07", e.motionForClip(f, 0).id);
        assertEquals("02", e.motionForClip(f, 1).id);
        assertEquals("01", e.motionForClip(f, 2).id);
    }

    // ------------------------------------------------- the core invariant

    /**
     * Within ONE clip the motion identity never changes and the transform goes
     * monotonically from that motion's start keyframe to its end keyframe.
     * This is the regression test for the old "multiple motions inside one
     * image" bug (audit finding C1).
     */
    @Test public void oneClipPlaysOneMotionForItsWholeDuration() {
        FormulaEngine e = new FormulaEngine();
        Formula f = specFormula();
        for (int clip = 0; clip < 12; clip++) {
            Formula motion = e.motionForClip(f, clip);
            KeyframeState start = e.stateForClip(f, clip, 0f);
            KeyframeState end = e.stateForClip(f, clip, 1f);
            assertEquals("clip " + clip + " must start at its own motion's start",
                    motion.start.scale, start.scale, 1e-5f);
            assertEquals("clip " + clip + " must end at its own motion's end",
                    motion.end.scale, end.scale, 1e-5f);
            assertEquals(motion.start.x, start.x, 1e-5f);
            assertEquals(motion.end.x, end.x, 1e-5f);

            // continuity: no jump anywhere inside the clip
            KeyframeState prev = start;
            for (int i = 1; i <= 300; i++) {
                KeyframeState s = e.stateForClip(f, clip, i / 300f);
                assertTrue("scale jumped inside clip " + clip, Math.abs(s.scale - prev.scale) < 0.02f);
                assertTrue("x jumped inside clip " + clip, Math.abs(s.x - prev.x) < 0.02f);
                prev = s;
            }
        }
    }

    /** A 5 s clip and an 8 s clip play the SAME normalized motion. */
    @Test public void motionIsNormalizedToEachClipsOwnDuration() {
        FormulaEngine e = new FormulaEngine();
        Formula f = specFormula();
        for (float p : new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}) {
            assertEquals(e.stateForClip(f, 0, p).scale, e.stateForClip(f, 4, p).scale, 1e-6f);
        }
    }

    // ------------------------------------------------------ step metadata

    @Test public void transitionStaysBetweenClipsNotInsideThem() {
        FormulaEngine e = new FormulaEngine();
        Formula f = specFormula();
        f.steps.get(0).transition = TransitionType.CROSS_DISSOLVE;
        f.steps.get(1).transition = TransitionType.NONE;
        // Step k defines the junction AFTER clip k (spec §46).
        assertSame(TransitionType.CROSS_DISSOLVE, e.transitionForClip(f, 0));
        assertSame(TransitionType.NONE, e.transitionForClip(f, 1));
        assertSame(TransitionType.CROSS_DISSOLVE, e.transitionForClip(f, 4)); // 4 % 4 == 0
    }

    @Test public void everyBuiltInPatternIsAPerClipPattern() {
        FormulaEngine e = new FormulaEngine();
        List<Formula> patterns = e.patterns();
        assertTrue("expected a real catalog of patterns", patterns.size() >= 20);
        for (Formula f : patterns) {
            assertTrue(f.name + " must be a pattern", f.isPattern());
            assertTrue(f.name + " must have steps", f.steps.size() >= 2);
            for (FormulaStep s : f.steps) assertNotNull(f.name + " step has no motion", s.motion);
        }
    }

    /** Unlimited steps — there is no 4-step cap (spec §5). */
    @Test public void unlimitedStepCountsAreSupported() {
        FormulaEngine e = new FormulaEngine();
        for (int n : new int[]{2, 3, 5, 7, 12, 25}) {
            Formula f = new Formula("Fn" + n, "N=" + n, "Test",
                    new KeyframeState(0, 0, 1f, 0, 1), new KeyframeState(0, 0, 1f, 0, 1));
            f.steps = new ArrayList<>();
            for (int i = 0; i < n; i++) f.steps.add(new FormulaStep(MotionCatalog.byId(i % 2 == 0 ? "06" : "07")));
            assertEquals(n, f.patternSize());
            assertEquals(f.steps.get((n * 3 + 2) % n).motion.id, e.motionForClip(f, n * 3 + 2).id);
        }
    }

    // ------------------------------------------------------- effect layers

    @Test public void effectStackIsLayeredNotCollapsed() {
        TimelineClip c = new TimelineClip("content://x", 1, MotionCatalog.byId("06"));
        c.setSingleEffect(EffectType.GLOW, 0.7f);
        c.addEffectLayer(EffectType.VIGNETTE, 0.5f);
        c.addEffectLayer(EffectType.FILM_GRAIN, 0.4f);
        assertEquals(3, c.resolvedLayers().size());
        assertSame(EffectType.GLOW, c.resolvedLayers().get(0).type);
        assertSame(EffectType.VIGNETTE, c.resolvedLayers().get(1).type);
        assertSame(EffectType.FILM_GRAIN, c.resolvedLayers().get(2).type);
        // the legacy single-effect fields stay in sync with layer 0
        assertSame(EffectType.GLOW, c.effect);
        assertEquals(0.7f, c.effectIntensity, 1e-6f);

        c.removeEffectLayer(EffectType.VIGNETTE);
        assertEquals(2, c.resolvedLayers().size());
        c.clearEffects();
        assertTrue(c.resolvedLayers().isEmpty());
        assertSame(EffectType.NONE, c.effect);
    }

    @Test public void patternEffectDoesNotDoubleApplyWithClipEffect() {
        FormulaEngine e = new FormulaEngine();
        Formula f = specFormula();
        f.steps.get(1).effect = EffectType.CINEMATIC;
        TimelineClip c = new TimelineClip("content://x", 2, f);
        c.setSingleEffect(EffectType.CINEMATIC, 0.5f);
        List<EffectLayer> layers = e.effectLayersForClip(c, 1);
        int count = 0;
        for (EffectLayer l : layers) if (l.type == EffectType.CINEMATIC) count++;
        assertEquals("the pattern and the clip must not stack the same effect twice", 1, count);
    }

    // ------------------------------------------------------ catalog scale

    @Test public void motionCatalogCoversTheSpecList() {
        List<Formula> all = MotionCatalog.all();
        assertTrue("spec asks for at least 40 motions, found " + all.size(), all.size() >= 40);
        // Every motion must be a real renderable transform, not a UI-only label:
        // either it moves, it scales, or it rotates between its keyframes.
        for (Formula m : all) {
            assertNotNull(m.id + " has no start", m.start);
            assertNotNull(m.id + " has no end", m.end);
            boolean moves = Math.abs(m.start.x - m.end.x) > 1e-4f || Math.abs(m.start.y - m.end.y) > 1e-4f;
            boolean scales = Math.abs(m.start.scale - m.end.scale) > 1e-4f;
            boolean rotates = Math.abs(m.start.rotation - m.end.rotation) > 1e-4f;
            assertTrue(m.id + " (" + m.name + ") is a no-op motion", moves || scales || rotates
                    || "00".equals(m.id));
            assertNotNull(m.id + " has no description", m.description);
            assertFalse(m.id + " description is empty", m.description.isEmpty());
            assertNotNull(m.id + " has no easing", m.easing);
        }
    }

    @Test public void everyCatalogIdIsUnique() {
        List<Formula> all = MotionCatalog.all();
        for (int i = 0; i < all.size(); i++)
            for (int j = i + 1; j < all.size(); j++)
                assertNotEquals("duplicate motion id " + all.get(i).id, all.get(i).id, all.get(j).id);
    }

    @Test public void everyRenderedTransitionIsLabelledAndBounded() {
        TransitionEngine te = new TransitionEngine();
        TransitionType[] all = TransitionEngine.rendered();
        assertTrue("expected a real transition catalog", all.length >= 28);
        for (TransitionType t : all) {
            assertNotNull(TransitionEngine.label(t));
            assertNotEquals(t.name(), TransitionEngine.label(t));
            for (float p : new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}) {
                TransitionEngine.Transform in = te.incoming(t, p);
                TransitionEngine.Transform out = te.outgoing(t, p);
                assertTrue(t + " incoming alpha", in.alpha >= 0f && in.alpha <= 1.0001f);
                assertTrue(t + " outgoing alpha", out.alpha >= 0f && out.alpha <= 1.0001f);
                assertTrue(t + " scale", in.scale > 0f && out.scale > 0f);
            }
        }
    }

    /**
     * A push must tile the canvas exactly: the incoming clip's leading edge
     * abuts the outgoing clip's trailing edge at every mix, and the union of
     * the two always covers [0,1]. The old implementation moved the incoming
     * clip by only 0.35*(1-p) while the outgoing travelled the full width,
     * which left a growing black wedge in the middle.
     */
    @Test public void pushTransitionsTileTheCanvasWithNoGap() {
        TransitionEngine te = new TransitionEngine();
        for (int i = 0; i <= 20; i++) {
            float p = i / 20f;

            // SLIDE keeps the outgoing clip in place and wipes the new one over it.
            assertEquals(0f, te.outgoing(TransitionType.SLIDE_LEFT, p).dx, 1e-5f);
            assertEquals(0f, te.outgoing(TransitionType.SLIDE_UP, p).dy, 1e-5f);
            assertCovers(0f, te.incoming(TransitionType.SLIDE_LEFT, p).dx, "SLIDE_LEFT p=" + p);
            assertCovers(0f, te.incoming(TransitionType.SLIDE_UP, p).dy, "SLIDE_UP p=" + p);
            assertCovers(0f, te.incoming(TransitionType.SLIDE_DOWN, p).dy, "SLIDE_DOWN p=" + p);
            assertCovers(0f, te.incoming(TransitionType.SLIDE_RIGHT, p).dx, "SLIDE_RIGHT p=" + p);

            float oL = te.outgoing(TransitionType.PUSH_LEFT, p).dx;
            float iL = te.incoming(TransitionType.PUSH_LEFT, p).dx;
            assertEquals("PUSH_LEFT: edges must meet exactly", oL + 1f, iL, 1e-5f);
            assertCovers(oL, iL, "PUSH_LEFT p=" + p);

            float oR = te.outgoing(TransitionType.PUSH_RIGHT, p).dx;
            float iR = te.incoming(TransitionType.PUSH_RIGHT, p).dx;
            assertEquals("PUSH_RIGHT: edges must meet exactly", oR - 1f, iR, 1e-5f);
            assertCovers(oR, iR, "PUSH_RIGHT p=" + p);

            float oU = te.outgoing(TransitionType.PUSH_UP, p).dy;
            float iU = te.incoming(TransitionType.PUSH_UP, p).dy;
            assertEquals("PUSH_UP: edges must meet exactly", oU + 1f, iU, 1e-5f);
            assertCovers(oU, iU, "PUSH_UP p=" + p);

            float oD = te.outgoing(TransitionType.PUSH_DOWN, p).dy;
            float iD = te.incoming(TransitionType.PUSH_DOWN, p).dy;
            assertEquals("PUSH_DOWN: edges must meet exactly", oD - 1f, iD, 1e-5f);
            assertCovers(oD, iD, "PUSH_DOWN p=" + p);
        }
    }

    /** Two unit-length panels at offsets a and b must jointly cover [0,1]. */
    private static void assertCovers(float a, float b, String what) {
        float lo = Math.min(a, b);
        float hi = Math.max(a, b) + 1f;
        assertTrue(what + " leaves a gap: union=[" + lo + "," + hi + "]",
                lo <= 1e-4f && hi >= 1f - 1e-4f);
    }

    @Test public void everyEffectIsImplementedAndLabelled() {
        for (EffectType t : EffectType.values()) {
            String l = EffectEngine.label(t);
            assertNotNull(l);
            assertFalse("effect " + t + " has no label", l.isEmpty());
            // An effect must do SOMETHING: a colour matrix, a post overlay,
            // a blur halo, or a channel shift. Otherwise it is a fake option.
            EffectEngine e = new EffectEngine();
            boolean hasMatrix = e.matrixFor(t, 0.8f) != null;
            boolean hasPost = t == EffectType.VIGNETTE || t == EffectType.FILM_GRAIN
                    || t == EffectType.LIGHT_LEAK || t == EffectType.LENS_FLARE
                    || t == EffectType.DUST || t == EffectType.PARTICLES
                    || t == EffectType.FILM_FLICKER || t == EffectType.SUBTLE_NOISE
                    || t == EffectType.GLOW || t == EffectType.SOFT_GLOW || t == EffectType.BLOOM
                    || t == EffectType.CINEMATIC_GLOW || t == EffectType.DREAM_GLOW
                    || t == EffectType.HIGHLIGHT_GLOW;
            boolean hasBlur = e.blurStrengthFor(t, 0.8f) > 0f;
            boolean hasShift = e.channelShift(t, 0.8f) > 0f;
            boolean isAlpha = t == EffectType.FADE;
            assertTrue("effect " + t + " has no rendering path",
                    t == EffectType.NONE || hasMatrix || hasPost || hasBlur || hasShift || isAlpha);
        }
    }
}
