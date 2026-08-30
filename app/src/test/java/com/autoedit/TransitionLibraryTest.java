package com.autoedit;

import com.autoedit.engine.TransitionEngine;
import com.autoedit.engine.TransitionRegistry;
import com.autoedit.model.TransitionCategory;
import com.autoedit.model.TransitionPreset;
import com.autoedit.model.TransitionType;
import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Tests for the CapCut-style transition library: registry, search, engine
 *  math, 3D/blur/glitch/flash, and backward compatibility (spec Parts 31, 40). */
public class TransitionLibraryTest {

    @Test public void registryIsLargeAndUnique() {
        List<TransitionPreset> all = TransitionRegistry.all();
        assertTrue("library should have many transitions, got " + all.size(), all.size() >= 150);
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (TransitionPreset p : all) {
            assertTrue("duplicate id " + p.id, ids.add(p.id));
            names.add(p.name);
        }
        assertTrue(names.size() >= 100);
    }

    @Test public void everyPresetResolvesToARenderer() {
        for (TransitionPreset p : TransitionRegistry.all()) {
            assertNotNull(p.type);
            // engine must be able to produce transforms across the whole mix
            for (float m = 0f; m <= 1.001f; m += 0.25f) {
                TransitionEngine.Transform in = new TransitionEngine().incoming(p, m);
                TransitionEngine.Transform out = new TransitionEngine().outgoing(p, m);
                assertNotNull(in); assertNotNull(out);
                assertTrue("alpha range " + p.id, in.alpha >= -0.001f && in.alpha <= 1.001f);
            }
        }
    }

    @Test public void invalidPresetIdFallsBackSafely() {
        assertNull(TransitionRegistry.byId("does_not_exist"));
        assertNotNull(TransitionRegistry.fallback());
        assertEquals(TransitionType.FADE, TransitionRegistry.fallback().type);
        // legacy raw enum name still resolves
        assertNotNull(TransitionRegistry.byId("CROSS_DISSOLVE"));
    }

    @Test public void searchMatchesNameTagsCategory() {
        List<TransitionPreset> zoom = TransitionRegistry.search("zoom");
        assertTrue("zoom should return several, got " + zoom.size(), zoom.size() >= 5);
        boolean hasZoomSwitch = zoom.stream().anyMatch(p -> p.id.equals("zoom_switch"));
        assertTrue(hasZoomSwitch);
        assertTrue(TransitionRegistry.search("blur").size() >= 8);
        assertTrue(TransitionRegistry.search("3d").size() >= 5);
        assertTrue(TransitionRegistry.search("glitch").size() >= 5);
        // empty query returns everything
        assertEquals(TransitionRegistry.all().size(), TransitionRegistry.search("").size());
    }

    @Test public void categoriesPopulated() {
        assertTrue(TransitionRegistry.byCategory(TransitionCategory.THREE_D).size() >= 15);
        assertTrue(TransitionRegistry.byCategory(TransitionCategory.BLUR).size() >= 10);
        assertTrue(TransitionRegistry.byCategory(TransitionCategory.GLITCH).size() >= 10);
        assertTrue(TransitionRegistry.byCategory(TransitionCategory.FLASH).size() >= 10);
        assertTrue(TransitionRegistry.byCategory(TransitionCategory.CAMERA).size() >= 10);
        assertTrue(TransitionRegistry.trending().size() >= 8);
    }

    @Test public void threeDUsesPerspectiveRotation() {
        TransitionPreset cube = TransitionRegistry.byId("cube_left");
        assertNotNull(cube);
        float maxY = 0f;
        for (float m = 0f; m <= 1f; m += 0.05f) {
            TransitionEngine.Transform out = new TransitionEngine().outgoing(cube, m);
            maxY = Math.max(maxY, Math.abs(out.rotY));
        }
        assertTrue("cube must rotate around Y (perspective), rotY max=" + maxY, maxY > 45f);
    }

