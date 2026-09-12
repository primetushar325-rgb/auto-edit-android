package com.autoedit;

import com.autoedit.zip.SerialParser;
import com.autoedit.zip.ZipBatchModels;
import com.autoedit.zip.ZipBatchModels.BatchResult;
import com.autoedit.zip.ZipBatchModels.ReadyImage;
import com.autoedit.zip.ZipBatchModels.UnnumberedPolicy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Acceptance tests for ZIP batch serial sorting (master prompt TESTS 1–7).
 * Pure JVM — no Android runtime required for SerialParser / sort / diagnostics.
 */
public class ZipSerialBatchTest {

    // =========================================================== serial parse

    @Test public void extractsSimpleSerials() {
        assertEquals(Integer.valueOf(1), SerialParser.extractSerial("image-001.png"));
        assertEquals(Integer.valueOf(2), SerialParser.extractSerial("image-002.png"));
        assertEquals(Integer.valueOf(10), SerialParser.extractSerial("image-010.png"));
        assertEquals(Integer.valueOf(11), SerialParser.extractSerial("image_011.jpg"));
        assertEquals(Integer.valueOf(12), SerialParser.extractSerial("scene-012.webp"));
        assertEquals(Integer.valueOf(123), SerialParser.extractSerial("abc123.png"));
    }

    @Test public void extractsFlexibleFilenames() {
        assertEquals(Integer.valueOf(1), SerialParser.extractSerial("image 001.png"));
        assertEquals(Integer.valueOf(1), SerialParser.extractSerial("scene001.jpg"));
        assertEquals(Integer.valueOf(1), SerialParser.extractSerial("scene-001-final.png"));
        assertEquals(Integer.valueOf(2), SerialParser.extractSerial("abc_002_test.webp"));
        assertEquals(Integer.valueOf(1), SerialParser.extractSerial("001.png"));
        assertEquals(Integer.valueOf(2), SerialParser.extractSerial("002.jpg"));
        assertEquals(Integer.valueOf(4), SerialParser.extractSerial("004-final.png"));
    }

    @Test public void usesLastNumericGroup() {
        // story_2026_scene_001.png → 1 (not 2026)
        assertEquals(Integer.valueOf(1), SerialParser.extractSerial("story_2026_scene_001.png"));
        // video_01_part_002.jpg → 2 (not 01)
        assertEquals(Integer.valueOf(2), SerialParser.extractSerial("video_01_part_002.jpg"));
    }

    @Test public void extensionIsNeverTheSerial() {
        assertEquals(Integer.valueOf(1), SerialParser.extractSerial("001.png"));
        assertNull(SerialParser.extractSerial("character.png"));
        assertNull(SerialParser.extractSerial("background.jpg"));
        assertNull(SerialParser.extractSerial("intro.webp"));
    }

    @Test public void pathPrefixIsIgnored() {
        assertEquals(Integer.valueOf(3), SerialParser.extractSerial("chapter1/image-003.png"));
        assertEquals(Integer.valueOf(4), SerialParser.extractSerial("a/b/c/004.jpg"));
    }

    @Test public void supportedImageExtensions() {
        assertTrue(SerialParser.isSupportedImage("x.PNG"));
        assertTrue(SerialParser.isSupportedImage("x.JpEg"));
        assertTrue(SerialParser.isSupportedImage("x.webp"));
        assertTrue(SerialParser.isSupportedImage("x.JPG"));
        assertFalse(SerialParser.isSupportedImage("readme.txt"));
        assertFalse(SerialParser.isSupportedImage("data.json"));
        assertFalse(SerialParser.isSupportedImage("music.mp3"));
        assertFalse(SerialParser.isSupportedImage("clip.mp4"));
        assertFalse(SerialParser.isSupportedImage("doc.pdf"));
    }

    // =========================================================== zip slip

