package com.autoedit;

import static org.junit.Assert.*;

import com.autoedit.engine.MotionCatalog;
import com.autoedit.model.EffectType;
import com.autoedit.model.TransitionType;
import com.autoedit.ui.PreviewArt;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Spec §13: card thumbnails must be varied and must not be the same image for
 * every option. The scene picker is pure Java, so the distribution is testable
 * without a device.
 */
public class PreviewArtTest {

    @Test public void sceneChoiceIsStableForTheSameId() {
        for (String id : new String[]{"14", "F01", "PUSH_LEFT", "NONE", "VIGNETTE"}) {
            assertEquals(id + " must always map to the same scene",
                    PreviewArt.Kind.forId(id), PreviewArt.Kind.forId(id));
        }
    }

    /** Every motion card must get a scene, and they must not all be identical. */
    @Test public void motionCardsDoNotAllShowTheSameScene() {
        java.util.List<com.autoedit.model.Formula> all = MotionCatalog.all();
        Set<PreviewArt.Kind> seen = new HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            seen.add(PreviewArt.Kind.forId("F" + all.get(i).id));
        }
        assertTrue("motion cards should span several scenes, got " + seen, seen.size() >= 4);
    }

    /** Effects get scenes from a semantic mapping too — verify they vary. */
    @Test public void effectAndTransitionCardsVary() {
        Set<PreviewArt.Kind> eff = new HashSet<>();
        for (EffectType t : EffectType.values()) eff.add(PreviewArt.Kind.forId(t.name()));
        assertTrue("effect cards should span several scenes, got " + eff, eff.size() >= 4);

        Set<PreviewArt.Kind> tr = new HashSet<>();
        for (TransitionType t : TransitionType.values()) tr.add(PreviewArt.Kind.forId("T" + t.name()));
        assertTrue("transition cards should span several scenes, got " + tr, tr.size() >= 4);
    }

    /**
     * The two halves of a transition preview must be DIFFERENT scenes, or the
     * user cannot tell which clip is leaving and which is entering.
     */
    @Test public void transitionHalvesUseDifferentScenes() {
        int same = 0, total = 0;
        for (TransitionType t : TransitionType.values()) {
            if (t == TransitionType.NONE) continue;
            total++;
            if (PreviewArt.Kind.forId("T" + t.name()) == PreviewArt.Kind.forId("T2" + t.name())) same++;
        }
        assertTrue(same + "/" + total + " transitions used the same scene for both halves",
                same < total / 4);
    }
}
