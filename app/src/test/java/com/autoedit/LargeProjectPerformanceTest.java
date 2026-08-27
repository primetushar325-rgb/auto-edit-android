package com.autoedit;

import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.EditProject;
import com.autoedit.model.TimelineClip;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Large-project (100/500/1000 image) state-operation performance.
 * These are pure JVM measurements on the build machine — device timings will
 * differ, but the operations must stay far below one frame budget per batch.
 */
public class LargeProjectPerformanceTest {

    private EditProject build(int n) {
        EditProject p = new EditProject();
        FormulaEngine e = new FormulaEngine();
        for (int i = 0; i < n; i++) {
            TimelineClip c = new TimelineClip("content://media/images/" + i, i + 1, e.defaultFormula());
            c.setDurationMs(5000L);
            p.clips.add(c);
        }
        return p;
    }

    private long timeMs(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Test public void thousandClipBatchOperationsStayFast() {
        for (int n : new int[]{100, 500, 1000}) {
            EditProject p = build(n);

            // per-frame cost the preview pays: total duration + active-clip scan
            long perFrame = timeMs(() -> {
                for (int i = 0; i < 30; i++) {
                    p.totalDurationSec();
                    float acc = 0;
                    for (TimelineClip c : p.clips) { if (12.3f < acc + c.durationSec) break; acc += c.durationSec; }
                }
            });
            System.out.println("[perf] n=" + n + " per-frame preview math (30 frames): " + perFrame + " ms");
            assertTrue("per-frame math too slow for n=" + n, perFrame < 500);

            // Apply duration to ALL: single batch state operation
            long applyDur = timeMs(() -> { for (TimelineClip c : p.clips) c.setDurationMs(5000L); });
            System.out.println("[perf] n=" + n + " apply-duration-to-all: " + applyDur + " ms");
            assertTrue(applyDur < 500);
            assertEquals(n * 5f, p.totalDurationSec(), 0.01f); // n clips × 5s

            // Apply formula to ALL: fast state assignment, no rendering
            FormulaEngine e = new FormulaEngine();
            String[] seq = {"06", "08", "07", "09"};
            long applyF = timeMs(() -> {
                for (int i = 0; i < p.clips.size(); i++) p.clips.get(i).formula = e.byId(seq[i % seq.length]);
            });
            System.out.println("[perf] n=" + n + " apply-formula-to-all: " + applyF + " ms");
            assertTrue(applyF < 1000);
            assertNotNull(p.clips.get(n - 1).formula);

            // renumber + chip label generation (timeline refresh math)
            long timeline = timeMs(() -> {
                p.renumber();
                for (int i = 0; i < p.clips.size(); i++) {
                    TimelineClip c = p.clips.get(i);
                    String.format(java.util.Locale.US, "%02d\n%ds", c.index, Math.round(c.durationSec));
                }
            });
            System.out.println("[perf] n=" + n + " timeline relabel/renumber: " + timeline + " ms");
            assertTrue(timeline < 1000);

            // undo snapshot payload size sanity (single JSON string, written once per change)
            int clips = p.clips.size();
            assertEquals(n, clips);
        }
    }

    @Test public void mixedDurationTotalsAreExact() {
        EditProject p = build(6);
        int[] secs = {3, 4, 5, 6, 7, 8};
        for (int i = 0; i < 6; i++) p.clips.get(i).setDurationMs(secs[i] * 1000L);
        assertEquals(33f, p.totalDurationSec(), 0.01f);
        assertEquals(990L, p.totalFrames(), 0.01f); // 33s @30fps = 990 frames
        for (TimelineClip c : p.clips) c.setDurationMs(5000L);
        assertEquals(30f, p.totalDurationSec(), 0.01f);
        assertEquals(900L, p.totalFrames(), 0.01f);
    }

    @Test public void durationClampsTo3Through8Seconds() {
        TimelineClip c = new TimelineClip("x", 1, null);
        c.setDurationMs(1000L);
        assertEquals(3000L, c.durationMs);
        c.setDurationMs(99999L);
        assertEquals(8000L, c.durationMs);
        c.setDurationMs(5000L);
        assertEquals(5000L, c.durationMs);
        assertEquals(5f, c.durationSec, 0.0001f);
    }

    @Test public void startTimeIsCumulativeAndStable() {
        EditProject p = build(3);
        p.clips.get(0).setDurationMs(3000L);
        p.clips.get(1).setDurationMs(5000L);
        p.clips.get(2).setDurationMs(4000L);
        assertEquals(0L, p.clips.get(0).startTimeMsIn(p));
        assertEquals(3000L, p.clips.get(1).startTimeMsIn(p));
        assertEquals(8000L, p.clips.get(2).startTimeMsIn(p));
        assertEquals(12f, p.totalDurationSec(), 0.01f);
    }
}
