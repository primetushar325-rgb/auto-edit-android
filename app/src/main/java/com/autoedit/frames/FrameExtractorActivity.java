package com.autoedit.frames;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import com.autoedit.R;
import com.autoedit.ui.AeDesign;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Video Frame Extractor — fully offline, integrated into AutoEdit.
 *
 * Flow: Select Video → Interval → Frame Size/Aspect (+crop) → Format →
 * Range → GENERATE → live progress (cancelable) → frame grid (select/delete)
 * → CREATE ZIP → Save / Share / Open.
 *
 * Extraction runs in {@link FrameExtractService} (background thread, one
 * frame in memory at a time); this activity only renders state.
 */
public class FrameExtractorActivity extends Activity {
    private static final int PICK_VIDEO = 40;
    private static final int SAVE_ZIP = 41;
    private static final String TAG = "AutoEditFrames";

    // survive rotation/config change while the service keeps working
    private static String sTempDirPath;
    private static volatile boolean sRunning;

    private LinearLayout root;
    private String screen = "setup";

    // options state
    private String videoUri, videoName;
    private double videoDurSec;
    private int videoW, videoH;
    private long videoSize;
    private double interval = 5;
    /** Framing the user set with pinch/drag in the preview (spec §9, §10). */
    private float zoom = 1f, panX = 0f, panY = 0f;
    private ZoomPanView zoomView;
    private FrameUtils.Aspect aspect = FrameUtils.Aspect.R16_9;
    private FrameUtils.Crop crop = FrameUtils.Crop.CENTER;
    private int customW = 1080, customH = 1920;
    private String format = "jpg";
    private int quality = 90;
    private double startSec = 0, endSec = 0;
    private String lastError = null;

    // results
    private ArrayList<File> frames = new ArrayList<>();
    private final HashSet<String> selected = new HashSet<>();
    private File zipFile;
    private long totalFrames, framesDone;
    private double lastTimeSec;
    private long procStartMs;

