package com.autoedit;

import com.autoedit.engine.TransitionEngine;
import com.autoedit.model.TransitionType;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransitionEngineTest {
    private final TransitionEngine te = new TransitionEngine();

    @Test public void noneIsIdentity(){
        TransitionEngine.Transform tr = te.incoming(TransitionType.NONE, 0.5f);
        assertEquals(1f, tr.alpha, 0.001f);
        assertEquals(1f, tr.scale, 0.001f);
        assertEquals(0f, tr.dx, 0.001f);
        assertEquals(0f, tr.dy, 0.001f);
    }

    @Test public void fadeFadesInThroughBackground(){
        assertEquals(0f, te.incoming(TransitionType.FADE, 0f).alpha, 0.001f);
        assertEquals(1f, te.incoming(TransitionType.FADE, 1f).alpha, 0.001f);
        assertTrue(te.fadesThroughBackground(TransitionType.FADE));
        assertFalse(te.fadesThroughBackground(TransitionType.CROSS_DISSOLVE));
        assertEquals(1f, te.incoming(TransitionType.FADE, 0.5f).scale, 0.001f);
    }

    @Test public void crossDissolveBlends(){
        assertEquals(0f, te.incoming(TransitionType.CROSS_DISSOLVE, 0f).alpha, 0.001f);
        assertEquals(1f, te.incoming(TransitionType.CROSS_DISSOLVE, 1f).alpha, 0.001f);
        assertEquals(1f, te.incoming(TransitionType.CROSS_DISSOLVE, 0.5f).scale, 0.001f);
    }

    @Test public void zoomScalesInAndStaysOpaque(){
        TransitionEngine.Transform s0 = te.incoming(TransitionType.ZOOM, 0f);
        TransitionEngine.Transform s1 = te.incoming(TransitionType.ZOOM, 1f);
        assertTrue(s0.scale > 1.2f);
        assertEquals(1f, s1.scale, 0.001f);
        assertEquals(1f, s0.alpha, 0.001f);
        assertEquals(1f, s1.alpha, 0.001f);
    }

    @Test public void slideLeftEntersFromRight(){
        assertEquals(1f, te.incoming(TransitionType.SLIDE_LEFT, 0f).dx, 0.001f);
        assertEquals(0f, te.incoming(TransitionType.SLIDE_LEFT, 1f).dx, 0.001f);
        assertEquals(0f, te.incoming(TransitionType.SLIDE_LEFT, 0.5f).dy, 0.001f);
        // slide right is the mirror
        assertEquals(-1f, te.incoming(TransitionType.SLIDE_RIGHT, 0f).dx, 0.001f);
    }

    /**
     * Vertical slides use the vertical axis and enter from the edge their name
     * says. "Slide Up" means the new clip slides UP into frame from the BOTTOM,
     * so it starts at dy=+1. The previous values (-1 / +1) had the incoming
     * clip entering from the same edge the outgoing one left through, which
     * left a growing black wedge across half the frame mid-transition.
     */
    @Test public void slideUpAndDownUseVerticalAxis(){
        assertEquals(1f, te.incoming(TransitionType.SLIDE_UP, 0f).dy, 0.001f);
        assertEquals(0f, te.incoming(TransitionType.SLIDE_UP, 1f).dy, 0.001f);
        assertEquals(-1f, te.incoming(TransitionType.SLIDE_DOWN, 0f).dy, 0.001f);
        assertEquals(0f, te.incoming(TransitionType.SLIDE_DOWN, 1f).dy, 0.001f);
        assertEquals(0f, te.incoming(TransitionType.SLIDE_UP, 0f).dx, 0.001f);
    }

    /**
     * No transition in the catalog may leave an uncovered band of the frame.
     * For every mix, the outgoing panel and the incoming panel must jointly
     * cover the full 0..1 span on the axis they move along.
     */
    @Test public void noDirectionalTransitionLeavesAGap(){
        TransitionType[] directional = {
                TransitionType.SLIDE_LEFT, TransitionType.SLIDE_RIGHT,
                TransitionType.SLIDE_UP, TransitionType.SLIDE_DOWN,
                TransitionType.PUSH_LEFT, TransitionType.PUSH_RIGHT,
                TransitionType.PUSH_UP, TransitionType.PUSH_DOWN
        };
        for (TransitionType t : directional) {
            for (int i = 0; i <= 40; i++) {
                float p = i / 40f;
                float outAxis = axisOf(te.outgoing(t, p), t);
                float inAxis = axisOf(te.incoming(t, p), t);
                float lo = Math.min(outAxis, inAxis);
                float hi = Math.max(outAxis, inAxis) + 1f;
                assertTrue(t + " leaves a gap at mix " + p + ": union=[" + lo + "," + hi + "]",
                        lo <= 1e-4f && hi >= 1f - 1e-4f);
            }
        }
    }

    private static float axisOf(TransitionEngine.Transform tr, TransitionType t) {
        switch (t) {
            case SLIDE_UP: case SLIDE_DOWN: case PUSH_UP: case PUSH_DOWN: return tr.dy;
            default: return tr.dx;
        }
    }

    @Test public void easingIsApplied(){
        // extremes must map exactly to 0/1 regardless of easing
        assertEquals(1f, te.incoming(TransitionType.SLIDE_LEFT, 0f).dx, 0.001f);
        assertEquals(0f, te.incoming(TransitionType.SLIDE_LEFT, 1f).dx, 0.001f);
        // eased midpoint (0.5) of an ease-in-out ramp is exactly 0.5
        assertEquals(0.5f, te.incoming(TransitionType.FADE, 0.5f).alpha, 0.001f);
    }

    @Test public void onlyRenderedTransitionsAreExposed(){
        for (TransitionType t : TransitionEngine.rendered()) {
            assertNotNull(TransitionEngine.label(t));
            TransitionEngine.Transform tr = te.incoming(t, 0.5f);
            assertTrue(tr.alpha >= 0f && tr.alpha <= 1f);
            assertTrue(tr.scale > 0f);
        }
    }
}
