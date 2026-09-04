package com.autoedit;

import com.autoedit.engine.FormulaEngine;
import com.autoedit.engine.Timeline;
import com.autoedit.engine.TransitionEngine;
import com.autoedit.engine.TransitionRegistry;
import com.autoedit.export.VideoExporter;
import com.autoedit.model.AspectRatio;
import com.autoedit.model.EditProject;
import com.autoedit.model.OverlayLayer;
import com.autoedit.model.TimelineClip;
import com.autoedit.model.TransitionCategory;
import com.autoedit.model.TransitionPreset;
import com.autoedit.model.TransitionType;
import com.autoedit.model.AudioTrack;
import com.autoedit.project.ProjectStore;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests for the v1.8 upgrades (master prompt Parts 1, 2, 3, 4):
 *
 * <ul>
 *   <li>gallery transition library — every spec name resolves, renders via the
 *       engine, categories/search work, ids are stable and unique;</li>
 *   <li>timeline — clip duration clamp, split-at-playhead invariants;</li>
 *   <li>layer system — OverlayLayer JSON round-trip + backward compatibility;</li>
 *   <li>project JSON — full round-trip incl. overlays, old projects without
 *       the field load as an empty layer stack;</li>
 *   <li>export — validation and audio-track math (pure-logic parts).</li>
 * </ul>
 *
 * Canvas-dependent rendering (TransitionDraw/FrameComposer) is exercised on
 * device; these tests lock the pure-logic contracts around it.
 */
public class GalleryAndTimelineTest {

    // =========================================================== gallery lib

    private static final String[] SPEC_GALLERY_PRESET_IDS = {
            // pre-existing (kept, ids stable)
            "fast_gallery", "gallery_slide", "gallery_wipe", "gallery_zoom", "random_gallery",
            // v1.8 additions (16 real multi-panel renderers)
            "gal_motion", "gal_wall", "gal_wall_v", "gal_scroll3d", "gal_scroll3d_r",
            "gal_align", "gal_social", "gal_frame", "gal_cam", "gal_space",
            "gal_preview", "gal_grid", "gal_messy", "gal_morph", "gal_carousel", "gal_columns",
    };

    @Test public void everySpecGalleryTransitionExists() {
        for (String id : SPEC_GALLERY_PRESET_IDS) {
            TransitionPreset p = TransitionRegistry.byId(id);
            assertNotNull("missing gallery preset " + id, p);
            assertTrue("gallery preset must render a gallery family: " + id,
                    p.type.isGallery() || p.type == TransitionType.GALLERY_ZOOM
                            || p.type == TransitionType.GALLERY_SLIDE
                            || p.type == TransitionType.RANDOM_GALLERY);
        }
    }

    @Test public void galleryPresetNamesMatchTheSpec() {
        assertName("gal_motion", "Motion Gallery");
        assertName("gal_wall", "Wall Gallery");
        assertName("gal_wall_v", "Gallery Wall");
        assertName("gal_scroll3d", "3D Gallery Scroll");
        assertName("gal_scroll3d_r", "3D-Gallery Scroll");
        assertName("gal_align", "Gallery Alignment");
        assertName("gal_social", "Social Gallery");
        assertName("gal_frame", "Gallery Frame");
        assertName("gal_cam", "Cam Gallery");
        assertName("gal_space", "Space Gallery");
        assertName("gal_preview", "Gallery Preview");
        assertName("gal_grid", "Gallery Grid");
        assertName("gal_messy", "Messy Gallery");
        assertName("gal_morph", "Gallery Morph");
        assertName("gal_carousel", "Gallery Carousel");
        assertName("gal_columns", "Gallery Columns");
        assertName("fast_gallery", "Fast Gallery");
        assertName("gallery_slide", "Gallery Slide");
        assertName("gallery_zoom", "Gallery Zoom");
        assertName("random_gallery", "Random Gallery");
    }

    private static void assertName(String id, String expected) {
        TransitionPreset p = TransitionRegistry.byId(id);
        assertNotNull(id, p);
        assertEquals(expected, p.name);
    }

