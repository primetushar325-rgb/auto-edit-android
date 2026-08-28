package com.autoedit;

import com.autoedit.engine.SafeTransform;
import com.autoedit.model.KeyframeState;
import org.junit.Test;
import static org.junit.Assert.*;

/** Dynamic safe-scale: pans/slides/rotations must NEVER reveal a black edge.
 *  Cover multiplier comes from actual keyframe extents, not a fixed value. */
public class SafeScaleTest {
    private static final float ASPECT_16_9 = 16f / 9f;

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
        assertTrue("pan sweep 0.12 needs cover >= 1.24, got " + m, m >= 1.24f);
    }

    @Test public void offsetPanRequiresMoreThanZeroPan() {
        KeyframeState a = new KeyframeState(0f, 0, 1f, 0, 1);
        KeyframeState b = new KeyframeState(0.08f, 0, 1f, 0, 1);
        float mPan = SafeTransform.safeScaleMultiplier(ASPECT_16_9, ASPECT_16_9, a, b);
        KeyframeState s = new KeyframeState(0, 0, 1f, 0, 1);
        float mStatic = SafeTransform.safeScaleMultiplier(ASPECT_16_9, ASPECT_16_9, s, s);
        assertTrue(mPan > mStatic);
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
        assertTrue("portrait vertical pan cover " + m, m >= 1.30f);
    }

    @Test public void neverBelowUnity() {
        KeyframeState a = new KeyframeState(0, 0, 1f, 0, 1);
        KeyframeState b = new KeyframeState(0, 0, 1f, 0, 1);
        assertTrue(SafeTransform.safeScaleMultiplier(0.5f, 2f, a, b) >= 1f);
        assertTrue(SafeTransform.safeScaleMultiplier(2f, 0.5f, a, b) >= 1f);
    }
}
