package com.autoedit.zip;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable-ish data carriers for the ZIP batch import pipeline.
 * No Android UI dependencies — safe for background threads and unit tests.
 */
public final class ZipBatchModels {

    private ZipBatchModels() {}

    /** One image candidate discovered inside a ZIP (before extraction to disk). */
    public static final class RawEntry {
        public final int zipIndex;       // 0-based selection order
        public final String zipName;     // display name of the ZIP
        public final String entryPath;   // path inside the ZIP
        public final Integer serial;     // null when unnumbered
        public final long entrySize;

        public RawEntry(int zipIndex, String zipName, String entryPath,
                        Integer serial, long entrySize) {
            this.zipIndex = zipIndex;
            this.zipName = zipName == null ? "" : zipName;
            this.entryPath = entryPath == null ? "" : entryPath;
            this.serial = serial;
            this.entrySize = entrySize;
        }
    }

    /**
     * One accepted image ready for the timeline. {@link #fileUri} is a
     * file:// (or content://) URI under app-controlled storage.
     */
    public static final class ReadyImage {
        public final String fileUri;
        public final Integer serial;     // null when unnumbered + appended
        public final String sourceName;  // original basename
        public final int zipIndex;
        public final String entryPath;

        public ReadyImage(String fileUri, Integer serial, String sourceName,
                          int zipIndex, String entryPath) {
            this.fileUri = fileUri;
            this.serial = serial;
            this.sourceName = sourceName == null ? "" : sourceName;
            this.zipIndex = zipIndex;
            this.entryPath = entryPath == null ? "" : entryPath;
        }
    }

    /** Full scan + sort + diagnostics result shown on the summary screen. */
    public static final class BatchResult {
        public int zipCount;
        public int imagesFound;
        public int serialMin = -1;
        public int serialMax = -1;
        public final List<Integer> missing = new ArrayList<>();
        public final List<Integer> duplicateSerials = new ArrayList<>();
        public int unnumberedCount;
        public int skippedNonImage;
        public int invalidImage;
        public final List<ReadyImage> ordered = new ArrayList<>();
        public final List<ReadyImage> unnumbered = new ArrayList<>();
        public String error;
        public boolean cancelled;

        public boolean hasProblems() {
            return !missing.isEmpty() || !duplicateSerials.isEmpty() || unnumberedCount > 0;
        }

        public boolean isReady() {
            return error == null && !cancelled && !ordered.isEmpty();
        }
    }

    /** Progress snapshot posted to the UI thread during scan/extract. */
    public static final class Progress {
        public final String stage;   // "Scanning files..." / "Extracting..."
        public final int done;
        public final int total;
        public final int imagesFound;

        public Progress(String stage, int done, int total, int imagesFound) {
            this.stage = stage;
            this.done = done;
            this.total = total;
            this.imagesFound = imagesFound;
        }
    }

    /** How to treat unnumbered images when the user confirms the summary. */
    public enum UnnumberedPolicy {
        SKIP,
        APPEND
    }

    /**
     * Builds the final timeline image list applying the user's choices.
     * Does NOT invent placeholders for missing serials.
     *
     * @param keepDuplicates when false, only the first image per serial is kept
     *                       (ZIP order / entry path already sorted)
     */
    public static List<ReadyImage> finalizeOrder(BatchResult result,
                                                 boolean keepDuplicates,
                                                 UnnumberedPolicy unnumberedPolicy) {
        List<ReadyImage> out = new ArrayList<>();
        if (result == null) return out;
        if (keepDuplicates) {
            out.addAll(result.ordered);
        } else {
            java.util.HashSet<Integer> seen = new java.util.HashSet<>();
            for (ReadyImage r : result.ordered) {
                if (r.serial == null) { out.add(r); continue; }
                if (seen.contains(r.serial)) continue;
                seen.add(r.serial);
                out.add(r);
            }
        }
        if (unnumberedPolicy == UnnumberedPolicy.APPEND) {
            out.addAll(result.unnumbered);
        }
        return out;
    }

    /** True when a ZIP entry path would escape the destination (Zip Slip). */
    public static boolean isUnsafeZipPath(String name) {
        if (name == null || name.isEmpty()) return true;
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.startsWith("../") || n.contains("/../") || n.endsWith("/.."))
            return true;
        if (n.length() >= 2 && Character.isLetter(n.charAt(0)) && n.charAt(1) == ':') return true;
        return false;
    }
}