    @Test public void zipSlipPathsAreRejected() {
        assertTrue(ZipBatchModels.isUnsafeZipPath("../etc/passwd"));
        assertTrue(ZipBatchModels.isUnsafeZipPath("../../x.png"));
        assertTrue(ZipBatchModels.isUnsafeZipPath("/absolute/x.png"));
        assertTrue(ZipBatchModels.isUnsafeZipPath("foo/../../bar.png"));
        assertTrue(ZipBatchModels.isUnsafeZipPath("C:/windows/x.png"));
        assertFalse(ZipBatchModels.isUnsafeZipPath("chapter1/image-001.png"));
        assertFalse(ZipBatchModels.isUnsafeZipPath("image-001.png"));
        assertFalse(ZipBatchModels.isUnsafeZipPath("a/b/c/d.webp"));
    }

    // =========================================================== sort logic

    /** Mirrors the importer's global numeric sort + missing/dup detection. */
    private static BatchResult sortAndDiagnose(List<ReadyImage> numbered,
                                               List<ReadyImage> unnumbered) {
        Collections.sort(numbered, new Comparator<ReadyImage>() {
            @Override public int compare(ReadyImage a, ReadyImage b) {
                int sa = a.serial == null ? Integer.MAX_VALUE : a.serial;
                int sb = b.serial == null ? Integer.MAX_VALUE : b.serial;
                if (sa != sb) return Integer.compare(sa, sb);
                if (a.zipIndex != b.zipIndex) return Integer.compare(a.zipIndex, b.zipIndex);
                return a.entryPath.compareTo(b.entryPath);
            }
        });
        BatchResult out = new BatchResult();
        Map<Integer, Integer> counts = new HashMap<>();
        for (ReadyImage r : numbered) {
            if (r.serial == null) continue;
            Integer c = counts.get(r.serial);
            counts.put(r.serial, c == null ? 1 : c + 1);
        }
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) out.duplicateSerials.add(e.getKey());
        }
        Collections.sort(out.duplicateSerials);
        if (!counts.isEmpty()) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (Integer s : counts.keySet()) {
                if (s < min) min = s;
                if (s > max) max = s;
            }
            out.serialMin = min;
            out.serialMax = max;
            for (int s = min; s <= max; s++) if (!counts.containsKey(s)) out.missing.add(s);
        }
        out.ordered.addAll(numbered);
        out.unnumbered.addAll(unnumbered);
        out.unnumberedCount = unnumbered.size();
        out.imagesFound = numbered.size() + unnumbered.size();
        return out;
    }

    private static ReadyImage img(int serial, int zip, String path) {
        return new ReadyImage("file:///x/" + path, serial, path, zip, path);
    }

    private static ReadyImage unnum(int zip, String path) {
        return new ReadyImage("file:///x/" + path, null, path, zip, path);
    }

    private static List<Integer> serialsOf(List<ReadyImage> list) {
        List<Integer> s = new ArrayList<>();
        for (ReadyImage r : list) s.add(r.serial);
        return s;
    }

    // TEST 1
    @Test public void test1_simpleSequence() {
        List<ReadyImage> n = Arrays.asList(
                img(1, 0, "image-001.png"),
                img(2, 0, "image-002.png"),
                img(3, 0, "image-003.png"));
        BatchResult r = sortAndDiagnose(new ArrayList<>(n), new ArrayList<ReadyImage>());
        assertEquals(Arrays.asList(1, 2, 3), serialsOf(r.ordered));
        assertTrue(r.missing.isEmpty());
    }

    // TEST 2 — numeric NOT lexicographic
    @Test public void test2_numericNotLexicographic() {
        List<ReadyImage> n = Arrays.asList(
                img(1, 0, "image-001.png"),
                img(10, 0, "image-010.png"),
                img(2, 0, "image-002.png"),
                img(3, 0, "image-003.png"));
        BatchResult r = sortAndDiagnose(new ArrayList<>(n), new ArrayList<ReadyImage>());
        assertEquals(Arrays.asList(1, 2, 3, 10), serialsOf(r.ordered));
        assertFalse(serialsOf(r.ordered).equals(Arrays.asList(1, 10, 2, 3)));
    }

    // TEST 3 — multi ZIP combined
    @Test public void test3_multiZipCombined() {
        List<ReadyImage> n = new ArrayList<>();
        for (int i = 1; i <= 10; i++) n.add(img(i, 0, "a-" + i + ".png"));
        for (int i = 11; i <= 20; i++) n.add(img(i, 1, "b-" + i + ".png"));
        // shuffle input order intentionally
        Collections.shuffle(n);
        BatchResult r = sortAndDiagnose(n, new ArrayList<ReadyImage>());
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= 20; i++) expected.add(i);
        assertEquals(expected, serialsOf(r.ordered));
        assertEquals(1, r.serialMin);
        assertEquals(20, r.serialMax);
    }

    // TEST 4 — missing serial, no blank clip
    @Test public void test4_missingSerialNoBlank() {
        List<ReadyImage> n = Arrays.asList(
                img(1, 0, "1.png"), img(2, 0, "2.png"), img(3, 0, "3.png"),
                img(5, 0, "5.png"), img(6, 0, "6.png"));
        BatchResult r = sortAndDiagnose(new ArrayList<>(n), new ArrayList<ReadyImage>());
        assertEquals(Arrays.asList(1, 2, 3, 5, 6), serialsOf(r.ordered));
        assertEquals(Collections.singletonList(4), r.missing);
        // finalize must NOT invent a serial-4 placeholder
        List<ReadyImage> finalOrder = ZipBatchModels.finalizeOrder(r, true, UnnumberedPolicy.SKIP);
        assertEquals(5, finalOrder.size());
        assertEquals(Arrays.asList(1, 2, 3, 5, 6), serialsOf(finalOrder));
    }

    // TEST 5 — interleaved ZIPs
    @Test public void test5_interleavedZips() {
        List<ReadyImage> n = Arrays.asList(
                img(1, 0, "a1.png"), img(3, 0, "a3.png"), img(5, 0, "a5.png"),
                img(2, 1, "b2.png"), img(4, 1, "b4.png"), img(6, 1, "b6.png"));
        BatchResult r = sortAndDiagnose(new ArrayList<>(n), new ArrayList<ReadyImage>());
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6), serialsOf(r.ordered));
        assertTrue(r.missing.isEmpty());
    }

    // TEST 6 — mixed names
    @Test public void test6_mixedNames() {
        List<ReadyImage> n = Arrays.asList(
                img(1, 0, "image-001.png"),
                img(2, 0, "scene_002.jpg"),
                img(3, 0, "abc003.webp"),
                img(4, 0, "004-final.png"));
        BatchResult r = sortAndDiagnose(new ArrayList<>(n), new ArrayList<ReadyImage>());
        assertEquals(Arrays.asList(1, 2, 3, 4), serialsOf(r.ordered));
    }

    // TEST 7 — non-images ignored at parse level (only images reach sort)
    @Test public void test7_onlyImagesReachSort() {
        // Simulate: scanner already dropped .txt/.json/.mp3
        List<ReadyImage> n = Arrays.asList(
                img(1, 0, "001.png"), img(2, 0, "002.jpg"), img(3, 0, "003.webp"));
        BatchResult r = sortAndDiagnose(new ArrayList<>(n), new ArrayList<ReadyImage>());
        assertEquals(Arrays.asList(1, 2, 3), serialsOf(r.ordered));
    }

    @Test public void duplicatesKeepBothWhenRequested() {
        List<ReadyImage> n = Arrays.asList(
                img(5, 0, "image-005.png"),
                img(5, 1, "scene-005.jpg"),
                img(1, 0, "image-001.png"));
        BatchResult r = sortAndDiagnose(new ArrayList<>(n), new ArrayList<ReadyImage>());
        assertEquals(Collections.singletonList(5), r.duplicateSerials);
        // Keep both: order is serial then zipIndex
        List<ReadyImage> keep = ZipBatchModels.finalizeOrder(r, true, UnnumberedPolicy.SKIP);
        assertEquals(3, keep.size());
        assertEquals(Integer.valueOf(1), keep.get(0).serial);
        assertEquals(Integer.valueOf(5), keep.get(1).serial);
        assertEquals(0, keep.get(1).zipIndex);
        assertEquals(Integer.valueOf(5), keep.get(2).serial);
        assertEquals(1, keep.get(2).zipIndex);

        // Drop duplicates: only first per serial
        List<ReadyImage> drop = ZipBatchModels.finalizeOrder(r, false, UnnumberedPolicy.SKIP);
        assertEquals(2, drop.size());
        assertEquals(Integer.valueOf(1), drop.get(0).serial);
        assertEquals(Integer.valueOf(5), drop.get(1).serial);
        assertEquals(0, drop.get(1).zipIndex);
    }

    @Test public void unnumberedSkipOrAppend() {
        List<ReadyImage> n = Arrays.asList(img(1, 0, "1.png"), img(2, 0, "2.png"));
        List<ReadyImage> u = Arrays.asList(unnum(0, "character.png"), unnum(0, "bg.jpg"));
        BatchResult r = sortAndDiagnose(new ArrayList<>(n), new ArrayList<>(u));
        assertEquals(2, r.unnumberedCount);

        List<ReadyImage> skip = ZipBatchModels.finalizeOrder(r, true, UnnumberedPolicy.SKIP);
        assertEquals(2, skip.size());
        assertEquals(Integer.valueOf(1), skip.get(0).serial);

        List<ReadyImage> append = ZipBatchModels.finalizeOrder(r, true, UnnumberedPolicy.APPEND);
        assertEquals(4, append.size());
        assertEquals(Integer.valueOf(1), append.get(0).serial);
        assertEquals(Integer.valueOf(2), append.get(1).serial);
        assertNull(append.get(2).serial);
        assertNull(append.get(3).serial);
    }

    @Test public void largeBatchSortIsStableAndFast() {
        List<ReadyImage> n = new ArrayList<>();
        // 1000 images, shuffled, with a gap at 500
        for (int i = 1; i <= 1000; i++) {
            if (i == 500) continue;
            n.add(img(i, i % 3, "img-" + i + ".png"));
        }
        Collections.shuffle(n);
        long t0 = System.nanoTime();
        BatchResult r = sortAndDiagnose(n, new ArrayList<ReadyImage>());
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(999, r.ordered.size());
        assertEquals(Collections.singletonList(500), r.missing);
        assertEquals(Integer.valueOf(1), r.ordered.get(0).serial);
        assertEquals(Integer.valueOf(1000), r.ordered.get(r.ordered.size() - 1).serial);
        // Strictly increasing serials (allowing the gap)
        int prev = -1;
        for (ReadyImage ri : r.ordered) {
            assertTrue(ri.serial >= prev);
            prev = ri.serial;
        }
        assertTrue("sort of 999 items took too long: " + ms + "ms", ms < 2000);
    }

    @Test public void combinedZipExampleFromSpec() {
        // ZIP A: 1,5,9  ZIP B: 2,6,10  → 1,2,5,6,9,10
        List<ReadyImage> n = Arrays.asList(
                img(1, 0, "a1.png"), img(5, 0, "a5.png"), img(9, 0, "a9.png"),
                img(2, 1, "b2.png"), img(6, 1, "b6.png"), img(10, 1, "b10.png"));
        BatchResult r = sortAndDiagnose(new ArrayList<>(n), new ArrayList<ReadyImage>());
        assertEquals(Arrays.asList(1, 2, 5, 6, 9, 10), serialsOf(r.ordered));
        // missing 3,4,7,8
        assertEquals(Arrays.asList(3, 4, 7, 8), r.missing);
    }
}
