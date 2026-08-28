package com.autoedit.engine;

import com.autoedit.model.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Catalog of FORMULA PATTERNS. A formula is a REPEATING per-clip motion
 * pattern: step k defines the ONE motion clip k plays (and clip k+size, ...).
 * No 4-step limit. Built purely from MotionCatalog motions.
 * Ids "F01".."F21" canonical; "S1".."S4" legacy compatibility.
 */
public final class FormulaCatalog {
    private static final List<Formula> PATTERNS = new ArrayList<>();
    static { build(); }
    private FormulaCatalog() {}

    private static void pattern(String id, String name, String category,
                                String description, String... motionIds) {
        Formula f = new Formula(id, name, category,
                new KeyframeState(0, 0, 1f, 0, 1), new KeyframeState(0, 0, 1f, 0, 1));
        f.category = category; f.description = description;
        f.steps = new ArrayList<>();
        for (String mid : motionIds) f.steps.add(new FormulaStep(MotionCatalog.byId(mid)));
        PATTERNS.add(f);
    }

    private static void patternWithTransitions(String id, String name, String category,
                                               String description, TransitionType transition,
                                               String... motionIds) {
        pattern(id, name, category, description, motionIds);
        Formula f = PATTERNS.get(PATTERNS.size() - 1);
        for (FormulaStep s : f.steps) s.transition = transition;
    }

    private static void build() {
        pattern("F01", "Cinematic Travel", "Travel", "Alternating push and horizontal pans for a travel montage.", "06", "21", "07", "22", "14", "24");
        pattern("F02", "Story Flow", "Story", "Slow push, pan right, pull out - classic narrative rhythm.", "14", "04", "15", "27");
        pattern("F03", "Smooth Documentary", "Documentary", "Gentle vertical moves and zooms for interviews and B-roll.", "05", "01", "06", "07", "30");
        pattern("F04", "Dynamic Portrait", "Portrait", "Zoom and alternating pans that keep faces centered.", "06", "02", "07", "04");
        pattern("F05", "Ken Burns Classic", "Cinematic", "The timeless slow drift-and-zoom on every clip.", "17", "18", "27", "28");
        pattern("F06", "Punchy Social", "Social", "Fast pushes and pulls for short-form energy.", "25", "15", "19", "26", "07");
        pattern("F07", "Calm Vlog", "Vlog", "Soft floating moves, never distracting.", "18", "27", "14", "28");
        pattern("F08", "Epic Landscape", "Travel", "Wide cinematic moves across scenery.", "22", "14", "23", "24", "21");
        pattern("F09", "Rhythm Montage", "Music", "Zoom in/out locked to a repeating beat.", "06", "07", "06", "07", "25", "15");
        pattern("F10", "Gentle Slideshow", "Basic", "Barely-there motion for photo slideshows.", "00", "14", "00", "15");
        pattern("F11", "Focus Story", "Story", "Focus pushes and pulls to guide the eye.", "19", "26", "19", "15");
        pattern("F12", "Parallax Reel", "Premium", "Depth-style parallax slides between clips.", "29", "21", "29", "22");
        pattern("F13", "Vertical Rise", "Portrait", "Upward cinematic rises for 9:16 reels.", "23", "05", "23", "27");
        pattern("F14", "Soft Dream", "Premium", "Floating drift with a dreamy feel.", "27", "28", "18", "30");
        pattern("F15", "Bold Push", "Social", "Strong push-ins for impact moments.", "19", "14", "25", "07");
        pattern("F16", "Panorama Sweep", "Travel", "Long horizontal sweeps across wide shots.", "04", "02", "22", "21");
        pattern("F17", "Wedding Memory", "Documentary", "Tender slow push and float for memories.", "14", "27", "15", "18");
        pattern("F18", "Product Spotlight", "Premium", "Subtle zoom focus for product clips.", "19", "06", "26", "00");
        pattern("F19", "Action Energy", "Social", "Dynamic zooms and quick pulls for action.", "25", "29", "19", "26");
        pattern("F20", "Timeless Retro", "Cinematic", "Slow Ken Burns drift, easy and nostalgic.", "17", "30", "28", "15");
        patternWithTransitions("F21", "Faded Reel", "Premium", "Every clip cross-fades into the next while gently pushing.", TransitionType.CROSS_DISSOLVE, "14", "15", "14", "18");

        pattern("S1", "Cinematic Travel (legacy)", "Travel", "Legacy pattern.", "06", "07", "02", "04");
        pattern("S2", "Story Flow (legacy)", "Story", "Legacy pattern.", "14", "04", "15");
        pattern("S3", "Dynamic Portrait (legacy)", "Portrait", "Legacy pattern.", "06", "02", "07", "04");
        pattern("S4", "Smooth Documentary (legacy)", "Documentary", "Legacy pattern.", "05", "01", "06", "07");
    }

    public static List<Formula> all() { return Collections.unmodifiableList(PATTERNS); }
    public static Formula byId(String id) {
        if (id != null) for (Formula f : PATTERNS) if (f.id.equals(id)) return f;
        return null;
    }
    public static List<Formula> byCategory(String category) {
        List<Formula> out = new ArrayList<>();
        for (Formula f : PATTERNS) if (f.category.equals(category)) out.add(f);
        return out;
    }
}