    private GridView grid;
    private TextView selLabel, zipInfo;
    private LruCache<String, Bitmap> thumbCache;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            int pct = i.getIntExtra("percent", 0);
            framesDone = i.getLongExtra("framesDone", 0);
            totalFrames = i.getLongExtra("framesTotal", 0);
            lastTimeSec = i.getDoubleExtra("currentTime", 0);
            String msg = i.getStringExtra("message");
            onProgress(pct, msg);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        thumbCache = new LruCache<String, Bitmap>(16 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount() / 1024; }
        };
        if (sRunning) {
            showProcessing();
        } else {
            showSetup();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, new IntentFilter(FrameExtractService.ACTION_PROGRESS), RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, new IntentFilter(FrameExtractService.ACTION_PROGRESS));
        if (sRunning && "setup".equals(screen)) showProcessing();
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    // ===================================================================== SETUP

    private void showSetup() {
        screen = "setup";
        base();
        header();

        ScrollView sv = new ScrollView(this);
        LinearLayout col = col();
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- video
        LinearLayout vcard = AeDesign.card(this);
        vcard.addView(label("VIDEO", 12, AeDesign.MUTED, Typeface.BOLD));
        if (videoUri == null) {
            TextView pick = label("📁  Select Video", 16, AeDesign.TEXT, Typeface.BOLD);
            pick.setGravity(Gravity.CENTER);
            pick.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(16), AeDesign.ACCENT, 2));
            AeDesign.press(pick, this::pickVideo);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(64));
            plp.setMargins(0, dp(8), 0, dp(8));
            vcard.addView(pick, plp);
            vcard.addView(label("MP4, MOV, MKV, WEBM, 3GP — decoded locally on this device, never uploaded.", 11, AeDesign.MUTED, Typeface.NORMAL));
        } else {
            // Pinch-to-zoom / drag-to-pan framing preview. The extracted frames
            // use this exact window, so what is on screen is what gets saved.
            zoomView = new ZoomPanView(this);
            zoomView.setImageBitmapSafe(videoThumb());
            zoomView.setListener((z, px, py) -> { zoom = z; panX = px; panY = py; });
            vcard.addView(zoomView, new LinearLayout.LayoutParams(-1, dp(190)));
            vcard.addView(label("Pinch with two fingers to zoom, drag to reposition. Extracted frames use this framing.",
                    10, AeDesign.MUTED, Typeface.NORMAL));

            LinearLayout vrow = row();
            vrow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout vinfo = col();
            vinfo.setPadding(dp(12), 0, 0, 0);
            vinfo.addView(label(videoName, 14, AeDesign.TEXT, Typeface.BOLD));
            vinfo.addView(label("Duration: " + FrameUtils.fmtTime(videoDurSec) + "   •   " + videoW + " × " + videoH + "   •   " + FrameUtils.fmtSize(videoSize), 12, AeDesign.MUTED, Typeface.NORMAL));
            vrow.addView(vinfo, new LinearLayout.LayoutParams(0, -2, 1));
            vcard.addView(vrow);
            Button change = AeDesign.button(this, "CHANGE VIDEO", false);
            AeDesign.press(change, this::pickVideo);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(44));
            clp.setMargins(0, dp(8), 0, 0);
            vcard.addView(change, clp);
        }
        col.addView(vcard);

        // ---- interval
        col.addView(label("⏱  EXTRACT EVERY", 12, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout ivRow = rowWrap();
        double[] presets = {1, 2, 3, 4, 5, 6, 10, 15, 30, 60};
        for (double p : presets) addChip(ivRow, (long) p + "s", interval == p && !customIntervalActive(), () -> { interval = p; showSetup(); });
        addChip(ivRow, "Custom", customIntervalActive(), () -> { interval = Math.max(0.1, interval); showSetup(); });
        col.addView(ivRow);
        if (customIntervalActive()) {
            LinearLayout cRow = row();
            cRow.setGravity(Gravity.CENTER_VERTICAL);
            cRow.addView(label("Custom interval (seconds, e.g. 0.5, 2.5, 7):", 12, AeDesign.MUTED, Typeface.NORMAL), new LinearLayout.LayoutParams(0, -2, 1));
            EditText ce = new EditText(this);
            ce.setText(String.valueOf(interval));
            ce.setTextColor(AeDesign.TEXT);
            ce.setTextSize(14);
            ce.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(14), AeDesign.STROKE, 1));
            ce.setPadding(dp(10), dp(8), dp(10), dp(8));
            ce.setSingleLine(true);
            ce.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            cRow.addView(ce, new LinearLayout.LayoutParams(dp(96), -2));
            col.addView(cRow, new LinearLayout.LayoutParams(-1, -2));
            ce.setOnFocusChangeListener((v, has) -> {
                if (!has) {
                    Double d = FrameUtils.parseTime(ce.getText().toString());
                    if (d != null && d >= 0.1 && d <= 3600) { interval = d; }
                    showSetup();
                }
            });
        }

        // ---- frame size / aspect
        col.addView(label("📐  FRAME SIZE / ASPECT RATIO", 12, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout aRow = rowWrap();
        FrameUtils.Aspect[] aspects = FrameUtils.Aspect.values();
        for (FrameUtils.Aspect a : aspects) addChip(aRow, FrameUtils.aspectLabel(a), aspect == a, () -> { aspect = a; showSetup(); });
        col.addView(aRow);
        if (aspect == FrameUtils.Aspect.CUSTOM) {
            LinearLayout cRow = row();
            cRow.setGravity(Gravity.CENTER_VERTICAL);
            cRow.addView(label("Width:", 12, AeDesign.MUTED, Typeface.NORMAL));
            EditText wE = new EditText(this);
            wE.setText(String.valueOf(customW));
            styleNum(wE);
            cRow.addView(wE, new LinearLayout.LayoutParams(dp(88), -2));
            cRow.addView(label("  Height:", 12, AeDesign.MUTED, Typeface.NORMAL));
            EditText hE = new EditText(this);
            hE.setText(String.valueOf(customH));
            styleNum(hE);
            cRow.addView(hE, new LinearLayout.LayoutParams(dp(88), -2));
            col.addView(cRow, new LinearLayout.LayoutParams(-1, -2));
            wE.setOnFocusChangeListener((v, has) -> { if (!has) { int vv = parseInt(wE.getText().toString(), customW); customW = Math.min(4096, Math.max(16, vv - vv % 2)); showSetup(); } });
            hE.setOnFocusChangeListener((v, has) -> { if (!has) { int vv = parseInt(hE.getText().toString(), customH); customH = Math.min(4096, Math.max(16, vv - vv % 2)); showSetup(); } });
        }
        col.addView(label("Crop Mode (frames are cropped, never stretched)", 11, AeDesign.MUTED, Typeface.NORMAL));
        LinearLayout cRow2 = row();
        FrameUtils.Crop[] crops = FrameUtils.Crop.values();
        for (FrameUtils.Crop c : crops) addChip(cRow2, c == FrameUtils.Crop.CENTER ? "Center" : c == FrameUtils.Crop.TOP ? "Top" : c == FrameUtils.Crop.BOTTOM ? "Bottom" : "Smart", crop == c, () -> { crop = c; showSetup(); });
        col.addView(cRow2);

        // ---- format
        col.addView(label("🖼  IMAGE FORMAT", 12, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout fRow = row();
        addChip(fRow, "JPG", "jpg".equals(format), () -> { format = "jpg"; showSetup(); });
        addChip(fRow, "PNG", "png".equals(format), () -> { format = "png"; showSetup(); });
        addChip(fRow, "WEBP", "webp".equals(format), () -> { format = "webp"; showSetup(); });
        col.addView(fRow);
        if (!"png".equals(format)) {
            LinearLayout qHead = row();
            qHead.addView(label("Quality", 12, AeDesign.MUTED, Typeface.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
            TextView qv = label(quality + "%", 12, AeDesign.ACCENT, Typeface.BOLD);
            qHead.addView(qv);
            col.addView(qHead);
            SeekBar qs = new SeekBar(this);
            qs.setMax(40);
            qs.setProgress(quality - 60);
            qs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) { quality = 60 + p; qv.setText(quality + "%"); }
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) {}
            });
            col.addView(qs);
        }

        // ---- range
        col.addView(label("⏰  EXTRACTION RANGE (optional)", 12, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout rRow = row();
        rRow.setGravity(Gravity.CENTER_VERTICAL);
        rRow.addView(label("Start:", 12, AeDesign.MUTED, Typeface.NORMAL));
        EditText sE = new EditText(this);
        sE.setText(startSec <= 0 ? "00:00" : FrameUtils.fmtTime(startSec));
        styleNum(sE);
        rRow.addView(sE, new LinearLayout.LayoutParams(dp(88), -2));
        rRow.addView(label("  End:", 12, AeDesign.MUTED, Typeface.NORMAL));
        EditText eE = new EditText(this);
        eE.setText(endSec <= 0 ? "" : FrameUtils.fmtTime(endSec));
        styleNum(eE);
        rRow.addView(eE, new LinearLayout.LayoutParams(dp(88), -2));
        rRow.addView(label("  (blank = video end)", 10, AeDesign.MUTED, Typeface.NORMAL));
        col.addView(rRow, new LinearLayout.LayoutParams(-1, -2));
        sE.setOnFocusChangeListener((v, has) -> { if (!has) { Double d = FrameUtils.parseTime(sE.getText().toString()); if (d != null) startSec = d; showSetup(); } });
        eE.setOnFocusChangeListener((v, has) -> { if (!has) { Double d = FrameUtils.parseTime(eE.getText().toString()); if (d != null) endSec = d; else endSec = 0; showSetup(); } });

        // ---- estimate
        LinearLayout est = AeDesign.card(this);
        double effEnd = endSec > 0 ? endSec : videoDurSec;
        long estFrames = estimateFrames();
        int[] dims = videoW > 0 ? FrameUtils.targetDims(aspect, videoW, videoH, customW, customH) : new int[]{1920, 1080};
        long estBytes = FrameUtils.estimateBytes(dims[0], dims[1], format, quality, Math.max(1, estFrames));
        est.addView(label("Estimated Frames:  ~" + estFrames, 14, AeDesign.TEXT, Typeface.BOLD));
        est.addView(label("Estimated output:  ~" + FrameUtils.fmtSize(estBytes), 13, AeDesign.MUTED, Typeface.NORMAL));
        est.addView(label("Available storage:  " + FrameUtils.fmtSize(availableBytes()), 13, estBytes > availableBytes() ? 0xffe0b46b : AeDesign.MUTED, Typeface.NORMAL));
        if (estBytes > availableBytes()) est.addView(label("⚠ Storage may be insufficient — the process will stop safely if the device runs out of space.", 11, 0xffe0b46b, Typeface.NORMAL));
        col.addView(est);

        if (lastError != null) {
            TextView err = label("⚠ " + lastError, 12, 0xffff5a6b, Typeface.NORMAL);
            col.addView(err);
        }

        Button go = AeDesign.button(this, "🚀  GENERATE FRAMES", true);
        AeDesign.press(go, this::startExtraction);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, dp(60));
        glp.setMargins(0, dp(16), 0, dp(12));
        col.addView(go, glp);
    }

    private boolean customIntervalActive() {
        double[] presets = {1, 2, 3, 4, 5, 6, 10, 15, 30, 60};
        for (double p : presets) if (Math.abs(interval - p) < 0.001) return false;
        return true;
    }

    private long estimateFrames() {
        if (videoUri == null) return 0;
        double effEnd = endSec > 0 && endSec <= videoDurSec ? endSec : videoDurSec;
        double effStart = Math.max(0, Math.min(startSec, effEnd - 0.001));
        return (long) ((effEnd - effStart) / Math.max(0.1, interval)) + 1;
    }

    private void startExtraction() {
        lastError = null;
        if (videoUri == null) { lastError = "No video selected."; showSetup(); return; }
        if (interval < 0.1 || interval > 3600) { lastError = "Invalid interval — use 0.1 to 3600 seconds."; showSetup(); return; }
        if (aspect == FrameUtils.Aspect.CUSTOM && (customW < 16 || customH < 16 || customW > 4096 || customH > 4096)) {
            lastError = "Invalid custom size — width and height must be 16–4096."; showSetup(); return;
        }
        double effEnd = endSec > 0 ? endSec : videoDurSec;
        if (startSec < 0 || startSec >= effEnd - 0.001) { lastError = "Start time must be before End time."; showSetup(); return; }
        long estFrames = estimateFrames();
        if (estFrames > 20000) { lastError = "Too many frames (" + estFrames + ") — choose a larger interval."; showSetup(); return; }

        File dir = new File(getCacheDir(), "frames_" + System.currentTimeMillis());
        dir.mkdirs();
        sTempDirPath = dir.getAbsolutePath();
        frames.clear();
        selected.clear();
        zipFile = null;
        framesDone = 0;
        totalFrames = estFrames;
        procStartMs = System.currentTimeMillis();

        Intent i = new Intent(this, FrameExtractService.class);
        i.setAction(FrameExtractService.ACTION_START);
        i.putExtra(FrameExtractService.EXTRA_URI, videoUri);
        i.putExtra(FrameExtractService.EXTRA_INTERVAL, interval);
        i.putExtra(FrameExtractService.EXTRA_START, startSec);
        i.putExtra(FrameExtractService.EXTRA_END, endSec);
        i.putExtra(FrameExtractService.EXTRA_ASPECT, aspect.name());
        i.putExtra(FrameExtractService.EXTRA_CROP, crop.name());
        i.putExtra(FrameExtractService.EXTRA_ZOOM, zoom);
        i.putExtra(FrameExtractService.EXTRA_PAN_X, panX);
        i.putExtra(FrameExtractService.EXTRA_PAN_Y, panY);
        i.putExtra(FrameExtractService.EXTRA_OUT_W, customW);
        i.putExtra(FrameExtractService.EXTRA_OUT_H, customH);
        i.putExtra(FrameExtractService.EXTRA_FORMAT, format);
        i.putExtra(FrameExtractService.EXTRA_QUALITY, quality);
        i.putExtra(FrameExtractService.EXTRA_DIR, sTempDirPath);
        startService(i);
        sRunning = true;
        showProcessing();
    }

    // ===================================================================== PROGRESS

    private ProgressBar bar;
    private TextView pctLabel, framesLabel, timeLabel, etaLabel, procMsg;

    private void showProcessing() {
        screen = "processing";
        base();
        header();

        LinearLayout c = AeDesign.card(this);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = label("Processing Video...", 22, AeDesign.TEXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        c.addView(title);
        pctLabel = label("0%", 40, AeDesign.ACCENT, Typeface.BOLD);
        pctLabel.setGravity(Gravity.CENTER);
        c.addView(pctLabel);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        c.addView(bar, new LinearLayout.LayoutParams(-1, dp(14)));
        framesLabel = label("Frames Created: 0 / " + totalFrames, 14, AeDesign.TEXT, Typeface.BOLD);
        framesLabel.setGravity(Gravity.CENTER);
        c.addView(framesLabel);
        timeLabel = label("Current Time: 00:00", 13, AeDesign.MUTED, Typeface.NORMAL);
        timeLabel.setGravity(Gravity.CENTER);
        c.addView(timeLabel);
        etaLabel = label("", 12, AeDesign.MUTED, Typeface.NORMAL);
        etaLabel.setGravity(Gravity.CENTER);
        c.addView(etaLabel);
        procMsg = label("Starting...", 11, AeDesign.MUTED, Typeface.NORMAL);
        procMsg.setGravity(Gravity.CENTER);
        c.addView(procMsg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(16);
        root.addView(c, clp);

        Button cancel = AeDesign.button(this, "CANCEL", false);
        AeDesign.press(cancel, this::confirmCancel);
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(-1, dp(54));
        xlp.topMargin = dp(18);
        root.addView(cancel, xlp);
        root.addView(label("Extraction runs on-device in the background — you can leave this screen; progress continues.", 11, AeDesign.MUTED, Typeface.NORMAL));
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this)
                .setTitle("Cancel extraction?")
                .setMessage("Partially created frames will be deleted.")
                .setNegativeButton("Keep extracting", null)
                .setPositiveButton("Cancel", (d, w) -> {
                    Intent ci = new Intent(this, FrameExtractService.class);
                    ci.setAction(FrameExtractService.ACTION_CANCEL);
                    startService(ci);
                    toast("Cancelling...");
                }).show();
    }

    private void onProgress(int pct, String msg) {
        if (pct == 100) {
            sRunning = false;
            scanFrames();
            showDone();
            return;
        }
        if (pct < 0) {
            sRunning = false;
            lastError = msg == null ? "Extraction failed." : msg;
            showSetup();
            return;
        }
        if ("processing".equals(screen)) {
            if (pctLabel != null) pctLabel.setText(pct + "%");
            if (bar != null) bar.setProgress(pct);
            if (framesLabel != null) framesLabel.setText("Frames Created: " + framesDone + " / " + totalFrames);
            if (timeLabel != null) timeLabel.setText("Current Time: " + FrameUtils.fmtTime(lastTimeSec));
            if (procMsg != null && msg != null) procMsg.setText(msg);
            if (etaLabel != null && pct > 0) {
                long elapsed = System.currentTimeMillis() - procStartMs;
                long total = elapsed * 100L / pct;
                long remain = Math.max(0, total - elapsed);
                etaLabel.setText("Estimated remaining: " + fmtDuration(remain));
            }
        }
    }

    private String fmtDuration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ===================================================================== DONE

    private void showDone() {
        screen = "done";
        base();
        header();

        LinearLayout head = AeDesign.card(this);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = label("✅  Extraction Complete", 20, AeDesign.TEXT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        head.addView(t);
        TextView n = label(frames.size() + " Frames Created", 14, AeDesign.ACCENT, Typeface.BOLD);
        n.setGravity(Gravity.CENTER);
        head.addView(n);
        root.addView(head);

        LinearLayout gridRow = row();
        gridRow.setGravity(Gravity.CENTER_VERTICAL);
        selLabel = label("0 / " + frames.size() + " selected", 13, AeDesign.TEXT, Typeface.BOLD);
        gridRow.addView(selLabel, new LinearLayout.LayoutParams(0, -2, 1));
        addAction(gridRow, "Select All", () -> { selected.clear(); selected.addAll(names()); refreshSel(); });
        addAction(gridRow, "None", () -> { selected.clear(); refreshSel(); });
        addAction(gridRow, "Delete Sel.", () -> deleteSelected());
        root.addView(gridRow);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(6));
        grid.setHorizontalSpacing(dp(6));
        grid.setAdapter(new FrameAdapter());
        grid.setOnItemClickListener((p, v, pos, id) -> {
            String name = frames.get(pos).getName();
            if (selected.contains(name)) selected.remove(name);
            else selected.add(name);
            refreshSel();
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        Button zip = AeDesign.button(this, "📦  CREATE ZIP", true);
        AeDesign.press(zip, this::createZip);
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(-1, dp(56));
        zlp.setMargins(0, dp(10), 0, dp(6));
        root.addView(zip, zlp);

        zipInfo = label("", 12, AeDesign.MUTED, Typeface.NORMAL);
        zipInfo.setGravity(Gravity.CENTER);
        root.addView(zipInfo);

        if (zipFile != null) showZipReady();
    }

    private class FrameAdapter extends BaseAdapter {
        public int getCount() { return frames.size(); }
        public Object getItem(int p) { return frames.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int pos, View convert, ViewGroup parent) {
            FrameLayout f;
            if (convert instanceof FrameLayout) f = (FrameLayout) convert;
            else {
                f = new FrameLayout(FrameExtractorActivity.this);
                ImageView iv = new ImageView(FrameExtractorActivity.this);
                iv.setId(R.id.thumb);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                ImageView tick = new ImageView(FrameExtractorActivity.this);
                tick.setId(R.id.tick);
                tick.setImageResource(R.drawable.ic_check);
                tick.setColorFilter(0xff49A8FF);
                tick.setPadding(dp(6), dp(6), dp(6), dp(6));
                tick.setBackgroundColor(0xcc020409);
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.END);
                f.addView(tick, tlp);
            }
            File file = frames.get(pos);
            ImageView iv = f.findViewById(R.id.thumb);
            Bitmap b = thumbCache.get(file.getName());
            if (b == null) {
                b = FrameUtils.decodeSampledFile(file, 480);
                if (b != null) thumbCache.put(file.getName(), b);
            }
            iv.setImageBitmap(b);
            ImageView tick = f.findViewById(R.id.tick);
            tick.setVisibility(selected.contains(file.getName()) ? View.VISIBLE : View.GONE);
            return f;
        }
    }

    private List<String> names() {
        ArrayList<String> out = new ArrayList<>();
        for (File f : frames) out.add(f.getName());
        return out;
    }

    private void refreshSel() {
        if (selLabel != null) selLabel.setText(selected.size() + " / " + frames.size() + " selected");
        if (grid != null) grid.invalidateViews();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) { toast("Nothing selected"); return; }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + selected.size() + " frame(s)?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    for (String n : selected) {
                        File f = new File(sTempDirPath, n);
                        if (f.exists()) f.delete();
                    }
                    selected.clear();
                    scanFrames();
                    showDone();
                }).show();
    }

    private void scanFrames() {
        frames.clear();
        File dir = sTempDirPath == null ? null : new File(sTempDirPath);
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, Comparator.comparing(File::getName));
        for (File k : kids) if (k.isFile() && k.length() > 0) frames.add(k);
    }

    // ================================================================ GALLERY

    /**
     * Saves the selected frames directly into the phone Gallery (spec §23).
     *
     * Android 10+: MediaStore insert into {@code Pictures/AutoEdit} with
     * IS_PENDING cleared on success. Below that: the real public directory plus
     * a MediaScanner pass. Either way the frame is visible in the Gallery app
     * immediately — never only in app-private storage.
     */
    private void saveFramesToGallery() {
        final List<File> list = new ArrayList<>();
        for (File f : frames) if (selected.isEmpty() || selected.contains(f.getName())) list.add(f);
        if (list.isEmpty()) { toast("Select at least one frame"); return; }

        new AlertDialog.Builder(this)
                .setTitle("Save to Gallery")
                .setMessage("Save " + list.size() + " frame(s) to Pictures/AutoEdit or DCIM/AutoEdit?")
                .setPositiveButton("Pictures/AutoEdit", (d, w) -> doSaveToGallery(list, GallerySaver.Folder.PICTURES))
                .setNeutralButton("DCIM/AutoEdit", (d, w) -> doSaveToGallery(list, GallerySaver.Folder.DCIM))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doSaveToGallery(List<File> list, GallerySaver.Folder folder) {
        if (zipInfo != null) zipInfo.setText("Saving " + list.size() + " frame(s) to Gallery...");
        final int[] ok = {0};
        final String[] firstError = {null};
        final GallerySaver.Saved[] first = {null};
        new Thread(() -> {
            for (File f : list) {
                android.graphics.Bitmap bmp = null;
                try {
                    bmp = FrameUtils.decodeSampledFile(f, 4096);
                    if (bmp == null) throw new IOException("Frame could not be decoded.");
                    String base = f.getName();
                    int dot = base.lastIndexOf('.');
                    String stem = dot > 0 ? base.substring(0, dot) : base;
                    GallerySaver.Saved saved = GallerySaver.save(this, bmp, stem, format, quality, folder);
                    if (first[0] == null) first[0] = saved;
                    ok[0]++;
                } catch (Exception e) {
                    if (firstError[0] == null)
                        firstError[0] = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                } finally {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                }
            }
            runOnUiThread(() -> {
                if (ok[0] == 0) {
                    if (zipInfo != null) zipInfo.setText("❌  Save failed: " + firstError[0]);
                    toast("Save failed: " + firstError[0]);
                    return;
                }
                GallerySaver.Saved s0 = first[0];
                String msg = "✓  Saved to Gallery\n" + ok[0] + " image(s) → " + s0.folderLabel
                        + (list.size() != ok[0] ? "\n(" + (list.size() - ok[0]) + " failed)" : "");
                if (zipInfo != null) zipInfo.setText(msg);
                toast("✓ Saved " + ok[0] + " to " + s0.folderLabel);
                Log.i(TAG, "Saved to gallery uri=" + s0.uri + " name=" + s0.displayName);
            });
        }, "AutoEditGallerySave").start();
    }

    // ===================================================================== ZIP

    private void createZip() {
        if (frames.isEmpty()) { toast("No frames to zip"); return; }
        final List<File> list = new ArrayList<>();
        for (File f : frames) if (selected.isEmpty() || selected.contains(f.getName())) list.add(f);
        if (list.isEmpty()) { toast("Select at least one frame"); return; }
        zipInfo.setText("Creating ZIP...");
        new Thread(() -> {
            try {
                String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                File out = new File(getCacheDir(), "AutoEdit_Frames_" + date + ".zip");
                FrameUtils.zip(list, out);
                zipFile = out;
                runOnUiThread(this::showZipReady);
            } catch (IOException e) {
                runOnUiThread(() -> { zipInfo.setText("ZIP creation failed: " + e.getMessage()); toast("ZIP creation failed"); });
            }
        }, "AutoEditZip").start();
    }

    private void showZipReady() {
        zipInfo.setText("✅  ZIP READY\n" + zipFile.getName() + "\n" + zipFile.length() / 1024 + " KB");
        // buttons are added once by showDone; rebuild them here is simpler:
        if (root == null) return;
        // find & remove previous action row (id-tagged)
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v.getTag() != null && "zip_actions".equals(v.getTag().toString())) { root.removeView(v); break; }
        }
        LinearLayout actions = row();
        actions.setTag("zip_actions");
        Button save = AeDesign.button(this, "SAVE TO DEVICE", true);
        AeDesign.press(save, this::saveZip);
        Button gallery = AeDesign.button(this, "SAVE TO GALLERY", true);
        AeDesign.press(gallery, this::saveFramesToGallery);
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button share = AeDesign.button(this, "SHARE ZIP", false);
        AeDesign.press(share, this::shareZip);
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(0, dp(52), 1);
        shlp.leftMargin = dp(6);
        actions.addView(share, shlp);
        Button open = AeDesign.button(this, "OPEN", false);
        AeDesign.press(open, this::openZip);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(52), 1);
        olp.leftMargin = dp(6);
        actions.addView(open, olp);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout galleryRow = row();
        galleryRow.setTag("zip_actions");
        galleryRow.addView(gallery, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(galleryRow, new LinearLayout.LayoutParams(-1, -2));
    }

    private void saveZip() {
        if (zipFile == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
            startActivityForResult(i, SAVE_ZIP);
        } catch (Exception e) {
            toast("Could not open save dialog: " + e.getMessage());
        }
    }

    private void shareZip() {
        if (zipFile == null) return;
        try {
            Uri u = ZipProvider.uriFor(this, zipFile);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share ZIP"));
        } catch (Exception e) {
            toast("Could not share: " + e.getMessage());
        }
    }

    private void openZip() {
        if (zipFile == null) return;
        try {
            Uri u = ZipProvider.uriFor(this, zipFile);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/zip");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open ZIP"));
        } catch (Exception e) {
            toast("Could not open: " + e.getMessage());
        }
    }

    // ===================================================================== PICKERS / RESULTS

    private void pickVideo() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, PICK_VIDEO);
        } catch (Exception e) {
            toast("Could not open video picker");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_VIDEO && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            videoUri = u.toString();
            probeVideo(u);
            showSetup();
        }
        if (req == SAVE_ZIP && res == RESULT_OK && data != null && data.getData() != null && zipFile != null) {
            try {
                try (java.io.OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                    FrameUtils.copy(zipFile, os);
                }
                toast("ZIP saved");
            } catch (Exception e) {
                toast("Save failed: " + e.getMessage());
            }
        }
    }

    private void probeVideo(Uri u) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, u);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            videoDurSec = d == null ? 0 : Double.parseDouble(d) / 1000.0;
            videoW = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            videoH = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
        } catch (Exception e) {
            toast("This video format/codec is not supported on this device.");
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        videoName = "video";
        videoSize = 0;
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (ni >= 0 && c.getString(ni) != null) videoName = c.getString(ni);
                if (si >= 0) videoSize = c.getLong(si);
            }
        } catch (Exception ignored) {}
    }

    private Bitmap videoThumb() {
        if (videoUri == null) return null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, Uri.parse(videoUri));
            return mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private long availableBytes() {
        try {
            StatFs fs = new StatFs(getCacheDir().getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    // ===================================================================== UI helpers

    private void header() {
        LinearLayout h = row();
        h.setGravity(Gravity.CENTER_VERTICAL);
        ImageView b = AeDesign.iconButton(this, R.drawable.ic_back, "Back", false);
        AeDesign.press(b, () -> {
            if (sRunning) { toast("Extraction is running — use CANCEL to stop it"); return; }
            finish();
        });
        h.addView(b, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout t = col();
        t.addView(label("🎬 Video Frame Extractor", 20, AeDesign.TEXT, Typeface.BOLD));
        t.addView(label("Extract frames from your video automatically — 100% offline", 11, AeDesign.MUTED, Typeface.NORMAL));
        h.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(h);
    }

    private void base() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setBackgroundColor(AeDesign.BG);
        setContentView(root);
    }

    private void addChip(LinearLayout p, String s, boolean on, Runnable r) {
        TextView v = label(s, 12, AeDesign.TEXT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(AeDesign.bg(on ? 0xff12395c : AeDesign.SURFACE_2, dp(14), on ? AeDesign.ACCENT : AeDesign.STROKE, on ? 2 : 1));
        AeDesign.press(v, r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        p.addView(v, lp);
    }

    private void addAction(LinearLayout p, String s, Runnable r) {
        TextView v = label(s, 11, AeDesign.TEXT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(14), AeDesign.STROKE, 1));
        AeDesign.press(v, r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        p.addView(v, lp);
    }

    private void styleNum(EditText e) {
        e.setTextColor(AeDesign.TEXT);
        e.setTextSize(14);
        e.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(14), AeDesign.STROKE, 1));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setSingleLine(true);
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout rowWrap() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView label(String s, int sp, int color, int style) { return AeDesign.text(this, s, sp, color, style); }
    private int dp(int v) { return AeDesign.dp(this, v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
