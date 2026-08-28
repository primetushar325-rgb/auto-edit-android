package com.autoedit;

import com.autoedit.engine.Timeline;
import com.autoedit.export.ExportStage;
import com.autoedit.export.StorageGuard;
import com.autoedit.export.VideoExporter;
import com.autoedit.model.*;
import com.autoedit.update.SemVer;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The export/timeline/audio/version contracts that can be proved without a
 * device: frame schedule (spec §17, audit C3/C4), progress monotonicity
 * (spec §18), audio gain envelope (spec §20), storage guard (spec §49) and
 * version comparison (spec §32).
 */
public class ExportPipelineTest {

    private EditProject project(int clips, int secondsEach, int fps) {
        EditProject p = new EditProject();
        p.fps = fps;
        for (int i = 0; i < clips; i++) {
            TimelineClip c = new TimelineClip("content://img/" + i, i + 1, null);
            c.setDurationMs(secondsEach * 1000L);
            p.clips.add(c);
        }
        return p;
    }

    // ------------------------------------------------- frame schedule (C3/C4)

    @Test public void frameScheduleMatchesTotalFramesExactly() {
        // This is audit finding C4: the old exporter wrote Σ round(dur*fps)
        // frames while the project claimed round(total*fps). They must agree.
        int[] clipCounts = {1, 2, 3, 10, 50, 100};
        int[] durs = {3, 5, 7, 8};
        for (int fps : new int[]{24, 30, 60}) {
            for (int n : clipCounts) {
                for (int d : durs) {
                    EditProject p = project(n, d, fps);
                    long total = Timeline.totalFrames(p);
                    assertEquals("clip=" + n + " dur=" + d + " fps=" + fps,
                            p.totalFrames(), total);
                    // every frame lands on a real clip and inside its duration
                    for (long i = 0; i < total; i++) {
                        float t = Timeline.frameTime(p, i);
                        assertTrue("frame " + i + " at t=" + t, t >= 0f);
                        assertTrue("frame " + i + " past the end: t=" + t,
                                t <= p.totalDurationSec() + 1e-3f);
                        Timeline.Point pt = Timeline.resolve(p, t);
                        assertNotNull(pt.clip);
                        assertTrue("progress out of range", pt.progress >= 0f && pt.progress <= 1f);
                    }
                }
            }
        }
    }

    @Test public void frameTimesAreMonotonicAndEvenlySpaced() {
        EditProject p = project(7, 5, 30);
        long total = Timeline.totalFrames(p);
        float prev = -1f;
        for (long i = 0; i < total; i++) {
            float t = Timeline.frameTime(p, i);
            assertTrue("frame times must increase", t > prev);
            prev = t;
        }
        // no boundary frame is rendered twice: consecutive times differ by ~1/fps
        for (long i = 1; i < total - 1; i++) {
            float dt = Timeline.frameTime(p, i) - Timeline.frameTime(p, i - 1);
            assertEquals(1f / 30f, dt, 1e-4f);
        }
    }

    @Test public void presentationTimestampsAreMonotonic() {
        EditProject p = project(5, 4, 30);
        long total = Timeline.totalFrames(p);
        long prev = -1;
        for (long i = 0; i < total; i++) {
            long pts = Timeline.framePtsUs(p, i);
            assertTrue("pts must increase", pts > prev);
            prev = pts;
        }
    }

    /** The clip boundary must resolve to the incoming clip, not both. */
    @Test public void clipBoundariesResolveToExactlyOneClip() {
        EditProject p = project(3, 5, 30);
        assertEquals(0, Timeline.resolve(p, 0f).clipIndex);
        assertEquals(0, Timeline.resolve(p, 4.99f).clipIndex);
        assertEquals(1, Timeline.resolve(p, 5.0f).clipIndex);
        assertEquals(2, Timeline.resolve(p, 10.0f).clipIndex);
        // past the end still resolves (to the last clip) instead of falling off
        assertEquals(2, Timeline.resolve(p, 999f).clipIndex);
        assertEquals(1f, Timeline.resolve(p, 999f).progress, 1e-6f);
    }

    // ---------------------------------------------------- progress (spec §18)

