package com.autoedit.engine;

import com.autoedit.model.Easing;
import com.autoedit.model.Formula;
import com.autoedit.model.KeyframeState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data-driven catalog of single-clip MOTIONS (one start->end keyframe move
 * each). 24 motions across Basic/Cinematic/Premium. Ids kept stable.
 */
public final class MotionCatalog {
    public static final String CAT_BASIC = "Basic";
    public static final String CAT_CINEMATIC = "Cinematic";
    public static final String CAT_PREMIUM = "Premium";
    private static final List<Formula> MOTIONS = new ArrayList<>();
    static { build(); }
    private MotionCatalog() {}

    private static void add(String id, String name, String category,
                            float sx, float sy, float ss, float ex, float ey, float es, Easing easing) {
        Formula f = new Formula(id, name, category,
                new KeyframeState(sx, sy, ss, 0f, 1f), new KeyframeState(ex, ey, es, 0f, 1f));
        f.category = category; f.easing = easing;
        MOTIONS.add(f);
    }

    private static void build() {
        add("00", "None",           CAT_BASIC,      0, 0, 1f,    0, 0, 1f,    Easing.LINEAR);
        add("06", "Zoom In",        CAT_BASIC,      0, 0, 1.04f, 0, 0, 1.16f, Easing.EASE_IN_OUT);
        add("07", "Zoom Out",       CAT_BASIC,      0, 0, 1.16f, 0, 0, 1.04f, Easing.EASE_IN_OUT);
        add("02", "Pan Right to Left", CAT_BASIC,   .10f, 0, 1.18f, -.10f, 0, 1.18f, Easing.EASE_IN_OUT);
        add("04", "Pan Left to Right", CAT_BASIC,  -.10f, 0, 1.18f, .10f, 0, 1.18f, Easing.EASE_IN_OUT);
        add("05", "Pan Bottom to Top", CAT_BASIC,    0, .10f, 1.20f, 0, -.10f, 1.20f, Easing.EASE_IN_OUT);
        add("01", "Pan Top to Bottom", CAT_BASIC,   0, -.10f, 1.20f, 0, .10f, 1.20f, Easing.EASE_IN_OUT);

        add("18", "Center Float",   CAT_CINEMATIC, -.02f, -.01f, 1.06f, .02f, .01f, 1.10f, Easing.SINE);
        add("14", "Slow Push In",   CAT_CINEMATIC,  0, 0, 1.02f, 0, 0, 1.12f, Easing.CUBIC);
        add("15", "Slow Pull Out",  CAT_CINEMATIC,  0, 0, 1.12f, 0, 0, 1.02f, Easing.CUBIC);
        add("21", "Cinematic Push Left",  CAT_CINEMATIC,  .06f, 0, 1.10f, -.06f, 0, 1.16f, Easing.CUBIC);
        add("22", "Cinematic Push Right", CAT_CINEMATIC, -.06f, 0, 1.16f,  .06f, 0, 1.10f, Easing.CUBIC);
        add("23", "Cinematic Rise",       CAT_CINEMATIC,  0, .06f, 1.12f, 0, -.06f, 1.18f, Easing.CUBIC);
        add("24", "Cinematic Descend",    CAT_CINEMATIC,  0, -.06f, 1.18f, 0, .06f, 1.12f, Easing.CUBIC);
        add("10", "Diagonal TL to BR", CAT_CINEMATIC, -.07f, -.07f, 1.10f, .07f, .07f, 1.16f, Easing.EASE_IN_OUT);
        add("13", "Diagonal BR to TL", CAT_CINEMATIC,  .07f, .07f, 1.16f, -.07f, -.07f, 1.10f, Easing.EASE_IN_OUT);
        add("17", "Ken Burns",        CAT_CINEMATIC, -.05f, .02f, 1.05f, .05f, -.02f, 1.14f, Easing.SINE);
        add("25", "Dynamic Zoom",     CAT_CINEMATIC,  0, 0, 1.03f, 0, 0, 1.20f, Easing.QUINT);

        add("19", "Focus Push In",   CAT_PREMIUM,    0, 0, 1f,    .02f, 0, 1.22f, Easing.EXPO);
        add("26", "Focus Pull Out",  CAT_PREMIUM,   .02f, 0, 1.22f, 0, 0, 1f,    Easing.EXPO);
        add("27", "Floating Drift",  CAT_PREMIUM,  -.03f, .02f, 1.08f, .03f, -.02f, 1.12f, Easing.SINE);
        add("28", "Slow Drift",      CAT_PREMIUM,   .04f, -.03f, 1.12f, -.04f, .03f, 1.08f, Easing.SINE);
        add("29", "Parallax Slide",  CAT_PREMIUM,   .09f, 0, 1.22f, -.09f, .02f, 1.22f, Easing.CUBIC);
        add("30", "Documentary Push",CAT_PREMIUM,    0, .02f, 1.04f, 0, -.02f, 1.14f, Easing.CUBIC);
        add("16", "Pan + Zoom",      CAT_PREMIUM,  -.08f, -.02f, 1.05f, .08f, .02f, 1.16f, Easing.EASE_IN_OUT);
        add("20", "Random Cinematic",CAT_PREMIUM,    0, 0, 1f, 0, 0, 1.10f, Easing.SINE);
    }

    public static List<Formula> all() { return Collections.unmodifiableList(MOTIONS); }
    public static Formula byId(String id) {
        if (id != null) for (Formula f : MOTIONS) if (f.id.equals(id)) return f;
        return byId("17");
    }
    public static List<Formula> byCategory(String category) {
        List<Formula> out = new ArrayList<>();
        for (Formula f : MOTIONS) if (f.category.equals(category)) out.add(f);
        return out;
    }
    public static int indexOf(String id) {
        for (int i = 0; i < MOTIONS.size(); i++) if (MOTIONS.get(i).id.equals(id)) return i;
        return -1;
    }
}