    @Test public void allGalleryTypesAreFlaggedAndLabelled() {
        for (TransitionType t : TransitionType.values()) {
            if (t.isGallery()) {
                String l = TransitionEngine.label(t);
                assertNotNull(l);
                assertFalse("label must be a friendly name, not the raw enum: " + t, l.equals(t.name()));
            }
        }
        assertFalse(TransitionType.FADE.isGallery());
        assertFalse(TransitionType.NONE.isGallery());
        // 16 gallery presets map onto 15 multi-panel types: the left/right
        // 3D-scroll pair shares GALLERY_SCROLL_3D via its direction field
        int galleryCount = 0;
        for (TransitionType t : TransitionType.values()) if (t.isGallery()) galleryCount++;
        assertEquals("multi-panel gallery types", 15, galleryCount);
        // the browser offers only renderers it really draws — all 15 v1.8
        // gallery families must be in that list (no dead options)
        Set<TransitionType> rendered = new HashSet<>();
        for (TransitionType t : TransitionEngine.rendered()) rendered.add(t);
        String[] v18 = {"GALLERY_MOTION","GALLERY_WALL","GALLERY_WALL_V","GALLERY_SCROLL_3D",
                "GALLERY_ALIGN","GALLERY_SOCIAL","GALLERY_FRAME","GALLERY_CAM","GALLERY_SPACE",
                "GALLERY_PREVIEW","GALLERY_GRID","GALLERY_MESSY","GALLERY_MORPH",
                "GALLERY_CAROUSEL","GALLERY_COLUMNS"};
        for (String n : v18) assertTrue("not rendered: " + n, rendered.contains(TransitionType.valueOf(n)));
    }

    @Test public void galleryEngineMathStaysValid() {
        TransitionEngine engine = new TransitionEngine();
        for (TransitionPreset p : TransitionRegistry.byCategory(TransitionCategory.GALLERY)) {
            for (float m = 0f; m <= 1.001f; m += 0.25f) {
                TransitionEngine.Transform in = engine.incoming(p, m);
                TransitionEngine.Transform out = engine.outgoing(p, m);
                assertNotNull(in); assertNotNull(out);
                assertTrue("alpha out of range " + p.id, in.alpha >= -0.001f && in.alpha <= 1.001f);
                assertTrue(out.alpha >= -0.001f && out.alpha <= 1.001f);
            }
        }
    }

    @Test public void galleryCategoriesAndSearchWork() {
        assertTrue(TransitionRegistry.byCategory(TransitionCategory.GALLERY).size() >= 16);
        assertTrue(TransitionRegistry.byCategory(TransitionCategory.SOCIAL).size() >= 1);
        assertTrue(TransitionRegistry.byCategory(TransitionCategory.PHOTO).size() >= 1);
        assertTrue(TransitionRegistry.search("wall").size() >= 2);
        assertTrue(TransitionRegistry.search("3d gallery").size() >= 1);
        assertTrue(TransitionRegistry.search("gallery").size() >= 20);
        // direction variant exists and differs only by direction
        TransitionPreset l = TransitionRegistry.byId("gal_scroll3d");
        TransitionPreset r = TransitionRegistry.byId("gal_scroll3d_r");
        assertEquals("left", l.direction);
        assertEquals("right", r.direction);
    }

    @Test public void registryStaysUniqueAndCompatible() {
        Set<String> ids = new HashSet<>();
        for (TransitionPreset p : TransitionRegistry.all())
            assertTrue("duplicate id " + p.id, ids.add(p.id));
        assertTrue(TransitionRegistry.all().size() >= 165);
        // old favorites/recent ids must still resolve
        for (String id : new String[]{"fade", "zoom_switch", "glare", "3d_carousel",
                "fast_gallery", "gallery_zoom", "random_gallery"}) {
            assertNotNull("legacy id broken: " + id, TransitionRegistry.byId(id));
        }
    }

    // ============================================================== timeline

    @Test public void clipDurationClampsToSafeRange() {
        TimelineClip c = new TimelineClip("file:///x.jpg", 1, new FormulaEngine().defaultFormula());
        c.setDurationMs(100L);
        assertEquals(TimelineClip.MIN_DURATION_MS, c.durationMs);
        assertEquals(0.5f, c.durationSec, 0.001f);
        c.setDurationMs(999_999L);
        assertEquals(TimelineClip.MAX_DURATION_MS, c.durationMs);
        c.setDurationMs(3500L);
        assertEquals(3.5f, c.durationSec, 0.001f);
    }