    @Test public void stagesAreContiguousAndCoverTheWholeRange() {
        ExportStage[] all = ExportStage.values();
        assertEquals(0, all[0].from);
        for (int i = 1; i < all.length - 1; i++)
            assertEquals("gap between " + all[i - 1] + " and " + all[i],
                    all[i - 1].to, all[i].from);
        assertEquals(100, all[all.length - 1].to);
    }

    @Test public void progressNeverGoesBackwardsWithinOrAcrossStages() {
        int prev = -1;
        for (ExportStage s : ExportStage.values()) {
            for (int i = 0; i <= 100; i++) {
                int pct = s.percent(i / 100f);
                assertTrue(s + " at " + i + "% went backwards: " + pct + " < " + prev, pct >= prev);
                assertTrue(s + " exceeded its ceiling", pct <= s.to);
                assertTrue(s + " below its floor", pct >= s.from);
                prev = pct;
            }
        }
        assertEquals(100, prev);
    }

    @Test public void progressFractionIsClamped() {
        assertEquals(ExportStage.RENDERING.from, ExportStage.RENDERING.percent(-5f));
        assertEquals(ExportStage.RENDERING.to, ExportStage.RENDERING.percent(99f));
        assertEquals(100, ExportStage.COMPLETE.percent(0f));
    }

    // ------------------------------------------------------- audio (spec §20)

    @Test public void audioGainEnvelopeIsCorrect() {
        AudioTrack t = new AudioTrack("content://audio");
        t.volume = 0.8f;
        t.fadeInSec = 2f;
        t.fadeOutSec = 2f;
        float used = 10f;
        assertEquals(0f, t.gainAt(0f, used), 1e-6f);
        assertEquals(0.4f, t.gainAt(1f, used), 1e-4f);   // half way through the fade-in
        assertEquals(0.8f, t.gainAt(2f, used), 1e-6f);   // full level
        assertEquals(0.8f, t.gainAt(5f, used), 1e-6f);
        assertEquals(0.4f, t.gainAt(9f, used), 1e-4f);   // half way through the fade-out
        assertEquals(0f, t.gainAt(10f, used), 1e-6f);
    }

    @Test public void mutedOrMissingAudioContributesNothing() {
        AudioTrack muted = new AudioTrack("content://audio");
        muted.muted = true;
        assertTrue(muted.isSilent());
        assertEquals(0f, muted.gainAt(1f, 10f), 1e-6f);

        AudioTrack zero = new AudioTrack("content://audio");
        zero.volume = 0f;
        assertTrue(zero.isSilent());

        AudioTrack noUri = new AudioTrack(null);
        assertTrue(noUri.isSilent());
    }

    @Test public void fadesCanNeverOverlapIntoNegativeGain() {
        AudioTrack t = new AudioTrack("content://audio");
        t.fadeInSec = 100f;   // longer than the whole track
        t.fadeOutSec = 100f;
        for (float x = 0f; x <= 4f; x += 0.25f) {
            float g = t.gainAt(x, 4f);
            assertTrue("negative gain at " + x, g >= 0f);
            assertTrue("gain above 1 at " + x, g <= 1f);
        }
    }

    @Test public void projectReportsAudioOnlyWhenATrackCanPlay() {
        EditProject p = project(2, 5, 30);
        assertFalse(p.hasAudio());
        p.audioTracks.add(new AudioTrack("content://audio"));
        assertTrue(p.hasAudio());
        p.audioTracks.get(0).muted = true;
        assertFalse("a muted track must not be treated as audio", p.hasAudio());
    }

    /** Legacy single-audio projects must migrate without losing the track. */
    @Test public void legacyAudioUriMigratesIntoTracks() {
        EditProject p = project(1, 5, 30);
        p.audioUri = "content://legacy/audio";
        p.migrateLegacyAudio();
        assertEquals(1, p.audioTracks.size());
        assertEquals("content://legacy/audio", p.audioTracks.get(0).uri);
        assertNull(p.audioUri);
        // migrating twice must not duplicate it
        p.migrateLegacyAudio();
        assertEquals(1, p.audioTracks.size());
    }

    // ------------------------------------------------------ storage (spec §49)

