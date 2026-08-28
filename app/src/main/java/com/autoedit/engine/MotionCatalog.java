package com.autoedit.engine;

import com.autoedit.model.Easing;
import com.autoedit.model.Formula;
import com.autoedit.model.KeyframeState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data-driven catalog of single-clip MOTIONS.
 *
 * ONE motion = ONE start→end keyframe pair that a clip plays across its WHOLE
 * duration (spec §1). Nothing in here ever stacks two moves inside a clip —
 * that is what {@link FormulaCatalog} patterns are for, and they work by
 * handing a DIFFERENT motion to each clip.
 *
 * <h3>Ids are stable</h3>
 * Saved projects and custom formulas reference motions by id, so the original
 * ids ("00".."30") are frozen. New motions use "31"..onwards.
 *
 * <h3>Motion ranges (spec §8)</h3>
 * Cinematic motion is deliberately subtle: normal zoom 100→108%, slow zoom
 * 100→104%, zoom out 108→100%, premium push 102→110%. Every curve is a cubic
 * or sine ease-in-out by default, never linear.
 */
public final class MotionCatalog {
    public static final String CAT_BASIC = "Basic";
    public static final String CAT_CINEMATIC = "Cinematic";
    public static final String CAT_PREMIUM = "Premium";
    public static final String CAT_PAN = "Pan";
    public static final String CAT_PUSH = "Push";
    public static final String CAT_DRIFT = "Drift";
    public static final String CAT_KEN_BURNS = "Ken Burns";
    public static final String CAT_PARALLAX = "Parallax";
    public static final String CAT_ROTATE = "Rotate";

    private static final List<Formula> MOTIONS = new ArrayList<>();
    static { build(); }

    private MotionCatalog() {}

    private static void add(String id, String name, String category, String description,
                            float sx, float sy, float ss, float srot,
                            float ex, float ey, float es, float erot, Easing easing) {
        Formula f = new Formula(id, name, category,
                new KeyframeState(sx, sy, ss, srot, 1f),
                new KeyframeState(ex, ey, es, erot, 1f));
        f.category = category;
        f.easing = easing;
        f.description = description;
        f.direction = name;
        MOTIONS.add(f);
    }

    private static void add(String id, String name, String category, String description,
                            float sx, float sy, float ss, float ex, float ey, float es, Easing easing) {
        add(id, name, category, description, sx, sy, ss, 0f, ex, ey, es, 0f, easing);
    }

