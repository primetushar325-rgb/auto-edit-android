package com.autoedit.model;

/** Canvas aspect ratios (spec §36). Names are persisted, so they are stable. */
public enum AspectRatio {
    R16_9(16, 9, "16:9 YouTube"),
    R9_16(9, 16, "9:16 Shorts/TikTok"),
    R1_1(1, 1, "1:1 Instagram"),
    R4_5(4, 5, "4:5 Instagram"),
    R4_3(4, 3, "4:3"),
    R3_4(3, 4, "3:4"),
    R3_2(3, 2, "3:2"),
    R2_3(2, 3, "2:3"),
    R21_9(21, 9, "21:9 Cinema"),
    R9_21(9, 21, "9:21 Ultra tall"),
    ORIGINAL(0, 0, "Original (source)");

    public final int w, h;
    public final String label;

    AspectRatio(int w, int h, String label) { this.w = w; this.h = h; this.label = label; }

    /** Fixed ratios only; ORIGINAL is resolved from the first clip at export. */
    public boolean isFixed() { return w > 0 && h > 0; }

    public float ratio() { return isFixed() ? w / (float) h : 0f; }
}
