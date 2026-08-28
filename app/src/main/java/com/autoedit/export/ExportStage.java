package com.autoedit.export;

import com.autoedit.model.EditProject;

/**
 * The real stages of an export (spec §18).
 *
 * Each stage owns a contiguous slice of the 0..100 progress range. Progress is
 * always derived from actual completed work inside the active stage, so the
 * number can never move backwards and can never stall on a fake value — the
 * stage boundaries are the only places it jumps, and every jump is forward.
 */
public enum ExportStage {
    PREPARING("Preparing", 0, 6),
    OPTIMIZING("Optimizing images", 6, 16),
    AUDIO("Preparing audio", 16, 26),
    RENDERING("Rendering", 26, 78),
    ENCODING("Encoding", 78, 90),
    FINALIZING("Finalizing", 90, 95),
    /** Reading the finished file back to prove it is a real, playable MP4. */
    VERIFYING("Verifying", 95, 98),
    SAVING("Saving to Gallery", 98, 100),
    COMPLETE("Complete", 100, 100);

    public final String label;
    public final int from;
    public final int to;

    ExportStage(String label, int from, int to) { this.label = label; this.from = from; this.to = to; }

    /**
     * Maps a 0..1 fraction of real work inside this stage onto the global
     * 0..100 range. {@code frac} is clamped, so a stage can never report past
     * its own ceiling.
     */
    public int percent(float frac) {
        float f = frac < 0f ? 0f : (frac > 1f ? 1f : frac);
        return Math.round(from + (to - from) * f);
    }
}