    private static EditProject twoClipProject() {
        EditProject p = new EditProject();
        FormulaEngine fe = new FormulaEngine();
        TimelineClip a = new TimelineClip("file:///a.jpg", 1, fe.defaultFormula());
        a.setDurationMs(4000L);
        TimelineClip b = new TimelineClip("file:///b.jpg", 2, fe.defaultFormula());
        b.setDurationMs(4000L);
        p.clips.add(a);
        p.clips.add(b);
        return p;
    }

    /** Mirrors MainActivity.splitAtPlayhead semantics on the model. */
    private static EditProject splitAt(EditProject p, float t) {
        Timeline.Point at = Timeline.resolve(p, t);
        TimelineClip c = p.clips.get(at.clipIndex);
        float oldDur = c.durationSec;
        c.transition = TransitionType.NONE;
        c.transitionDurationSec = 0f;
        c.transitionPresetId = null;
        c.setDurationSeconds(at.localSec);
        TimelineClip right = new TimelineClip(c.uri, c.index + 1, c.formula);
        right.setDurationSeconds(oldDur - at.localSec);
        p.clips.add(at.clipIndex + 1, right);
        p.renumber();
        return p;
    }

    @Test public void splitPreservesDurationAndOrder() {
        EditProject p = twoClipProject();
        float totalBefore = p.totalDurationSec();
        splitAt(p, 1.5f);
        assertEquals(3, p.clips.size());
        assertEquals(totalBefore, p.totalDurationSec(), 0.01f);
        assertEquals(1.5f, p.clips.get(0).durationSec, 0.01f);
        assertEquals(2.5f, p.clips.get(1).durationSec, 0.01f);
        assertEquals(4.0f, p.clips.get(2).durationSec, 0.01f);
        // indices renumbered 1..3
        for (int i = 0; i < 3; i++) assertEquals(i + 1, p.clips.get(i).index);
        // the cut junction is a clean cut
        assertEquals(TransitionType.NONE, p.clips.get(0).transition);
    }

    @Test public void resolveAfterSplitMapsTimesCorrectly() {
        EditProject p = twoClipProject();
        splitAt(p, 1.5f);
        assertEquals(0, Timeline.resolve(p, 0.5f).clipIndex);
        assertEquals(1, Timeline.resolve(p, 2.5f).clipIndex);
        assertEquals(2, Timeline.resolve(p, 5.0f).clipIndex);
        // frame math still consistent
        p.fps = 30;
        assertEquals(Math.round(8 * 30), Timeline.totalFrames(p));
    }

    // ================================================================= layers

    @Test public void overlayRoundTripKeepsEverything() throws Exception {
        EditProject p = twoClipProject();
        OverlayLayer o = new OverlayLayer();
        o.kind = OverlayLayer.Kind.IMAGE;
        o.uri = "content://media/1";
        o.x = 0.21f; o.y = 0.77f; o.scale = 1.35f; o.rotation = 25f; o.opacity = 0.8f;
        o.startSec = 1f; o.endSec = 3f; o.locked = true; o.hidden = false;
        o.corner = "top-right"; o.cornerMargin = 0.09f;
        p.overlays.add(o);

        String json = ProjectStore.serialize(p);
        EditProject q = ProjectStore.deserialize(json);
        assertEquals(1, q.overlays.size());
        OverlayLayer r = q.overlays.get(0);
        assertEquals(OverlayLayer.Kind.IMAGE, r.kind);
        assertEquals("content://media/1", r.uri);
        assertEquals(o.x, r.x, 0.001f);
        assertEquals(o.y, r.y, 0.001f);
        assertEquals(o.scale, r.scale, 0.001f);
        assertEquals(o.rotation, r.rotation, 0.001f);
        assertEquals(o.opacity, r.opacity, 0.001f);
        assertEquals(o.startSec, r.startSec, 0.001f);
        assertEquals(o.endSec, r.endSec, 0.001f);
        assertTrue(r.locked);
        assertEquals("top-right", r.corner);
        // effective range math
        assertEquals(2.0f, r.durationSec(q), 0.001f);
        assertTrue(r.activeAt(2f, q));
        assertFalse(r.activeAt(0.5f, q));
    }

    @Test public void overlayTextRoundTrip() throws Exception {
        EditProject p = twoClipProject();
        OverlayLayer o = new OverlayLayer();
        o.kind = OverlayLayer.Kind.TEXT;
        o.text = "@channel";
        o.color = 0xffff00ff;
        o.bold = false;
        o.textSize = 88f;
        p.overlays.add(o);
        EditProject q = ProjectStore.deserialize(ProjectStore.serialize(p));
        assertEquals(1, q.overlays.size());
        assertEquals(OverlayLayer.Kind.TEXT, q.overlays.get(0).kind);
        assertEquals("@channel", q.overlays.get(0).text);
        assertEquals(0xffff00ff, q.overlays.get(0).color);
        assertEquals(88f, q.overlays.get(0).textSize, 0.001f);
    }

