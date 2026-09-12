package com.autoedit.zip;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.autoedit.zip.ZipBatchModels.BatchResult;
import com.autoedit.zip.ZipBatchModels.Progress;
import com.autoedit.zip.ZipBatchModels.RawEntry;
import com.autoedit.zip.ZipBatchModels.ReadyImage;
import com.autoedit.zip.ZipBatchModels.UnnumberedPolicy;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Memory-safe multi-ZIP image batch importer.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Scan every selected ZIP (streaming, recursive folders).</li>
 *   <li>Keep only supported image extensions; skip directories / non-images.</li>
 *   <li>Zip-Slip guard on every entry path.</li>
 *   <li>Extract accepted images into project-controlled storage
 *       ({@code files/zip_imports/&lt;batchId&gt;/}).</li>
 *   <li>Validate each extracted file is a real decodable image
 *       (BitmapFactory bounds — no full decode).</li>
 *   <li>Global numeric serial sort across ALL ZIPs combined.</li>
 *   <li>Detect missing serials, duplicates, unnumbered images.</li>
 * </ol>
 *
 * <p>Never loads full-resolution bitmaps. Cancelable via {@link #cancel()}.
 * Does not touch the export pipeline.
 */
public final class ZipBatchImporter {

    private static final String TAG = "ZipBatchImporter";
    private static final int BUF = 64 * 1024;
    /** Hard ceiling so a malicious ZIP cannot fill the disk unbounded. */
    private static final int MAX_IMAGES = 5000;
    private static final long MAX_ENTRY_BYTES = 80L * 1024L * 1024L; // 80 MB per image

    public interface ProgressListener {
        void onProgress(Progress p);
    }

    private final Context app;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public ZipBatchImporter(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }

    /**
     * Scan + extract + sort. Runs on the calling thread (call from a
     * background executor). Returns a fully populated {@link BatchResult}.
     *
     * @param zipUris ordered list of SAF document URIs (selection order matters
     *                for duplicate-serial tie-breaks)
     * @param zipNames optional display names parallel to {@code zipUris}
     * @param listener optional progress callback (may be called from this thread)
     */
    public BatchResult importZips(List<Uri> zipUris, List<String> zipNames,
                                  ProgressListener listener) {
        BatchResult out = new BatchResult();
        if (zipUris == null || zipUris.isEmpty()) {
            out.error = "No ZIP files selected.";
            return out;
        }
        out.zipCount = zipUris.size();

        // ---- 1. Scan: collect raw image entries across all ZIPs ------------
        List<RawEntry> raw = new ArrayList<>();
        int scanned = 0;
        int totalHint = Math.max(1, zipUris.size());
        for (int zi = 0; zi < zipUris.size(); zi++) {
            if (cancelled.get()) { out.cancelled = true; return out; }
            Uri uri = zipUris.get(zi);
            String zname = (zipNames != null && zi < zipNames.size() && zipNames.get(zi) != null)
                    ? zipNames.get(zi) : ("ZIP " + (zi + 1));
            try {
                scanned = scanZip(uri, zi, zname, raw, scanned, listener, out);
            } catch (Exception e) {
                Log.e(TAG, "Scan failed for " + zname, e);
                // Continue with other ZIPs; record a soft error only if nothing found later.
            }
            if (raw.size() >= MAX_IMAGES) break;
        }
        out.imagesFound = raw.size();
        if (cancelled.get()) { out.cancelled = true; return out; }
        if (raw.isEmpty()) {
            out.error = out.skippedNonImage > 0
                    ? "No supported images found (jpg/png/webp). Non-image files were skipped."
                    : "No images found inside the selected ZIP file(s).";
            return out;
        }

        // ---- 2. Extract accepted entries into project storage --------------
        String batchId = "b" + System.currentTimeMillis();
        File destDir = new File(app.getFilesDir(), "zip_imports/" + batchId);
        if (!destDir.mkdirs() && !destDir.isDirectory()) {
            out.error = "Could not create import folder.";
            return out;
        }

        // Group by ZIP so we open each archive once.
        Map<Integer, List<RawEntry>> byZip = new HashMap<>();
        for (RawEntry r : raw) {
            List<RawEntry> list = byZip.get(r.zipIndex);
            if (list == null) { list = new ArrayList<>(); byZip.put(r.zipIndex, list); }
            list.add(r);
        }

        List<ReadyImage> numbered = new ArrayList<>();
        List<ReadyImage> unnumbered = new ArrayList<>();
        int extracted = 0;
        int extractTotal = raw.size();

        for (int zi = 0; zi < zipUris.size(); zi++) {
            if (cancelled.get()) { out.cancelled = true; cleanupDir(destDir); return out; }
            List<RawEntry> want = byZip.get(zi);
            if (want == null || want.isEmpty()) continue;
            // Build a set of wanted entry paths for O(1) lookup.
            Map<String, RawEntry> wantMap = new HashMap<>();
            for (RawEntry r : want) wantMap.put(r.entryPath, r);

            Uri uri = zipUris.get(zi);
            try (InputStream is = app.getContentResolver().openInputStream(uri);
                 ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is, BUF))) {
                if (is == null) continue;
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (cancelled.get()) break;
                    if (entry.isDirectory()) { zis.closeEntry(); continue; }
                    String name = entry.getName();
                    if (name == null) { zis.closeEntry(); continue; }
                    // Normalize separators for lookup.
                    String key = name.replace('\\', '/');
                    RawEntry target = wantMap.get(key);
                    if (target == null) { zis.closeEntry(); continue; }

                    // Zip-Slip: resolve + force under destDir.
                    File outFile = safeDestFile(destDir, key, extracted);
                    if (outFile == null) {
                        out.invalidImage++;
                        zis.closeEntry();
                        continue;
                    }
                    long written = copyEntry(zis, outFile, entry.getSize());
                    zis.closeEntry();
                    extracted++;
                    post(listener, "Extracting images...", extracted, extractTotal, numbered.size() + unnumbered.size());

                    if (written <= 0 || !isValidImageFile(outFile)) {
                        // Not a real image — discard.
                        //noinspection ResultOfMethodCallIgnored
                        outFile.delete();
                        out.invalidImage++;
                        continue;
                    }
                    String fileUri = Uri.fromFile(outFile).toString();
                    ReadyImage ri = new ReadyImage(fileUri, target.serial,
                            SerialParser.basename(key), target.zipIndex, key);
                    if (target.serial == null) unnumbered.add(ri);
                    else numbered.add(ri);
                }
            } catch (Exception e) {
                Log.e(TAG, "Extract failed for zip " + zi, e);
            }
        }

        if (cancelled.get()) { out.cancelled = true; cleanupDir(destDir); return out; }

        // ---- 3. Global numeric sort + diagnostics -------------------------
        // Stable deterministic order for equal serials:
        //   1) ZIP selection order (zipIndex)
        //   2) ZIP entry path/name
        Collections.sort(numbered, new Comparator<ReadyImage>() {
            @Override public int compare(ReadyImage a, ReadyImage b) {
                int sa = a.serial == null ? Integer.MAX_VALUE : a.serial;
                int sb = b.serial == null ? Integer.MAX_VALUE : b.serial;
                if (sa != sb) return Integer.compare(sa, sb);
                if (a.zipIndex != b.zipIndex) return Integer.compare(a.zipIndex, b.zipIndex);
                return a.entryPath.compareTo(b.entryPath);
            }
        });

        // Duplicates: serials that appear more than once.
        Map<Integer, Integer> counts = new HashMap<>();
        for (ReadyImage r : numbered) {
            if (r.serial == null) continue;
            Integer c = counts.get(r.serial);
            counts.put(r.serial, c == null ? 1 : c + 1);
        }
        Set<Integer> dupSet = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() != null && e.getValue() > 1) dupSet.add(e.getKey());
        }
        List<Integer> dups = new ArrayList<>(dupSet);
        Collections.sort(dups);
        out.duplicateSerials.addAll(dups);

        // Missing serials between min..max (only among unique present serials).
        if (!counts.isEmpty()) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (Integer s : counts.keySet()) {
                if (s < min) min = s;
                if (s > max) max = s;
            }
            out.serialMin = min;
            out.serialMax = max;
            // Cap the missing scan so a sparse 1..1_000_000 range cannot OOM.
            if (max - min <= 100_000) {
                for (int s = min; s <= max; s++) {
                    if (!counts.containsKey(s)) out.missing.add(s);
                }
            }
        }

        out.ordered.addAll(numbered);
        out.unnumbered.addAll(unnumbered);
        out.unnumberedCount = unnumbered.size();
        out.imagesFound = numbered.size() + unnumbered.size();

        if (out.ordered.isEmpty() && out.unnumbered.isEmpty()) {
            out.error = "No valid images could be extracted.";
            cleanupDir(destDir);
        }
        return out;
    }

    /** @see ZipBatchModels#finalizeOrder */
    public static List<ReadyImage> finalizeOrder(BatchResult result,
                                                 boolean keepDuplicates,
                                                 UnnumberedPolicy unnumberedPolicy) {
        return ZipBatchModels.finalizeOrder(result, keepDuplicates, unnumberedPolicy);
    }

    // ------------------------------------------------------------------ scan

    private int scanZip(Uri uri, int zipIndex, String zipName, List<RawEntry> raw,
                        int scannedSoFar, ProgressListener listener, BatchResult out)
            throws Exception {
        int local = scannedSoFar;
        try (InputStream is = app.getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is, BUF))) {
            if (is == null) throw new IllegalStateException("Cannot open ZIP");
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (cancelled.get()) break;
                local++;
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                String name = entry.getName();
                if (name == null) { zis.closeEntry(); continue; }
                name = name.replace('\\', '/');

                // Zip-Slip probe (reject absolute / .. traversal).
                if (isUnsafeZipPath(name)) {
                    zis.closeEntry();
                    continue;
                }
                if (!SerialParser.isSupportedImage(name)) {
                    out.skippedNonImage++;
                    zis.closeEntry();
                    continue;
                }
                // Skip macOS resource forks / hidden junk images.
                String base = SerialParser.basename(name);
                if (base.startsWith(".") || name.contains("__MACOSX/")) {
                    out.skippedNonImage++;
                    zis.closeEntry();
                    continue;
                }
                long size = entry.getSize();
                if (size > MAX_ENTRY_BYTES) {
                    out.skippedNonImage++;
                    zis.closeEntry();
                    continue;
                }
                Integer serial = SerialParser.extractSerial(base);
                raw.add(new RawEntry(zipIndex, zipName, name, serial, size));
                post(listener, "Scanning files...", local, Math.max(local, zipIndex + 1), raw.size());
                zis.closeEntry();
                if (raw.size() >= MAX_IMAGES) break;
            }
        }
        return local;
    }

    // --------------------------------------------------------------- helpers

    /** @see ZipBatchModels#isUnsafeZipPath */
    public static boolean isUnsafeZipPath(String name) {
        return ZipBatchModels.isUnsafeZipPath(name);
    }

    /**
     * Builds a safe destination file under {@code destDir}. Returns null when
     * the resolved path would escape the destination (Zip Slip).
     */
    private static File safeDestFile(File destDir, String entryPath, int seq) {
        try {
            String base = SerialParser.basename(entryPath);
            // Sanitize filename: keep alnum, dash, underscore, dot.
            String safe = base.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safe.isEmpty()) safe = "img";
            // Prefix with sequence to avoid collisions across folders/ZIPs.
            String fname = String.format(Locale.US, "%05d_%s", seq, safe);
            File out = new File(destDir, fname);
            String destCanon = destDir.getCanonicalPath();
            String outCanon = out.getCanonicalPath();
            if (!outCanon.startsWith(destCanon + File.separator) && !outCanon.equals(destCanon)) {
                return null; // Zip Slip
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static long copyEntry(InputStream in, File out, long declaredSize) throws Exception {
        long limit = declaredSize > 0 ? Math.min(declaredSize, MAX_ENTRY_BYTES) : MAX_ENTRY_BYTES;
        long written = 0;
        byte[] buf = new byte[BUF];
        try (FileOutputStream fos = new FileOutputStream(out)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                written += n;
                if (written > limit) {
                    fos.close();
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    return -1;
                }
                fos.write(buf, 0, n);
            }
            fos.flush();
        }
        return written;
    }

    /** Bounds-only decode — never materializes pixels. */
    static boolean isValidImageFile(File f) {
        if (f == null || !f.isFile() || f.length() < 24) return false;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            return opts.outWidth > 0 && opts.outHeight > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void post(ProgressListener l, String stage, int done, int total, int found) {
        if (l != null) l.onProgress(new Progress(stage, done, total, found));
    }

    private static void cleanupDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) {
            //noinspection ResultOfMethodCallIgnored
            k.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}