    @Test public void blurTransitionsProduceBlur() {
        TransitionPreset mb = TransitionRegistry.byId("vertical_blur");
        float peak = 0f;
        for (float m = 0f; m <= 1f; m += 0.05f)
            peak = Math.max(peak, new TransitionEngine().incoming(mb, m).blurAmount);
        assertTrue("blur transition should blur mid-mix", peak > 0.3f);
    }

    @Test public void glitchProducesChromaAndRgbSplit() {
        TransitionPreset g = TransitionRegistry.byId("rgb_split");
        float peak = 0f;
        for (float m = 0f; m <= 1f; m += 0.05f)
            peak = Math.max(peak, new TransitionEngine().incoming(g, m).chroma);
        assertTrue("RGB split should produce chroma offset", peak > 0.3f);
    }

    @Test public void flashProducesOverlay() {
        TransitionPreset wf = TransitionRegistry.byId("white_flash");
        TransitionEngine te = new TransitionEngine();
        float peakAlpha = 0f; int color = 0;
        for (float m = 0f; m <= 1f; m += 0.05f) {
            TransitionEngine.Transform in = te.incoming(wf, m);
            if (in.overlayAlpha > peakAlpha) { peakAlpha = in.overlayAlpha; color = in.overlayColor; }
        }
        assertTrue("white flash must paint overlay", peakAlpha > 0.5f);
        assertEquals("white", 0xFFFFFFFF, color);
        // black flash uses black
        TransitionPreset bf = TransitionRegistry.byId("black_flash");
        TransitionEngine.Transform mid = te.incoming(bf, 0.5f);
        assertEquals(0xFF000000, mid.overlayColor);
    }

    @Test public void maskRevealExpands() {
        TransitionPreset circle = TransitionRegistry.byId("circle_reveal");
        TransitionEngine te = new TransitionEngine();
        assertEquals(0f, te.incoming(circle, 0f).revealRadius, 0.001f);
        assertTrue(te.incoming(circle, 0.5f).revealRadius > 0.3f);
        // incoming starts fully masked (alpha via reveal), old clip remains
        assertEquals(1f, te.outgoing(circle, 0.2f).alpha, 0.05f);
    }

    @Test public void durationClampRespectsNeighbours() {
        TransitionPreset p = TransitionRegistry.byId("zoom_switch");
        float d = p.clampDuration(5f); // asks for 5s
        assertTrue("clamped to max 2s", d <= 2f);
        assertTrue(p.clampDuration(0.01f) >= p.minDuration);
    }

    @Test public void legacyTransitionsStillWork() {
        // the original 23 enum values must all still exist and render
        for (TransitionType t : TransitionEngine.rendered()) {
            TransitionEngine.Transform in = new TransitionEngine().incoming(t, 0.5f);
            TransitionEngine.Transform out = new TransitionEngine().outgoing(t, 0.5f);
            assertNotNull(in); assertNotNull(out);
        }
        // labels preserved
        assertEquals("Fade", TransitionEngine.label(TransitionType.FADE));
        assertEquals("Cross Dissolve", TransitionEngine.label(TransitionType.CROSS_DISSOLVE));
    }

    @Test public void directionalPresetMirrorsMotion() {
        TransitionPreset l = TransitionRegistry.byId("push_left");
        TransitionPreset r = TransitionRegistry.byId("push_right");
        float dxL = new TransitionEngine().incoming(l, 0.5f).dx;
        float dxR = new TransitionEngine().incoming(r, 0.5f).dx;
        assertTrue("left/right push should move opposite directions: " + dxL + "," + dxR,
                Math.signum(dxL) != Math.signum(dxR));
    }

    @Test public void selectDoesNotMutatePreset() {
        // presets are stateless metadata; running the engine never mutates them
        TransitionPreset p = TransitionRegistry.byId("3d_flip");
        String snap = p.id + p.type + p.defaultDuration;
        for (float m = 0f; m <= 1f; m += 0.1f) {
            new TransitionEngine().incoming(p, m);
            new TransitionEngine().outgoing(p, m);
        }
        assertEquals(snap, p.id + p.type + p.defaultDuration);
    }
}