    @Test public void storageEstimateIsPlausible() {
        long est = StorageGuard.estimateBytes(30f, 8_000_000);
        // 30 s at 8 Mbps is 30 MB; the guard adds 20% headroom.
        assertTrue("estimate too small: " + est, est > 25_000_000);
        assertTrue("estimate implausibly large: " + est, est < 60_000_000);
    }

    @Test public void validationRejectsEmptyAndBrokenProjects() {
        assertNotNull(VideoExporter.validate(null));
        assertNotNull(VideoExporter.validate(new EditProject()));
        EditProject p = project(2, 5, 30);
        assertNull(VideoExporter.validate(p));
        p.clips.get(1).uri = null;
        assertNotNull("a clip with no source must be rejected", VideoExporter.validate(p));
    }

    // ------------------------------------------------------ versions (spec §32)

    @Test public void semanticVersionComparesNumericallyNotAsAString() {
        // The exact case the spec calls out: 1.10.0 > 1.9.0.
        assertTrue(SemVer.isNewer("1.10.0", "1.9.0"));
        assertFalse(SemVer.isNewer("1.9.0", "1.10.0"));
        assertTrue("string compare would get this wrong",
                "1.10.0".compareTo("1.9.0") < 0);
        assertTrue(SemVer.isNewer("1.2.10", "1.2.9"));
        assertTrue(SemVer.isNewer("2.0.0", "1.99.99"));
        assertFalse(SemVer.isNewer("1.0.0", "1.0.0"));
        assertTrue(SemVer.isNewer("1.0.1", "1.0.0"));
    }

    @Test public void versionParsingToleratesMessyInput() {
        assertEquals("1.2.3", SemVer.parse("v1.2.3").toString());
        assertEquals("1.2.0", SemVer.parse("1.2").toString());
        assertEquals("1.0.0", SemVer.parse("1").toString());
        assertEquals("0.0.0", SemVer.parse(null).toString());
        assertEquals("0.0.0", SemVer.parse("not a version").toString());
        assertTrue(SemVer.isNewer("1.2.0", "1.2.0-beta1")); // a release beats its pre-release
    }

    // ------------------------------------------------------- easing (spec §11)

    @Test public void everyEasingIsPinnedAtBothEnds() {
        for (Easing e : Easing.values()) {
            assertEquals(e + " must start at 0", 0f, e.apply(0f), 1e-6f);
            assertEquals(e + " must end at 1", 1f, e.apply(1f), 1e-6f);
            assertEquals(e + " must clamp below 0", 0f, e.apply(-1f), 1e-6f);
            assertEquals(e + " must clamp above 1", 1f, e.apply(2f), 1e-6f);
        }
    }

    @Test public void monotonicEasingsNeverRunBackwards() {
        for (Easing e : Easing.values()) {
            if (e.overshoots()) continue;
            float prev = 0f;
            for (int i = 0; i <= 1000; i++) {
                float v = e.apply(i / 1000f);
                assertTrue(e + " ran backwards at " + i, v >= prev - 1e-6f);
                prev = v;
            }
        }
    }

    @Test public void backEasingsOvershootWithinTheirDeclaredBudget() {
        for (Easing e : new Easing[]{Easing.BACK_IN, Easing.BACK_OUT, Easing.BACK_IN_OUT}) {
            assertTrue(e + " must declare itself as overshooting", e.overshoots());
            float budget = e.overshootAmount();
            for (int i = 0; i <= 1000; i++) {
                float v = e.apply(i / 1000f);
                assertTrue(e + " exceeded its overshoot budget at " + i + ": " + v,
                        v >= -budget - 1e-3f && v <= 1f + budget + 1e-3f);
            }
        }
    }

    @Test public void theSpecEasingFamiliesAllExist() {
        // Spec §11 minimum list.
        for (String n : new String[]{"LINEAR", "EASE_IN", "EASE_OUT", "EASE_IN_OUT",
                "CUBIC_IN", "CUBIC_OUT", "CUBIC_IN_OUT", "QUART_IN_OUT", "QUINT_IN_OUT",
                "SINE_IN_OUT", "BACK_IN_OUT", "EXPO_IN_OUT"}) {
            Easing.valueOf(n); // throws if missing
        }
        assertSame("default must be cubic ease in out (spec §11)", Easing.CUBIC_IN_OUT, Easing.DEFAULT);
    }

