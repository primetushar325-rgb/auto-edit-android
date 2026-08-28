package com.autoedit.model;

/**
 * How a source image is placed on the canvas (spec §36).
 *
 * FILL and CROP both cover the canvas; they differ in how much overscan they
 * keep (CROP frames the subject tighter and leans on the background layer for
 * any residual edge). FIT / BLUR_BG / SOLID_BG contain the whole image; the
 * difference is what fills the letterbox bars — and none of them ever shows a
 * black wedge, because the renderers always paint a background layer first.
 *
 * FILL and FIT are persisted by name in saved projects, so their names are
 * stable.
 */
public enum FitMode {
    FILL("Fill (crop)", true),
    CROP("Crop to fill", true),
    FIT("Fit (letterbox)", false),
    BLUR_BG("Blur background", false),
    SOLID_BG("Solid background", false);

    public final String label;
    /** True when the foreground is scaled to cover the whole canvas. */
    public final boolean covers;

    FitMode(String label, boolean covers) { this.label = label; this.covers = covers; }

    /** Contain modes draw the image behind the foreground to fill the bars. */
    public boolean needsBackgroundLayer() { return this == BLUR_BG || this == SOLID_BG; }

    public static FitMode orDefault(FitMode m) { return m == null ? FILL : m; }
}