    private static void build() {
        // ================================================= ORIGINAL (ids frozen)
        add("00", "None", CAT_BASIC, "Hold the frame still.",
                0, 0, 1f, 0, 0, 1f, Easing.LINEAR);
        add("06", "Zoom In", CAT_BASIC, "Classic 4% to 16% push in.",
                0, 0, 1.04f, 0, 0, 1.16f, Easing.EASE_IN_OUT);
        add("07", "Zoom Out", CAT_BASIC, "Classic pull back.",
                0, 0, 1.16f, 0, 0, 1.04f, Easing.EASE_IN_OUT);
        add("02", "Pan Right to Left", CAT_BASIC, "Horizontal sweep across the frame.",
                .10f, 0, 1.18f, -.10f, 0, 1.18f, Easing.EASE_IN_OUT);
        add("04", "Pan Left to Right", CAT_BASIC, "Horizontal sweep, mirrored.",
                -.10f, 0, 1.18f, .10f, 0, 1.18f, Easing.EASE_IN_OUT);
        add("05", "Pan Bottom to Top", CAT_BASIC, "Vertical rise.",
                0, .10f, 1.20f, 0, -.10f, 1.20f, Easing.EASE_IN_OUT);
        add("01", "Pan Top to Bottom", CAT_BASIC, "Vertical fall.",
                0, -.10f, 1.20f, 0, .10f, 1.20f, Easing.EASE_IN_OUT);

        add("18", "Center Float", CAT_CINEMATIC, "Barely-there floating drift.",
                -.02f, -.01f, 1.06f, .02f, .01f, 1.10f, Easing.SINE);
        add("14", "Slow Push In", CAT_CINEMATIC, "Gentle narrative push.",
                0, 0, 1.02f, 0, 0, 1.12f, Easing.CUBIC);
        add("15", "Slow Pull Out", CAT_CINEMATIC, "Gentle narrative pull.",
                0, 0, 1.12f, 0, 0, 1.02f, Easing.CUBIC);
        add("21", "Cinematic Push Left", CAT_CINEMATIC, "Push in while drifting left.",
                .06f, 0, 1.10f, -.06f, 0, 1.16f, Easing.CUBIC);
        add("22", "Cinematic Push Right", CAT_CINEMATIC, "Push in while drifting right.",
                -.06f, 0, 1.16f, .06f, 0, 1.10f, Easing.CUBIC);
        add("23", "Cinematic Rise", CAT_CINEMATIC, "Rise while pushing in.",
                0, .06f, 1.12f, 0, -.06f, 1.18f, Easing.CUBIC);
        add("24", "Cinematic Descend", CAT_CINEMATIC, "Descend while pulling out.",
                0, -.06f, 1.18f, 0, .06f, 1.12f, Easing.CUBIC);
        add("10", "Diagonal TL to BR", CAT_CINEMATIC, "Corner-to-corner travel.",
                -.07f, -.07f, 1.10f, .07f, .07f, 1.16f, Easing.EASE_IN_OUT);
        add("13", "Diagonal BR to TL", CAT_CINEMATIC, "Corner-to-corner, reversed.",
                .07f, .07f, 1.16f, -.07f, -.07f, 1.10f, Easing.EASE_IN_OUT);
        add("17", "Ken Burns", CAT_CINEMATIC, "The timeless drift-and-zoom.",
                -.05f, .02f, 1.05f, .05f, -.02f, 1.14f, Easing.SINE);
        add("25", "Dynamic Zoom", CAT_CINEMATIC, "Faster, more energetic push.",
                0, 0, 1.03f, 0, 0, 1.20f, Easing.QUINT);

        add("19", "Focus Push In", CAT_PREMIUM, "Strong focus push with a soft landing.",
                0, 0, 1f, .02f, 0, 1.22f, Easing.EXPO);
        add("26", "Focus Pull Out", CAT_PREMIUM, "Strong focus pull.",
                .02f, 0, 1.22f, 0, 0, 1f, Easing.EXPO);
        add("27", "Floating Drift", CAT_PREMIUM, "Weightless sine drift.",
                -.03f, .02f, 1.08f, .03f, -.02f, 1.12f, Easing.SINE);
        add("28", "Slow Drift", CAT_PREMIUM, "Very slow opposing drift.",
                .04f, -.03f, 1.12f, -.04f, .03f, 1.08f, Easing.SINE);
        add("29", "Parallax Slide", CAT_PREMIUM, "Depth-style parallax travel.",
                .09f, 0, 1.22f, -.09f, .02f, 1.22f, Easing.CUBIC);
        add("30", "Documentary Push", CAT_PREMIUM, "Observational rise and push.",
                0, .02f, 1.04f, 0, -.02f, 1.14f, Easing.CUBIC);
        add("16", "Pan + Zoom", CAT_PREMIUM, "Combined pan and zoom.",
                -.08f, -.02f, 1.05f, .08f, .02f, 1.16f, Easing.EASE_IN_OUT);
        add("20", "Random Cinematic", CAT_PREMIUM, "Neutral settle.",
                0, 0, 1f, 0, 0, 1.10f, Easing.SINE);

        // ================================================= ZOOM (spec §8 ranges)
        add("31", "Normal Zoom In", CAT_BASIC, "100% → 108%, the default cinematic push.",
                0, 0, 1.00f, 0, 0, 1.08f, Easing.CUBIC_IN_OUT);
        add("32", "Normal Zoom Out", CAT_BASIC, "108% → 100%, the default reveal.",
                0, 0, 1.08f, 0, 0, 1.00f, Easing.CUBIC_IN_OUT);
        add("33", "Slow Zoom In", CAT_CINEMATIC, "100% → 104%. Barely noticeable, very classy.",
                0, 0, 1.00f, 0, 0, 1.04f, Easing.SINE_IN_OUT);
        add("34", "Slow Zoom Out", CAT_CINEMATIC, "104% → 100%. Calm and unhurried.",
                0, 0, 1.04f, 0, 0, 1.00f, Easing.SINE_IN_OUT);
        add("35", "Breathing Zoom", CAT_PREMIUM, "Slow organic in-breath, like a held shot.",
                0, 0, 1.00f, 0, 0, 1.05f, Easing.SINE_IN_OUT);

        // ================================================= PAN
        add("36", "Pan Left", CAT_PAN, "Steady leftward sweep.",
                .08f, 0, 1.18f, -.08f, 0, 1.18f, Easing.CUBIC_IN_OUT);
        add("37", "Pan Right", CAT_PAN, "Steady rightward sweep.",
                -.08f, 0, 1.18f, .08f, 0, 1.18f, Easing.CUBIC_IN_OUT);
        add("38", "Pan Up", CAT_PAN, "Steady upward sweep.",
                0, .08f, 1.18f, 0, -.08f, 1.18f, Easing.CUBIC_IN_OUT);
        add("39", "Pan Down", CAT_PAN, "Steady downward sweep.",
                0, -.08f, 1.18f, 0, .08f, 1.18f, Easing.CUBIC_IN_OUT);
        add("40", "Diagonal Up Left", CAT_PAN, "Travel toward the top-left corner.",
                .06f, .06f, 1.20f, -.06f, -.06f, 1.20f, Easing.CUBIC_IN_OUT);
        add("41", "Diagonal Up Right", CAT_PAN, "Travel toward the top-right corner.",
                -.06f, .06f, 1.20f, .06f, -.06f, 1.20f, Easing.CUBIC_IN_OUT);
        add("42", "Diagonal Down Left", CAT_PAN, "Travel toward the bottom-left corner.",
                .06f, -.06f, 1.20f, -.06f, .06f, 1.20f, Easing.CUBIC_IN_OUT);
        add("43", "Diagonal Down Right", CAT_PAN, "Travel toward the bottom-right corner.",
                -.06f, -.06f, 1.20f, .06f, .06f, 1.20f, Easing.CUBIC_IN_OUT);

        // ================================================= PUSH (102 → 110%)
        add("44", "Push Left", CAT_PUSH, "102% → 110% while sliding left.",
                .04f, 0, 1.02f, -.04f, 0, 1.10f, Easing.CUBIC_IN_OUT);
        add("45", "Push Right", CAT_PUSH, "102% → 110% while sliding right.",
                -.04f, 0, 1.02f, .04f, 0, 1.10f, Easing.CUBIC_IN_OUT);
        add("46", "Push Up", CAT_PUSH, "102% → 110% while rising.",
                0, .04f, 1.02f, 0, -.04f, 1.10f, Easing.CUBIC_IN_OUT);
        add("47", "Push Down", CAT_PUSH, "102% → 110% while descending.",
                0, -.04f, 1.02f, 0, .04f, 1.10f, Easing.CUBIC_IN_OUT);
        add("48", "Center Push", CAT_PUSH, "Straight-in push from the centre.",
                0, 0, 1.02f, 0, 0, 1.10f, Easing.CUBIC_IN_OUT);
        add("49", "Center Pull", CAT_PUSH, "Straight-out pull from the centre.",
                0, 0, 1.10f, 0, 0, 1.02f, Easing.CUBIC_IN_OUT);
        add("50", "Cinematic Push", CAT_PREMIUM, "Premium 102% → 110% with a soft landing.",
                0, 0, 1.02f, 0, 0, 1.10f, Easing.EXPO_OUT);
        add("51", "Cinematic Pull", CAT_PREMIUM, "Premium 110% → 102% reveal.",
                0, 0, 1.10f, 0, 0, 1.02f, Easing.EXPO_OUT);

        // ================================================= DRIFT
        add("52", "Drift Left", CAT_DRIFT, "Weightless leftward float.",
                .05f, 0, 1.14f, -.05f, 0, 1.16f, Easing.SINE_IN_OUT);
        add("53", "Drift Right", CAT_DRIFT, "Weightless rightward float.",
                -.05f, 0, 1.14f, .05f, 0, 1.16f, Easing.SINE_IN_OUT);
        add("54", "Drift Up", CAT_DRIFT, "Weightless upward float.",
                0, .05f, 1.14f, 0, -.05f, 1.16f, Easing.SINE_IN_OUT);
        add("55", "Drift Down", CAT_DRIFT, "Weightless downward float.",
                0, -.05f, 1.14f, 0, .05f, 1.16f, Easing.SINE_IN_OUT);
        add("56", "Float", CAT_DRIFT, "Tiny omnidirectional float, ideal for portraits.",
                -.02f, -.015f, 1.06f, .02f, .015f, 1.09f, Easing.SINE_IN_OUT);

        // ================================================= KEN BURNS
        add("57", "Ken Burns Left", CAT_KEN_BURNS, "Drift left while slowly zooming.",
                .05f, 0, 1.05f, -.05f, 0, 1.14f, Easing.SINE_IN_OUT);
        add("58", "Ken Burns Right", CAT_KEN_BURNS, "Drift right while slowly zooming.",
                -.05f, 0, 1.05f, .05f, 0, 1.14f, Easing.SINE_IN_OUT);
        add("59", "Ken Burns Up", CAT_KEN_BURNS, "Drift up while slowly zooming.",
                0, -.05f, 1.05f, 0, .05f, 1.14f, Easing.SINE_IN_OUT);
        add("60", "Ken Burns Down", CAT_KEN_BURNS, "Drift down while slowly zooming.",
                0, .05f, 1.05f, 0, -.05f, 1.14f, Easing.SINE_IN_OUT);

        // ================================================= PARALLAX
        add("61", "Parallax Left", CAT_PARALLAX, "Deep leftward parallax travel.",
                .09f, 0, 1.22f, -.09f, 0, 1.22f, Easing.CUBIC_IN_OUT);
        add("62", "Parallax Right", CAT_PARALLAX, "Deep rightward parallax travel.",
                -.09f, 0, 1.22f, .09f, 0, 1.22f, Easing.CUBIC_IN_OUT);
        add("63", "Parallax Up", CAT_PARALLAX, "Deep upward parallax travel.",
                0, .09f, 1.22f, 0, -.09f, 1.22f, Easing.CUBIC_IN_OUT);
        add("64", "Parallax Down", CAT_PARALLAX, "Deep downward parallax travel.",
                0, -.09f, 1.22f, 0, .09f, 1.22f, Easing.CUBIC_IN_OUT);

        // ================================================= ROTATE / TILT
        add("65", "Tilt Left", CAT_ROTATE, "Slow counter-clockwise tilt.",
                0, 0, 1.10f, 1.6f, 0, 0, 1.14f, -1.6f, Easing.SINE_IN_OUT);
        add("66", "Tilt Right", CAT_ROTATE, "Slow clockwise tilt.",
                0, 0, 1.10f, -1.6f, 0, 0, 1.14f, 1.6f, Easing.SINE_IN_OUT);
        add("67", "Subtle Rotation", CAT_ROTATE, "Almost imperceptible rotation drift.",
                0, 0, 1.10f, -0.8f, 0, 0, 1.12f, 0.8f, Easing.SINE_IN_OUT);

        // ================================================= CHARACTER
        add("68", "Dynamic Portrait", CAT_PREMIUM, "Push in with a slight lift — keeps faces centred.",
                0, .02f, 1.02f, 0, -.02f, 1.10f, Easing.CUBIC_IN_OUT);
    }

    /** Every motion, in catalog order. */
    public static List<Formula> all() { return Collections.unmodifiableList(MOTIONS); }

    public static Formula byId(String id) {
        if (id != null) for (Formula f : MOTIONS) if (f.id.equals(id)) return f;
        return byId("17");
    }

    public static boolean has(String id) {
        if (id == null) return false;
        for (Formula f : MOTIONS) if (f.id.equals(id)) return true;
        return false;
    }

    public static List<Formula> byCategory(String category) {
        List<Formula> out = new ArrayList<>();
        for (Formula f : MOTIONS) if (f.category.equals(category)) out.add(f);
        return out;
    }

    /** Category names in the order the UI should group them. */
    public static List<String> categories() {
        List<String> out = new ArrayList<>();
        for (Formula f : MOTIONS) if (!out.contains(f.category)) out.add(f.category);
        return out;
    }

    public static int indexOf(String id) {
        for (int i = 0; i < MOTIONS.size(); i++) if (MOTIONS.get(i).id.equals(id)) return i;
        return -1;
    }
}
