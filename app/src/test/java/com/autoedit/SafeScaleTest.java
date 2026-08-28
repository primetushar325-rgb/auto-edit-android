package com.autoedit;

import com.autoedit.engine.MotionCatalog;
import com.autoedit.engine.SafeTransform;
import com.autoedit.model.Formula;
import com.autoedit.model.KeyframeState;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * No black gaps (spec §7).
 *
 * The cover multiplier must come from the ACTUAL drawn geometry — the pan
 * offset in canvas fractions, the rotation, and the source/canvas aspects —
 * not from a fixed heuristic.
 *
 * <h3>Why two of these numbers moved</h3>
 * The previous implementation estimated the requirement as
 * {@code 1 + 2*(maxPan + travel/2)} on BOTH axes. That is a canvas-fraction
 * formula applied to a pixel scale, so it over-estimated: for a ±0.06
 * horizontal pan on a 16:9 canvas the true requirement is
 * {@code 1 + 2*0.06 = 1.12}, while the heuristic produced 1.24. Over-scaling
 * never shows a black edge, but it crops the subject far more than the motion
 * needs, which is exactly what spec §8 ("avoid cropping faces unnecessarily")
 * asks us not to do.
 *
 * So the numeric assertions below now state the EXACT geometric requirement,
 * and {@link #everyCatalogMotionNeverExposesAnEdge} proves the property that
 * actually matters — at 200 progress samples, for every catalog motion, across
 * five canvas aspects and three source aspects, the drawn rect covers the
 * canvas. That is a strictly stronger test than a magic number.
 */
public class SafeScaleTest {
    private static final float ASPECT_16_9 = 16f / 9f;

    /** Exact requirement for a pure horizontal pan of ±0.06 on a matching aspect. */
    private static final float EXACT_PAN_006 = 1f + 2f * 0.06f; // 1.12

    @Test public void staticFrameHasOnlySmallSafetyMargin() {
        KeyframeState a = new KeyframeState(0, 0, 1f, 0, 1);
        KeyframeState b = new KeyframeState(0, 0, 1f, 0, 1);
        float m = SafeTransform.safeScaleMultiplier(ASPECT_16_9, ASPECT_16_9, a, b);
        assertEquals(SafeTransform.SAFETY_MARGIN, m, 0.005f);
        assertTrue(m >= 1f);
    }

    @Test public void widePanSweepRequiresLargeScale() {
        KeyframeState a = new KeyframeState(-0.06f, 0, 1f, 0, 1);
        KeyframeState b = new KeyframeState(0.06f, 0, 1f, 0, 1);
        float m = SafeTransform.safeScaleMultiplier(ASPECT_16_9, ASPECT_16_9, a, b);
        float expected = EXACT_PAN_006 * SafeTransform.SAFETY_MARGIN;
        assertEquals("exact cover for a ±0.06 pan", expected, m, 0.01f);
        assertTrue("must not under-scale", m >= EXACT_PAN_006);
        assertTrue("must not over-crop like the old heuristic did", m < 1.20f);
    }

    @Test public void offsetPanRequiresMoreThanZeroPan() {
        KeyframeState a = new KeyframeState(0f, 0, 1f, 0, 1);
        KeyframeState b = new KeyframeState(0.08f, 0, 1f, 0, 1);
        float mPan = SafeTransform.safeScaleMultiplier(ASPECT_16_9, ASPECT_16_9, a, b);
        KeyframeState s = new KeyframeState(0, 0, 1f, 0, 1);
        float mStatic = SafeTransform.safeScaleMultiplier(ASPECT_16_9, ASPECT_16_9, s, s);
        assertTrue(mPan > mStatic);
        assertEquals((1f + 2f * 0.08f) * SafeTransform.SAFETY_MARGIN, mPan, 0.01f);
    }

    @Test public void rotationRequiresExtraCover() {
        KeyframeState a = new KeyframeState(0, 0, 1f, -8f, 1);
        KeyframeState b = new KeyframeState(0, 0, 1f, 8f, 1);
        float m = SafeTransform.safeScaleMultiplier(ASPECT_16_9, ASPECT_16_9, a, b);
        assertTrue("8 deg rotation needs cover > 1.05, got " + m, m > 1.05f);
    }

    @Test public void verticalPanCoveredOnPortrait() {
        float aspect916 = 9f / 16f;
        KeyframeState a = new KeyframeState(0, -0.08f, 1f, 0, 1);
        KeyframeState b = new KeyframeState(0, 0.08f, 1f, 0, 1);
        float m = SafeTransform.safeScaleMultiplier(aspect916, aspect916, a, b);
        float expected = (1f + 2f * 0.08f) * SafeTransform.SAFETY_MARGIN;
        assertEquals("exact cover for a ±0.08 vertical pan", expected, m, 0.01f);
        assertTrue(m >= 1f + 2f * 0.08f);
    }

    @Test public void neverBelowUnity() {
        KeyframeState a = new KeyframeState(0, 0, 1f, 0, 1);
        KeyframeState b = new KeyframeState(0, 0, 1f, 0, 1);
        assertTrue(SafeTransform.safeScaleMultiplier(0.5f, 2f, a, b) >= 1f);
        assertTrue(SafeTransform.safeScaleMultiplier(2f, 0.5f, a, b) >= 1f);
    }

    /**
     * The property that actually matters: no motion in the catalog, on any
     * canvas, at any point in its own timeline, leaves an uncovered edge.
     */
    @Test public void everyCatalogMotionNeverExposesAnEdge() {
        float[] canvasAspects = {16f / 9f, 9f / 16f, 1f, 4f / 5f, 21f / 9f};
        float[] sourceAspects = {16f / 9f, 9f / 16f, 1f};
        int checked = 0;
        for (Formula m : MotionCatalog.all()) {
            for (float ca : canvasAspects) {
                for (float sa : sourceAspects) {
                    int sw = 1200, sh = Math.max(2, Math.round(1200f / sa));
                    int cw = 1080, ch = Math.max(2, Math.round(1080f / ca));
                    for (int i = 0; i <= 200; i++) {
                        float p = i / 200f;
                        KeyframeState st = KeyframeState.lerp(m.start, m.end,
                                (m.easing == null ? com.autoedit.model.Easing.DEFAULT : m.easing).apply(p));
                        SafeTransform.Box r = SafeTransform.fillRect(sw, sh, cw, ch, st, m.start, m.end, m.easing,
                                SafeTransform.SAFETY_MARGIN);
                        assertTrue("black edge: motion " + m.id + " (" + m.name + ") canvas "
                                        + ca + " source " + sa + " p=" + p + " rect=" + r,
                                SafeTransform.coversCanvas(r, cw, ch));
                        checked++;
                    }
                }
            }
        }
        assertTrue("expected to sample the whole catalog", checked > 100_000);
    }

    /** An overshooting (Back) easing must not expose an edge either. */
    @Test public void overshootingEasingStillCovers() {
        int sw = 1200, sh = 675, cw = 1080, ch = 608;
        KeyframeState a = new KeyframeState(-0.05f, 0, 1.05f, 0, 1);
        KeyframeState b = new KeyframeState(0.05f, 0, 1.10f, 0, 1);
        com.autoedit.model.Easing back = com.autoedit.model.Easing.BACK_IN_OUT;
        for (int i = 0; i <= 400; i++) {
            float p = i / 400f;
            KeyframeState st = KeyframeState.lerp(a, b, back.apply(p));
            SafeTransform.Box r = SafeTransform.fillRect(sw, sh, cw, ch, st, a, b, back, SafeTransform.SAFETY_MARGIN);
            assertTrue("back-eased frame uncovered at p=" + p, SafeTransform.coversCanvas(r, cw, ch));
        }
    }

    /** The background layer always covers, whatever the source aspect. */
    @Test public void backgroundLayerAlwaysCovers() {
        float[] src = {0.3f, 1f, 3.5f};
        for (float sa : src) {
            int sw = 1000, sh = Math.max(2, Math.round(1000f / sa));
            SafeTransform.Box r = SafeTransform.backgroundRect(sw, sh, 1080, 1920, 1.18f);
            assertTrue("background uncovered for source aspect " + sa,
                    SafeTransform.coversCanvas(r, 1080, 1920));
        }
    }
}