    /** Old projects have NO "overlays" field — they must load as an empty stack. */
    @Test public void oldProjectJsonWithoutOverloadsLoadsCleanly() {
        String oldJson = "{" +
                "\"name\":\"Legacy\",\"fps\":30,\"width\":1080,\"height\":1920," +
                "\"quality\":\"High\",\"defaultDuration\":5," +
                "\"aspect\":\"R9_16\",\"exportPreset\":\"PORTRAIT_9_16\",\"fitMode\":\"FILL\"," +
                "\"clips\":[{\"uri\":\"file:///a.jpg\",\"index\":1,\"durationMs\":4000," +
                "\"formula\":\"17\",\"transition\":\"CROSS_DISSOLVE\",\"transitionDuration\":0.5," +
                "\"effect\":\"NONE\",\"effectIntensity\":0}]," +
                "\"texts\":[],\"audioTracks\":[]}";
        EditProject p = ProjectStore.deserialize(oldJson);
        assertNotNull(p);
        assertEquals(1, p.clips.size());
        assertNotNull(p.overlays);
        assertTrue("legacy project must load with zero overlays", p.overlays.isEmpty());
        assertEquals("Legacy", p.name);
    }

    @Test public void malformedOverlayEntryNeverCrashesLoad() {
        String bad = "{" +
                "\"name\":\"X\",\"fps\":30,\"clips\":[]," +
                "\"overlays\":[{\"kind\":\"BOGUS\",\"x\":\"oops\"},null,{}]}";
        EditProject p = ProjectStore.deserialize(bad);
        assertNotNull(p);
        // the two real objects parse defensively into valid layers; the
        // explicit null is skipped without a crash
        assertEquals(2, p.overlays.size());
    }

    // ================================================================ export

    @Test public void exportValidationRejectsEmptyAndBadProjects() {
        assertNotNull(VideoExporter.validate(null));
        assertNotNull(VideoExporter.validate(new EditProject())); // no clips
        EditProject p = twoClipProject();
        p.clips.get(0).setDurationMs(0L); // clamps to 500ms, still valid
        assertNull("valid project must pass", VideoExporter.validate(p));
        EditProject emptyUri = new EditProject();
        TimelineClip c = new TimelineClip("", 1, new FormulaEngine().defaultFormula());
        c.setDurationMs(3000L);
        emptyUri.clips.add(c);
        assertNotNull("missing source must be rejected", VideoExporter.validate(emptyUri));
    }

    @Test public void audioTrackEffectiveDurationMath() {
        AudioTrack t = new AudioTrack("content://a");
        t.sourceDurationMs = 60_000L;
        assertEquals(60f, t.effectiveDurationSec(), 0.01f);
        t.trimStartSec = 10f;
        assertEquals(50f, t.effectiveDurationSec(), 0.01f);
        t.trimEndSec = 30f;
        assertEquals(20f, t.effectiveDurationSec(), 0.01f);
        t.trimEndSec = 999f; // beyond file end -> clamped to file end
        assertEquals(50f, t.effectiveDurationSec(), 0.01f);
        AudioTrack unknown = new AudioTrack("content://b");
        assertEquals(30f, unknown.effectiveDurationSec(), 0.01f); // default probe
    }

    @Test public void audioSplitMathKeepsCoverage() {
        // mirrors MainActivity.splitAudioAtPlayhead: head + tail cover the used region
        AudioTrack t = new AudioTrack("content://a");
        t.sourceDurationMs = 60_000L;
        t.startSec = 5f;
        float play = 20f;
        float local = play - t.startSec;      // 15
        // tail
        AudioTrack b = new AudioTrack(t.uri);
        b.sourceDurationMs = t.sourceDurationMs;
        b.startSec = t.startSec + local;      // 20
        b.trimStartSec = t.trimStartSec + local; // 15
        // head
        t.trimEndSec = t.trimStartSec + local;   // 15
        // used region: head [5..20), tail [20..end of file)
        float headLen = t.effectiveDurationSec();        // 15
        float tailLen = b.effectiveDurationSec();        // 45
        assertEquals(15f, headLen, 0.01f);
        assertEquals(45f, tailLen, 0.01f);
        assertEquals(t.sourceDurationMs / 1000f, headLen + tailLen, 0.01f);
    }