    /** The persisted easing names must keep the curves old projects expect. */
    @Test public void legacyEasingNamesKeepTheirOriginalCurves() {
        assertEquals(0.75f, Easing.EASE_OUT.apply(0.5f), 1e-6f);   // quadratic
        assertEquals(0.25f, Easing.EASE_IN.apply(0.5f), 1e-6f);
        assertEquals(0.5f, Easing.EASE_IN_OUT.apply(0.5f), 1e-6f);
        assertEquals(0.5f, Easing.CUBIC.apply(0.5f), 1e-6f);
        assertEquals(0.5f, Easing.LINEAR.apply(0.5f), 1e-6f);
    }

    // ------------------------------------------------------- fit modes (§36)

    @Test public void everyAspectRatioAndFitModeHasALabel() {
        for (AspectRatio a : AspectRatio.values()) {
            assertNotNull(a.label);
            assertFalse(a.label.isEmpty());
        }
        for (FitMode f : FitMode.values()) {
            assertNotNull(f.label);
            assertFalse(f.label.isEmpty());
        }
        // Spec §36 list.
        for (String n : new String[]{"FILL", "FIT", "CROP", "BLUR_BG", "SOLID_BG"}) FitMode.valueOf(n);
        for (String n : new String[]{"R16_9", "R9_16", "R1_1", "R4_5", "R4_3", "R3_4",
                "R3_2", "R2_3", "R21_9", "R9_21", "ORIGINAL"}) AspectRatio.valueOf(n);
    }

    /**
     * Regression guard for the "stuck on Finalizing" bug.
     *
     * The old code had no state between FINALIZING and the 100% broadcast, so
     * the whole close-writer / read-back / publish window was invisible and a
     * failure inside it looked like a freeze. VERIFYING must exist, must sit
     * between FINALIZING and SAVING, and the chain must still be contiguous and
     * strictly increasing so the percentage can never stall or go backwards.
     */
    @Test public void verifyingStageExistsBetweenFinalizingAndSaving() {
        ExportStage[] order = ExportStage.values();
        int fin = -1, ver = -1, sav = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == ExportStage.FINALIZING) fin = i;
            if (order[i] == ExportStage.VERIFYING) ver = i;
            if (order[i] == ExportStage.SAVING) sav = i;
        }
        assertTrue("FINALIZING missing", fin >= 0);
        assertTrue("VERIFYING missing - finalization would be invisible", ver >= 0);
        assertTrue("SAVING missing", sav >= 0);
        assertTrue("VERIFYING must come after FINALIZING", ver > fin);
        assertTrue("VERIFYING must come before SAVING", ver < sav);
    }

    /** Every stage must be strictly ahead of the one before it, with no hole. */
    @Test public void stageChainIsContiguousAndStrictlyIncreasing() {
        ExportStage[] order = ExportStage.values();
        assertEquals(0, order[0].from);
        for (int i = 0; i < order.length; i++) {
            assertTrue(order[i] + " has an empty range", order[i].to >= order[i].from);
            if (i > 0) {
                assertEquals(order[i] + " must start where " + order[i - 1] + " ended",
                        order[i - 1].to, order[i].from);
                assertTrue(order[i] + " must be ahead of " + order[i - 1],
                        order[i].to > order[i - 1].to || order[i] == ExportStage.COMPLETE);
            }
            // percent() must clamp: no stage can report outside its own band
            assertEquals(order[i].from, order[i].percent(-5f));
            assertEquals(order[i].to, order[i].percent(99f));
        }
        assertEquals(100, order[order.length - 1].to);
    }

    /**
     * A NaN fraction (frame/total when total is 0) must not produce a NaN or
     * negative percentage on screen.
     */
    @Test public void nanFractionCannotProduceAnInvalidPercentage() {
        int pct = ExportStage.RENDERING.percent(Float.NaN);
        assertTrue("NaN produced " + pct, pct >= 0 && pct <= 100);
    }
}
