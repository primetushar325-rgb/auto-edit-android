package com.autoedit.engine;

import com.autoedit.model.EditProject;
import com.autoedit.model.TimelineClip;

/**
 * THE timeline resolver. Preview and export both ask this class "which clip is
 * on screen at time T, and how far through it are we?" so the two can never
 * disagree (spec §16, §37, and audit findings C3/C4).
 *
 * <h3>Frame accounting</h3>
 * {@link #frameTime} and {@link #totalFrames} define the exact frame schedule
 * the encoder walks. Using one global schedule instead of
 * {@code Σ round(clipDuration * fps)} removes the off-by-a-few-frames drift
 * between the rendered count and {@code EditProject.totalFrames()}, and makes
 * the export duration equal {@code totalFrames / fps} exactly.
 */
public final class Timeline {

    private Timeline() {}

    /** One resolved point on the timeline. */
    public static final class Point {
        public int clipIndex = 0;
        public TimelineClip clip;
        /** Seconds elapsed inside this clip. */
        public float localSec = 0f;
        /** 0..1 across this clip's OWN duration. */
        public float progress = 0f;
        /** Seconds from the project start to this clip's first frame. */
        public float clipStartSec = 0f;
    }

    /**
     * Resolves an absolute project time to (clipIndex, progress).
     *
     * The last clip absorbs any rounding remainder so the final frame never
     * falls off the end of the timeline.
     */
    public static Point resolve(EditProject project, float timeSec) {
        Point r = new Point();
        if (project == null || project.clips.isEmpty()) return r;
        float t = Math.max(0f, timeSec);
        int last = project.clips.size() - 1;
        float acc = 0f;
        for (int i = 0; i <= last; i++) {
            TimelineClip c = project.clips.get(i);
            c.setDurationSeconds(c.durationSec);
            float dur = Math.max(0.001f, c.durationSec);
            if (t < acc + dur || i == last) {
                r.clipIndex = i;
                r.clip = c;
                r.clipStartSec = acc;
                r.localSec = Math.max(0f, Math.min(t - acc, dur));
                r.progress = r.localSec / dur;
                if (r.progress > 1f) r.progress = 1f;
                return r;
            }
            acc += dur;
        }
        return r;
    }

    /** Exact number of frames the encoder must write. */
    public static long totalFrames(EditProject project) {
        if (project == null) return 0L;
        int fps = Math.max(1, project.fps);
        return Math.max(1L, Math.round(project.totalDurationSec() * fps));
    }

    /**
     * Presentation time (seconds) of frame {@code frameIndex}. The last frame
     * is pulled back by half a frame so it lands inside the final clip rather
     * than exactly on the boundary.
     */
    public static float frameTime(EditProject project, long frameIndex) {
        if (project == null) return 0f;
        int fps = Math.max(1, project.fps);
        float total = project.totalDurationSec();
        float t = frameIndex / (float) fps;
        float max = total - 0.5f / fps;
        return t > max ? Math.max(0f, max) : t;
    }

    /**
     * Presentation timestamp in microseconds for the encoder. Uses the same
     * schedule as {@link #frameTime} so A/V timestamps stay aligned.
     */
    public static long framePtsUs(EditProject project, long frameIndex) {
        return Math.round(frameTime(project, frameIndex) * 1_000_000d);
    }
}