    // ======================================================= backward compat

    @Test public void legacyAudioUriStillMigrates() {
        String old = "{" +
                "\"name\":\"A\",\"clips\":[{\"uri\":\"file:///a.jpg\",\"index\":1,\"durationMs\":3000," +
                "\"formula\":\"17\",\"transition\":\"NONE\",\"effect\":\"NONE\"}]," +
                "\"audio\":\"content://audio/77\"}";
        EditProject p = ProjectStore.deserialize(old);
        p.migrateLegacyAudio();
        assertEquals(1, p.audioTracks.size());
        assertEquals("content://audio/77", p.audioTracks.get(0).uri);
        assertTrue(p.hasAudio());
    }

    @Test public void projectJsonRoundTripFull() throws Exception {
        EditProject p = twoClipProject();
        p.name = "Round trip";
        p.fps = 60;
        p.aspectRatio = AspectRatio.R16_9;
        p.audioTracks.add(new AudioTrack("content://a"));
        p.audioTracks.get(0).startSec = 1f;
        p.audioTracks.get(0).fadeInSec = 2f;
        OverlayLayer o = new OverlayLayer();
        o.kind = OverlayLayer.Kind.IMAGE;
        o.uri = "content://logo";
        o.startSec = 0f;
        p.overlays.add(o);
        p.clips.get(0).transition = TransitionType.GALLERY_MORPH;
        p.clips.get(0).transitionDurationSec = 0.9f;
        p.clips.get(0).transitionPresetId = "gal_morph";

        EditProject q = ProjectStore.deserialize(ProjectStore.serialize(p));
        assertEquals(p.name, q.name);
        assertEquals(p.fps, q.fps);
        assertEquals(p.aspectRatio, q.aspectRatio);
        assertEquals(2, q.clips.size());
        assertEquals(TransitionType.GALLERY_MORPH, q.clips.get(0).transition);
        assertEquals("gal_morph", q.clips.get(0).transitionPresetId);
        assertEquals(0.9f, q.clips.get(0).transitionDurationSec, 0.001f);
        assertEquals(1, q.audioTracks.size());
        assertEquals(2f, q.audioTracks.get(0).fadeInSec, 0.001f);
        assertEquals(1, q.overlays.size());
        assertEquals("content://logo", q.overlays.get(0).uri);
        // and the registry still resolves the stored preset id
        assertNotNull(TransitionRegistry.byId(q.clips.get(0).transitionPresetId));
    }

    @Test public void largeProjectClipsResolveInOrder() {
        EditProject p = new EditProject();
        FormulaEngine fe = new FormulaEngine();
        for (int i = 0; i < 200; i++) {
            TimelineClip c = new TimelineClip("file:///img" + i + ".jpg", i + 1, fe.defaultFormula());
            c.setDurationMs(3000L);
            p.clips.add(c);
        }
        p.renumber();
        assertEquals(200, p.clips.size());
        assertEquals(600f, p.totalDurationSec(), 0.5f);
        assertEquals(199, Timeline.resolve(p, 599.9f).clipIndex);
        assertEquals(0, Timeline.resolve(p, 0.1f).clipIndex);
        // mid-project split keeps the total
        float before = p.totalDurationSec();
        Timeline.Point at = Timeline.resolve(p, 100f);
        TimelineClip c = p.clips.get(at.clipIndex);
        float left = at.localSec, right = c.durationSec - left;
        c.setDurationSeconds(left);
        p.clips.add(at.clipIndex + 1, new TimelineClip(c.uri, c.index + 1, c.formula));
        p.clips.get(at.clipIndex + 1).setDurationSeconds(right);
        p.renumber();
        assertEquals(before, p.totalDurationSec(), 0.01f);
        assertEquals(201, p.clips.size());
        // every GALLERY-category preset is discoverable via the gallery tag
        List<TransitionPreset> gallery = TransitionRegistry.byCategory(TransitionCategory.GALLERY);
        Set<String> found = new HashSet<>();
        for (TransitionPreset h : TransitionRegistry.search("gallery")) found.add(h.id);
        for (TransitionPreset g : gallery) assertTrue("not searchable: " + g.id, found.contains(g.id));
    }
}
