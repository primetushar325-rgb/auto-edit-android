package com.autoedit;

import android.app.*;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import org.json.JSONObject;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.autoedit.model.*;
import com.autoedit.engine.*;
import com.autoedit.project.*;
import com.autoedit.export.*;
import com.autoedit.ui.*;
import com.autoedit.formula.CustomFormulaActivity;
import com.autoedit.frames.FrameExtractorActivity;
import com.autoedit.frames.GallerySaver;
import com.autoedit.update.UpdateActivity;
import com.autoedit.update.UpdateChecker;
import com.autoedit.update.VersionConfig;
import com.autoedit.update.SemVer;

public class MainActivity extends Activity {
    private static final int PICK_IMAGES = 10, PICK_AUDIO = 11, REQ_CUSTOM_FORMULA = 12,
            PICK_OVERLAY_IMAGE = 13, PICK_LOGO = 14;
    private static final String TAG = "AutoEditMain";

    private EditProject project;
    private ProjectStore store;
    private FormulaEngine formulas;
    private LinearLayout root;
    private TextView saveStatus;
    private String screen = "home";
    private int selected = -1;
    // Bulk selection (10, 18): multiple clips can be selected together
    private final Set<Integer> multiSelected = new HashSet<>();
    private boolean bulkSelectMode = false;

    // editor refs (rebuilt on each showEditor)
    private PreviewView preview;
    private MonitorLayout monitor;
    private TimelineRulerView ruler;
    private LinearLayout timeline;
    private TextView playLabel, metaLabel;
    private ImageView playButton;
    private LinearLayout panelHost;
    private final List<TextView> chips = new ArrayList<>();
    private final List<ImageView> junctions = new ArrayList<>();
    // Virtualization for 500–1000 clips: pool + window (no bitmaps, text-only chips)
    private static final int VIRTUAL_THRESHOLD = 150;
    private static final int VIRTUAL_WINDOW = 80;
    private int virtualStart = 0;
    private final List<TextView> chipPool = new ArrayList<>();
    private final List<ImageView> junctionPool = new ArrayList<>();
    private float[] prefixWidths = null;
    private int lastActiveChip = -1;
    private float lastFrameT = 0f;
    private int batchDur = 5;
    /** Clip index whose .transition defines the junction panel scope (-1 = selected/all). */
    private int transitionScopeClip = -1;

    // v1.8 timeline: 4 lanes (ruler / clips / waveform / overlays), shared geometry
    private HorizontalScrollView timelineScroll;
    private WaveformTrackView waveTrack;
    private OverlayTrackView ovlTrack;
    private float tlZoom = 1f;          // 0.5x..4x
    private SeekBar tlZoomSlider;
    private int resizing = -1;          // clip index currently edge-dragged
    private int selectedOverlay = -1;   // overlaysPanel selection
    private String pendingOverlayCorner = null; // corner preset awaiting a logo pick
    private final Map<String, ToolTile> tiles = new HashMap<>();

    // Bottom-sheet overlay (floats over the editor; never resizes the monitor)
    private PanelSheet sheet;
    private String selectedMotionId = null;
    private String selectedFormulaId = null;
    private EffectType selectedEffect = null;
    private TransitionType selectedTransition = null;
    // ── MASTER BORDER / FRAME ──
    private String selectedBorderPresetId = null;
    private BorderEffectConfig pendingBorder = null;
    private String borderSearch = "";
    private String borderCategory = "All";
    private boolean previewBorderActive = false;

    // --- export progress screen state (survives activity recreation; the
    //     service keeps exporting independently of the UI)
    private boolean notifPermissionAsked = false;
    private boolean exportRunning = false;
    private int lastExportPct = 0;
    private String lastExportMsg = "";
    private ExportRingView ring;
    private NeonProgressBar neonBar;
    private TextView pctBig, statusBig;

    // export completion widgets (kept for the async permission-grant refresh)
    private static final int REQ_VIDEO_PERM = 20;

    // Startup permission pass: requested one at a time from onCreate().
    private static final int REQ_STARTUP_NOTIFICATIONS = 9001;
    private static final int REQ_STARTUP_MEDIA = 9002;
    /** Set once the user has answered the startup prompts, so we never nag again. */
    private static final String PREFS = "autoedit.startup";
    private static final String KEY_STARTUP_ASKED = "startupPermsAsked";
    private ImageView completionThumb;
    private Button completionPlay, completionShare;
    private Uri completionUri;
    private String completionFileName;

    /** Preview playback of the project's audio track. */
    private MediaPlayer audioPlayer;
    private String lastExportStage = ExportStage.PREPARING.label;
    private boolean completionHasAudio = false;

    private ExportPreset draftPreset = ExportPreset.PORTRAIT_9_16;
    private int draftFps = 30;
    private int draftQuality = 1080;
    private FitMode draftFit = FitMode.FILL;

    private final ArrayDeque<String> undoStack = new ArrayDeque<>();
    private final ArrayDeque<String> redoStack = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autosave = new Runnable() { public void run() { saveProject(false); handler.postDelayed(this, 30000); } };
    private final BroadcastReceiver exportReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            int p = i.getIntExtra(ExportService.EXTRA_PERCENT, 0);
            String m = i.getStringExtra(ExportService.EXTRA_MESSAGE);
            String stageName = i.getStringExtra(ExportService.EXTRA_STAGE);
            ExportStage stage = ExportStage.PREPARING;
            if (stageName != null) { try { stage = ExportStage.valueOf(stageName); } catch (Exception ignored) {} }
            String uri = i.getStringExtra(ExportService.EXTRA_URI);
            String name = i.getStringExtra(ExportService.EXTRA_DISPLAY_NAME);
            boolean hasAudio = i.getBooleanExtra(ExportService.EXTRA_HAS_AUDIO, false);
            // The service hands us the FINAL published MediaStore URI, so the
            // completion screen never has to guess which file was written.
            if (uri != null) { completionUri = Uri.parse(uri); completionFileName = name; completionHasAudio = hasAudio; }
            // -3 is the service telling us "nothing is running". Drop a stale
            // progress screen rather than showing an error for an export that
            // already finished.
            if (p == -3) {
                exportRunning = false;
                if ("exporting".equals(screen)) showEditor();
                return;
            }
            updateExportProgress(p, stage, m);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { WindowCompat.setDecorFitsSystemWindows(getWindow(), false); } catch (Exception ignored) {}
        store = new ProjectStore(this);
        formulas = new FormulaEngine();
        project = store.load();
        draftPreset = project.exportPreset;
        draftFps = project.fps;
        draftFit = project.fitMode;
        if (savedInstanceState != null) {
            // Activity was recreated (rotation/config): restore export state.
            exportRunning = savedInstanceState.getBoolean("exportRunning", false);
            lastExportPct = savedInstanceState.getInt("exportPct", 0);
            lastExportMsg = savedInstanceState.getString("exportMsg", "");
        }
        if (exportRunning) showExportProgressScreen(); else showHome();
        handler.postDelayed(autosave, 30000);
        runUpdateCheck();
        // Ask for everything the app needs up front, one prompt at a time,
        // instead of interrupting the user later in the middle of a task.
        requestStartupPermissions();
    }

    // ------------------------------------------------- startup permissions

    /**
     * Requests the app's runtime permissions once, serially, on first launch.
     *
     * <p>Android shows one dialog at a time and only answers through
     * {@link #onRequestPermissionsResult}, so this hands out the first missing
     * permission and {@code onRequestPermissionsResult} calls it again to hand
     * out the next. That keeps the prompts sequential rather than stacking.
     *
     * <p>It runs only until the user has answered: once every prompt in the
     * list has been shown, {@link #KEY_STARTUP_ASKED} is persisted and later
     * launches stay silent. A denial is never fatal - the affected feature
     * simply stays off (no export-progress notification, no thumbnail refresh)
     * and the rest of the editor works normally.
     */
    private void requestStartupPermissions() {
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_STARTUP_ASKED, false)) return;
        requestNextStartupPermission();
    }

    /** Hands out the next un-granted startup permission, or marks the pass done. */
    private void requestNextStartupPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED && !notifPermissionAsked) {
                    notifPermissionAsked = true;
                    toast("Allow notifications to see export progress in the background");
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                            REQ_STARTUP_NOTIFICATIONS);
                    return;
                }
                if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_VIDEO},
                            REQ_STARTUP_MEDIA);
                    return;
                }
            } else if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQ_STARTUP_MEDIA);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "Startup permission request failed", e);
        }
        // Nothing left to ask for (all granted, or all answered).
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STARTUP_ASKED, true).apply();
    }

    // ------------------------------------------------- update system (mandatory)

    private static long lastUpdateCheckMs = 0;
    /** versionCode the optional dialog was last shown for, so it never nags. */
    private static int lastOptionalOffer = -1;

    /** Offers the optional update at most once per version per process. */
    private boolean updateOfferedFor(int code) {
        if (lastOptionalOffer == code) return false;
        lastOptionalOffer = code;
        return true;
    }

    /** Checks remote version.json. Offline/cached rules live in UpdateChecker;
     *  only a version BELOW minimumSupportedVersionCode opens the blocking
     *  mandatory update screen — everything else continues normally. */
    private void runUpdateCheck() {
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheckMs < 10 * 60 * 1000L) return; // throttle: 10 min
        lastUpdateCheckMs = now;
        UpdateChecker.checkAsync(this, (cfg, fromCache) -> {
            if (cfg == null) return; // never reached + no cache → open normally
            int local = UpdateChecker.localVersionCode(this);
            // Blocking: below the minimum supported version (spec §30).
            // Non-blocking: a newer version exists but the current one still
            // works — the user gets UPDATE NOW / LATER and editing continues.
            boolean mandatory = local < cfg.minimumSupportedVersionCode;
            boolean newer = local < cfg.latestVersionCode
                    || SemVer.isNewer(cfg.latestVersionName, UpdateChecker.localVersionName(this));
            if (!mandatory && !newer) return;
            if (!mandatory && !updateOfferedFor(cfg.latestVersionCode)) return;
            Intent i = new Intent(this, UpdateActivity.class);
            i.putExtra(UpdateActivity.EXTRA_LATEST_CODE, cfg.latestVersionCode);
            i.putExtra(UpdateActivity.EXTRA_LATEST_NAME, cfg.latestVersionName);
            i.putExtra(UpdateActivity.EXTRA_MIN_CODE, cfg.minimumSupportedVersionCode);
            i.putExtra(UpdateActivity.EXTRA_DOWNLOAD_URL, cfg.downloadUrl);
            i.putExtra(UpdateActivity.EXTRA_OPTIONAL, !mandatory);
            if (!cfg.releaseNotes.isEmpty()) i.putStringArrayListExtra(UpdateActivity.EXTRA_NOTES, new ArrayList<>(cfg.releaseNotes));
            startActivity(i);
        });
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putBoolean("exportRunning", exportRunning);
        b.putInt("exportPct", lastExportPct);
        b.putString("exportMsg", lastExportMsg);
    }

    @Override protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(exportReceiver, new IntentFilter(ExportService.ACTION_PROGRESS), RECEIVER_NOT_EXPORTED);
        else registerReceiver(exportReceiver, new IntentFilter(ExportService.ACTION_PROGRESS));
        runUpdateCheck(); // re-check when the app comes back (network may be available now)
        queryExportState();
    }

    /**
     * Asks the export service what it is doing right now (spec §3).
     *
     * The export runs in a foreground service, which outlives this activity. So
     * when the user minimises the app mid-export and opens it again, the
     * activity's own {@code exportRunning} flag is stale - it only survives a
     * configuration change, not a fresh launch. Querying the service is the only
     * reliable way to know, and it must NOT restart the export: {@code
     * ACTION_QUERY} only re-broadcasts current progress.
     */
    private void queryExportState() {
        try {
            Intent q = new Intent(this, ExportService.class);
            q.setAction(ExportService.ACTION_QUERY);
            startService(q);
        } catch (Exception e) {
            // Service unavailable - fall back to the service's own snapshot.
            Log.w(TAG, "Could not query export state", e);
        }
        if (ExportService.sRunning && !"exporting".equals(screen)) {
            exportRunning = true;
            showExportProgressScreen();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(exportReceiver); } catch (Exception ignored) {}
        releaseAudio();
        saveProject(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(autosave);
        releaseAudio();
    }

    @Override public void onBackPressed() {
        if ("exporting".equals(screen)) {
            if (exportRunning) confirmCancelExport();
            else showEditor();
            return;
        }
        if (sheet != null && sheet.isShowing()) { sheet.dismiss(); clearActiveTool(); return; }
        if ("editor".equals(screen)) showHome();
        else if ("create".equals(screen) || "export".equals(screen) || "settings".equals(screen)) showEditor();
        else if ("prompts".equals(screen)) showHome();
        else super.onBackPressed();
    }

    private void confirmCancelExport() {
        new AlertDialog.Builder(this)
                .setTitle("Export in progress")
                .setMessage("Are you sure you want to stop exporting? The partial file will be deleted.")
                .setPositiveButton("Continue Export", null)
                .setNegativeButton("Cancel Export", (d, w) -> {
                    Intent ci = new Intent(this, ExportService.class);
                    ci.setAction(ExportService.ACTION_CANCEL);
                    startService(ci);
                    toast("Cancelling export...");
                })
                .show();
    }

    // ---------------------------------------------------------------- layout

    private void base() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackgroundColor(AeDesign.BG);
        applySystemInsets(root);
        setContentView(root);
    }

    /** Status bar / nav bar / notch via WindowInsetsCompat — no hardcoded heights. */
    private void applySystemInsets(View v) {
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            Insets nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            Insets dc = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            int top = Math.max(sb.top, dc.top);
            int bottom = Math.max(nb.bottom, dc.bottom);
            int left = Math.max(sb.left, Math.max(nb.left, dc.left));
            int right = Math.max(sb.right, Math.max(nb.right, dc.right));
            view.setPadding(dp(16) + left, dp(14) + top, dp(16) + right, dp(14) + bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        // Also apply bottom inset to timeline if present (keeps controls above nav bar)
        ViewCompat.requestApplyInsets(v);
    }

    // ---------------------------------------------------------------- home

    private void showHome() {
        screen = "home";
        base();
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_autoedit_alpha); // transparent logo (background keyed out)
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);      // aspect-preserved, never stretched
        logo.setAdjustViewBounds(true);
        header.addView(logo, new LinearLayout.LayoutParams(dp(66), dp(48)));
        LinearLayout titles = col();
        titles.addView(label("Auto-Edit", 30, AeDesign.TEXT, Typeface.BOLD));
        titles.addView(label("Create. Edit. Export.", 14, AeDesign.MUTED, Typeface.NORMAL));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView gear = AeDesign.iconButton(this, R.drawable.ic_settings, "Settings", false);
        AeDesign.press(gear, () -> showSettings());
        header.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header);

        Button create = AeDesign.button(this, "+ Create Project", true);
        AeDesign.press(create, () -> showCreateProject(false));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(62));
        cp.setMargins(0, dp(22), 0, dp(20));
        root.addView(create, cp);

        root.addView(label("Recent Projects", 20, AeDesign.TEXT, Typeface.BOLD));
        java.util.List<EditProject> allProjects = store.loadAll();
        if (allProjects.isEmpty()) {
            if (project.clips.isEmpty()) emptyState(); else projectCard(project);
        } else {
            for (EditProject p : allProjects) projectCard(p);
            boolean currentInList = false;
            for (EditProject x : allProjects) if (x.id.equals(project.id)) { currentInList = true; break; }
            if (project.clips.isEmpty() && !currentInList) {
                LinearLayout hint = AeDesign.card(this);
                hint.setGravity(Gravity.CENTER);
                TextView t = label("Current draft is empty — create a new project or add images", 12, AeDesign.MUTED, Typeface.NORMAL);
                t.setGravity(Gravity.CENTER);
                hint.addView(t);
                root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        // ---- Prompt Library entry (infrastructure per master task Part 13) ----
        LinearLayout promptCard = AeDesign.card(this);
        LinearLayout prow = row();
        prow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView picon = new ImageView(this);
        picon.setImageResource(R.drawable.ic_formula);
        picon.setColorFilter(AeDesign.ACCENT);
        picon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picon.setPadding(dp(8), dp(8), dp(8), dp(8));
        picon.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(16), AeDesign.STROKE, 1));
        prow.addView(picon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout pinfo = col();
        pinfo.setPadding(dp(12), 0, 0, 0);
        pinfo.addView(label("Prompts", 17, AeDesign.TEXT, Typeface.BOLD));
        pinfo.addView(label("Prompt name • description • preview • formula", 12, AeDesign.MUTED, Typeface.NORMAL));
        prow.addView(pinfo, new LinearLayout.LayoutParams(0, -2, 1));
        Button popen = AeDesign.button(this, "OPEN", false);
        AeDesign.press(popen, () -> showPrompts());
        prow.addView(popen, new LinearLayout.LayoutParams(-2, dp(44)));
        promptCard.addView(prow);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(0, dp(14), 0, 0);
        root.addView(promptCard, plp);

        // ---- Video Frame Extractor entry ----
        LinearLayout frameCard = AeDesign.card(this);
        LinearLayout frow = row();
        frow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ficon = new ImageView(this);
        ficon.setImageResource(R.drawable.ic_images);
        ficon.setColorFilter(AeDesign.ACCENT);
        ficon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ficon.setPadding(dp(8), dp(8), dp(8), dp(8));
        ficon.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(16), AeDesign.STROKE, 1));
        frow.addView(ficon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout finfo = col();
        finfo.setPadding(dp(12), 0, 0, 0);
        finfo.addView(label("🎬 Video Frame Extractor", 17, AeDesign.TEXT, Typeface.BOLD));
        finfo.addView(label("Extract frames from your video automatically — 100% offline", 12, AeDesign.MUTED, Typeface.NORMAL));
        frow.addView(finfo, new LinearLayout.LayoutParams(0, -2, 1));
        Button fopen = AeDesign.button(this, "OPEN", true);
        AeDesign.press(fopen, () -> {
            try {
                startActivity(new Intent(this, FrameExtractorActivity.class));
            } catch (Exception e) {
                Log.e(TAG, "Frame extractor failed", e);
                toast("Could not open Frame Extractor");
            }
        });
        frow.addView(fopen, new LinearLayout.LayoutParams(-2, dp(44)));
        frameCard.addView(frow);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.setMargins(0, dp(10), 0, 0);
        root.addView(frameCard, flp);
    }

    /** Prompt Library screen. Schema + storage are live; the library starts
     *  empty (no fake prompts) — entries plug in later without rework. */
    private void showPrompts() {
        screen = "prompts";
        base();
        addHeader("Prompt Library", "Saved prompts with name, description, preview and formula", () -> showHome());
        List<PromptItem> prompts = PromptStore.all(this);
        if (prompts.isEmpty()) {
            LinearLayout c = AeDesign.card(this);
            c.setGravity(Gravity.CENTER);
            TextView t = label("No prompts yet", 20, AeDesign.TEXT, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            c.addView(t);
            TextView s = label("Prompts can bundle a name, description, preview image and an associated formula. The library is ready — entries will be added in an upcoming update.", 13, AeDesign.MUTED, Typeface.NORMAL);
            s.setGravity(Gravity.CENTER);
            c.addView(s);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
            lp.setMargins(0, dp(20), 0, 0);
            root.addView(c, lp);
            return;
        }
        ScrollView sv = new ScrollView(this);
        LinearLayout col = col();
        for (PromptItem p : prompts) {
            LinearLayout card = AeDesign.card(this);
            card.addView(label(p.name, 16, AeDesign.TEXT, Typeface.BOLD));
            if (p.description != null && !p.description.isEmpty()) card.addView(label(p.description, 13, AeDesign.MUTED, Typeface.NORMAL));
            if (p.formulaId != null) card.addView(label("Associated formula: " + p.formulaId, 12, AeDesign.ACCENT, Typeface.NORMAL));
            if (p.action != null) card.addView(label("Action: " + p.action, 12, AeDesign.MUTED, Typeface.NORMAL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(8), 0, dp(4));
            col.addView(card, lp);
        }
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void emptyState() {
        LinearLayout c = AeDesign.card(this);
        c.setGravity(Gravity.CENTER);
        TextView icon = label("No clips yet", 22, AeDesign.TEXT, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        c.addView(icon);
        TextView b = label("Create your first video from images and make it move.", 14, AeDesign.MUTED, Typeface.NORMAL);
        b.setGravity(Gravity.CENTER);
        c.addView(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.setMargins(0, dp(20), 0, 0);
        root.addView(c, lp);
    }

    private void projectCard(EditProject p) {
        LinearLayout card = AeDesign.card(this);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        // thumbnail preview: try to load first clip bitmap sampled
        ImageView thumbIv = new ImageView(this);
        TextView thumbFallback = label(String.format(Locale.US, "%d", p.clips.size()), 26, AeDesign.ACCENT, Typeface.BOLD);
        thumbFallback.setGravity(Gravity.CENTER);
        thumbFallback.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(18), AeDesign.STROKE, 1));
        FrameLayout thumbBox = new FrameLayout(this);
        thumbBox.addView(thumbIv, new FrameLayout.LayoutParams(dp(86), dp(72)));
        thumbBox.addView(thumbFallback, new FrameLayout.LayoutParams(dp(86), dp(72)));
        // try load thumb
        if (!p.clips.isEmpty() && p.clips.get(0).uri != null) {
            try {
                android.graphics.Bitmap bm = null;
                try (java.io.InputStream is = getContentResolver().openInputStream(android.net.Uri.parse(p.clips.get(0).uri))) {
                    if (is != null) {
                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                        opts.inSampleSize = 4;
                        bm = android.graphics.BitmapFactory.decodeStream(is, null, opts);
                    }
                } catch (Exception ignored) {}
                if (bm != null) {
                    thumbIv.setImageBitmap(bm);
                    thumbIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    thumbFallback.setVisibility(View.GONE);
                }
            } catch (Exception ignored) {}
        }
        top.addView(thumbBox, new LinearLayout.LayoutParams(dp(86), dp(72)));
        LinearLayout info = col();
        info.setPadding(dp(14), 0, 0, 0);
        info.addView(label(p.name, 19, AeDesign.TEXT, Typeface.BOLD));
        info.addView(label(p.clips.size() + " clips • " + fmt(p.totalDurationSec()) + " • " + p.width + "×" + p.height + " • " + p.fitMode.label, 13, AeDesign.MUTED, Typeface.NORMAL));
        String lm = android.text.format.DateFormat.format("MMM dd, HH:mm", new java.util.Date(p.lastModified)).toString();
        info.addView(label("Modified: " + lm + " • " + (p.hasAudio() ? "audio ✓" : "no audio"), 12, 0xff6f8ca4, Typeface.NORMAL));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView more = AeDesign.iconButton(this, R.drawable.ic_settings, "Project menu", false);
        AeDesign.press(more, () -> projectMenu(p));
        top.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        card.addView(top);
        Button cont = AeDesign.button(this, p.id.equals(project.id) ? "Continue Editing" : "Open Project", true);
        AeDesign.press(cont, () -> {
            project = p;
            // ensure current points to this project
            saveProject(true);
            showEditor();
        });
        LinearLayout.LayoutParams lpbtn = new LinearLayout.LayoutParams(-1, dp(52));
        lpbtn.setMargins(0, dp(16), 0, 0);
        card.addView(cont, lpbtn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        root.addView(card, lp);
    }

    private void projectMenu(EditProject p) {
        String[] ops = {"Rename", "Duplicate", "Delete", "Open", "Project Settings"};
        new AlertDialog.Builder(this).setTitle(p.name).setItems(ops, (d, w) -> {
            if (w == 0) renameProject(p);
            if (w == 1) { store.duplicateProject(p.id); showHome(); }
            if (w == 2) {
                new AlertDialog.Builder(this).setTitle("Delete project?").setMessage("Delete \"" + p.name + "\" ? This cannot be undone.")
                    .setPositiveButton("Delete", (dd, ww) -> {
                        store.deleteProject(p.id);
                        if (p.id.equals(project.id)) { project = new EditProject(); saveProject(true); }
                        showHome();
                    }).setNegativeButton("Cancel", null).show();
            }
            if (w == 3) { project = store.loadProject(p.id); if (project == null) project = p; saveProject(true); showEditor(); }
            if (w == 4) { project = store.loadProject(p.id); if (project != null) { saveProject(true); showCreateProject(true); } }
        }).show();
    }

    private void projectMenu() { projectMenu(project); }

    private void renameProject(EditProject p) {
        final EditText e = new EditText(this);
        e.setText(p.name);
        new AlertDialog.Builder(this).setTitle("Rename project").setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    String nn = e.getText().toString().trim();
                    if (!nn.isEmpty()) {
                        store.renameProject(p.id, nn);
                        if (p.id.equals(project.id)) project.name = nn;
                        showHome();
                    }
                }).show();
    }

    private void renameProject() { renameProject(project); }

    // ---------------------------------------------------------------- create

    private void showCreateProject(boolean settingsOnly) {
        screen = "create";
        base();
        addHeader("Create Project", "Setup canvas, resolution and frame rate", () -> showHome());
        root.addView(label("Aspect Ratio", 18, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout ratios = rowWrap();
        addRatio(ratios, ExportPreset.PORTRAIT_9_16, "9:16\nReels / Shorts");
        addRatio(ratios, ExportPreset.LANDSCAPE_16_9, "16:9\nYouTube");
        addRatio(ratios, ExportPreset.SQUARE_1_1, "1:1\nSquare");
        addRatio(ratios, ExportPreset.PORTRAIT_4_5, "4:5\nInstagram");
        addRatio(ratios, ExportPreset.CLASSIC_4_3, "4:3\nClassic");
        root.addView(ratios);
        root.addView(label("Resolution", 18, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout res = row();
        addChoice(res, "720p", draftQuality == 720, () -> draftQuality = 720);
        addChoice(res, "1080p", draftQuality == 1080, () -> draftQuality = 1080);
        addChoice(res, "4K", draftQuality == 2160, () -> draftQuality = 2160);
        root.addView(res);
        root.addView(label("FPS", 18, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout fps = row();
        addChoice(fps, "24 FPS", draftFps == 24, () -> draftFps = 24);
        addChoice(fps, "30 FPS", draftFps == 30, () -> draftFps = 30);
        addChoice(fps, "60 FPS", draftFps == 60, () -> draftFps = 60);
        root.addView(fps);
        Button create = AeDesign.button(this, settingsOnly ? "Apply Settings" : "Create Project", true);
        AeDesign.press(create, () -> { if (!settingsOnly) project = new EditProject(); applyDraftToProject(); saveProject(true); showEditor(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58));
        lp.setMargins(0, dp(20), 0, 0);
        root.addView(create, lp);
    }

    private void addRatio(LinearLayout parent, ExportPreset preset, String text) {
        TextView v = label((draftPreset == preset ? "✓ " : "") + text, 14, AeDesign.TEXT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(AeDesign.bg(draftPreset == preset ? 0xff102D4A : AeDesign.SURFACE, dp(20), draftPreset == preset ? AeDesign.ACCENT : AeDesign.STROKE, 2));
        AeDesign.press(v, () -> { draftPreset = preset; showCreateProject(false); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(106), dp(92));
        lp.setMargins(dp(5), dp(8), dp(5), dp(8));
        parent.addView(v, lp);
    }

    private void applyDraftToProject() {
        int h = draftQuality;
        int w = Math.round(h * draftPreset.width / (float) draftPreset.height);
        if (draftPreset == ExportPreset.PORTRAIT_9_16) { w = 1080; h = draftQuality == 2160 ? 3840 : 1920; }
        else if (draftPreset == ExportPreset.SQUARE_1_1) { w = h = draftQuality; }
        else if (draftPreset == ExportPreset.PORTRAIT_4_5) { w = 1080; h = 1350; }
        else if (draftPreset == ExportPreset.CLASSIC_4_3) { w = 1440; h = 1080; }
        project.exportPreset = draftPreset;
        project.width = w;
        project.height = h;
        project.fps = draftFps;
        project.fitMode = draftFit;
    }

    // ---------------------------------------------------------------- editor

    private void showEditor() {
        screen = "editor";
        base();
        tiles.clear();
        transitionScopeClip = -1;

        // --- header: back | title+save | undo | redo | EXPORT
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = AeDesign.iconButton(this, R.drawable.ic_back, "Back", false);
        AeDesign.press(back, () -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout title = col();
        title.addView(label(project.name, 17, AeDesign.TEXT, Typeface.BOLD));
        saveStatus = label("Saved", 11, 0xff7ce0a2, Typeface.NORMAL);
        title.addView(saveStatus);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView undo = AeDesign.iconButton(this, R.drawable.ic_undo, "Undo", false);
        AeDesign.press(undo, () -> undo());
        header.addView(undo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView redo = AeDesign.iconButton(this, R.drawable.ic_redo, "Redo", false);
        AeDesign.press(redo, () -> redo());
        header.addView(redo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button export = AeDesign.button(this, "EXPORT", true);
        AeDesign.press(export, () -> showExportScreen());
        header.addView(export, new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(header);

        // --- monitor: dedicated large preview surface (BUGFIX: was collapsible / tiny)
        // Uses FrameComposer directly (preview == export) and respects canvas ratio
        // via MonitorLayout. Height is fixed from ratio & screen width so inside
        // vertical scroll it stays large and ratio-correct (not UNSPECIFIED-collapsed).
        monitor = new MonitorLayout(this);
        monitor.setPadding(dp(8), dp(8), dp(8), dp(8));
        monitor.setBackground(AeDesign.bg(0xff03070d, dp(22), 0x22334a68, 1));
        float ratio = project.width / (float) Math.max(1, project.height);
        monitor.setRatio(ratio);
        preview = new PreviewView(this);
        preview.project = project;
        monitor.addView(preview, new MonitorLayout.LayoutParams(-1, -1));
        int monH = calcMonitorHeight(ratio);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, monH);
        mlp.setMargins(0, dp(10), 0, dp(8));
        root.addView(monitor, mlp);

        // --- transport: play/pause | moving time | meta
        LinearLayout player = row();
        player.setGravity(Gravity.CENTER_VERTICAL);
        playButton = AeDesign.iconButton(this, R.drawable.ic_play, "Play", true);
        AeDesign.press(playButton, () -> togglePlay());
        player.addView(playButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        playLabel = label("00:00 / " + fmt(project.totalDurationSec()), 16, AeDesign.TEXT, Typeface.BOLD);
        playLabel.setPadding(dp(12), 0, 0, 0);
        player.addView(playLabel);
        metaLabel = label(project.clips.size() + " clips \u2022 " + project.fps + " FPS \u2022 " + project.fitMode.label, 12, AeDesign.MUTED, Typeface.NORMAL);
        metaLabel.setPadding(dp(10), 0, 0, 0);
        player.addView(metaLabel, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(player, new LinearLayout.LayoutParams(-1, -2));

        // --- outer vertical scroll: timeline + tools + clip/adjust panels
        // BUGFIX root-cause: root LinearLayout was non-scrollable; only an inner
        // ScrollView around panelHost scrolled, so timeline/tools stayed fixed and
        // Duration/Clip settings below toolbar were unreachable on small screens.
        // Fix: entire lower editor (timeline, tools, panels) lives in ONE
        // NestedScrollView. Horizontal timeline gestures are isolated via
        // requestDisallowInterceptTouchEvent so they never trigger vertical scroll.
        androidx.core.widget.NestedScrollView outerScroll = new androidx.core.widget.NestedScrollView(this);
        outerScroll.setFillViewport(true);
        outerScroll.setVerticalScrollBarEnabled(false);
        outerScroll.setClipToPadding(false);
        outerScroll.setPadding(0, 0, 0, dp(12));
        LinearLayout scrollContent = col();
        // bottom inset already on root, but add extra breathing room so last
        // panel card is not hidden behind gesture navigation
        scrollContent.setPadding(0, 0, 0, dp(8));
        outerScroll.addView(scrollContent, new FrameLayout.LayoutParams(-1, -2));
        root.addView(outerScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- timeline: 4 lanes (ruler / clips / waveform / overlays), one px-per-second geometry
        LinearLayout tbox = AeDesign.card(this);
        tbox.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout tHead = row();
        tHead.setGravity(Gravity.CENTER_VERTICAL);
        tHead.addView(iconButton(R.drawable.ic_zoom_out, () -> setTlZoom(tlZoom / 1.25f)));
        ImageView zIn = iconButton(R.drawable.ic_zoom_in, () -> setTlZoom(tlZoom * 1.25f));
        LinearLayout.LayoutParams zilp = new LinearLayout.LayoutParams(-2, -2);
        zilp.leftMargin = dp(4);
        tHead.addView(zIn, zilp);
        tlZoomSlider = new SeekBar(this);
        tlZoomSlider.setMax(100);
        tlZoomSlider.setProgress((int)((tlZoom - 0.5f)/3.5f*100));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(96), -2);
        slp.leftMargin = dp(4);
        slp.rightMargin = dp(4);
        tlZoomSlider.setContentDescription("Timeline zoom slider 0.5x to 4x");
        tHead.addView(tlZoomSlider, slp);
        tlZoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    float z = 0.5f + p / 100f * 3.5f;
                    setTlZoom(z);
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        ImageView selAll = iconButton(R.drawable.ic_copy, this::selectAllClips);
        LinearLayout.LayoutParams sall = new LinearLayout.LayoutParams(-2, -2);
        sall.leftMargin = dp(4);
        tHead.addView(selAll, sall);
        ImageView clrAll = iconButton(R.drawable.ic_close, this::clearSelection);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(-2, -2);
        clp2.leftMargin = dp(4);
        tHead.addView(clrAll, clp2);
        ImageView sp = iconButton(R.drawable.ic_split, this::splitAtPlayhead);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-2, -2);
        splp.leftMargin = dp(10);
        tHead.addView(sp, splp);
        tHead.addView(label("Split \u2022 Select All \u2022 Clear", 11, AeDesign.MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, -2, 1));
        tbox.addView(tHead, new LinearLayout.LayoutParams(-1, -2));
        ruler = new TimelineRulerView(this);
        ruler.setProject(project);
        ruler.setZoom(tlZoom);
        timelineScroll = new HorizontalScrollView(this);
        timelineScroll.setHorizontalScrollBarEnabled(false);
        // isolate horizontal timeline drag from outer vertical NestedScrollView
        timelineScroll.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) outerScroll.requestDisallowInterceptTouchEvent(true);
            if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) outerScroll.requestDisallowInterceptTouchEvent(false);
            return false;
        });
        LinearLayout scrollContentTimeline = col();
        float pad0 = TimelineRulerView.PAD_DP * getResources().getDisplayMetrics().density;
        scrollContentTimeline.setPadding((int) pad0, 0, (int) pad0, 0);
        ruler.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (TimelineRulerView.contentWidthPx(this, project, tlZoom) - 2 * pad0), dp(34)));
        timeline = row();
        waveTrack = new WaveformTrackView(this);
        ovlTrack = new OverlayTrackView(this);
        scrollContentTimeline.addView(ruler);
        scrollContentTimeline.addView(timeline, new LinearLayout.LayoutParams(-1, dp(78)));
        scrollContentTimeline.addView(waveTrack, new LinearLayout.LayoutParams(-1, dp(38)));
        scrollContentTimeline.addView(ovlTrack, new LinearLayout.LayoutParams(-1, dp(44)));
        timelineScroll.addView(scrollContentTimeline);
        tbox.addView(timelineScroll, new LinearLayout.LayoutParams(-1, dp(202)));
        // Virtual timeline: recycle chips on scroll for 500–1000 clips (keeps 80 views, not 1000)
        if (Build.VERSION.SDK_INT >= 23) {
            timelineScroll.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
                if (project != null && project.clips.size() > VIRTUAL_THRESHOLD) updateVirtualWindow();
            });
        }
        ovlTrack.setOnSelect(ix -> { selectedOverlay = ix; if (panelHost != null) overlaysPanel(); });
        LinearLayout tracks = col();
        project.migrateLegacyAudio();
        tracks.addView(trackLabel("Audio track",
                project.audioTracks.isEmpty() ? "no audio"
                        : audioTrackSummary(project.primaryAudio()),
                !project.audioTracks.isEmpty()));
        tracks.addView(trackLabel("Text track", project.texts.size() + " text block(s)", false));
        tracks.addView(trackLabel("Overlay track", project.overlays.size() + " layer(s)", !project.overlays.isEmpty()));
        tbox.addView(tracks);
        scrollContent.addView(tbox, new LinearLayout.LayoutParams(-1, -2));

        // --- tools: compact icon toolbar (every tool is real; no fakes)
        GridLayout tools = new GridLayout(this);
        tools.setColumnCount(4);
        addToolTile(tools, "images", R.drawable.ic_images, "Images", () -> imagesPanel());
        addToolTile(tools, "motion", R.drawable.ic_motion, "Motion", () -> motionPanel());
        addToolTile(tools, "formula", R.drawable.ic_formula, "Formula", () -> formulaBatchPanel());
        addToolTile(tools, "transition", R.drawable.ic_transition, "Transition", () -> transitionPanel());
        addToolTile(tools, "duration", R.drawable.ic_timer, "Duration", () -> durationBatchPanel());
        addToolTile(tools, "text", R.drawable.ic_text, "Text", () -> textStudio());
        addToolTile(tools, "layers", R.drawable.ic_layer, "Layers", () -> overlaysPanel());
        addToolTile(tools, "audio", R.drawable.ic_audio, "Audio", () -> audioPanel());
        addToolTile(tools, "canvas", R.drawable.ic_canvas, "Canvas", () -> canvasPanel());
        addToolTile(tools, "filters", R.drawable.ic_filters, "Filters", () -> filtersPanel());
        addToolTile(tools, "effects", R.drawable.ic_effects, "Effects", () -> effectsPanel());
        addToolTile(tools, "frame", R.drawable.ic_frame, "Frame", () -> framePanel());
        addToolTile(tools, "adjust", R.drawable.ic_adjust, "Adjust", () -> adjustPanel());
        addToolTile(tools, "autoedit", R.drawable.ic_autoedit, "Auto Edit", () -> autoEditPanel());
        HorizontalScrollView toolsScroll = new HorizontalScrollView(this);
        toolsScroll.setHorizontalScrollBarEnabled(false);
        toolsScroll.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) outerScroll.requestDisallowInterceptTouchEvent(true);
            if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) outerScroll.requestDisallowInterceptTouchEvent(false);
            return false;
        });
        toolsScroll.addView(tools, new FrameLayout.LayoutParams(-2, -2));
        scrollContent.addView(toolsScroll, new LinearLayout.LayoutParams(-1, -2));

        // --- panel host (no inner ScrollView — outer NestedScrollView owns vertical scroll)
        panelHost = col();
        panelHost.setPadding(0, dp(4), 0, dp(16));
        scrollContent.addView(panelHost, new LinearLayout.LayoutParams(-1, -2));

        buildTimeline(true);
        showClipPanel();
        wirePreview();
        bindAudio();
    }

    private void wirePreview() {
        lastActiveChip = -1;
        lastFrameT = 0f;
        preview.onFrame = (t, idx, total) -> {
            if (playLabel != null) playLabel.setText(fmt(t) + " / " + fmt(total));
            if (ruler != null) ruler.setTime(t);
            if (waveTrack != null) waveTrack.setPlayhead(t);
            if (ovlTrack != null) ovlTrack.setPlayhead(t);
            if (idx != lastActiveChip) highlightPlayheadChip(idx);
            // keep the playhead in view while playing (auto-scroll, v1.8)
            if (preview.playing && timelineScroll != null) {
                float d = getResources().getDisplayMetrics().density;
                float pps = TimelineRulerView.pxPerSecPx(this, tlZoom);
                float px = TimelineRulerView.PAD_DP * d + t * pps;
                int vis = timelineScroll.getWidth();
                if (vis > 0) {
                    int sx = timelineScroll.getScrollX();
                    if (px < sx + vis * 0.1f || px > sx + vis * 0.75f)
                        timelineScroll.smoothScrollTo(Math.max(0, (int) (px - vis * 0.4f)), 0);
                }
            }
            if (audioPlayer != null && audioPlayer.isPlaying() && t < lastFrameT - 1f) {
                try { audioPlayer.seekTo(0); } catch (Exception e) { Log.e(TAG, "Audio loop restart failed", e); }
            }
            lastFrameT = t;
        };
    }

    private int tileCol = 0;

    private void addToolTile(GridLayout parent, String tag, int icon, String label, Runnable onTap) {
        ToolTile t = new ToolTile(this, icon, label, onTap);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(76);
        lp.height = -2;
        lp.columnSpec = GridLayout.spec(tileCol % 4);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        tileCol++;
        parent.addView(t, lp);
        tiles.put(tag, t);
    }

    /** Tag of the tool whose sheet is currently open; null when none is. */
    private String activeToolTag = null;

    /**
     * Marks a tool active. Opening a DIFFERENT tool starts a fresh selection;
     * re-entering the SAME tool (which is what a card tap does when it rebuilds
     * the sheet to draw the selection ring) KEEPS the user's selection.
     *
     * This is the fix for "formula cards are not reliably selectable": the old
     * code cleared selectedFormulaId on every rebuild, so the card could never
     * show a selected state and APPLY always said "select a formula first".
     */
    private void openTool(String tag) {
        if (!tag.equals(activeToolTag)) resetSheetSelection();
        activeToolTag = tag;
        for (Map.Entry<String, ToolTile> e : tiles.entrySet()) e.getValue().setActive(e.getKey().equals(tag));
    }

    private void resetSheetSelection() {
        selectedMotionId = null; selectedFormulaId = null;
        selectedEffect = null; selectedTransition = null;
        selectedBorderPresetId = null;
        // keep pendingBorder for live preview until sheet dismiss clears it explicitly
        if (preview != null) preview.clearTempBorder();
    }

    // ---------------------------------------------------------------- bottom sheet
    private PanelSheet sheet() {
        if (sheet == null) sheet = new PanelSheet(this);
        return sheet;
    }

    /**
     * Opens the floating bottom sheet (the editor keeps its full size behind
     * it). Selection is NOT cleared here — {@link #openTool} owns that, so a
     * rebuild triggered by a card tap preserves what the user picked.
     */
    private void openSheet(String title) {
        PanelSheet s = sheet();
        s.setOnDismiss(() -> {
            clearActiveTool();
            resetSheetSelection();
            pendingBorder = null;
            if (preview != null) preview.clearTempBorder();
            previewBorderActive = false;
        });
        s.show();
        s.setTitle(title);
    }

    private LinearLayout sheetCardsRow(PanelSheet s) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = row();
        hsv.addView(row, new FrameLayout.LayoutParams(-2, -2));
        s.content().addView(hsv, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void sheetHint(PanelSheet s, String text) {
        s.content().addView(label(text, 12, AeDesign.MUTED, Typeface.NORMAL));
    }

    /**
     * A card with a live preview, name, category and a clear SELECTED state.
     * Tapping one is a pure UI selection — it never mutates the project
     * (spec §42: selection != application).
     */
    interface CardOnTap { void onTap(); }

    private LinearLayout previewCard(View preview, String title, String subtitle, boolean selected) {
        LinearLayout card = col();
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        FrameLayout shot = new FrameLayout(this);
        shot.addView(preview, new FrameLayout.LayoutParams(dp(112), dp(112)));
        if (selected) {
            ImageView tick = new ImageView(this);
            tick.setImageResource(R.drawable.ic_check);
            tick.setColorFilter(0xff041018);
            tick.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tick.setPadding(dp(3), dp(3), dp(3), dp(3));
            tick.setBackground(AeDesign.bg(AeDesign.ACCENT, dp(10), 0, 0));
            tick.setContentDescription("Selected");
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
            tlp.setMargins(0, dp(4), dp(4), 0);
            shot.addView(tick, tlp);
        }
        card.addView(shot, new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView nm = label(title, 12, selected ? AeDesign.ACCENT : AeDesign.TEXT, Typeface.BOLD);
        nm.setGravity(Gravity.CENTER);
        nm.setMaxLines(2);
        card.addView(nm, new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null) {
            TextView sub = label(subtitle, 10, AeDesign.MUTED, Typeface.NORMAL);
            sub.setGravity(Gravity.CENTER);
            sub.setMaxLines(1);
            card.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        card.setBackground(AeDesign.bg(selected ? 0xff102D4A : AeDesign.SURFACE, dp(18),
                selected ? AeDesign.ACCENT : AeDesign.STROKE, selected ? 2 : 1));
        if (selected) card.setElevation(dp(6));
        return card;
    }

    private void addApplyButtons(PanelSheet s, String label, Runnable applySelected, Runnable applyAll) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        boolean hasSel = selected >= 0 && selected < project.clips.size();
        if (hasSel) {
            Button sel = AeDesign.button(this, label + " CLIP " + project.clips.get(selected).index, true);
            AeDesign.press(sel, () -> { if (applySelected != null) { applySelected.run(); } });
            bar.addView(sel, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        Button all = AeDesign.button(this, label + " ALL (" + project.clips.size() + ")", !hasSel);
        AeDesign.press(all, () -> { if (applyAll != null) applyAll.run(); });
        bar.addView(all, new LinearLayout.LayoutParams(hasSel ? 0 : -1, dp(48), hasSel ? 1 : 0));
        s.applyBar().addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Close sheet after a successful apply and restore the normal editor. */
    private void afterApply(String msg) {
        if (preview != null) preview.invalidate();
        if (sheet != null) sheet.dismiss();
        clearActiveTool();
        buildTimeline(false);
        if (msg != null) toast(msg);
    }


    private void clearActiveTool() {
        activeToolTag = null;
        for (ToolTile t : tiles.values()) t.setActive(false);
    }

    // ---------------------------------------------------------------- timeline

    private TextView trackLabel(String a, String b, boolean accent) {
        TextView v = label(a + "  •  " + b, 11, accent ? AeDesign.ACCENT : AeDesign.MUTED, Typeface.NORMAL);
        v.setPadding(dp(8), dp(2), dp(8), dp(2));
        return v;
    }

    /**
     * structural=true  → rebuild all chip views (import / delete / reorder / undo)
     * structural=false → in-place width/text/style update (duration changes):
     *                    no view inflation, instant for 500–1000 clips.
     */
    /**
     * structural=true  → rebuild all chip views (import / delete / reorder / undo)
     * structural=false → in-place width/text/style update (duration changes):
     *                    no view inflation, instant for 500–1000 clips.
     * Virtual mode (clips > 150): only 80 TextViews are ever attached (pool),
     * recycled on scroll/idle — 1000 clips stay as metadata, no bitmaps.
     */
    private void buildTimeline(boolean structural) {
        if (timeline == null) return;
        project.renumber();
        boolean virtual = project.clips.size() > VIRTUAL_THRESHOLD;
        if (virtual) {
            buildPrefixWidths();
            if (structural) {
                ensureChipPool();
                // Start window at playhead or selected or scroll
                int anchor = selected >=0 ? selected : (preview != null ? Timeline.resolve(project, preview.currentTimeSec()).clipIndex : 0);
                if (anchor <0) anchor = 0;
                virtualStart = Math.max(0, Math.min(anchor - VIRTUAL_WINDOW/3, Math.max(0, project.clips.size() - VIRTUAL_WINDOW)));
                if (timelineScroll != null) {
                    // If we have a scroll position, prefer it
                    int sx = timelineScroll.getScrollX();
                    if (sx > 0) virtualStart = computeWindowStartFor(sx);
                }
                rebuildVirtualTimeline();
            } else {
                // Duration/motion change: just rebind visible chips
                updateVirtualWindow();
                for (int i = 0; i < chipPool.size(); i++) {
                    int g = virtualStart + i;
                    if (g >= project.clips.size() || g >= prefixWidths.length-1) break;
                    TextView v = chipPool.get(i);
                    if (v.getParent() == null) continue;
                    TimelineClip c = project.clips.get(g);
                    v.setText(String.format(Locale.US, "%02d\n%ds", c.index, Math.round(c.durationSec)));
                    styleChipVirtual(g, v);
                }
                refreshJunctionIconsVirtual();
            }
            if (ruler != null) ruler.setProject(project);
            if (waveTrack != null) {
                AudioTrack at = project.primaryAudio();
                waveTrack.setTrack(at);
                if (at != null) {
                    WaveformCache.ensure(this, at.uri, handler, (uri, peaks) -> {
                        AudioTrack now = project.primaryAudio();
                        if (waveTrack != null && now != null && uri.equals(now.uri)) waveTrack.setPeaks(peaks);
                    });
                }
            }
            if (ovlTrack != null) ovlTrack.setProject(project);
            applyTimelineGeometryVirtual();
            if (metaLabel != null) metaLabel.setText(project.clips.size() + " clips • " + project.fps + " FPS • " + project.fitMode.label + " • " + fmt(project.totalDurationSec()));
            if (playLabel != null) playLabel.setText(fmt(preview == null ? 0f : preview.currentTimeSec()) + " / " + fmt(project.totalDurationSec()));
            if (preview != null) preview.invalidate();
            return;
        }
        // ---- non-virtual path (≤150 clips): classic inflate-all ----
        if (structural) {
            timeline.removeAllViews();
            chips.clear();
            junctions.clear();
            for (int i = 0; i < project.clips.size(); i++) {
                TimelineClip c = project.clips.get(i);
                TextView v = label("", 10, AeDesign.TEXT, Typeface.BOLD);
                v.setGravity(Gravity.CENTER);
                v.setMinWidth(dp(28));
                final int ix = i;
                AeDesign.press(v, () -> {
                    if (bulkSelectMode || !multiSelected.isEmpty()) {
                        if (multiSelected.contains(ix)) multiSelected.remove(ix);
                        else multiSelected.add(ix);
                        if (multiSelected.size()==1) selected = new ArrayList<>(multiSelected).get(0);
                        else if (multiSelected.isEmpty()) { bulkSelectMode=false; selected=-1; }
                        else selected = ix;
                    } else {
                        selected = ix;
                        multiSelected.clear();
                    }
                    transitionScopeClip = -1;
                    if (preview != null) preview.seekTo(project.clips.get(ix).startTimeMsIn(project) / 1000f);
                    buildTimeline(false);
                    showClipPanel();
                });
                v.setOnLongClickListener(x -> { removeOrMoveDialog(ix); return true; });
                v.setOnTouchListener((x, ev) -> {
                    float vw = x.getWidth();
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        if (ev.getX() > vw - dp(12)) {
                            resizing = ix;
                            pushUndo();
                            return true;
                        }
                        return false;
                    }
                    if (resizing == ix) {
                        if (ev.getAction() == MotionEvent.ACTION_MOVE) {
                            float pps = TimelineRulerView.pxPerSecPx(this, tlZoom);
                            if (pps > 0f) project.clips.get(ix).setDurationSeconds(ev.getX() / pps);
                            applyTimelineGeometry();
                            return true;
                        }
                        if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                            resizing = -1;
                            saveProject(true);
                            buildTimeline(false);
                            return true;
                        }
                    }
                    return false;
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp((int) (c.durationSec * TimelineRulerView.VEL_DP)), dp(78));
                lp.leftMargin = dp((int) TimelineRulerView.GAP_DP);
                timeline.addView(v, lp);
                chips.add(v);
                if (i > 0) {
                    final int junctionClip = i - 1;
                    ImageView j = new ImageView(this);
                    j.setPadding(dp(6), dp(6), dp(6), dp(6));
                    j.setContentDescription("Add transition between clip " + i + " and " + (i + 1));
                    j.setElevation(dp(5));
                    AeDesign.press(j, () -> { transitionScopeClip = junctionClip; transitionPanel(); });
                    LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(dp(28), dp(28));
                    jlp.leftMargin = -dp(14);
                    jlp.rightMargin = -dp(14);
                    jlp.topMargin = (dp(84) - dp(28)) / 2;
                    timeline.addView(j, jlp);
                    junctions.add(j);
                }
            }
        }
        for (int i = 0; i < project.clips.size() && i < chips.size(); i++) {
            TimelineClip c = project.clips.get(i);
            TextView v = chips.get(i);
            v.setText(String.format(Locale.US, "%02d\n%ds", c.index, Math.round(c.durationSec)));
            styleChip(i);
        }
        if (ruler != null) ruler.setProject(project);
        if (waveTrack != null) {
            AudioTrack at = project.primaryAudio();
            waveTrack.setTrack(at);
            if (at != null) {
                WaveformCache.ensure(this, at.uri, handler, (uri, peaks) -> {
                    AudioTrack now = project.primaryAudio();
                    if (waveTrack != null && now != null && uri.equals(now.uri)) waveTrack.setPeaks(peaks);
                });
            }
        }
        if (ovlTrack != null) ovlTrack.setProject(project);
        applyTimelineGeometry();
        if (metaLabel != null) metaLabel.setText(project.clips.size() + " clips • " + project.fps + " FPS • " + project.fitMode.label + " • " + fmt(project.totalDurationSec()));
        if (playLabel != null) playLabel.setText(fmt(preview == null ? 0f : preview.currentTimeSec()) + " / " + fmt(project.totalDurationSec()));
        refreshJunctionIcons();
        if (preview != null) preview.invalidate();
    }

    // ---- virtual timeline helpers (pool + window) ----
    private void buildPrefixWidths() {
        int n = project.clips.size();
        prefixWidths = new float[n+1];
        float pps = TimelineRulerView.pxPerSecPx(this, tlZoom);
        float gap = dp((int) TimelineRulerView.GAP_DP);
        prefixWidths[0]=0;
        for (int i=0;i<n;i++) {
            float w = Math.max(dp(28), project.clips.get(i).durationSec * pps) + gap;
            // junctions have zero net width, so not added
            prefixWidths[i+1]=prefixWidths[i]+w;
        }
    }
    private void ensureChipPool() {
        if (chipPool.size() == VIRTUAL_WINDOW && junctionPool.size() == VIRTUAL_WINDOW) return;
        chipPool.clear(); junctionPool.clear();
        for (int i=0;i<VIRTUAL_WINDOW;i++) {
            TextView v = label("", 10, AeDesign.TEXT, Typeface.BOLD);
            v.setGravity(Gravity.CENTER);
            v.setMinWidth(dp(28));
            chipPool.add(v);
            ImageView j = new ImageView(this);
            j.setPadding(dp(6), dp(6), dp(6), dp(6));
            j.setElevation(dp(5));
            junctionPool.add(j);
        }
    }
    private int computeWindowStartFor(int scrollX) {
        if (prefixWidths==null || prefixWidths.length<=1) return 0;
        float pad = TimelineRulerView.PAD_DP * getResources().getDisplayMetrics().density;
        float sx = Math.max(0, scrollX - pad);
        // binary search prefix
        int lo=0, hi=project.clips.size()-1, ans=0;
        while (lo<=hi) {
            int mid=(lo+hi)/2;
            if (prefixWidths[mid] <= sx) { ans=mid; lo=mid+1; } else hi=mid-1;
        }
        ans = Math.max(0, Math.min(ans - 5, Math.max(0, project.clips.size()-VIRTUAL_WINDOW)));
        return ans;
    }
    private void rebuildVirtualTimeline() {
        if (timeline==null) return;
        timeline.removeAllViews();
        float pps = TimelineRulerView.pxPerSecPx(this, tlZoom);
        float gap = dp((int) TimelineRulerView.GAP_DP);
        int n = project.clips.size();
        int end = Math.min(n, virtualStart + VIRTUAL_WINDOW);
        // We use LinearLayout but only add window views plus left spacer to keep total width
        // Left spacer
        float leftW = prefixWidths[virtualStart];
        if (leftW > 0) {
            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams((int)leftW, dp(78)));
            timeline.addView(spacer);
        }
        for (int g=virtualStart; g<end; g++) {
            int poolIdx = g - virtualStart;
            TextView v = chipPool.get(poolIdx);
            TimelineClip c = project.clips.get(g);
            v.setText(String.format(Locale.US, "%02d\n%ds", c.index, Math.round(c.durationSec)));
            styleChipVirtual(g, v);
            final int ix = g;
            AeDesign.press(v, () -> {
                if (bulkSelectMode || !multiSelected.isEmpty()) {
                    if (multiSelected.contains(ix)) multiSelected.remove(ix); else multiSelected.add(ix);
                    if (multiSelected.size()==1) selected = new ArrayList<>(multiSelected).get(0);
                    else if (multiSelected.isEmpty()) { bulkSelectMode=false; selected=-1; } else selected=ix;
                } else { selected=ix; multiSelected.clear(); }
                transitionScopeClip=-1;
                if (preview!=null) preview.seekTo(project.clips.get(ix).startTimeMsIn(project)/1000f);
                buildTimeline(false);
                showClipPanel();
            });
            v.setOnLongClickListener(x -> { removeOrMoveDialog(ix); return true; });
            v.setOnTouchListener((x, ev) -> {
                float vw=x.getWidth();
                if (ev.getAction()==MotionEvent.ACTION_DOWN) {
                    if (ev.getX() > vw - dp(12)) { resizing=ix; pushUndo(); return true; }
                    return false;
                }
                if (resizing==ix) {
                    if (ev.getAction()==MotionEvent.ACTION_MOVE) {
                        float pp = TimelineRulerView.pxPerSecPx(this, tlZoom);
                        if (pp>0f) project.clips.get(ix).setDurationSeconds(ev.getX()/pp);
                        buildPrefixWidths();
                        applyTimelineGeometryVirtual();
                        return true;
                    }
                    if (ev.getAction()==MotionEvent.ACTION_UP || ev.getAction()==MotionEvent.ACTION_CANCEL) {
                        resizing=-1; saveProject(true); buildTimeline(false); return true;
                    }
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(dp(28), (int)(c.durationSec * pps)), dp(78));
            lp.leftMargin = (int)gap;
            // Remove if already has parent (reused)
            if (v.getParent()!=null) ((ViewGroup)v.getParent()).removeView(v);
            timeline.addView(v, lp);
            if (g > virtualStart) {
                ImageView j = junctionPool.get(poolIdx);
                boolean has = project.clips.get(g-1).transition != TransitionType.NONE;
                j.setImageResource(has ? R.drawable.ic_transition : R.drawable.ic_add);
                j.setColorFilter(has ? AeDesign.ACCENT : AeDesign.MUTED);
                j.setBackground(AeDesign.bg(has ? 0xff12395c : AeDesign.SURFACE, dp(14), has ? AeDesign.ACCENT : AeDesign.STROKE, has?2:1));
                final int jc = g-1;
                j.setContentDescription("Add transition between clip " + g + " and " + (g+1));
                AeDesign.press(j, () -> { transitionScopeClip=jc; transitionPanel(); });
                LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(dp(28), dp(28));
                jlp.leftMargin=-dp(14); jlp.rightMargin=-dp(14); jlp.topMargin=(dp(84)-dp(28))/2;
                if (j.getParent()!=null) ((ViewGroup)j.getParent()).removeView(j);
                timeline.addView(j, jlp);
            } else if (g==virtualStart && g>0) {
                // junction before first visible (from previous clip) still show if needed
                ImageView j = junctionPool.get(poolIdx);
                boolean has = project.clips.get(g-1).transition != TransitionType.NONE;
                j.setImageResource(has ? R.drawable.ic_transition : R.drawable.ic_add);
                j.setColorFilter(has ? AeDesign.ACCENT : AeDesign.MUTED);
                j.setBackground(AeDesign.bg(has ? 0xff12395c : AeDesign.SURFACE, dp(14), has ? AeDesign.ACCENT : AeDesign.STROKE, has?2:1));
                final int jc=g-1;
                AeDesign.press(j, () -> { transitionScopeClip=jc; transitionPanel(); });
                LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(dp(28), dp(28));
                jlp.leftMargin=-dp(14); jlp.rightMargin=-dp(14); jlp.topMargin=(dp(84)-dp(28))/2;
                if (j.getParent()!=null) ((ViewGroup)j.getParent()).removeView(j);
                // Insert before first chip (after left spacer, so at index 1)
                timeline.addView(j, 1, jlp);
            }
        }
        float totalW = prefixWidths[n];
        float rightW = totalW - prefixWidths[end];
        if (rightW > 0) {
            View spacerR = new View(this);
            spacerR.setLayoutParams(new LinearLayout.LayoutParams((int)rightW, dp(78)));
            timeline.addView(spacerR);
        }
    }
    private void updateVirtualWindow() {
        if (timelineScroll==null || prefixWidths==null) return;
        int sx = timelineScroll.getScrollX();
        int ns = computeWindowStartFor(sx);
        if (ns != virtualStart) {
            virtualStart = ns;
            rebuildVirtualTimeline();
        }
    }
    private void styleChipVirtual(int global, TextView v) {
        boolean sel = global==selected || multiSelected.contains(global);
        boolean bulk = multiSelected.contains(global);
        int bg = sel ? (bulk ? 0xff0f2d5a : 0xff12395c) : AeDesign.SURFACE_2;
        v.setBackground(AeDesign.bg(bg, dp(14), sel ? AeDesign.ACCENT : AeDesign.STROKE, sel?2:1));
        v.setTextColor(sel ? 0xffffffff : AeDesign.TEXT);
        // playhead highlight
        if (global==lastActiveChip && global!=selected) v.setBackground(AeDesign.bg(0xff16324f, dp(14), 0x6649A8FF, 1));
    }
    private void refreshJunctionIconsVirtual() {
        for (int i=0;i<junctionPool.size();i++) {
            int g = virtualStart + i;
            if (g<=0 || g>=project.clips.size()) continue;
            ImageView v = junctionPool.get(i);
            if (v.getParent()==null) continue;
            boolean has = project.clips.get(g-1).transition != TransitionType.NONE;
            v.setImageResource(has ? R.drawable.ic_transition : R.drawable.ic_add);
            v.setColorFilter(has ? AeDesign.ACCENT : AeDesign.MUTED);
            v.setBackground(AeDesign.bg(has ? 0xff12395c : AeDesign.SURFACE, dp(14), has ? AeDesign.ACCENT : AeDesign.STROKE, has?2:1));
        }
    }
    private void applyTimelineGeometryVirtual() {
        if (ruler==null) return;
        float d=getResources().getDisplayMetrics().density;
        float pps=TimelineRulerView.pxPerSecPx(this, tlZoom);
        float total=project.totalDurationSec();
        float pad=TimelineRulerView.PAD_DP * d;
        float laneW=TimelineRulerView.contentWidthPx(this, project, tlZoom) -2*pad;
        if (laneW<dp(20)) laneW=dp(20);
        LinearLayout.LayoutParams rlp=(LinearLayout.LayoutParams) ruler.getLayoutParams();
        rlp.width=(int)laneW; ruler.requestLayout();
        if (waveTrack!=null) waveTrack.setGeometry(pps,0f,total);
        if (ovlTrack!=null) ovlTrack.setGeometry(pps,0f,total);
        // update visible chips width
        for (int i=0;i<chipPool.size();i++) {
            int g=virtualStart+i;
            if (g>=project.clips.size()) break;
            TextView v=chipPool.get(i);
            if (v.getParent()==null) continue;
            LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams) v.getLayoutParams();
            lp.width=Math.max(dp(28), (int)(project.clips.get(g).durationSec * pps));
            v.requestLayout();
        }
        for (ImageView j: junctionPool) {
            if (j.getParent()==null) continue;
            LinearLayout.LayoutParams jlp=(LinearLayout.LayoutParams) j.getLayoutParams();
            jlp.topMargin=(dp(78)-dp(28))/2;
        }
    }


    /** Junction k sits between clip k and k+1; its state is clips[k].transition. */
    /** Junction k sits between clip k and k+1; its state is clips[k].transition. */
    private void refreshJunctionIcons() {
        if (project != null && project.clips.size() > VIRTUAL_THRESHOLD) { refreshJunctionIconsVirtual(); return; }
        for (int k = 0; k < junctions.size(); k++) {
            if (k + 1 >= project.clips.size()) continue;
            boolean has = project.clips.get(k).transition != TransitionType.NONE;
            ImageView v = junctions.get(k);
            v.setImageResource(has ? R.drawable.ic_transition : R.drawable.ic_add);
            v.setColorFilter(has ? AeDesign.ACCENT : AeDesign.MUTED);
            v.setBackground(AeDesign.bg(has ? 0xff12395c : AeDesign.SURFACE, dp(14), has ? AeDesign.ACCENT : AeDesign.STROKE, has ? 2 : 1));
        }
    }


    /** One shared px-per-second for every timeline lane (v1.8). */
    /** One shared px-per-second for every timeline lane (v1.8). */
    private void applyTimelineGeometry() {
        if (project != null && project.clips.size() > VIRTUAL_THRESHOLD) { applyTimelineGeometryVirtual(); return; }
        if (ruler == null) return;
        float d = getResources().getDisplayMetrics().density;
        float pps = TimelineRulerView.pxPerSecPx(this, tlZoom);
        float total = project.totalDurationSec();
        float pad = TimelineRulerView.PAD_DP * d;
        float laneW = TimelineRulerView.contentWidthPx(this, project, tlZoom) - 2 * pad;
        if (laneW < dp(20)) laneW = dp(20);
        if (ruler != null) {
            LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) ruler.getLayoutParams();
            rlp.width = (int) laneW;
            ruler.requestLayout();
        }
        if (waveTrack != null) waveTrack.setGeometry(pps, 0f, total);
        if (ovlTrack != null) ovlTrack.setGeometry(pps, 0f, total);
        for (TextView v : chips) {
            int idx = chips.indexOf(v);
            if (idx < 0 || idx >= project.clips.size()) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.width = Math.max(dp(28), (int) (project.clips.get(idx).durationSec * pps));
            v.requestLayout();
        }
        // keep junction icons centred on the 78dp clip lane
        for (ImageView j : junctions) {
            LinearLayout.LayoutParams jlp = (LinearLayout.LayoutParams) j.getLayoutParams();
            jlp.topMargin = (dp(78) - dp(28)) / 2;
        }
    }


    private void setTlZoom(float z) {
        tlZoom = Math.max(0.5f, Math.min(4f, z));
        if (tlZoomSlider != null) tlZoomSlider.setProgress((int)((tlZoom - 0.5f)/3.5f*100));
        if (ruler != null) ruler.setZoom(tlZoom);
        if (project != null && project.clips.size() > VIRTUAL_THRESHOLD) {
            buildPrefixWidths();
            // keep current scroll anchored
            if (timelineScroll != null) virtualStart = computeWindowStartFor(timelineScroll.getScrollX());
            rebuildVirtualTimeline();
            applyTimelineGeometryVirtual();
        } else {
            applyTimelineGeometry();
        }
    }

    /** Compact 32dp icon action button (zoom, split, layer row actions). */
    private ImageView iconButton(int icon, Runnable action) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(AeDesign.TEXT);
        iv.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(10), AeDesign.STROKE, 1));
        iv.setPadding(dp(8), dp(6), dp(8), dp(6));
        AeDesign.press(iv, action);
        return iv;
    }

    private void styleChip(int i) {
        if (project != null && project.clips.size() > VIRTUAL_THRESHOLD) {
            // virtual mode: find view in pool
            if (i < virtualStart || i >= virtualStart + chipPool.size()) return;
            TextView vv = chipPool.get(i - virtualStart);
            if (vv.getParent()==null) return;
            styleChipVirtual(i, vv);
            return;
        }
        if (i < 0 || i >= chips.size()) return;
        TextView v = chips.get(i);
        boolean sel = i == selected || multiSelected.contains(i);
        boolean bulk = multiSelected.contains(i);
        int bg = sel ? (bulk ? 0xff0f2d5a : 0xff12395c) : AeDesign.SURFACE_2;
        v.setBackground(AeDesign.bg(bg, dp(14), sel ? AeDesign.ACCENT : AeDesign.STROKE, sel ? 2 : 1));
        v.setTextColor(sel ? 0xffffffff : AeDesign.TEXT);
    }


    private void refreshSelection() {
        if (selected >= 0 && selected < chips.size()) styleChip(selected);
    }

    private void highlightPlayheadChip(int idx) {
        if (project != null && project.clips.size() > VIRTUAL_THRESHOLD) {
            if (lastActiveChip >= 0) {
                if (lastActiveChip >= virtualStart && lastActiveChip < virtualStart + chipPool.size()) {
                    TextView vv = chipPool.get(lastActiveChip - virtualStart);
                    if (vv.getParent()!=null && lastActiveChip != selected && !multiSelected.contains(lastActiveChip)) {
                        // restore normal style
                        styleChipVirtual(lastActiveChip, vv);
                    }
                }
            }
            if (idx >= virtualStart && idx < virtualStart + chipPool.size()) {
                TextView vv = chipPool.get(idx - virtualStart);
                if (vv.getParent()!=null && idx != selected && !multiSelected.contains(idx)) vv.setBackground(AeDesign.bg(0xff16324f, dp(14), 0x6649A8FF, 1));
            }
            lastActiveChip = idx;
            return;
        }
        if (lastActiveChip >= 0 && lastActiveChip < chips.size() && lastActiveChip != selected) styleChip(lastActiveChip);
        if (idx >= 0 && idx < chips.size() && idx != selected) {
            TextView v = chips.get(idx);
            v.setBackground(AeDesign.bg(0xff16324f, dp(14), 0x6649A8FF, 1));
        }
        lastActiveChip = idx;
    }


    // ---------------------------------------------------------------- panels

    private void showClipPanel() {
        if (panelHost == null) return;
        clearActiveTool();
        panelHost.removeAllViews();
        if (selected < 0 || selected >= project.clips.size()) {
            TextView hint = label("Select a clip in the timeline — or tap a tool above to edit the whole project.\nTip: long-press a clip for move / duplicate / delete.", 13, AeDesign.MUTED, Typeface.NORMAL);
            hint.setPadding(dp(6), dp(10), dp(6), dp(10));
            panelHost.addView(hint);
            return;
        }
        TimelineClip c = project.clips.get(selected);
        TextView head = label("Clip " + c.index + "  •  " + Math.round(c.durationSec) + "s  •  motion: " + (c.formula == null ? "static" : c.formula.name), 15, AeDesign.TEXT, Typeface.BOLD);
        head.setPadding(dp(6), dp(8), dp(6), dp(4));
        panelHost.addView(head);

        // Bulk hint
        if (!multiSelected.isEmpty()) {
            panelHost.addView(label(multiSelected.size() + " clips selected (blue). Duration/motion/effects below affect selection. Use Clear in timeline header to deselect.", 12, AeDesign.ACCENT, Typeface.NORMAL));
            LinearLayout bulkActs = rowWrap();
            addAction(bulkActs, "Clear Sel", this::clearSelection);
            addAction(bulkActs, "Delete Sel", this::deleteSelectedClips);
            addAction(bulkActs, "Save Sel", this::saveImagesToGallery);
            panelHost.addView(bulkActs);
        }
        panelHost.addView(label("Duration (3–8s)", 12, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout durations = row();
        int[] vals = {3, 4, 5, 6, 7, 8};
        for (int val : vals) {
            final int sec = val;
            TextView v = label(sec + "s", 13, AeDesign.TEXT, Typeface.BOLD);
            v.setGravity(Gravity.CENTER);
            boolean on = Math.round(c.durationSec) == sec;
            v.setBackground(AeDesign.bg(on ? 0xff12395c : AeDesign.SURFACE_2, dp(14), on ? AeDesign.ACCENT : AeDesign.STROKE, on ? 2 : 1));
            AeDesign.press(v, () -> {
                pushUndo();
                c.setDurationMs(sec * 1000L);
                saveProject(true);
                buildTimeline(false);
                showClipPanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
            lp.setMargins(dp(3), dp(3), dp(3), dp(6));
            durations.addView(v, lp);
        }
        panelHost.addView(durations);

        Button applyAll = AeDesign.button(this, "APPLY THIS DURATION TO ALL CLIPS", true);
        AeDesign.press(applyAll, () -> applyDurationToAll(Math.round(c.durationSec)));
        panelHost.addView(applyAll, new LinearLayout.LayoutParams(-1, dp(46)));

        panelHost.addView(label("Crop / Fit (black wedges never shown — safe transform)", 12, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout cropRow = rowWrap();
        addChoice(cropRow, project.fitMode == FitMode.FILL ? "✓ Fill Crop" : "Fill Crop", project.fitMode == FitMode.FILL, () -> { pushUndo(); project.fitMode = FitMode.FILL; saveProject(true); if (preview != null) preview.invalidate(); showClipPanel(); });
        addChoice(cropRow, project.fitMode == FitMode.FIT ? "✓ Fit Letterbox" : "Fit Letterbox", project.fitMode == FitMode.FIT, () -> { pushUndo(); project.fitMode = FitMode.FIT; saveProject(true); if (preview != null) preview.invalidate(); showClipPanel(); });
        panelHost.addView(cropRow);

        LinearLayout actions = rowWrap();
        addAction(actions, "Duplicate", () -> duplicateClip());
        addAction(actions, "Move ←", () -> moveClip(selected, -1));
        addAction(actions, "Move →", () -> moveClip(selected, 1));
        addAction(actions, "Delete", () -> deleteClip(selected));
        panelHost.addView(actions);
    }

    /** Duration tool: pick a length, then one batch operation over all clips. */
    private void durationBatchPanel() {
        openTool("duration");
        if (panelHost == null) return;
        panelHost.removeAllViews();
        panelHost.addView(label("Duration — seconds per image", 16, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout durations = row();
        int[] vals = {3, 4, 5, 6, 7, 8};
        for (int val : vals) {
            final int sec = val;
            TextView v = label(sec + "s", 14, AeDesign.TEXT, Typeface.BOLD);
            v.setGravity(Gravity.CENTER);
            boolean on = batchDur == sec;
            v.setBackground(AeDesign.bg(on ? 0xff12395c : AeDesign.SURFACE_2, dp(14), on ? AeDesign.ACCENT : AeDesign.STROKE, on ? 2 : 1));
            AeDesign.press(v, () -> { batchDur = sec; durationBatchPanel(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.setMargins(dp(3), dp(3), dp(3), dp(6));
            durations.addView(v, lp);
        }
        panelHost.addView(durations);
        panelHost.addView(label("One efficient batch state operation — near-instant even for 1000 clips.", 12, AeDesign.MUTED, Typeface.NORMAL));
        Button all = AeDesign.button(this, "APPLY " + batchDur + "s TO ALL (" + project.clips.size() + " CLIPS)", true);
        AeDesign.press(all, () -> applyDurationToAll(batchDur));
        panelHost.addView(all, new LinearLayout.LayoutParams(-1, dp(50)));
    }

    private void motionPanel() {
        openTool("motion");
        String scope = selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL " + project.clips.size() + " clips";
        PanelSheet s = sheet();
        openSheet("Motion → " + scope);
        java.util.List<Formula> motions = formulas.motions();

        for (String cat : new String[]{MotionCatalog.CAT_BASIC, MotionCatalog.CAT_CINEMATIC, MotionCatalog.CAT_PREMIUM}) {
            s.content().addView(label(cat, 13, AeDesign.MUTED, Typeface.BOLD));
            LinearLayout row = sheetCardsRow(s);
            for (Formula m : motions) {
                if (!cat.equals(m.category)) continue;
                MotionPreviewView mpv = new MotionPreviewView(this);
                mpv.setMotion(formulas.byId(m.id));
                boolean isSel = selectedMotionId != null
                        ? selectedMotionId.equals(m.id)
                        : sameFormulaId(selected >= 0 && selected < project.clips.size() ? project.clips.get(selected).formula : null, m.id);
                final String id = m.id;
                LinearLayout card = previewCard(mpv, m.name, cat, isSel);
                card.setContentDescription("Motion " + m.name + (isSel ? ", selected" : ""));
                AeDesign.tap(card, () -> {
                    selectedMotionId = id;
                    motionPanel(); // rebuild to show the selection ring
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(dp(4), dp(4), dp(4), dp(6));
                row.addView(card, lp);
            }
        }
        sheetHint(s, "Tap a card to SELECT (no change yet). Then choose APPLY. One clip plays ONE motion for its whole duration.");
        addApplyButtons(s, "APPLY TO",
                () -> { if (selectedMotionId != null) { applyFormula(selectedMotionId); afterApply("Motion applied to clip"); } else toast("Select a motion first"); },
                () -> { if (selectedMotionId != null) { applyFormulaToAll(selectedMotionId); afterApply("Motion applied to all"); } else toast("Select a motion first"); });
    }

    private void applyFormulaToAll(String id) {
        pushUndo();
        Formula f = formulaById(id);
        for (int i = 0; i < project.clips.size(); i++) project.clips.get(i).formula = f;
        saveProject(true);
        if (preview != null) preview.invalidate();
    }

    /**
     * Formula cards: each card loops a lightweight preview (same FormulaEngine
     * math as preview/export) that visually demonstrates the sequence.
     * Tap applies the WHOLE sequence — to the selected clip, or to ALL clips
     * when none is selected. "None" removes the formula.
     */
    private void formulaBatchPanel() {
        openTool("formula");
        String scope = selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL clips";
        PanelSheet s = sheet();
        openSheet("Formulas → " + scope);

        // ---- built-in patterns (cap the row height; grouped) ----
        s.content().addView(label("Formula patterns (clip i → step i % size)", 13, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout cards = sheetCardsRow(s);
        addSheetFormulaCard(cards, "00");
        for (Formula f : formulas.sequences()) addSheetFormulaCard(cards, f.id);

        // ---- custom formulas ----
        s.content().addView(label("Custom Formulas", 13, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout ccards = sheetCardsRow(s);
        addSheetNewFormulaCard(ccards);
        for (JSONObject o : CustomFormulaStore.all(this)) addSheetCustomFormulaCard(ccards, o);

        sheetHint(s, "Tap a card to SELECT. Then APPLY — to the selected clip or to ALL. A pattern repeats one motion per clip (never multiple motions in one clip). Undo-safe.");
        addApplyButtons(s, "APPLY FORMULA TO",
                () -> { if (selectedFormulaId != null) { applyFormula(selectedFormulaId); afterApply("Formula applied to clip"); } else toast("Select a formula first"); },
                () -> { if (selectedFormulaId != null) { applyFormulaToAll(selectedFormulaId); afterApply("Formula applied to all " + project.clips.size() + " clips"); } else toast("Select a formula first"); });
    }

    private boolean formulaApplied(String id) {
        if (selected >= 0 && selected < project.clips.size()) return sameFormulaId(project.clips.get(selected).formula, id);
        if (!project.clips.isEmpty()) {
            for (TimelineClip c : project.clips) if (!sameFormulaId(c.formula, id)) return false;
            return true;
        }
        return false;
    }

    private void addSheetFormulaCard(LinearLayout parent, String id) {
        Formula f = formulas.byId(id);
        FormulaPreviewView pv = new FormulaPreviewView(this);
        pv.setFormula(f);
        String sub = f.isPattern() ? f.category + " • " + f.patternSize() + "-clip" : "Single motion";
        boolean isSel = selectedFormulaId != null ? selectedFormulaId.equals(id) : formulaApplied(id);
        LinearLayout card = previewCard(pv, f.name, sub, isSel);
        card.setContentDescription("Formula " + f.name + (isSel ? ", selected" : ""));
        AeDesign.tap(card, () -> { selectedFormulaId = id; selectedMotionId = null; formulaBatchPanel(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(6));
        parent.addView(card, lp);
    }

    private void addSheetNewFormulaCard(LinearLayout parent) {
        LinearLayout card = col();
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setColorFilter(AeDesign.ACCENT);
        plus.setScaleType(ImageView.ScaleType.FIT_CENTER);
        plus.setPadding(dp(30), dp(30), dp(30), dp(30));
        card.addView(plus, new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView nm = label("+ New", 12, AeDesign.TEXT, Typeface.BOLD); nm.setGravity(Gravity.CENTER);
        card.addView(nm);
        card.setBackground(AeDesign.bg(AeDesign.SURFACE, dp(18), AeDesign.STROKE, 1));
        AeDesign.press(card, () -> { sheet.dismiss(); clearActiveTool(); openCustomFormulaLibrary(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(6));
        parent.addView(card, lp);
    }

    private void addSheetCustomFormulaCard(LinearLayout parent, JSONObject o) {
        String id = o.optString("id");
        String name = o.optString("name", "Custom");
        int steps = o.optJSONArray("steps") != null ? o.optJSONArray("steps").length()
                : (o.optJSONArray("keyframes") != null ? Math.max(1, o.optJSONArray("keyframes").length() - 1) : 1);
        FormulaPreviewView pv = new FormulaPreviewView(this);
        pv.setFormula(CustomFormulaStore.toFormula(o));
        boolean isSel = selectedFormulaId != null ? selectedFormulaId.equals(id) : formulaApplied(id);
        LinearLayout card = previewCard(pv, name, o.optString("category", "Custom") + " • " + steps + "-clip", isSel);
        card.setContentDescription("Custom formula " + name + (isSel ? ", selected" : ""));
        AeDesign.tap(card, () -> { selectedFormulaId = id; formulaBatchPanel(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(6));
        parent.addView(card, lp);
    }

    /** Opens the Custom Formula library; applying returns via onActivityResult. */
    private void openCustomFormulaLibrary() {
        try {
            Intent i = new Intent(this, CustomFormulaActivity.class);
            startActivityForResult(i, REQ_CUSTOM_FORMULA);
        } catch (Exception e) {
            Log.e(TAG, "Custom formula screen failed", e);
            toast("Could not open Custom Formulas");
        }
    }


    private boolean sameFormulaId(Formula a, String id) {
        return a != null && a.id != null && a.id.equals(id);
    }

    // --- CapCut-style transition library (v1.7) ---
    private TransitionPreset selectedPreset = null;
    private TransitionCategory transCat = TransitionCategory.TRENDING;
    private String transSearch = "";
    private boolean transLibOpen = false;
    private float transDuration = 0.5f;

    private void transitionPanel() {
        openTool("transition");
        boolean junctionScoped = transitionScopeClip >= 0 && transitionScopeClip + 1 < project.clips.size();
        String scope;
        if (junctionScoped) scope = "Junction clip " + (transitionScopeClip + 1) + " → " + (transitionScopeClip + 2);
        else scope = selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL boundaries";
        if (!transLibOpen) seedPresetSelection(junctionScoped);
        transLibOpen = true;

        PanelSheet s = sheet();
        s.content().removeAllViews();
        s.applyBar().removeAllViews();
        openSheet("Transitions → " + scope);

        // ---- search box ----
        EditText search = new EditText(this);
        search.setHint("🔍 Search — zoom, blur, glitch, 3d, flash…");
        search.setSingleLine(true);
        search.setTextColor(AeDesign.TEXT);
        search.setHintTextColor(AeDesign.MUTED);
        search.setText(transSearch);
        search.setBackground(AeDesign.bg(AeDesign.SURFACE, dp(14), AeDesign.STROKE, 1));
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
            public void afterTextChanged(android.text.Editable e) {
                transSearch = e.toString().trim();
                transCat = transSearch.isEmpty() ? TransitionCategory.TRENDING : null;
                transitionPanel();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(0, dp(2), 0, dp(8));
        s.content().addView(search, slp);

        // ---- category tabs (horizontal scroll) ----
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        for (TransitionCategory cat : TransitionCategory.values()) {
            final TransitionCategory c = cat;
            TextView tab = label(cat.label, 13, transCat == cat ? 0xff041018 : AeDesign.TEXT, Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(12), dp(8), dp(12), dp(8));
            tab.setBackground(AeDesign.bg(transCat == cat ? AeDesign.ACCENT : AeDesign.SURFACE_2, dp(16),
                    transCat == cat ? AeDesign.ACCENT : AeDesign.STROKE, 1));
            AeDesign.press(tab, () -> { transCat = c; transSearch = ""; transitionPanel(); });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.setMargins(dp(3), dp(2), dp(3), dp(6));
            tab.setLayoutParams(tlp);
            tabs.addView(tab);
        }
        tabScroll.addView(tabs);
        s.content().addView(tabScroll, new LinearLayout.LayoutParams(-1, -2));

        // ---- selected line + duration chips ----
        s.content().addView(label(selectedPreset == null ? "Selected: none — tap a card" : "Selected: " + selectedPreset.name,
                13, selectedPreset == null ? AeDesign.MUTED : AeDesign.ACCENT, Typeface.BOLD));
        HorizontalScrollView durScroll = new HorizontalScrollView(this);
        durScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout durRow = row();
        for (float d : new float[]{0.2f, 0.3f, 0.5f, 0.7f, 1.0f, 1.5f, 2.0f}) {
            final float d2 = d;
            String ds = d < 1f ? (d == 0.2f ? "0.2s" : d == 0.3f ? "0.3s" : d == 0.5f ? "0.5s" : "0.7s") : ((int) d) + "s";
            TextView dc = label(ds, 12, Math.abs(d - transDuration) < 0.02f ? 0xff041018 : AeDesign.TEXT, Typeface.BOLD);
            dc.setGravity(Gravity.CENTER);
            dc.setPadding(dp(12), dp(7), dp(12), dp(7));
            dc.setBackground(AeDesign.bg(Math.abs(d - transDuration) < 0.02f ? AeDesign.ACCENT_2 : AeDesign.SURFACE_2,
                    dp(14), AeDesign.STROKE, 1));
            AeDesign.press(dc, () -> { transDuration = clampTransDuration(d2); transitionPanel(); });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.setMargins(dp(3), dp(2), dp(3), dp(4));
            dc.setLayoutParams(dlp);
            durRow.addView(dc);
        }
        durScroll.addView(durRow);
        s.content().addView(durScroll, new LinearLayout.LayoutParams(-1, -2));

        // ---- recently used row (if any, and not already on Recent tab) ----
        if ((transCat == null || transCat != TransitionCategory.RECENT) && !recentPresets().isEmpty()) {
            s.content().addView(label("Recently used", 12, AeDesign.MUTED, Typeface.BOLD));
            LinearLayout rrow = sheetCardsRow(s);
            for (TransitionPreset p : recentPresets()) addTransitionCard(rrow, p, true);
        }

        // ---- main grid for the active category / search ----
        java.util.List<TransitionPreset> items;
        boolean isRecent = false, isFav = false;
        if (transSearch != null && !transSearch.isEmpty()) {
            items = TransitionRegistry.search(transSearch);
            s.content().addView(label("Search: " + transSearch + "  (" + items.size() + ")", 12, AeDesign.MUTED, Typeface.NORMAL));
        } else if (transCat == TransitionCategory.RECENT) {
            items = recentPresets(); isRecent = true;
            if (items.isEmpty()) s.content().addView(label("No recent transitions yet — apply one and it shows here.", 12, AeDesign.MUTED, Typeface.NORMAL));
        } else if (transCat == TransitionCategory.FAVORITES) {
            items = favoritePresets(); isFav = true;
            if (items.isEmpty()) s.content().addView(label("No favourites yet — tap ♥ on a card.", 12, AeDesign.MUTED, Typeface.NORMAL));
        } else if (transCat == TransitionCategory.TRENDING) {
            items = TransitionRegistry.trending();
        } else {
            items = TransitionRegistry.byCategory(transCat == null ? TransitionCategory.BASIC : transCat);
        }
        LinearLayout grid = sheetCardsRow(s);
        for (TransitionPreset p : items) addTransitionCard(grid, p, false);
        if (items.isEmpty() && transSearch != null && !transSearch.isEmpty())
            s.content().addView(label("No transitions match \"" + transSearch + "\".", 12, AeDesign.MUTED, Typeface.NORMAL));

        sheetHint(s, "Tap a card to SELECT (no change yet), set duration, then APPLY to this junction or to ALL. Every card is a live preview using the SAME math as export. Undo-safe.");

        // ---- apply bar: junction OR selected/all ----
        if (junctionScoped) {
            Button apply = AeDesign.button(this, "APPLY TO JUNCTION", true);
            AeDesign.press(apply, () -> applySelectedPreset(false));
            Button all = AeDesign.button(this, "APPLY TO ALL (" + project.clips.size() + ")", false);
            AeDesign.press(all, () -> applySelectedPreset(true));
            LinearLayout bar = row();
            bar.addView(apply, new LinearLayout.LayoutParams(0, dp(48), 1));
            LinearLayout.LayoutParams allp = new LinearLayout.LayoutParams(0, dp(48), 1);
            allp.leftMargin = dp(8);
            bar.addView(all, allp);
            s.applyBar().addView(bar, new LinearLayout.LayoutParams(-1, -2));
        } else {
            addApplyButtons(s, "APPLY",
                    () -> applySelectedPreset(false),
                    () -> applySelectedPreset(true));
        }
    }

    private void seedPresetSelection(boolean junctionScoped) {
        selectedPreset = null;
        try {
            int idx = junctionScoped ? transitionScopeClip : selected;
            if (idx >= 0 && idx < project.clips.size()) {
                TimelineClip c = project.clips.get(idx);
                if (c.transitionPresetId != null) selectedPreset = TransitionRegistry.byId(c.transitionPresetId);
                if (selectedPreset == null && c.transition != TransitionType.NONE)
                    for (TransitionPreset p : TransitionRegistry.all()) if (p.type == c.transition) { selectedPreset = p; break; }
                transDuration = c.transitionDurationSec;
            }
        } catch (Exception ignored) {}
    }

    private float clampTransDuration(float d) {
        float cap = Float.MAX_VALUE;
        for (TimelineClip c : project.clips) cap = Math.min(cap, c.durationSec / 2f);
        return Math.max(0.1f, Math.min(d, Math.min(cap, 2.0f)));
    }

    private java.util.List<TransitionPreset> recentPresets() {
        ArrayList<TransitionPreset> out = new ArrayList<>();
        for (String id : RecentsStore.recentIds(this, "transition")) {
            TransitionPreset p = TransitionRegistry.byId(id); // invalid ids ignored safely
            if (p != null) out.add(p);
        }
        return out;
    }
    private java.util.List<TransitionPreset> favoritePresets() {
        ArrayList<TransitionPreset> out = new ArrayList<>();
        String prefix = "transition:";
        for (String key : FavoritesStore.all(this))
            if (key.startsWith(prefix)) {
                TransitionPreset p = TransitionRegistry.byId(key.substring(prefix.length()));
                if (p != null) out.add(p);
            }
        return out;
    }

    /** One animated transition card (live preview + name + favourite). Tap = SELECT only. */
    private void addTransitionCard(LinearLayout parent, TransitionPreset p, boolean compact) {
        TransitionPreviewView tpv = new TransitionPreviewView(this);
        tpv.setTransition(p);
        boolean isSel = selectedPreset != null && selectedPreset.id.equals(p.id);
        String sub = p.category.label + (p.isTrending ? " • 🔥" : "") + (p.isNew ? " • NEW" : "");
        LinearLayout card = previewCard(tpv, p.name, sub, isSel);
        // favourite heart overlaid on the preview
        TextView heart = label(FavoritesStore.isFavorite(this, "transition", p.id) ? "♥" : "♡",
                14, FavoritesStore.isFavorite(this, "transition", p.id) ? 0xFFFFC84D : 0xFFFFFFFF, Typeface.BOLD);
        heart.setPadding(dp(6), dp(2), dp(6), dp(2));
        // heart lives at the bottom-left under the check; attach to card
        AeDesign.press(heart, () -> { FavoritesStore.toggle(this, "transition", p.id); transitionPanel(); });
        LinearLayout hl = col(); hl.setGravity(Gravity.CENTER_HORIZONTAL); hl.addView(heart);
        card.addView(hl, new LinearLayout.LayoutParams(-1, -2));
        AeDesign.tap(card, () -> { selectedPreset = p; transDuration = clampTransDuration(p.defaultDuration); transitionPanel(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(6));
        parent.addView(card, lp);
    }

    /** SELECTED → APPLY: records a recent, mutates via one undo, preview + export both use it. */
    private void applySelectedPreset(boolean toAll) {
        if (selectedPreset == null) { toast("First tap a transition to select it"); return; }
        TransitionPreset p = selectedPreset;
        float dur = clampTransDuration(transDuration);
        pushUndo();
        try {
            if (toAll) {
                for (TimelineClip c : project.clips) applyPresetTo(c, p, dur);
            } else if (transitionScopeClip >= 0 && transitionScopeClip < project.clips.size()) {
                applyPresetTo(project.clips.get(transitionScopeClip), p, dur);
            } else if (selected >= 0 && selected < project.clips.size()) {
                applyPresetTo(project.clips.get(selected), p, dur);
            } else {
                for (TimelineClip c : project.clips) applyPresetTo(c, p, dur);
            }
            RecentsStore.record(this, "transition", p.id);
            saveProject(true);
            afterApply((p.type == TransitionType.NONE ? "Transition removed" : p.name + " applied")
                    + (toAll ? " to all boundaries" : "") + " • " + dur + "s");
            transLibOpen = false;
        } catch (Exception e) {
            Log.e(TAG, "apply transition failed", e);
            toast("Transition failed — previous kept"); // never crash; keep prior state
        }
    }

    private void applyPresetTo(TimelineClip c, TransitionPreset p, float dur) {
        c.transitionPresetId = p.type == TransitionType.NONE ? null : p.id;
        c.transition = p.type;             // raw enum kept for legacy/fallback
        c.transitionDurationSec = dur;
    }

    // ---------------------------------------------------------------- layers
    //  v1.8 overlay layer system: images/logos/text above the transition,
    //  drawn by the SAME FrameComposer path in preview and export.

    private void overlaysPanel() {
        openTool("layers");
        if (panelHost == null) return;
        panelHost.removeAllViews();
        panelHost.addView(label("Layers / Overlays", 16, AeDesign.TEXT, Typeface.BOLD));

        LinearLayout grid = rowWrap();
        addAction(grid, "Add image overlay", this::pickOverlayImage);
        addAction(grid, "Add text layer", this::addTextOverlayLayer);
        panelHost.addView(grid);

        LinearLayout logos = rowWrap();
        addChoice(logos, "Logo · top-left", false, () -> addLogoOverlay("top-left"));
        addChoice(logos, "Logo · top-right", false, () -> addLogoOverlay("top-right"));
        addChoice(logos, "Logo · bottom-right", false, () -> addLogoOverlay("bottom-right"));
        panelHost.addView(logos);

        if (project.overlays.isEmpty()) {
            panelHost.addView(label("No layers yet. Add an image, a text block, or a corner logo — layers sit above the transition and are baked into the export.",
                    12, AeDesign.MUTED, Typeface.NORMAL));
            return;
        }

        for (int i = 0; i < project.overlays.size(); i++) {
            final int ix = i;
            OverlayLayer o = project.overlays.get(ix);
            boolean sel = i == selectedOverlay;
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackground(AeDesign.bg(sel ? 0xff12395c : AeDesign.SURFACE_2, dp(12),
                    sel ? AeDesign.ACCENT : AeDesign.STROKE, sel ? 2 : 1));
            item.setPadding(dp(10), dp(7), dp(10), dp(7));
            String title = (o.kind == OverlayLayer.Kind.TEXT
                    ? (o.text == null || o.text.isEmpty() ? "text" : o.text)
                    : (o.hidden ? "image (hidden)" : "image"));
            item.addView(label(title.length() > 20 ? title.substring(0, 20) + "…" : title, 13, AeDesign.TEXT, Typeface.BOLD),
                    new LinearLayout.LayoutParams(0, -2, 1));
            item.setOnClickListener(v -> { selectedOverlay = ix; if (ovlTrack != null) ovlTrack.setSelected(ix); overlaysPanel(); });
            LinearLayout acts = row();
            acts.addView(iconButton(R.drawable.ic_lock, () -> {
                pushUndo(); o.locked = !o.locked; saveProject(true); overlaysPanel();
            }));
            acts.addView(iconButton(R.drawable.ic_eye, () -> {
                pushUndo(); o.hidden = !o.hidden; saveProject(true); overlaysPanel();
            }));
            acts.addView(iconButton(R.drawable.ic_copy, () -> {
                pushUndo(); project.overlays.add(dupOverlay(o));
                selectedOverlay = project.overlays.size() - 1;
                if (ovlTrack != null) ovlTrack.setSelected(selectedOverlay);
                saveProject(true); overlaysPanel();
            }));
            acts.addView(iconButton(R.drawable.ic_delete, () -> {
                pushUndo(); project.overlays.remove(ix);
                selectedOverlay = -1;
                if (ovlTrack != null) ovlTrack.setSelected(-1);
                saveProject(true); buildTimeline(false); overlaysPanel();
            }));
            item.addView(acts);
            panelHost.addView(item);
            LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) item.getLayoutParams();
            ilp.setMargins(0, 0, 0, dp(6));
        }

        // ---- editor for the selected layer
        if (selectedOverlay < 0 || selectedOverlay >= project.overlays.size()) selectedOverlay = 0;
        OverlayLayer o = project.overlays.get(selectedOverlay);
        panelHost.addView(label("— " + (o.kind == OverlayLayer.Kind.TEXT ? "Text layer" : "Image layer") + " —", 13, AeDesign.MUTED, Typeface.BOLD));

        if (o.kind == OverlayLayer.Kind.TEXT) {
            EditText et = new EditText(this);
            et.setText(o.text);
            et.setHint("Layer text");
            et.setTextColor(AeDesign.TEXT);
            et.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(10), AeDesign.STROKE, 1));
            et.setPadding(dp(10), dp(8), dp(10), dp(8));
            et.setOnEditorActionListener((v, id, ev) -> {
                String txt = et.getText().toString();
                if (txt != null && !txt.isEmpty() && !txt.equals(o.text)) {
                    pushUndo(); o.text = txt; saveProject(true);
                }
                return true;
            });
            panelHost.addView(et, new LinearLayout.LayoutParams(-1, -2));
            int[] colors = {0xFFFFFFFF, 0xFFFFD54A, 0xFF40C4FF, 0xFFFF4FA3, 0xFF69F0AE};
            LinearLayout cols = rowWrap();
            for (int cc : colors) {
                final int c2 = cc;
                View dot = new View(this);
                dot.setBackground(AeDesign.bg(c2, dp(12), o.color == c2 ? AeDesign.ACCENT : AeDesign.STROKE, o.color == c2 ? 3 : 1));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(30), dp(30));
                dlp.setMargins(dp(4), dp(4), dp(4), dp(4));
                dot.setOnClickListener(v -> { pushUndo(); o.color = c2; saveProject(true); overlaysPanel(); });
                cols.addView(dot, dlp);
            }
            panelHost.addView(cols);
        }

        panelHost.addView(label("Corner preset", 13, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout corners = rowWrap();
        String[][] presets = {{"TL", "top-left"}, {"TR", "top-right"}, {"BL", "bottom-left"},
                {"BR", "bottom-right"}, {"Center", "center"}, {"Free", ""}};
        for (String[] pn : presets) {
            final String cn = pn[1];
            boolean cur = cn.isEmpty() ? o.corner.isEmpty() : o.corner.equals(cn);
            addChoice(corners, cur ? "✓ " + pn[0] : pn[0], cur, () -> {
                pushUndo(); o.applyCornerPreset(cn, o.cornerMargin);
                saveProject(true); overlaysPanel();
            });
        }
        panelHost.addView(corners);

        float total = Math.max(1f, project.totalDurationSec());
        panelHost.addView(label("X  " + Math.round(o.x * 100) + "%", 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, 100, Math.round(o.x * 100), v -> {
            pushUndo(); o.x = v / 100f; o.corner = ""; saveProject(true); overlaysPanel();
        }));
        panelHost.addView(label("Y  " + Math.round(o.y * 100) + "%", 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, 100, Math.round(o.y * 100), v -> {
            pushUndo(); o.y = v / 100f; o.corner = ""; saveProject(true); overlaysPanel();
        }));
        panelHost.addView(label("Size  " + Math.round(o.scale * 100) + "%", 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(10, 300, Math.round(o.scale * 100), v -> {
            pushUndo(); o.scale = v / 100f; saveProject(true); overlaysPanel();
        }));
        panelHost.addView(label("Rotation  " + Math.round(o.rotation) + "°", 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(-180, 180, Math.round(o.rotation), v -> {
            pushUndo(); o.rotation = v; saveProject(true); overlaysPanel();
        }));
        panelHost.addView(label("Opacity  " + Math.round(o.opacity * 100) + "%", 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, 100, Math.round(o.opacity * 100), v -> {
            pushUndo(); o.opacity = v / 100f; saveProject(true); overlaysPanel();
        }));
        panelHost.addView(label("Start  " + fmt(o.startSec), 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, Math.max(1, Math.round(total)), Math.round(o.startSec), v -> {
            pushUndo(); o.startSec = v;
            if (o.endSec >= 0f && o.endSec < o.startSec + 0.5f) o.endSec = -1f;
            saveProject(true); buildTimeline(false); overlaysPanel();
        }));
        panelHost.addView(label("End  " + (o.endSec < 0f ? "until end" : fmt(o.endSec)), 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, Math.max(1, Math.round(total)),
                Math.round(Math.max(o.startSec, o.endSec < 0f ? total : o.endSec)), v -> {
            pushUndo(); o.endSec = v; saveProject(true); buildTimeline(false); overlaysPanel();
        }));

        panelHost.addView(label("Layers render above the transition in preview AND in the exported MP4.",
                12, AeDesign.MUTED, Typeface.NORMAL));
    }

    private OverlayLayer dupOverlay(OverlayLayer o) {
        OverlayLayer n = new OverlayLayer();
        n.kind = o.kind;
        n.uri = o.uri;
        n.text = o.text;
        n.color = o.color;
        n.bold = o.bold;
        n.textSize = o.textSize;
        n.x = o.x; n.y = o.y; n.scale = o.scale; n.rotation = o.rotation; n.opacity = o.opacity;
        n.startSec = o.startSec; n.endSec = o.endSec;
        n.corner = o.corner; n.cornerMargin = o.cornerMargin;
        return n;
    }

    private void addTextOverlayLayer() {
        pushUndo();
        OverlayLayer o = new OverlayLayer();
        o.kind = OverlayLayer.Kind.TEXT;
        o.text = "Text";
        o.color = 0xffffffff;
        o.bold = true;
        o.textSize = 72f;
        o.x = 0.5f; o.y = 0.35f;
        project.overlays.add(o);
        selectedOverlay = project.overlays.size() - 1;
        if (ovlTrack != null) ovlTrack.setSelected(selectedOverlay);
        saveProject(true);
        buildTimeline(false);
        overlaysPanel();
        toast("Text layer added");
    }

    private void pickOverlayImage() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(i, "Choose an image overlay"), PICK_OVERLAY_IMAGE);
        } catch (Exception e) {
            toast("Could not open the image picker");
        }
    }

    /** Picks a corner logo; the corner preset is applied when the image comes back. */
    private void addLogoOverlay(String corner) {
        pendingOverlayCorner = corner;
        pickOverlayImage();
    }

    private void textStudio() {
        openTool("text");
        showPanel("Text (shown in preview + export)",
                new String[]{"Title", "Subtitle", "Caption", "YouTube Title", "Shorts Caption", "Documentary Lower Third", "End Card"},
                new Runnable[]{() -> addText("Title"), () -> addText("Subtitle"), () -> addText("Caption"), () -> addText("YouTube Title"), () -> addText("Shorts Caption"), () -> addText("Documentary Lower Third"), () -> addText("Thanks for watching")});
    }

    /**
     * Audio tools (spec §20, §47). This is a REAL audio track: everything set
     * here is honoured by the preview player AND by the exporter, which decodes,
     * trims, loops, fades and mixes the track into an AAC stream inside the MP4.
     */
    private void audioPanel() {
        openTool("audio");
        if (panelHost == null) return;
        project.migrateLegacyAudio();
        panelHost.removeAllViews();
        panelHost.addView(label("Audio / Voice-over", 16, AeDesign.TEXT, Typeface.BOLD));

        LinearLayout grid = rowWrap();
        addAction(grid, project.audioTracks.isEmpty() ? "Import audio" : "Change audio", this::pickAudio);
        if (!project.audioTracks.isEmpty()) {
            addAction(grid, "Preview", this::previewAudioNow);
            addAction(grid, "Split at playhead", this::splitAudioAtPlayhead);
            addAction(grid, "Remove audio", () -> {
                pushUndo(); project.audioTracks.clear(); project.audioUri = null;
                releaseAudio(); saveProject(true); showEditor();
            });
        }
        panelHost.addView(grid);

        AudioTrack t = project.primaryAudio();
        if (t == null) {
            panelHost.addView(label("No audio yet. Import a track and it will be mixed into the exported MP4.",
                    12, AeDesign.MUTED, Typeface.NORMAL));
            return;
        }

        panelHost.addView(label("Volume  " + Math.round(t.volume * 100) + "%", 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, 100, Math.round(t.volume * 100), v -> {
            pushUndo(); t.volume = v / 100f; saveProject(true); audioPanel();
        }));

        panelHost.addView(label("Start on timeline  " + fmt(t.startSec), 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, Math.max(1, Math.round(project.totalDurationSec())),
                Math.round(t.startSec), v -> { pushUndo(); t.startSec = v; saveProject(true); audioPanel(); }));

        panelHost.addView(label("Trim from  " + fmt(t.trimStartSec), 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, Math.max(1, audioLengthSec(t)), Math.round(t.trimStartSec), v -> {
            pushUndo();
            t.trimStartSec = v;
            if (t.trimEndSec > 0 && t.trimEndSec <= t.trimStartSec) t.trimEndSec = 0;
            saveProject(true); audioPanel();
        }));

        panelHost.addView(label("Trim to  " + (t.trimEndSec > 0 ? fmt(t.trimEndSec) : "end of file"),
                13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, Math.max(1, audioLengthSec(t)), Math.round(t.trimEndSec), v -> {
            pushUndo(); t.trimEndSec = v; saveProject(true); audioPanel();
        }));

        panelHost.addView(label("Fade in  " + fmt(t.fadeInSec), 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, 10, Math.round(t.fadeInSec), v -> {
            pushUndo(); t.fadeInSec = v; saveProject(true); audioPanel();
        }));

        panelHost.addView(label("Fade out  " + fmt(t.fadeOutSec), 13, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(slider(0, 10, Math.round(t.fadeOutSec), v -> {
            pushUndo(); t.fadeOutSec = v; saveProject(true); audioPanel();
        }));

        LinearLayout flags = rowWrap();
        addChoice(flags, t.loop ? "✓ Loop audio" : "Loop audio", t.loop, () -> {
            pushUndo(); t.loop = !t.loop; saveProject(true); audioPanel();
        });
        addChoice(flags, t.muted ? "✓ Muted" : "Mute", t.muted, () -> {
            pushUndo(); t.muted = !t.muted; saveProject(true); audioPanel();
        });
        panelHost.addView(flags);

        // Fit images to audio — premium feature
        LinearLayout fitRow = rowWrap();
        addAction(fitRow, "FIT IMAGES TO AUDIO", this::fitImagesToAudio);
        panelHost.addView(fitRow);
        panelHost.addView(label("Fits every image duration so total video matches the audio (\" + fmt(audioLengthSec(t)) + \" source). One batch operation.", 11, AeDesign.MUTED, Typeface.NORMAL));
        panelHost.addView(label("Plays in sync with the preview timeline and is encoded into the "
                + "exported MP4 as a real AAC track.", 12, AeDesign.MUTED, Typeface.NORMAL));
    }

    /**
     * Splits the clip under the playhead into two adjacent clips (v1.8).
     * The junction is a clean cut (no transition); both halves must be at
     * least 0.6s so the timeline geometry stays stable.
     */
    private void splitAtPlayhead() {
        if (preview == null || project.clips.isEmpty()) { toast("Import images first"); return; }
        float t = preview.currentTimeSec();
        Timeline.Point at = Timeline.resolve(project, t);
        TimelineClip c = at.clip;
        if (c == null) return;
        if (at.localSec < 0.6f || c.durationSec - at.localSec < 0.6f) {
            toast("Move the playhead at least 0.6s inside the clip");
            return;
        }
        float oldDur = c.durationSec;
        pushUndo();
        c.transition = TransitionType.NONE;
        c.transitionDurationSec = 0f;
        c.transitionPresetId = null;
        c.setDurationSeconds(at.localSec);
        TimelineClip right = new TimelineClip(c.uri, c.index + 1, c.formula);
        right.setDurationSeconds(oldDur - at.localSec);
        right.effect = c.effect;
        right.effectIntensity = c.effectIntensity;
        for (EffectLayer l : c.effectLayers) right.effectLayers.add(new EffectLayer(l.type, l.intensity));
        if (c.borderEffect != null) right.borderEffect = c.borderEffect.copy();
        project.clips.add(at.clipIndex + 1, right);
        project.renumber();
        saveProject(true);
        buildTimeline(false);
        toast("Clip split into two");
    }

    /**
     * Splits the primary audio track at the playhead (v1.8): the head track
     * is trimmed at the cut and the tail becomes a NEW track starting there,
     * so both halves keep covering the full used region.
     */
    private void splitAudioAtPlayhead() {
        AudioTrack t = project.primaryAudio();
        if (t == null) { toast("Import audio first"); return; }
        float play = preview == null ? 0f : preview.currentTimeSec();
        float local = play - t.startSec;
        float len = t.effectiveDurationSec();
        if (local < 0.1f || local > len - 0.1f) {
            toast("Put the playhead inside the audio");
            return;
        }
        pushUndo();
        AudioTrack b = new AudioTrack(t.uri);
        b.sourceDurationMs = t.sourceDurationMs;
        b.startSec = t.startSec + local;
        b.trimStartSec = t.trimStartSec + local;
        b.trimEndSec = t.trimEndSec;
        b.loop = false;
        b.fadeInSec = 0f;
        b.fadeOutSec = t.fadeOutSec;
        b.volume = t.volume;
        b.muted = t.muted;
        t.trimEndSec = t.trimStartSec + local;
        t.loop = false;
        t.fadeOutSec = 0f;
        int ix = project.audioTracks.indexOf(t);
        project.audioTracks.add(Math.max(0, ix) + 1, b);
        saveProject(true);
        buildTimeline(false);
        audioPanel();
        toast("Audio split at playhead");
    }

    /** Length of the loaded audio file in seconds, probed once and cached. */
    private int audioLengthSec(AudioTrack t) {
        if (t == null || t.uri == null) return 30;
        if (t.sourceDurationMs <= 0) {
            MediaPlayer probe = null;
            try {
                probe = MediaPlayer.create(this, Uri.parse(t.uri));
                if (probe != null) { t.sourceDurationMs = probe.getDuration(); saveProject(false); }
            } catch (Exception e) { Log.w(TAG, "Audio probe failed", e); }
            finally { if (probe != null) try { probe.release(); } catch (Exception ignored) {} }
        }
        return t.sourceDurationMs > 0 ? Math.max(1, (int) (t.sourceDurationMs / 1000)) : 30;
    }

    /** Plays the track once from its trim start so the user can audition it. */
    private void previewAudioNow() {
        AudioTrack t = project.primaryAudio();
        if (t == null) { toast("No audio to preview"); return; }
        releaseAudio();
        try {
            audioPlayer = MediaPlayer.create(this, Uri.parse(t.uri));
            if (audioPlayer == null) throw new IOException("MediaPlayer unavailable");
            audioPlayer.setVolume(t.effectiveVolume(), t.effectiveVolume());
            if (t.trimStartSec > 0) audioPlayer.seekTo((int) (t.trimStartSec * 1000));
            audioPlayer.start();
            toast("Playing audio");
        } catch (Exception e) {
            Log.e(TAG, "Audio preview failed", e);
            toast("Audio could not be decoded: " + (e.getMessage() == null ? "unsupported format" : e.getMessage()));
        }
    }

    /** A labelled SeekBar; commits on release so it does not thrash undo. */
    private android.widget.SeekBar slider(int min, int max, int value, java.util.function.IntConsumer onCommit) {
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(Math.max(1, max - min));
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
        lp.setMargins(dp(4), dp(2), dp(4), dp(8));
        sb.setLayoutParams(lp);
        sb.setContentDescription("Value between " + min + " and " + max);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { onCommit.accept(min + s.getProgress()); }
        });
        return sb;
    }

    private void canvasPanel() {
        openTool("canvas");
        if (panelHost == null) return;
        panelHost.removeAllViews();
        panelHost.addView(label("Canvas — aspect ratio", 16, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout ratios = rowWrap();
        for (AspectRatio ar : AspectRatio.values()) {
            if (!ar.isFixed()) continue;
            addChoice(ratios, (project.aspectRatio == ar ? "✓ " : "") + ar.label.split(" ")[0],
                    project.aspectRatio == ar, () -> {
                        pushUndo();
                        project.aspectRatio = ar;
                        applyAspectToPreset(ar);
                        saveProject(true);
                        refreshAfterCanvasChange();
                    });
        }
        panelHost.addView(ratios);

        panelHost.addView(label("Background / framing", 16, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout fits = rowWrap();
        for (FitMode fm : FitMode.values()) {
            addChoice(fits, (project.fitMode == fm ? "✓ " : "") + fm.label, project.fitMode == fm, () -> {
                pushUndo(); project.fitMode = fm; saveProject(true); refreshAfterCanvasChange();
            });
        }
        panelHost.addView(fits);
        panelHost.addView(label("Every mode paints a background layer first, so no mode ever shows a black wedge.",
                12, AeDesign.MUTED, Typeface.NORMAL));
    }

    /** Maps a canvas aspect ratio onto the nearest export preset. */
    private void applyAspectToPreset(AspectRatio ar) {
        switch (ar) {
            case R16_9: project.applyExportPreset(ExportPreset.LANDSCAPE_16_9); break;
            case R9_16: project.applyExportPreset(ExportPreset.PORTRAIT_9_16); break;
            case R1_1:  project.applyExportPreset(ExportPreset.SQUARE_1_1); break;
            case R4_5:  project.applyExportPreset(ExportPreset.PORTRAIT_4_5); break;
            case R4_3:  project.applyExportPreset(ExportPreset.CLASSIC_4_3); break;
            default:
                project.updateSizeForAspect(project.height);
                break;
        }
        draftPreset = project.exportPreset;
    }

    /** Short human summary of the audio track for the timeline label. */
    private String audioTrackSummary(AudioTrack t) {
        if (t == null) return "no audio";
        StringBuilder sb = new StringBuilder();
        sb.append(t.muted ? "muted" : Math.round(t.volume * 100) + "%");
        if (t.fadeInSec > 0 || t.fadeOutSec > 0) sb.append(" • fade ").append(Math.round(t.fadeInSec)).append("/").append(Math.round(t.fadeOutSec)).append("s");
        if (t.loop) sb.append(" • loop");
        sb.append(" • in export");
        return sb.toString();
    }

    private void filtersPanel() {
        openTool("filters");
        effectsSheet("Filters & Color", new EffectType[]{
                EffectType.CINEMATIC, EffectType.TEMPERATURE, EffectType.SOFT_FOCUS, EffectType.VINTAGE,
                EffectType.FILM, EffectType.BLACK_WHITE, EffectType.CONTRAST, EffectType.DREAM, EffectType.SHARPEN,
                EffectType.SEPIA, EffectType.SATURATION, EffectType.EXPOSURE});
    }

    private void effectsPanel() {
        openTool("effects");
        effectsSheet("Effects", new EffectType[]{
                EffectType.NONE, EffectType.GLOW, EffectType.SOFT_GLOW, EffectType.BLOOM, EffectType.VIGNETTE,
                EffectType.BLUR, EffectType.MOTION_BLUR, EffectType.FILM_GRAIN, EffectType.VINTAGE,
                EffectType.CINEMATIC, EffectType.BRIGHTNESS, EffectType.CONTRAST, EffectType.SATURATION,
                EffectType.TEMPERATURE, EffectType.EXPOSURE, EffectType.HIGHLIGHTS, EffectType.SHADOWS,
                EffectType.FADE, EffectType.BLACK_WHITE, EffectType.SEPIA, EffectType.DREAM, EffectType.FILM,
                EffectType.SOFT_FOCUS, EffectType.SHARPEN});
    }

    private void effectsSheet(String title, EffectType[] list) {
        String scope = selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL clips";
        PanelSheet s = sheet();
        openSheet(title + " → " + scope);
        EffectType current = selected >= 0 && selected < project.clips.size()
                ? project.clips.get(selected).effect : null;
        LinearLayout row = sheetCardsRow(s);
        for (EffectType t : list) {
            EffectPreviewView epv = new EffectPreviewView(this);
            epv.setEffect(t, 0.7f);
            boolean isSel = selectedEffect != null ? selectedEffect == t : current == t;
            LinearLayout card = previewCard(epv, EffectEngine.label(t),
                    t == EffectType.NONE ? "Reset" : "Layerable", isSel);
            card.setContentDescription("Effect " + EffectEngine.label(t) + (isSel ? ", selected" : ""));
            AeDesign.tap(card, () -> { selectedEffect = t; effectsPanelOrRefresh(title); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(4), dp(4), dp(4), dp(6));
            row.addView(card, lp);
        }
        sheetHint(s, "Tap a card to SELECT, then APPLY. Every effect has a real rendering path shared by preview and export.");
        addApplyButtons(s, "APPLY EFFECT TO",
                () -> { if (selectedEffect != null) { applyEffectTo(selected, selectedEffect); afterApply("Effect applied to clip"); } },
                () -> { if (selectedEffect != null) { applyEffectToAll(selectedEffect); afterApply("Effect applied to all"); } });
    }

    private void effectsPanelOrRefresh(String title) {
        if ("Effects".equals(title)) effectsPanel();
        else if ("Color Adjust".equals(title)) adjustPanel();
        else filtersPanel();
    }

    private void applyEffectTo(int clipIdx, EffectType e) {
        if (clipIdx < 0 || clipIdx >= project.clips.size()) return;
        pushUndo();
        project.clips.get(clipIdx).setSingleEffect(e, project.clips.get(clipIdx).effectIntensity);
        saveProject(true);
        if (preview != null) preview.invalidate();
    }

    /** ONE undo entry for the whole batch (spec §34). */
    private void applyEffectToAll(EffectType e) {
        pushUndo();
        for (TimelineClip c : project.clips) c.setSingleEffect(e, c.effectIntensity);
        saveProject(true);
        if (preview != null) preview.invalidate();
    }

    /**
     * Stacks one more effect on top of what the clip already has (spec §45).
     * Still a single undo entry.
     */
    private void addEffectLayerToSelection(EffectType e) {
        if (e == null || e == EffectType.NONE) return;
        pushUndo();
        if (selected >= 0 && selected < project.clips.size())
            project.clips.get(selected).addEffectLayer(e, 0.6f);
        else for (TimelineClip c : project.clips) c.addEffectLayer(e, 0.6f);
        saveProject(true);
        if (preview != null) preview.invalidate();
    }

    private void adjustPanel() {
        openTool("adjust");
        if (panelHost == null) return;
        panelHost.removeAllViews();
        panelHost.addView(label("Adjust — fine controls (preview = export)", 16, AeDesign.TEXT, Typeface.BOLD));
        Set<Integer> sel = effectiveSelection();
        String scope = sel.isEmpty() ? "ALL " + project.clips.size() + " clips" : sel.size() + " selected clip(s)";
        panelHost.addView(label("Scope: " + scope + " — sliders stack via EffectLayer, not single effect. Undo-safe.", 12, AeDesign.MUTED, Typeface.NORMAL));
        // Helper to get current intensity for a type in scope
        // For simplicity, show sliders for the effective selection's first clip or first clip overall
        TimelineClip rep = null;
        if (!sel.isEmpty()) { int first = new ArrayList<>(sel).get(0); if (first>=0 && first<project.clips.size()) rep = project.clips.get(first); }
        else if (!project.clips.isEmpty()) rep = project.clips.get(0);
        // brightness
        addAdjustSlider("Brightness", EffectType.BRIGHTNESS, rep);
        addAdjustSlider("Contrast", EffectType.CONTRAST, rep);
        addAdjustSlider("Saturation", EffectType.SATURATION, rep);
        addAdjustSlider("Exposure", EffectType.EXPOSURE, rep);
        addAdjustSlider("Temperature", EffectType.TEMPERATURE, rep);
        addAdjustSlider("Highlights", EffectType.HIGHLIGHTS, rep);
        addAdjustSlider("Shadows", EffectType.SHADOWS, rep);
        addAdjustSlider("Sharpen", EffectType.SHARPEN, rep);
        LinearLayout row = rowWrap();
        addAction(row, "Reset Adjust", () -> {
            Set<Integer> s = effectiveSelection();
            if (s.isEmpty() && !project.clips.isEmpty()) { s = new HashSet<>(); for (int i=0;i<project.clips.size();i++) s.add(i); }
            if (s.isEmpty()) { toast("No clips"); return; }
            pushUndo();
            for (int idx: s) {
                TimelineClip c = project.clips.get(idx);
                // Remove adjust types
                for (EffectType t2 : new EffectType[]{EffectType.BRIGHTNESS, EffectType.CONTRAST, EffectType.SATURATION, EffectType.EXPOSURE, EffectType.TEMPERATURE, EffectType.HIGHLIGHTS, EffectType.SHADOWS, EffectType.SHARPEN}) c.effectLayers.removeIf(l -> l.type == t2);
                // Also clear single effect if it's one of them
                if (c.effect == EffectType.BRIGHTNESS || c.effect == EffectType.CONTRAST || c.effect == EffectType.SATURATION || c.effect == EffectType.EXPOSURE || c.effect == EffectType.TEMPERATURE || c.effect == EffectType.HIGHLIGHTS || c.effect == EffectType.SHADOWS || c.effect == EffectType.SHARPEN) { c.effect = EffectType.NONE; }
            }
            saveProject(true);
            if (preview != null) preview.invalidate();
            adjustPanel();
            toast("Adjust reset");
        });
        panelHost.addView(row);
        panelHost.addView(label("Each slider writes an EffectLayer (stackable). Preview and export share the same rendering path.", 11, AeDesign.MUTED, Typeface.NORMAL));
    }

    private void addAdjustSlider(String name, EffectType type, TimelineClip rep) {
        float cur = 0.5f;
        if (rep != null) {
            for (EffectLayer l : rep.effectLayers) if (l.type == type) { cur = l.intensity; break; }
            if (rep.effect == type) cur = rep.effectIntensity;
        }
        int pct = Math.round(cur * 100);
        panelHost.addView(label(name + "  " + pct + "%", 13, AeDesign.TEXT, Typeface.BOLD));
        // Range 0..100 maps to 0.0..1.0, with 50% as neutral
        android.widget.SeekBar sb = slider(0, 100, pct, v -> {
            float intensity = v / 100f;
            Set<Integer> s = effectiveSelection();
            if (s.isEmpty() && !project.clips.isEmpty()) { s = new HashSet<>(); for (int i=0;i<project.clips.size();i++) s.add(i); }
            if (s.isEmpty()) { toast("No clips"); return; }
            pushUndo();
            for (int idx: s) {
                TimelineClip c = project.clips.get(idx);
                boolean found=false;
                for (EffectLayer l: c.effectLayers) if (l.type == type) { l.intensity = intensity; found=true; break; }
                if (!found) c.addEffectLayer(type, intensity);
                // Keep single effect in sync for preview fallback
                if (c.effect == type) c.effectIntensity = intensity;
            }
            saveProject(true);
            if (preview != null) preview.invalidate();
            adjustPanel();
        });
        panelHost.addView(sb);
    }


    // ─────────────────────────────────────────────────────────────────
    // MASTER BORDER / FRAME ENGINE — Effects -> Frame & Border
    // Pure procedural, preview==export, metadata-only, Canvas-aligned.
    // Library 39 presets (Neon 8 + Dual 5 + Glow 6 + Electric 5 + Cinematic 5 + Flow 4 + Special 6)
    // Includes Neon Flow (blue+green moving glow). Every card real; no Coming Soon.
    // Live preview: tap -> 2-3s loop temp overlay with Cancel/Apply (no immediate commit).
    // Controls: intensity/opacity/thickness/glow/speed/corner/direction + colors.
    // Duration: whole clip (default) or custom start/end (effectStart/End) — toggle below.
    // Apply to Selected / All (one undo, O(n) metadata), Remove, Undo grouped.
    // Compatibility: Motion+Border stays canvas-aligned, Transition+Border crossfades, Filter+Border layers order BG->Motion->Filter->Border->Text/Overlay.
    // Canvas adaptive: 16:9/9:16/1:1/4:5 uses same FrameComposer geometry.
    // Search + categories All/Neon/Glow/Electric/Cinematic/Color/Light/Special.
    // WindowInsets + scroll handled by PanelSheet (fillViewport + clipToPadding false).
    // ─────────────────────────────────────────────────────────────────
    private void framePanel() {
        openTool("frame");
        boolean hasClips = !project.clips.isEmpty();
        String scopeSel = effectiveSelection().isEmpty() ? (selected>=0? "Clip "+project.clips.get(selected).index : "All " + project.clips.size() + " clips") : effectiveSelection().size()+" selected";
        PanelSheet s = sheet();
        s.content().removeAllViews();
        s.applyBar().removeAllViews();
        openSheet("Frame & Border → " + scopeSel);

        // Restore temp preview if pending already exists (re-entry keeps selection)
        if (pendingBorder != null && preview != null) preview.setTempBorder(pendingBorder);

        // ── search ──
        EditText search = new EditText(this);
        search.setHint("🔍 Search — neon, electric, glow, cinematic, rainbow, flow…");
        search.setSingleLine(true);
        search.setTextColor(AeDesign.TEXT);
        search.setHintTextColor(AeDesign.MUTED);
        search.setText(borderSearch);
        search.setBackground(AeDesign.bg(AeDesign.SURFACE, dp(14), AeDesign.STROKE, 1));
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence a,int b,int c,int d){}
            public void onTextChanged(CharSequence a,int b,int c,int d){}
            public void afterTextChanged(android.text.Editable e){
                borderSearch = e.toString().trim();
                if (!borderSearch.isEmpty()) borderCategory = "All";
                framePanel();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1,-2);
        slp.setMargins(0, dp(2),0, dp(8));
        s.content().addView(search, slp);

        // ── category tabs ──
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        for (String cat : BorderEffectRegistry.categories()) {
            final String c = cat;
            boolean on = c.equalsIgnoreCase(borderCategory);
            TextView tab = label(cat, 13, on? 0xff041018 : AeDesign.TEXT, Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(12), dp(8), dp(12), dp(8));
            tab.setBackground(AeDesign.bg(on? AeDesign.ACCENT : AeDesign.SURFACE_2, dp(16), on? AeDesign.ACCENT : AeDesign.STROKE,1));
            AeDesign.press(tab, () -> { borderCategory=c; borderSearch=""; framePanel(); });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2,-2);
            tlp.setMargins(dp(3),dp(2),dp(3),dp(6));
            tabs.addView(tab, tlp);
        }
        tabScroll.addView(tabs);
        s.content().addView(tabScroll, new LinearLayout.LayoutParams(-1,-2));

        // ── selected line + live preview hint ──
        String selName = selectedBorderPresetId==null ? "none — tap a card for 2–3s live preview" : BorderEffectRegistry.byId(selectedBorderPresetId)==null? "—" : BorderEffectRegistry.byId(selectedBorderPresetId).name;
        s.content().addView(label("Selected: " + selName, 13, selectedBorderPresetId==null? AeDesign.MUTED: AeDesign.ACCENT, Typeface.BOLD));
        if (hasClips) s.content().addView(label("Tap a border → live animated preview on the monitor (2–3s loop). Tweak below, then Cancel / Apply.", 12, AeDesign.MUTED, Typeface.NORMAL));
        else s.content().addView(label("Add images first — preview shows on a placeholder until clips exist. Config still saves.",11, AeDesign.MUTED, Typeface.NORMAL));

        // ── border preview state indicator (glow pulse when live) ──
        if (previewBorderActive && pendingBorder != null) {
            TextView live = label("◉ LIVE PREVIEW — monitoring clip", 12, 0xff49ff88, Typeface.BOLD);
            live.setPadding(dp(6), dp(4), dp(6), dp(4));
            live.setBackground(AeDesign.bg(0xff0b2214, dp(12), 0xff49ff88,1));
            s.content().addView(live);
        }

        // ── build filtered list ──
        java.util.List<BorderEffectRegistry.Preset> items;
        if (borderSearch != null && !borderSearch.isEmpty()) items = BorderEffectRegistry.search(borderSearch);
        else items = BorderEffectRegistry.byCategory(borderCategory);
        if (borderSearch != null && !borderSearch.isEmpty()) s.content().addView(label("Search: \""+borderSearch+"\" ("+items.size()+")",12, AeDesign.MUTED, Typeface.NORMAL));
        else if (items.isEmpty()) s.content().addView(label("No borders in this category.",12, AeDesign.MUTED, Typeface.NORMAL));

        // ── card grid (2 rows if many) ──
        LinearLayout grid = sheetCardsRow(s);
        for (BorderEffectRegistry.Preset p : items) {
            boolean isSel = p.id.equals(selectedBorderPresetId);
            BorderPreviewView bpv = new BorderPreviewView(this);
            bpv.setPreset(p);
            LinearLayout card = previewCard(bpv, p.name, p.category + " • " + p.desc, isSel);
            card.setContentDescription("Border " + p.name + (isSel?" selected":""));
            AeDesign.tap(card, () -> {
                // Tap = SELECT + LIVE PREVIEW (no commit)
                selectedBorderPresetId = p.id;
                // defaults from preset but preserve previous tweaks if same id re-tapped?
                if (pendingBorder == null || !p.id.equals(pendingBorder.presetId)) {
                    pendingBorder = new BorderEffectConfig(p.id);
                    pendingBorder.thickness = p.defaultThickness;
                    pendingBorder.glow = p.defaultGlow;
                    pendingBorder.speed = p.defaultSpeed;
                    pendingBorder.intensity = 1f; pendingBorder.opacity = 1f; pendingBorder.cornerRadius = 10f;
                    // if a clip is selected and has a border, seed from it
                    int ref = selected>=0 && selected<project.clips.size() ? selected : (project.clips.isEmpty()? -1:0);
                    if (ref>=0 && project.clips.get(ref).borderEffect!=null && p.id.equals(project.clips.get(ref).borderEffect.presetId)) {
                        pendingBorder = project.clips.get(ref).borderEffect.copy();
                    }
                }
                previewBorderActive = true;
                if (preview != null) preview.setTempBorder(pendingBorder);
                framePanel(); // rebuild to show ring + controls
            });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
            lp.setMargins(dp(4),dp(4),dp(4),dp(6));
            grid.addView(card, lp);
        }

        // ── controls (only when a border is pending) ──
        if (pendingBorder != null && selectedBorderPresetId != null) {
            s.content().addView(label("— Tweaks (live) —",13, AeDesign.MUTED, Typeface.BOLD));
            // intensity
            s.content().addView(label("Intensity  " + Math.round(pendingBorder.intensity*100)+"%",13, AeDesign.TEXT, Typeface.BOLD));
            s.content().addView(slider(0,100, Math.round(pendingBorder.intensity*100), v->{ pendingBorder.intensity=v/100f; if(preview!=null) preview.setTempBorder(pendingBorder); }));
            s.content().addView(label("Opacity  " + Math.round(pendingBorder.opacity*100)+"%",13, AeDesign.TEXT, Typeface.BOLD));
            s.content().addView(slider(0,100, Math.round(pendingBorder.opacity*100), v->{ pendingBorder.opacity=v/100f; if(preview!=null) preview.setTempBorder(pendingBorder); }));
            s.content().addView(label("Thickness  " + Math.round(pendingBorder.thickness)+"dp",13, AeDesign.TEXT, Typeface.BOLD));
            s.content().addView(slider(1,20, Math.round(pendingBorder.thickness), v->{ pendingBorder.thickness=v; if(preview!=null) preview.setTempBorder(pendingBorder); }));
            s.content().addView(label("Glow / Blur  " + Math.round(pendingBorder.glow),13, AeDesign.TEXT, Typeface.BOLD));
            s.content().addView(slider(0,12, Math.round(pendingBorder.glow), v->{ pendingBorder.glow=v; if(preview!=null) preview.setTempBorder(pendingBorder); }));
            s.content().addView(label("Speed  " + Math.round(pendingBorder.speed*100)+"%",13, AeDesign.TEXT, Typeface.BOLD));
            s.content().addView(slider(0,100, Math.round(pendingBorder.speed*100), v->{ pendingBorder.speed=v/100f; if(preview!=null) preview.setTempBorder(pendingBorder); }));
            s.content().addView(label("Corner radius  " + Math.round(pendingBorder.cornerRadius)+"dp",13, AeDesign.TEXT, Typeface.BOLD));
            s.content().addView(slider(0,32, Math.round(pendingBorder.cornerRadius), v->{ pendingBorder.cornerRadius=v; if(preview!=null) preview.setTempBorder(pendingBorder); }));

            // direction
            s.content().addView(label("Direction",13, AeDesign.TEXT, Typeface.BOLD));
            LinearLayout dirs=rowWrap();
            addChoice(dirs, pendingBorder.direction==0?"✓ Clockwise":"Clockwise", pendingBorder.direction==0, ()->{ pendingBorder.direction=0; if(preview!=null) preview.setTempBorder(pendingBorder); framePanel(); });
            addChoice(dirs, pendingBorder.direction==1?"✓ Counter":"Counter", pendingBorder.direction==1, ()->{ pendingBorder.direction=1; if(preview!=null) preview.setTempBorder(pendingBorder); framePanel(); });
            addChoice(dirs, pendingBorder.direction==2?"✓ Alternate":"Alternate", pendingBorder.direction==2, ()->{ pendingBorder.direction=2; if(preview!=null) preview.setTempBorder(pendingBorder); framePanel(); });
            s.content().addView(dirs);

            // quick colors (primary overrides)
            s.content().addView(label("Primary color (overrides preset)",13, AeDesign.TEXT, Typeface.BOLD));
            LinearLayout cols=rowWrap();
            int[] quick={0, 0xFF00D4FF, 0xFFFF2E97, 0xFF00FF88, 0xFFFFD700, 0xFF9D00FF, 0xFFFFFFFF, 0xFF000000};
            String[] names={"Preset","Cyan","Pink","Green","Gold","Purple","White","Black"};
            for (int i=0;i<quick.length;i++){
                final int col=quick[i]; final String nm=names[i];
                boolean on = (col==0 && pendingBorder.primaryColor==0) || pendingBorder.primaryColor==col;
                TextView dot=new TextView(this);
                dot.setText(on?"✓ ":""); dot.setGravity(Gravity.CENTER);
                dot.setTextSize(10f); dot.setTextColor(col==0xFFFFFFFF? 0xff041018 : 0xffffffff);
                dot.setBackground(AeDesign.bg(col==0? AeDesign.SURFACE_2 : col, dp(14), on? AeDesign.ACCENT: AeDesign.STROKE, on?2:1));
                LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(dp(46), dp(32));
                dlp.setMargins(dp(3),dp(3),dp(3),dp(3));
                dot.setLayoutParams(dlp);
                dot.setOnClickListener(v->{ pendingBorder.primaryColor=col; if(preview!=null) preview.setTempBorder(pendingBorder); framePanel(); });
                cols.addView(dot);
            }
            s.content().addView(cols);

            // duration toggle — whole clip vs custom range
            s.content().addView(label("Duration — where on the clip",13, AeDesign.TEXT, Typeface.BOLD));
            LinearLayout durToggle=rowWrap();
            boolean whole = pendingBorder.startMs==0 && pendingBorder.endMs==0;
            addChoice(durToggle, whole?"✓ Entire clip":"Entire clip", whole, ()->{ pendingBorder.startMs=0; pendingBorder.endMs=0; framePanel(); });
            addChoice(durToggle, !whole?"✓ Custom range":"Custom range", !whole, ()->{
                if (whole) { pendingBorder.startMs=0; pendingBorder.endMs=2000; }
                framePanel();
            });
            s.content().addView(durToggle);
            if (!whole) {
                int maxSec = hasClips ? Math.max(1, Math.round(project.clips.get(Math.max(0, selected)).durationSec)) : 8;
                s.content().addView(label("Start  " + fmt(pendingBorder.startMs/1000f),13, AeDesign.TEXT, Typeface.BOLD));
                s.content().addView(slider(0, maxSec, (int)(pendingBorder.startMs/1000), v->{ pendingBorder.startMs=v*1000L; if(pendingBorder.endMs<=pendingBorder.startMs) pendingBorder.endMs=(v+1)*1000L; framePanel(); }));
                s.content().addView(label("End  " + (pendingBorder.endMs<=0? "until end": fmt(pendingBorder.endMs/1000f)),13, AeDesign.TEXT, Typeface.BOLD));
                s.content().addView(slider(0, maxSec, (int)(pendingBorder.endMs/1000), v->{ pendingBorder.endMs=v*1000L; framePanel(); }));
            }
        }

        // ── hint + current clip state ──
        if (!hasClips) {
            s.content().addView(label("Add images to see borders on real clips. Borders are saved per-clip metadata, so 1000 clips cost nothing extra.",11, AeDesign.MUTED, Typeface.NORMAL));
        } else {
            int withBorder=0; for(TimelineClip c: project.clips) if(c.borderEffect!=null) withBorder++;
            s.content().addView(label(withBorder+" / "+project.clips.size()+" clips have a border. Tap a card to preview, then Apply.",12, AeDesign.MUTED, Typeface.NORMAL));
        }

        // ── apply bar: Cancel + Apply Selected / All + Remove ──
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel = AeDesign.button(this, "Cancel", false);
        AeDesign.press(cancel, () -> {
            pendingBorder=null; selectedBorderPresetId=null; previewBorderActive=false;
            if(preview!=null) preview.clearTempBorder();
            if(sheet!=null) sheet.dismiss();
            clearActiveTool();
        });
        bar.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 0.9f));

        boolean hasSel = selected>=0 && selected<project.clips.size();
        boolean hasSelection = !effectiveSelection().isEmpty();
        String selLabel = hasSelection ? effectiveSelection().size()+" clips" : (hasSel? "Clip "+project.clips.get(selected).index : "All");
        Button applySel = AeDesign.button(this, "Apply to "+selLabel, true);
        AeDesign.press(applySel, () -> {
            if (pendingBorder==null || selectedBorderPresetId==null) { toast("Pick a border first"); return; }
            Set<Integer> targets = effectiveSelection();
            if (targets.isEmpty()) {
                if (selected>=0 && selected<project.clips.size()) { targets=new HashSet<>(); targets.add(selected); }
                else if (!project.clips.isEmpty()) { // apply to first if nothing selected but has clips -> apply to all metadata
                    applyBorderToAll(pendingBorder);
                    return;
                } else { toast("Add images first"); return; }
            }
            applyBorderToSelection(pendingBorder, targets);
        });
        bar.addView(applySel, new LinearLayout.LayoutParams(0, dp(48), 1.6f));

        Button applyAll = AeDesign.button(this, "All ("+project.clips.size()+")", false);
        AeDesign.press(applyAll, () -> {
            if (pendingBorder==null) { toast("Pick a border first"); return; }
            applyBorderToAll(pendingBorder);
        });
        bar.addView(applyAll, new LinearLayout.LayoutParams(0, dp(48), 1.15f));
        s.applyBar().addView(bar, new LinearLayout.LayoutParams(-1,-2));

        // second row: Remove + Undo
        LinearLayout bar2=row();
        bar2.setGravity(Gravity.CENTER_VERTICAL);
        Button remove = AeDesign.button(this, "Remove", false);
        AeDesign.press(remove, () -> removeBorderFromSelection());
        bar2.addView(remove, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button undoB = AeDesign.button(this, "Undo", false);
        AeDesign.press(undoB, () -> { undo(); framePanel(); });
        bar2.addView(undoB, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams b2lp=new LinearLayout.LayoutParams(-1,-2);
        b2lp.topMargin=dp(6);
        s.applyBar().addView(bar2, b2lp);

        sheetHint(s, "Border sits canvas-aligned (Background → Image/Motion → Filter → Border → Text). Preview loop is temporary — real commit only on Apply. One undo covers the batch.");
    }

    private void applyBorderToSelection(BorderEffectConfig cfg, Set<Integer> targets){
        if (project.clips.isEmpty()) { toast("Add images first"); return; }
        pushUndo();
        for(int idx: targets) if(idx>=0 && idx<project.clips.size()) project.clips.get(idx).setBorder(cfg);
        pendingBorder=null; selectedBorderPresetId=null; previewBorderActive=false;
        if(preview!=null) preview.clearTempBorder();
        saveProject(true);
        if(sheet!=null) sheet.dismiss(); clearActiveTool();
        buildTimeline(false);
        if(preview!=null) preview.invalidate();
        toast("Border applied to "+targets.size()+" clip(s)");
    }
    private void applyBorderToAll(BorderEffectConfig cfg){
        if (project.clips.isEmpty()) { toast("Add images first"); return; }
        pushUndo();
        for(TimelineClip c: project.clips) c.setBorder(cfg);
        pendingBorder=null; selectedBorderPresetId=null; previewBorderActive=false;
        if(preview!=null) preview.clearTempBorder();
        saveProject(true);
        if(sheet!=null) sheet.dismiss(); clearActiveTool();
        buildTimeline(false);
        if(preview!=null) preview.invalidate();
        toast("Border applied to all "+project.clips.size()+" clips");
    }
    private void removeBorderFromSelection(){
        Set<Integer> targets = effectiveSelection();
        if (targets.isEmpty() && selected>=0 && selected<project.clips.size()) { targets=new HashSet<>(); targets.add(selected); }
        if (targets.isEmpty()) {
            // if nothing selected, remove from all where exists
            int cnt=0; for(TimelineClip c: project.clips) if(c.hasBorder()) cnt++;
            if(cnt==0){ toast("No borders to remove"); return; }
            pushUndo();
            for(TimelineClip c: project.clips) c.clearBorder();
            pendingBorder=null; selectedBorderPresetId=null; previewBorderActive=false;
            if(preview!=null) preview.clearTempBorder();
            saveProject(true);
            if(sheet!=null) sheet.dismiss(); clearActiveTool();
            buildTimeline(false);
            if(preview!=null) preview.invalidate();
            toast("Removed border from all");
            return;
        }
        pushUndo();
        for(int idx: targets) if(idx>=0 && idx<project.clips.size()) project.clips.get(idx).clearBorder();
        pendingBorder=null; selectedBorderPresetId=null; previewBorderActive=false;
        if(preview!=null) preview.clearTempBorder();
        saveProject(true);
        if(sheet!=null) sheet.dismiss(); clearActiveTool();
        buildTimeline(false);
        if(preview!=null) preview.invalidate();
        toast("Border removed from "+targets.size()+" clip(s)");
    }

    private void autoEditPanel() {
        openTool("autoedit");
        if (panelHost == null) return;
        panelHost.removeAllViews();
        panelHost.addView(label("Auto Edit — one tap story", 16, AeDesign.TEXT, Typeface.BOLD));
        panelHost.addView(label("Fills duration + motion + transition + effect. Undo-safe. Audio sync available via FIT IMAGES TO AUDIO in Audio panel.", 12, AeDesign.MUTED, Typeface.NORMAL));
        LinearLayout modes = rowWrap();
        addAction(modes, "Cinematic", () -> autoEdit(1));
        addAction(modes, "Fast", () -> autoEdit(2));
        addAction(modes, "Smooth", () -> autoEdit(0));
        addAction(modes, "Shorts", () -> autoEdit(3));
        addAction(modes, "Documentary", () -> autoEdit(4));
        addAction(modes, "Vlog", () -> autoEdit(5));
        panelHost.addView(modes);
        panelHost.addView(label("Auto Motion variants (apply to selection or all)", 13, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout motionRow = rowWrap();
        addAction(motionRow, "Balanced", this::applyAutoMotionToSelection);
        addAction(motionRow, "Random", () -> applyRandomMotionToSelection(false));
        addAction(motionRow, "Minimal", this::applyMinimalMotionToSelection);
        addAction(motionRow, "Cinematic Seq", () -> { pushUndo(); autoEdit(1); toast("Cinematic auto edit"); });
        panelHost.addView(motionRow);
        panelHost.addView(label("Create video presets", 13, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout create = rowWrap();
        addChoice(create, "9:16", project.aspectRatio == AspectRatio.R9_16, () -> { project.aspectRatio=AspectRatio.R9_16; applyAspectToPreset(AspectRatio.R9_16); saveProject(true); refreshAfterCanvasChange(); toast("Canvas 9:16"); });
        addChoice(create, "16:9", project.aspectRatio == AspectRatio.R16_9, () -> { project.aspectRatio=AspectRatio.R16_9; applyAspectToPreset(AspectRatio.R16_9); saveProject(true); refreshAfterCanvasChange(); toast("Canvas 16:9"); });
        addChoice(create, "1:1", project.aspectRatio == AspectRatio.R1_1, () -> { project.aspectRatio=AspectRatio.R1_1; applyAspectToPreset(AspectRatio.R1_1); saveProject(true); refreshAfterCanvasChange(); toast("Canvas 1:1"); });
        panelHost.addView(create);
    }

    private String scopeLabel() {
        return selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL clips";
    }

    private void showPanel(String title, String[] items, Runnable[] actions) {
        if (panelHost == null) return;
        panelHost.removeAllViews();
        panelHost.addView(label(title, 16, AeDesign.TEXT, Typeface.BOLD));
        // v1.0.7: option rows swipe horizontally (same approach as the Formula
        // card row) — previously a static wrap row that could not scroll.
        LinearLayout grid = row();
        for (int i = 0; i < items.length; i++) {
            final Runnable r = actions[i];
            addAction(grid, items[i], () -> { r.run(); if (preview != null) preview.invalidate(); });
        }
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFillViewport(true);
        hsv.addView(grid);
        panelHost.addView(hsv, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---------------------------------------------------------------- operations (fast state ops — no rendering)

    private void addText(String s) {
        pushUndo();
        TextOverlay t = new TextOverlay();
        t.text = s;
        t.endSec = Math.max(3, project.totalDurationSec());
        project.texts.add(t);
        saveProject(true);
        toast("Text added: " + s);
    }

    /** Resolves a formula id → real Formula: custom formulas (ids starting with
     *  "C") load from CustomFormulaStore, everything else from FormulaEngine. */
    private Formula formulaById(String id) {
        if (id != null && id.startsWith("C")) {
            Formula cf = CustomFormulaStore.resolve(this, id, formulas);
            if (cf != null && cf.id != null && cf.id.equals(id)) return cf;
        }
        return formulas.byId(id);
    }

    private void applyFormula(String id) {
        pushUndo();
        Formula f = formulaById(id);
        if (selected >= 0) project.clips.get(selected).formula = f;
        else for (int i = 0; i < project.clips.size(); i++) project.clips.get(i).formula = f;
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Motion: " + f.name + " → " + (selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL " + project.clips.size() + " clips"));
    }

    /** Single batch state operation: O(n) field writes, no UI inflation, no rendering. */
    private void applyDurationToAll(int sec) {
        pushUndo();
        long ms = sec * 1000L;
        for (TimelineClip c : project.clips) c.setDurationMs(ms);
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("ALL " + project.clips.size() + " images → " + sec + "s (1 batch)");
    }

    /** Fast: assigns pre-resolved formula sequences per index. State only — nothing is rendered here. */
    private void applyTransition(TransitionType t) {
        if (selected >= 0) { applyTransitionAt(selected, t); return; }
        pushUndo();
        for (TimelineClip c : project.clips) c.transition = t;
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Transition: " + TransitionEngine.label(t) + " → ALL " + project.clips.size() + " clips");
    }

    /** Sets the transition at one specific junction (clip clipIdx → clip clipIdx+1). */
    private void applyTransitionAt(int clipIdx, TransitionType t) {
        if (clipIdx < 0 || clipIdx >= project.clips.size()) return;
        pushUndo();
        project.clips.get(clipIdx).transition = t;
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Transition: " + TransitionEngine.label(t) + " (Clip " + (clipIdx + 1) + " → " + (clipIdx + 2) + ")");
    }

    private void applyEffect(EffectType e) {
        pushUndo();
        if (selected >= 0) project.clips.get(selected).setSingleEffect(e, project.clips.get(selected).effectIntensity);
        else for (TimelineClip c : project.clips) c.setSingleEffect(e, c.effectIntensity);
        saveProject(true);
        if (preview != null) preview.invalidate();
        toast("Effect: " + EffectEngine.label(e) + " → " + (selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL clips"));
    }

    private void duplicateClip() {
        if (selected < 0) return;
        pushUndo();
        TimelineClip c = project.clips.get(selected);
        TimelineClip n = new TimelineClip(c.uri, selected + 2, c.formula);
        n.setDurationMs(c.durationMs);
        n.effect = c.effect;
        n.effectIntensity = c.effectIntensity;
        for (EffectLayer l : c.effectLayers) n.effectLayers.add(l.copy());
        n.transition = c.transition;
        n.transitionDurationSec = c.transitionDurationSec;
        n.transitionPresetId = c.transitionPresetId;
        if (c.borderEffect != null) n.borderEffect = c.borderEffect.copy();
        project.clips.add(selected + 1, n);
        selected = selected + 1;
        saveProject(true);
        buildTimeline(true);
        showClipPanel();
    }

    private void moveClip(int ix, int dir) {
        int to = ix + dir;
        if (ix < 0 || to < 0 || to >= project.clips.size()) return;
        pushUndo();
        Collections.swap(project.clips, ix, to);
        selected = to;
        saveProject(true);
        buildTimeline(true);
        showClipPanel();
    }

    private void deleteClip(int ix) {
        if (ix < 0 || ix >= project.clips.size()) return;
        pushUndo();
        project.clips.remove(ix);
        selected = -1;
        saveProject(true);
        buildTimeline(true);
        showClipPanel();
    }

    /**
     * Part 28: Auto Edit uses the SAME per-clip formula pattern engine. It
     * assigns a built-in pattern so clip i resolves step (i % size) through
     * stateForClip - never a separate motion implementation.
     */
    private void autoEdit(int mode) {
        pushUndo();
        String patternId; float dur; TransitionType trans; EffectType fx;
        switch (mode) {
            case 1:  patternId = "F01"; dur = 5f; trans = TransitionType.CROSS_DISSOLVE; fx = EffectType.CINEMATIC; break;
            case 2:  patternId = "F06"; dur = 3f; trans = TransitionType.ZOOM;            fx = EffectType.VIGNETTE;  break;
            case 3:  patternId = "F15"; dur = 4f; trans = TransitionType.FLASH;           fx = EffectType.SATURATION; break;
            case 4:  patternId = "F03"; dur = 5f; trans = TransitionType.FADE;            fx = EffectType.CINEMATIC; break;
            case 5:  patternId = "F07"; dur = 5f; trans = TransitionType.CROSS_DISSOLVE; fx = EffectType.NONE;      break;
            default: patternId = "F05"; dur = 5f; trans = TransitionType.CROSS_DISSOLVE; fx = EffectType.DREAM;     break;
        }
        for (int i = 0; i < project.clips.size(); i++) {
            TimelineClip c = project.clips.get(i);
            c.setDurationSeconds(dur);
            c.formula = formulas.byId(patternId); // pattern resolves per clip via stateForClip
            c.transition = trans;
            c.effect = fx;
        }
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Auto Edit → " + formulas.byId(patternId).name + " on " + project.clips.size() + " clips");
    }

    private void setPreset(ExportPreset p) {
        pushUndo();
        draftPreset = p;
        project.applyExportPreset(p);
        saveProject(true);
        refreshAfterCanvasChange();
        toast("Canvas set: " + p.label + " (" + project.width + "×" + project.height + ")");
    }

    private void refreshAfterCanvasChange() {
        if (monitor != null) {
            float r = project.width / (float) Math.max(1, project.height);
            monitor.setRatio(r);
            android.view.ViewGroup.LayoutParams lp = monitor.getLayoutParams();
            if (lp != null) { lp.height = calcMonitorHeight(r); monitor.setLayoutParams(lp); }
        }
        if (metaLabel != null) metaLabel.setText(project.clips.size() + " clips • " + project.fps + " FPS • " + project.fitMode.label);
        if (preview != null) preview.invalidate();
    }

    // ---------------------------------------------------------------- playback + audio

    private void togglePlay() {
        if (preview == null) return;
        if (preview.playing) {
            preview.pause();
            pauseAudio();
        } else {
            preview.play();
            startAudioAt(preview.currentTimeSec());
        }
        updatePlayIcon();
    }

    private void updatePlayIcon() {
        if (playButton != null && preview != null) playButton.setImageResource(preview.playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    /**
     * Preview playback bound to the same timeline time the preview renders,
     * honouring the track's own volume, mute, trim start and start offset so
     * what you hear is what the exporter will mix (spec §20).
     */
    private void startAudioAt(float timeSec) {
        releaseAudio();
        AudioTrack track = project.primaryAudio();
        if (track == null || track.isSilent()) return;
        float into = timeSec - track.startSec;
        if (into < 0f) return; // the track has not started on the timeline yet
        try {
            audioPlayer = MediaPlayer.create(this, Uri.parse(track.uri));
            if (audioPlayer == null) throw new IOException("MediaPlayer unavailable");
            float v = track.effectiveVolume();
            audioPlayer.setVolume(v, v);
            audioPlayer.setLooping(track.loop);
            int sourceMs = (int) ((track.trimStartSec + into) * 1000f);
            int dur = audioPlayer.getDuration();
            if (sourceMs > 300 && sourceMs < dur - 300) audioPlayer.seekTo(sourceMs);
            audioPlayer.start();
        } catch (Exception e) {
            audioPlayer = null;
            Log.e(TAG, "Audio preview failed", e);
            toast("Audio could not be decoded: " + (e.getMessage() == null ? "unsupported format" : e.getMessage()));
        }
    }

    private void pauseAudio() {
        if (audioPlayer != null) { try { audioPlayer.pause(); } catch (Exception ignored) {} }
    }

    private void releaseAudio() {
        if (audioPlayer != null) { try { audioPlayer.release(); } catch (Exception ignored) {} audioPlayer = null; }
    }

    private void bindAudio() { /* preview audio starts on Play; nothing to prepare */ }

    // ---------------------------------------------------------------- media pickers

    /**
     * Images come from the system PHOTO PICKER on Android 13+, which needs no
     * runtime permission at all and shows the user's real gallery, albums and
     * recents (spec §21, §22). Older devices fall back to ACTION_GET_CONTENT,
     * which opens the same system document/gallery UI — never a browser.
     */
    private void pickImages() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 33) {
                i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                i.setType("image/*");
                i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                        Math.max(2, MediaStore.getPickImagesMaxLimit()));
            } else {
                i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, PICK_IMAGES);
        } catch (Exception e) {
            Log.e(TAG, "Photo picker failed, falling back", e);
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("image/*");
                fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(fallback, PICK_IMAGES);
            } catch (Exception e2) {
                Log.e(TAG, "Image picker failed", e2);
                toast("Could not open the photo picker");
            }
        }
    }

    /** Audio uses the system document picker (the photo picker is images only). */
    private void pickAudio() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, PICK_AUDIO);
        } catch (Exception e) {
            Log.e(TAG, "Audio picker failed", e);
            toast("Could not open the audio picker");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_CUSTOM_FORMULA && data.hasExtra(CustomFormulaActivity.EXTRA_FORMULA_ID)) {
            String fid = data.getStringExtra(CustomFormulaActivity.EXTRA_FORMULA_ID);
            if (fid != null) {
                applyFormula(fid); // undo/redo-safe state operation (pushUndo inside)
                if ("editor".equals(screen)) showEditor();
                else if ("settings".equals(screen)) showSettings();
            }
            return;
        }
        if (req == PICK_IMAGES) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            if (uris.isEmpty()) return;
            pushUndo();
            int before = project.clips.size();
            for (Uri u : uris) {
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                try {
                    TimelineClip clip = new TimelineClip(u.toString(), project.clips.size() + 1, formulas.defaultFormula());
                    clip.setDurationMs(5000L); // default 5s
                    project.clips.add(clip);
                } catch (Exception e) {
                    Log.e(TAG, "Image clip creation failed: " + u, e);
                    toast("Skipped one image");
                }
            }
            saveProject(true);
            showEditor();
            toast("Imported " + (project.clips.size() - before) + " image(s)");
        }
        if (req == PICK_OVERLAY_IMAGE && data.getData() != null) {
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            String corner = pendingOverlayCorner;
            pendingOverlayCorner = null;
            pushUndo();
            OverlayLayer o = new OverlayLayer();
            o.kind = OverlayLayer.Kind.IMAGE;
            o.uri = u.toString();
            if (corner != null && !corner.isEmpty()) o.applyCornerPreset(corner, 0.06f);
            else { o.x = 0.5f; o.y = 0.5f; }
            project.overlays.add(o);
            selectedOverlay = project.overlays.size() - 1;
            if (ovlTrack != null) ovlTrack.setSelected(selectedOverlay);
            saveProject(true);
            if ("editor".equals(screen)) showEditor();
            toast(corner != null ? "Logo added" : "Layer added");
            return;
        }
        if (req == PICK_AUDIO && data.getData() != null) {
            pushUndo();
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            project.migrateLegacyAudio();
            project.audioTracks.clear();
            AudioTrack t = new AudioTrack(u.toString());
            // Probe the real duration once, so trim/loop decisions and the
            // exporter's timeline math are based on facts, not guesses.
            MediaPlayer probe = null;
            try {
                probe = MediaPlayer.create(this, u);
                if (probe != null) t.sourceDurationMs = probe.getDuration();
            } catch (Exception e) { Log.w(TAG, "Audio probe failed", e); }
            finally { if (probe != null) try { probe.release(); } catch (Exception ignored) {} }
            project.audioTracks.add(t);
            saveProject(true);
            if ("editor".equals(screen)) showEditor();
            toast(t.sourceDurationMs > 0
                    ? "Audio added (" + fmt(t.sourceDurationMs / 1000f) + ") — it will be mixed into the export"
                    : "Audio added — it will be mixed into the export");
        }
    }

    private void removeOrMoveDialog(int ix) {
        new AlertDialog.Builder(this).setTitle("Clip " + (ix + 1))
                .setItems(new String[]{"Move left", "Move right", "Duplicate", "Delete"}, (d, w) -> {
                    if (w == 2) { selected = ix; duplicateClip(); return; }
                    pushUndo();
                    if (w == 0 && ix > 0) moveClip(ix, -1);
                    if (w == 1 && ix < project.clips.size() - 1) moveClip(ix, 1);
                    if (w == 3) deleteClip(ix);
                }).show();
    }

    // ---------------------------------------------------------------- undo / redo (real state history)

    private void pushUndo() {
        try {
            undoStack.push(store.toJsonString(project));
            redoStack.clear();
            while (undoStack.size() > 40) undoStack.removeLast();
        } catch (Exception e) {
            Log.e(TAG, "Undo snapshot failed", e);
        }
    }

    private void undo() {
        if (undoStack.isEmpty()) { toast("Nothing to undo"); return; }
        try {
            redoStack.push(store.toJsonString(project));
            project = store.fromJsonString(undoStack.pop());
            selected = -1;
            saveProject(false);
            if ("editor".equals(screen)) showEditor();
            else if ("home".equals(screen)) showHome();
            else if ("export".equals(screen)) showExportScreen();
            toast("Undo");
        } catch (Exception e) {
            Log.e(TAG, "Undo failed", e);
            toast("Undo failed");
        }
    }

    private void redo() {
        if (redoStack.isEmpty()) { toast("Nothing to redo"); return; }
        try {
            undoStack.push(store.toJsonString(project));
            project = store.fromJsonString(redoStack.pop());
            selected = -1;
            saveProject(false);
            if ("editor".equals(screen)) showEditor();
            else if ("home".equals(screen)) showHome();
            else if ("export".equals(screen)) showExportScreen();
            toast("Redo");
        } catch (Exception e) {
            Log.e(TAG, "Redo failed", e);
            toast("Redo failed");
        }
    }

    // ---------------------------------------------------------------- export screen

    private ProgressBar exportProgress;
    private TextView exportPercent, exportStage;

    private void showExportScreen() {
        screen = "export";
        base();
        addHeader("Export Video", "Existing production export system (protected)", () -> showEditor());
        LinearLayout summary = AeDesign.card(this);
        summary.addView(label("PROJECT", 12, AeDesign.MUTED, Typeface.NORMAL));
        summary.addView(label(project.name, 20, AeDesign.TEXT, Typeface.BOLD));
        summary.addView(label("Duration: " + fmt(project.totalDurationSec()) + " • Clips: " + project.clips.size() + " • " + project.width + "×" + project.height + " @ " + project.fps + " FPS", 14, AeDesign.MUTED, Typeface.NORMAL));
        project.migrateLegacyAudio();
        summary.addView(label(project.audioTracks.isEmpty()
                ? "Audio: none — the MP4 will contain the video stream only."
                : "Audio: " + audioTrackSummary(project.primaryAudio()) + " — encoded into the MP4.",
                12, project.audioTracks.isEmpty() ? AeDesign.MUTED : 0xff7ce0a2, Typeface.NORMAL));
        root.addView(summary);
        root.addView(label("Aspect Ratio / Resolution", 18, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout presets = rowWrap();
        for (ExportPreset p : ExportPreset.values()) if (p != ExportPreset.CUSTOM) addExportPreset(presets, p);
        root.addView(presets);
        root.addView(label("FPS", 18, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout fps = row();
        addChoice(fps, "24", draftFps == 24, () -> { draftFps = 24; showExportScreen(); });
        addChoice(fps, "30", draftFps == 30, () -> { draftFps = 30; showExportScreen(); });
        addChoice(fps, "60", draftFps == 60, () -> { draftFps = 60; showExportScreen(); });
        root.addView(fps);
        LinearLayout fit = row();
        addChoice(fit, "Fill Crop", draftFit == FitMode.FILL, () -> { draftFit = FitMode.FILL; showExportScreen(); });
        addChoice(fit, "Fit Letterbox", draftFit == FitMode.FIT, () -> { draftFit = FitMode.FIT; showExportScreen(); });
        root.addView(fit);
        exportProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        exportProgress.setMax(100);
        exportProgress.setProgress(0);
        root.addView(exportProgress, new LinearLayout.LayoutParams(-1, dp(12)));
        exportPercent = label("0%", 24, AeDesign.ACCENT, Typeface.BOLD);
        exportStage = label("Ready to export. Output uses the existing Gallery save implementation (Movies/AutoEdit).", 14, AeDesign.MUTED, Typeface.NORMAL);
        root.addView(exportPercent);
        root.addView(exportStage);
        Button go = AeDesign.button(this, "EXPORT VIDEO", true);
        // AdGate runs the connectivity check + interstitial sequence, then calls
        // the unchanged export entry point exactly once. startExistingExport()
        // itself, and everything under export/, is untouched.
        AeDesign.press(go, () -> new com.autoedit.ads.AdGate(this).start(MainActivity.this::startExistingExport));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(60));
        lp.setMargins(0, dp(18), 0, 0);
        root.addView(go, lp);
    }

    private void addExportPreset(LinearLayout parent, ExportPreset p) {
        TextView v = label((draftPreset == p ? "✓ " : "") + p.label + "\n" + p.width + "×" + p.height, 13, AeDesign.TEXT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(AeDesign.bg(draftPreset == p ? 0xff102D4A : AeDesign.SURFACE, dp(18), draftPreset == p ? AeDesign.ACCENT : AeDesign.STROKE, 2));
        AeDesign.press(v, () -> { draftPreset = p; showExportScreen(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(78));
        lp.setMargins(dp(4), dp(7), dp(4), dp(7));
        parent.addView(v, lp);
    }

    /** Part 21: validate the project before encoding. Returns an error string, or null if OK. */
    private String validateExport(int width, int height, int fps) {
        if (project == null || project.clips.isEmpty()) return "No images to export — add photos first.";
        if (width <= 0 || height <= 0) return "Invalid canvas size.";
        if (width % 2 != 0 || height % 2 != 0) return "Canvas width/height must be even numbers.";
        if (fps < 15 || fps > 120) return "Invalid frame rate (must be 15–120).";
        FormulaEngine engine = formulas != null ? formulas : new FormulaEngine();
        for (TimelineClip c : project.clips) {
            if (c.durationSec <= 0.05f) return "A clip has an invalid duration.";
            if (c.uri == null) return "One image is missing its source.";
            try (java.io.InputStream is = getContentResolver().openInputStream(android.net.Uri.parse(c.uri))) {
                if (is == null) return "An image can no longer be read: " + c.index;
            } catch (Exception e) {
                return "Image for clip " + c.index + " is unreadable (it may have been moved or deleted).";
            }
            try { engine.stateForClip(c.formula, c.index, 0.5f); }
            catch (Exception e) { return "The motion on clip " + c.index + " is invalid."; }
        }
        return null;
    }

    private void startExistingExport() {
        if (project.clips.isEmpty()) { toast("Import images first"); return; }
        if (exportRunning) { toast("Export already running"); showExportProgressScreen(); return; }
        int width = draftPreset.width, height = draftPreset.height;
        if (draftPreset == ExportPreset.CUSTOM) { width = project.width; height = project.height; }
        if (width % 2 == 1) width++;
        if (height % 2 == 1) height++;
        String err = validateExport(width, height, draftFps);
        if (err != null) { toast(err); return; }
        project.exportPreset = draftPreset;
        project.width = width;
        project.height = height;
        project.fps = draftFps;
        project.fitMode = draftFit;
        if (project.width % 2 == 1) project.width++;
        if (project.height % 2 == 1) project.height++;
        Intent i = new Intent(this, ExportService.class);
        i.setAction(ExportService.ACTION_START);
        i.putExtra("w", project.width);
        i.putExtra("h", project.height);
        i.putExtra("fps", project.fps);
        i.putExtra("fitMode", project.fitMode.name());
        saveProject(true);
        ensureNotificationPermission();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        exportRunning = true;
        lastExportPct = 1;
        lastExportMsg = "Preparing...";
        showExportProgressScreen();
    }

    /**
     * Android 13+ requires POST_NOTIFICATIONS before the export service can
     * show its progress notification. Asked once per install, with the reason
     * up front, and never re-asked after a denial (spec §22) — the export works
     * fine without it, the notification is only a keep-alive + status.
     */
    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (notifPermissionAsked) return;
        notifPermissionAsked = true;
        toast("Allow notifications to see export progress in the background");
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9001);
    }

    /**
     * Full-screen premium export progress screen.
     * Every percentage shown here comes from the REAL export pipeline
     * (ExportService broadcast: image optimization 0–10%, frame rendering
     * 10–99%, finalization 100%). No timers, no fake progress.
     */
    private void showExportProgressScreen() {
        screen = "exporting";
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(AeDesign.BG);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        applySystemInsets(l);
        setContentView(l);

        // hero: rotating neon ring + pulsing glow + logo + particles
        FrameLayout top = new FrameLayout(this);
        ring = new ExportRingView(this);
        ring.setRunning(exportRunning);
        ring.setDone(!exportRunning);
        ring.setProgress(exportRunning ? Math.max(0f, Math.min(1f, lastExportPct / 100f)) : 1f);
        top.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        ImageView close = AeDesign.iconButton(this, R.drawable.ic_close, "Close", false);
        AeDesign.press(close, () -> {
            if (exportRunning) confirmCancelExport();
            else showEditor();
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        clp.topMargin = dp(4);
        clp.rightMargin = dp(4);
        top.addView(close, clp);
        l.addView(top, new LinearLayout.LayoutParams(-1, 0, 1.2f));

        // center content
        LinearLayout center = col();
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = label("EXPORTING VIDEO", 24, AeDesign.TEXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        center.addView(title);
        TextView sub = label("Please wait while we export your video...", 13, AeDesign.MUTED, Typeface.NORMAL);
        sub.setGravity(Gravity.CENTER);
        center.addView(sub);
        pctBig = label(exportRunning ? Math.max(0, Math.min(100, lastExportPct)) + "%" : "100%",
                52, AeDesign.ACCENT, Typeface.BOLD);
        pctBig.setGravity(Gravity.CENTER);
        center.addView(pctBig, new LinearLayout.LayoutParams(-1, -2));
        neonBar = new NeonProgressBar(this);
        neonBar.setRunning(exportRunning);
        neonBar.setProgress(exportRunning ? Math.max(0f, Math.min(1f, lastExportPct / 100f)) : 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(18));
        blp.topMargin = dp(10);
        center.addView(neonBar, blp);
        statusBig = label(exportRunning ? lastExportStage : "Export complete!", 13, AeDesign.MUTED, Typeface.NORMAL);
        statusBig.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(12);
        center.addView(statusBig, slp);
        l.addView(center, new LinearLayout.LayoutParams(-1, -2));
        l.addView(col(), new LinearLayout.LayoutParams(-1, 0, 0.6f));

        if (!exportRunning) showExportComplete(lastExportMsg);
    }

    /** Human status line for a stage. The percentage itself always comes from
     *  real completed work inside that stage — never from a timer. */
    private String exportStatusText(ExportStage stage) {
        switch (stage == null ? ExportStage.PREPARING : stage) {
            case PREPARING:  return "Preparing your video...";
            case OPTIMIZING: return "Optimizing images...";
            case AUDIO:      return "Preparing audio...";
            case RENDERING:  return "Rendering frames...";
            case ENCODING:   return "Encoding video...";
            case FINALIZING: return "Finalizing...";
            case VERIFYING:  return "Verifying the file...";
            case SAVING:     return "Saving to Gallery...";
            case COMPLETE:   return "Export complete!";
            default:         return "Working...";
        }
    }

    private void updateExportProgress(int p, String m) { updateExportProgress(p, ExportStage.PREPARING, m); }

    private void updateExportProgress(int p, ExportStage stage, String m) {
        if (p >= 0 && p < lastExportPct) return; // never move backwards (spec §18)
        lastExportStage = (stage == null ? ExportStage.PREPARING : stage).label;
        // Failure/cancel arrive as negative sentinels. They must never be stored
        // as the "current percent", or a later rebuild of the export screen
        // renders them literally (the user saw "-1%" stuck next to
        // "Finalizing..."). Keep the last real number on screen instead.
        lastExportPct = p < 0 ? Math.max(0, lastExportPct) : p;
        lastExportMsg = m == null ? "" : m;
        if (p == 100) {
            exportRunning = false;
            if ("exporting".equals(screen)) showExportComplete(m);
            else toast("✓ Export complete: " + m);
            if (exportProgress != null) { exportProgress.setProgress(100); exportPercent.setText("100%"); exportStage.setText("✓ Export Complete • " + m); }
            return;
        }
        if (p < 0) {
            exportRunning = false;
            if ("exporting".equals(screen)) showExportFailed(m);
            else toast("Export failed: " + m);
            if (pctBig != null) { pctBig.setText("—"); pctBig.setTextColor(AeDesign.DANGER); }
            // Kill any in-flight stage-label animation FIRST. Its withEndAction
            // runs ~120 ms later and would otherwise overwrite the failure text
            // with the previous stage ("Finalizing..."), which is exactly the
            // stuck-looking screen that was being reported: status frozen on
            // "Finalizing..." while the percent showed an em dash.
            if (statusBig != null) {
                statusBig.animate().cancel();
                statusBig.setAlpha(1f);
                statusBig.setText("Export failed");
                statusBig.setTextColor(AeDesign.DANGER);
            }
            if (ring != null) { ring.setRunning(false); ring.setDone(false); }
            if (neonBar != null) neonBar.setRunning(false);
            if (exportProgress != null) { exportPercent.setText("Failed"); exportStage.setText(m); }
            return;
        }
        if ("exporting".equals(screen)) {
            if (pctBig != null) pctBig.setText(p + "%");
            if (neonBar != null) neonBar.setProgress(p / 100f);
            if (ring != null) ring.setProgress(p / 100f);
            if (statusBig != null) {
                String s = exportStatusText(stage);
                if (!s.equals(statusBig.getText().toString())) {
                    statusBig.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                        statusBig.setText(s);
                        statusBig.animate().alpha(1f).setDuration(180).start();
                    }).start();
                }
            }
        }
        if (exportProgress != null) {
            exportProgress.setProgress(Math.max(0, p));
            exportPercent.setText(p + "%");
            exportStage.setText(stage(p, m));
        }
    }

    private String stage(int p, String m) {
        if (p < 0) return m;
        return lastExportStage + (m == null || m.isEmpty() ? "" : " — " + m);
    }

    // ------------------------------------------------- export completion

    private void showExportComplete(String m) {
        if (statusBig != null) { statusBig.animate().cancel(); statusBig.setAlpha(1f); }
        if (ring != null) { ring.setRunning(false); ring.setDone(true); }
        if (neonBar != null) { neonBar.setRunning(false); neonBar.setProgress(1f); }
        if (pctBig != null) pctBig.setText("100%");
        if (statusBig != null) { statusBig.setText("Export complete!"); statusBig.setTextColor(0xff7ce0a2); }

        // The service already sent the FINAL published MediaStore URI. Only if
        // that is missing (e.g. the activity was recreated mid-export) do we
        // fall back to a display-name lookup.
        String fileName = completionFileName;
        if (fileName == null && m != null) {
            int i = m.lastIndexOf('/');
            if (i >= 0) fileName = m.substring(i + 1).trim();
        }
        completionFileName = fileName;
        if (completionUri == null && fileName != null) completionUri = findExportedVideo(fileName);

        // completion card: thumbnail + actions (replaces the status line)
        LinearLayout holder = (LinearLayout) statusBig.getParent();
        statusBig.setVisibility(View.GONE);

        LinearLayout done = col();
        done.setGravity(Gravity.CENTER_HORIZONTAL);
        done.setPadding(0, dp(14), 0, 0);
        completionThumb = new ImageView(this);
        completionThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        completionThumb.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(18), AeDesign.ACCENT, 2));
        done.addView(completionThumb, new LinearLayout.LayoutParams(dp(120), dp(120)));
        completionThumb.setScaleX(0.6f); completionThumb.setScaleY(0.6f);
        completionThumb.animate().scaleX(1f).scaleY(1f).setDuration(380).start();
        TextView saved = label("Saved to Movies/AutoEdit/" + (fileName == null ? "" : fileName)
                + (completionHasAudio ? "\nVideo + audio" : "\nVideo"), 12, AeDesign.MUTED, Typeface.NORMAL);
        saved.setGravity(Gravity.CENTER);
        done.addView(saved, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout btns = row();
        btns.setGravity(Gravity.CENTER);
        completionPlay = AeDesign.button(this, "Play Video", true);
        btns.addView(completionPlay, new LinearLayout.LayoutParams(-2, dp(48)));
        completionShare = AeDesign.button(this, "Share", false);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(-2, dp(48));
        slp2.leftMargin = dp(8);
        btns.addView(completionShare, slp2);
        Button doneBtn = AeDesign.button(this, "Done", false);
        AeDesign.press(doneBtn, () -> showEditor());
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(-2, dp(48));
        dlp2.leftMargin = dp(8);
        btns.addView(doneBtn, dlp2);
        done.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(6);
        holder.addView(done, dlp);
        wireCompletionVideo();
    }

    /**
     * Looks up the exported video in MediaStore and enables thumbnail /
     * Play / Share. On Android 13+ this needs READ_MEDIA_VIDEO — requested
     * once here, then the UI refreshes automatically when granted.
     */
    private void wireCompletionVideo() {
        if (!"exporting".equals(screen)) return;
        if (completionThumb == null) return;
        // We already hold the URI this app inserted, so no READ_MEDIA_VIDEO
        // permission is needed to play or share it — only to load its
        // thumbnail, which is why the permission request is now optional and
        // can never leave the Play button without a click listener.
        if (completionUri == null && completionFileName != null) completionUri = findExportedVideo(completionFileName);
        boolean wantThumbPerm = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                   != PackageManager.PERMISSION_GRANTED;
        if (completionUri == null && wantThumbPerm)
            requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_VIDEO}, REQ_VIDEO_PERM);
        Bitmap t = completionUri == null ? null : loadVideoThumb(completionUri);
        if (t != null) completionThumb.setImageBitmap(t);
        else completionThumb.setImageResource(R.drawable.ic_play);
        boolean ok = completionUri != null;
        completionPlay.setEnabled(ok);
        completionPlay.setAlpha(ok ? 1f : .5f);
        if (ok) AeDesign.press(completionPlay, () -> playVideo(completionUri));
        completionShare.setAlpha(ok ? 1f : .5f);
        if (ok) AeDesign.press(completionShare, () -> shareVideo(completionUri));
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_VIDEO_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            completionUri = null;
            wireCompletionVideo();
        }
        // Advance the serial startup pass. The result is deliberately not
        // inspected: granted or denied, we simply move on to the next prompt so
        // a refusal can never stall the sequence or crash the app.
        if (req == REQ_STARTUP_NOTIFICATIONS || req == REQ_STARTUP_MEDIA) {
            requestNextStartupPermission();
        }
    }

    private void showExportFailed(String m) {
        if (statusBig != null) { statusBig.animate().cancel(); statusBig.setAlpha(1f); }
        if (ring != null) { ring.setRunning(false); ring.setDone(true); }
        if (neonBar != null) { neonBar.setRunning(false); }
        if (pctBig != null) { pctBig.setText("—"); pctBig.setTextColor(AeDesign.DANGER); }
        if (statusBig != null) { statusBig.setText(m == null ? "Export stopped" : m); statusBig.setTextColor(AeDesign.DANGER); }
        LinearLayout holder = (LinearLayout) statusBig.getParent();
        Button again = AeDesign.button(this, "Back to Editor", true);
        AeDesign.press(again, () -> showEditor());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, dp(52));
        alp.topMargin = dp(18);
        holder.addView(again, alp);
    }

    private Uri findExportedVideo(String fileName) {
        try {
            Uri coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            Cursor c = getContentResolver().query(coll, new String[]{MediaStore.Video.Media._ID},
                    MediaStore.Video.Media.DISPLAY_NAME + "=?", new String[]{fileName},
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (c != null) {
                if (c.moveToFirst()) { long id = c.getLong(0); c.close(); return ContentUris.withAppendedId(coll, id); }
                c.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore lookup failed", e);
        }
        return null;
    }

    /**
     * Modern thumbnail loading: ContentResolver.loadThumbnail on Q+ (no
     * deprecated Thumbnails.getThumbnail). Falls back to nothing on older
     * devices so the UI shows a placeholder rather than crashing.
     */
    private Bitmap loadVideoThumb(Uri uri) {
        if (uri == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.util.Size size = new android.util.Size(dp(320), dp(320));
                android.os.CancellationSignal cs = new android.os.CancellationSignal();
                try { return getContentResolver().loadThumbnail(uri, size, cs); }
                catch (NoSuchMethodError nse) { return null; }
            }
        } catch (Exception e) {
            Log.e(TAG, "Thumbnail load failed", e);
        }
        return null;
    }

    private void playVideo(Uri uri) {
        if (uri == null) { toast("Video is not available yet"); return; }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "video/mp4");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return;
            }
        } catch (Exception e) {
            // resolveActivity can succeed and startActivity still fail (a
            // handler that refuses our URI). Fall through to the chooser.
            Log.w(TAG, "Direct playback failed, offering alternatives", e);
        }
        offerPlaybackAlternatives(uri);
    }

    /**
     * Fallback when nothing can play the file directly (spec §22).
     *
     * A bare "no player installed" toast is a dead end, and on Android 11+
     * {@code resolveActivity} can also return null purely because of package
     * visibility even when a player exists. So instead of failing we hand the
     * user the two routes that always work: the system chooser, which lets any
     * app that can take a video/mp4 URI have it, and Share.
     */
    private void offerPlaybackAlternatives(Uri uri) {
        String name = completionFileName == null ? "your video" : completionFileName;
        new android.app.AlertDialog.Builder(this)
                .setTitle("No default video player")
                .setMessage("No app is set to open videos directly. You can still watch \""
                        + name + "\" - choose how to open it, or share it to another app.\n\n"
                        + "The file is saved in Movies/AutoEdit and is visible in Gallery.")
                .setPositiveButton("Choose an app", (d, w) -> {
                    try {
                        Intent pick = new Intent(Intent.ACTION_VIEW);
                        pick.setDataAndType(uri, "video/mp4");
                        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(Intent.createChooser(pick, "Open video with"));
                    } catch (Exception e) {
                        Log.e(TAG, "Chooser failed", e);
                        toast("Could not open the video: " + e.getMessage());
                    }
                })
                .setNeutralButton("Share", (d, w) -> shareVideo(uri))
                .setNegativeButton("Close", null)
                .show();
    }

    private void shareVideo(Uri uri) {
        if (uri == null) { toast("Video is not available yet"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("video/mp4");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            PackageManager pm = getPackageManager();
            if (i.resolveActivity(pm) == null) { toast("No app available to share."); return; }
            startActivity(Intent.createChooser(i, "Share video"));
        } catch (Exception e) {
            Log.e(TAG, "Share failed", e);
            toast("Could not share video: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- settings

    private void showSettings() {
        String from = screen;
        screen = "settings";
        base();
        addHeader("Settings", "Editor • Playback • Export • Storage • About", () -> { if ("home".equals(from)) showHome(); else showEditor(); });
        LinearLayout cf = AeDesign.card(this);
        cf.addView(label("Custom Formula", 16, AeDesign.TEXT, Typeface.BOLD));
        cf.addView(label("Create your own motion formulas with keyframes. Saved formulas appear in the editor Formula panel with a CUSTOM badge.", 12, AeDesign.MUTED, Typeface.NORMAL));
        Button openCf = AeDesign.button(this, "Open Custom Formulas", true);
        AeDesign.press(openCf, () -> openCustomFormulaLibrary());
        LinearLayout.LayoutParams cfbtn = new LinearLayout.LayoutParams(-1, dp(48));
        cfbtn.topMargin = dp(8);
        cf.addView(openCf, cfbtn);
        LinearLayout.LayoutParams cflp = new LinearLayout.LayoutParams(-1, -2);
        cflp.setMargins(0, dp(8), 0, dp(4));
        root.addView(cf, cflp);
        showPanelIntoRoot("Editor", new String[]{"Default aspect ratio: " + draftPreset.label, "Default FPS: " + draftFps, "Auto-save: ON (every 30s)", "Undo / Redo: 40 steps"});
        showPanelIntoRoot("Playback", new String[]{"Preview quality: Optimized (sampled decode + LRU)", "Preview FPS: " + project.fps, "Audio in preview: ON (plays with Play)"});
        showPanelIntoRoot("Export", new String[]{"Resolution: " + project.width + "×" + project.height,
                "Pipeline: MediaCodec H.264 → MediaMuxer MP4",
                "Audio: " + (project.hasAudio() ? "AAC mixed into the MP4" : "none")});
        showPanelIntoRoot("Storage", new String[]{"Export location: Movies/AutoEdit", "Image cache: app cache dir (auto-cleaned)"});
        showPanelIntoRoot("About", new String[]{"Auto-Edit v" + UpdateChecker.localVersionName(this), "Offline-first: media never leaves the device"});
    }

    // ---------------------------------------------------------------- helpers

    private void addHeader(String title, String subtitle, Runnable back) {
        LinearLayout h = row();
        h.setGravity(Gravity.CENTER_VERTICAL);
        ImageView b = AeDesign.iconButton(this, R.drawable.ic_back, "Back", false);
        AeDesign.press(b, back);
        h.addView(b, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout t = col();
        t.addView(label(title, 24, AeDesign.TEXT, Typeface.BOLD));
        t.addView(label(subtitle, 13, AeDesign.MUTED, Typeface.NORMAL));
        h.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(h);
    }

    private void addAction(LinearLayout p, String s, Runnable r) {
        TextView v = label(s, 12, AeDesign.TEXT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(18), AeDesign.STROKE, 1));
        AeDesign.press(v, r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), dp(48));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        p.addView(v, lp);
    }

    private void addChoice(LinearLayout p, String s, boolean sel, Runnable r) {
        TextView v = label((sel ? "✓ " : "") + s, 14, AeDesign.TEXT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(AeDesign.bg(sel ? 0xff102D4A : AeDesign.SURFACE, dp(20), sel ? AeDesign.ACCENT : AeDesign.STROKE, 2));
        AeDesign.press(v, r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
        lp.setMargins(dp(4), dp(8), dp(4), dp(8));
        p.addView(v, lp);
    }

    private void showPanelIntoRoot(String title, String[] rows) {
        LinearLayout c = AeDesign.card(this);
        c.addView(label(title, 16, AeDesign.TEXT, Typeface.BOLD));
        for (String s : rows) c.addView(label("• " + s, 13, AeDesign.MUTED, Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(4));
        root.addView(c, lp);
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout rowWrap() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.LEFT); return l; }
    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView label(String s, int sp, int color, int style) { return AeDesign.text(this, s, sp, color, style); }


    // ---------------------------------------------------------------- bulk selection (10)

    private void selectAllClips() {
        if (project.clips.isEmpty()) { toast("No clips to select"); return; }
        multiSelected.clear();
        for (int i = 0; i < project.clips.size(); i++) multiSelected.add(i);
        selected = 0;
        bulkSelectMode = true;
        buildTimeline(false);
        showClipPanel();
        toast("Selected all " + project.clips.size() + " clips");
    }

    private void clearSelection() {
        multiSelected.clear();
        bulkSelectMode = false;
        selected = -1;
        buildTimeline(false);
        showClipPanel();
        toast("Selection cleared");
    }

    private Set<Integer> effectiveSelection() {
        if (!multiSelected.isEmpty()) return new HashSet<>(multiSelected);
        if (selected >= 0 && selected < project.clips.size()) {
            Set<Integer> s = new HashSet<>(); s.add(selected); return s;
        }
        return Collections.emptySet();
    }

    private void applyDurationToSelection(int sec) {
        Set<Integer> sel = effectiveSelection();
        if (sel.isEmpty()) { toast("Select clips first"); return; }
        pushUndo();
        for (int idx : sel) project.clips.get(idx).setDurationMs(sec * 1000L);
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast(sel.size() + " clips \u2192 " + sec + "s");
    }

    private void applyMotionToSelection(String motionId) {
        Set<Integer> sel = effectiveSelection();
        if (sel.isEmpty()) { toast("Select clips first"); return; }
        Formula f = formulaById(motionId);
        pushUndo();
        for (int idx : sel) project.clips.get(idx).formula = f;
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Motion " + f.name + " \u2192 " + sel.size() + " clips");
    }

    // ---------------------------------------------------------------- images / media management (18,19)

    private void imagesPanel() {
        openTool("images");
        if (panelHost == null) return;
        panelHost.removeAllViews();
        panelHost.addView(label("Images / Media (" + project.clips.size() + " clips)", 16, AeDesign.TEXT, Typeface.BOLD));
        if (project.clips.isEmpty()) {
            LinearLayout empty = AeDesign.card(this);
            empty.setGravity(Gravity.CENTER);
            ImageView art = new ImageView(this);
            art.setImageResource(R.drawable.ic_images);
            art.setColorFilter(AeDesign.ACCENT);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setPadding(dp(8), dp(8), dp(8), dp(8));
            empty.addView(art, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView t = label("Start your story", 18, AeDesign.TEXT, Typeface.BOLD); t.setGravity(Gravity.CENTER); empty.addView(t);
            TextView s = label("Add images to create a cinematic video. Supports 1 to 1000 images.", 12, AeDesign.MUTED, Typeface.NORMAL); s.setGravity(Gravity.CENTER); empty.addView(s);
            Button add = AeDesign.button(this, "+ ADD IMAGES", true);
            AeDesign.press(add, this::pickImages);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
            lp.setMargins(0, dp(12), 0, 0);
            empty.addView(add, lp);
            panelHost.addView(empty);
            return;
        }
        // Action row
        LinearLayout actions = rowWrap();
        addAction(actions, "+ Add Images", this::pickImages);
        addAction(actions, "Select All", this::selectAllClips);
        addAction(actions, "Deselect All", this::clearSelection);
        addAction(actions, "Save to Gallery", this::saveImagesToGallery);
        panelHost.addView(actions);

        LinearLayout bulk = rowWrap();
        addAction(bulk, "Delete Selected", this::deleteSelectedClips);
        addAction(bulk, "Duplicate Sel", this::duplicateSelectedClips);
        addAction(bulk, "Reverse Order", this::reverseClips);
        panelHost.addView(bulk);

        // Selected info
        Set<Integer> sel = effectiveSelection();
        String selInfo = sel.isEmpty() ? "No selection — tap a clip or Select All" : sel.size() + " clip(s) selected (blue highlight)";
        panelHost.addView(label(selInfo, 12, sel.isEmpty() ? AeDesign.MUTED : AeDesign.ACCENT, Typeface.NORMAL));

        // Bulk duration quick apply
        panelHost.addView(label("Apply duration to selection", 13, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout durs = row();
        for (int sec : new int[]{3,4,5,6,7,8}) {
            final int s = sec;
            TextView v = label(s+"s", 12, AeDesign.TEXT, Typeface.BOLD);
            v.setGravity(Gravity.CENTER);
            v.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(12), AeDesign.STROKE, 1));
            AeDesign.press(v, () -> applyDurationToSelection(s));
            LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(0, dp(40), 1);
            lpp.setMargins(dp(3), dp(3), dp(3), dp(3));
            durs.addView(v, lpp);
        }
        panelHost.addView(durs);

        // Bulk motion quick apply
        panelHost.addView(label("Apply motion to selection — open Motion panel for full library", 12, AeDesign.MUTED, Typeface.NORMAL));
        LinearLayout mrow = rowWrap();
        addAction(mrow, "Auto Motion", this::applyAutoMotionToSelection);
        addAction(mrow, "Random Motion", () -> applyRandomMotionToSelection(false));
        panelHost.addView(mrow);

        // Create video project from selected
        panelHost.addView(label("Create video from selection", 13, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout createRow = rowWrap();
        addChoice(createRow, "9:16 Shorts", project.aspectRatio == AspectRatio.R9_16, () -> { project.aspectRatio = AspectRatio.R9_16; applyAspectToPreset(AspectRatio.R9_16); saveProject(true); refreshAfterCanvasChange(); toast("Canvas 9:16"); });
        addChoice(createRow, "16:9 YouTube", project.aspectRatio == AspectRatio.R16_9, () -> { project.aspectRatio = AspectRatio.R16_9; applyAspectToPreset(AspectRatio.R16_9); saveProject(true); refreshAfterCanvasChange(); toast("Canvas 16:9"); });
        addChoice(createRow, "1:1", project.aspectRatio == AspectRatio.R1_1, () -> { project.aspectRatio = AspectRatio.R1_1; applyAspectToPreset(AspectRatio.R1_1); saveProject(true); refreshAfterCanvasChange(); toast("Canvas 1:1"); });
        panelHost.addView(createRow);

        panelHost.addView(label("Images are stored as lightweight URIs + metadata. Full bitmaps are decoded only for preview/export with downsampling & LRU — 500–1000 images stay responsive.", 11, AeDesign.MUTED, Typeface.NORMAL));
    }

    private void deleteSelectedClips() {
        Set<Integer> sel = effectiveSelection();
        if (sel.isEmpty()) { toast("Nothing selected"); return; }
        pushUndo();
        List<Integer> sorted = new ArrayList<>(sel);
        Collections.sort(sorted, Collections.reverseOrder());
        for (int idx : sorted) if (idx >=0 && idx < project.clips.size()) project.clips.remove(idx);
        multiSelected.clear();
        selected = -1;
        bulkSelectMode = false;
        saveProject(true);
        buildTimeline(true);
        showClipPanel();
        toast("Deleted " + sel.size() + " clip(s)");
    }

    private void duplicateSelectedClips() {
        Set<Integer> sel = effectiveSelection();
        if (sel.isEmpty()) { toast("Nothing selected"); return; }
        pushUndo();
        List<TimelineClip> toAdd = new ArrayList<>();
        for (int idx : sel) {
            TimelineClip c = project.clips.get(idx);
            TimelineClip n = new TimelineClip(c.uri, project.clips.size()+1, c.formula);
            n.setDurationMs(c.durationMs);
            n.effect = c.effect; n.effectIntensity = c.effectIntensity;
            for (EffectLayer l : c.effectLayers) n.effectLayers.add(l.copy());
            n.transition = c.transition; n.transitionDurationSec = c.transitionDurationSec;
            n.transitionPresetId = c.transitionPresetId;
            if (c.borderEffect != null) n.borderEffect = c.borderEffect.copy();
            toAdd.add(n);
        }
        project.clips.addAll(toAdd);
        saveProject(true);
        buildTimeline(true);
        imagesPanel();
        toast("Duplicated " + toAdd.size() + " clip(s)");
    }

    private void reverseClips() {
        if (project.clips.size() < 2) { toast("Need at least 2 clips"); return; }
        pushUndo();
        Collections.reverse(project.clips);
        multiSelected.clear();
        selected = -1;
        saveProject(true);
        buildTimeline(true);
        showClipPanel();
        toast("Order reversed");
    }

    private void saveImagesToGallery() {
        Set<Integer> sel = effectiveSelection();
        if (sel.isEmpty()) {
            // If nothing selected, save all
            if (project.clips.isEmpty()) { toast("No images to save"); return; }
            sel = new HashSet<>();
            for (int i=0;i<project.clips.size();i++) sel.add(i);
        }
        final Set<Integer> toSave = new HashSet<>(sel);
        // Background batch with progress dialog, downsampling, no OOM
        ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("Saving to Gallery");
        pd.setMessage("Preparing " + toSave.size() + " images...");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(toSave.size());
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            int ok = 0, fail = 0;
            List<Integer> list = new ArrayList<>(toSave);
            Collections.sort(list);
            for (int i=0;i<list.size();i++) {
                int idx = list.get(i);
                if (idx <0 || idx >= project.clips.size()) { fail++; continue; }
                String uriStr = project.clips.get(idx).uri;
                try {
                    Uri uri = Uri.parse(uriStr);
                    // Decode with sampling to avoid OOM, then save via GallerySaver
                    Bitmap bmp = null;
                    try (InputStream is = getContentResolver().openInputStream(uri)) {
                        if (is != null) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inSampleSize = 1;
                            // First decode bounds to avoid huge
                            // For saving we want reasonable size, not full 4000px
                            bmp = BitmapFactory.decodeStream(is);
                        }
                    } catch (Exception e) {
                        // fallback try direct
                        Log.w(TAG, "Save decode failed idx " + idx, e);
                    }
                    if (bmp == null) {
                        // Try via content resolver with downsampling
                        try {
                            BitmapFactory.Options bounds = new BitmapFactory.Options();
                            bounds.inJustDecodeBounds = true;
                            try (InputStream is2 = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(is2, null, bounds); }
                            int sample = 1;
                            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *=2;
                            BitmapFactory.Options opts2 = new BitmapFactory.Options();
                            opts2.inSampleSize = sample;
                            try (InputStream is3 = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(is3, null, opts2); }
                        } catch (Exception e2) { Log.e(TAG, "Second decode failed", e2); }
                    }
                    if (bmp == null) { fail++; continue; }
                    String name = "AutoEdit_img_" + System.currentTimeMillis() + "_" + idx;
                    // Use GallerySaver which handles MediaStore correctly
                    GallerySaver.save(MainActivity.this, bmp, name, "JPG", 92, GallerySaver.Folder.PICTURES);
                    bmp.recycle();
                    ok++;
                } catch (Exception e) {
                    Log.e(TAG, "Save to gallery failed idx " + idx, e);
                    fail++;
                }
                final int p = i+1;
                final int okF = ok, failF = fail;
                handler.post(() -> {
                    pd.setProgress(p);
                    pd.setMessage("Saved " + okF + " / " + toSave.size() + (failF>0?" ("+failF+" failed)":""));
                });
                // Yield to avoid blocking UI too long, and batch
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
            final int okFinal = ok, failFinal = fail;
            handler.post(() -> {
                try { pd.dismiss(); } catch (Exception ignored) {}
                if (failFinal==0) toast("Saved " + okFinal + " image(s) to Pictures/AutoEdit");
                else toast("Saved " + okFinal + " image(s), " + failFinal + " failed — see log");
            });
        }, "SaveImagesToGallery").start();
    }

    private void applyAutoMotionToSelection() {
        Set<Integer> sel = effectiveSelection();
        if (sel.isEmpty()) {
            if (project.clips.isEmpty()) { toast("No clips"); return; }
            sel = new HashSet<>();
            for (int i=0;i<project.clips.size();i++) sel.add(i);
        }
        String[] modes = new String[]{"Zoom In","Pan Left","Zoom Out","Pan Right","Push In","Pull Out"};
        List<Formula> motions = formulas.motions();
        // Map to motion names intelligently
        Map<String,String> map = new HashMap<>();
        for (Formula f: motions) map.put(f.name.toLowerCase(), f.id);
        String[] seq = {"slow zoom in","slow zoom out","pan left","pan right","cinematic push","cinematic pull","zoom","pan"};
        pushUndo();
        int i=0;
        List<Integer> ordered = new ArrayList<>(sel);
        Collections.sort(ordered);
        for (int idx : ordered) {
            String mode = modes[i % modes.length];
            String bestId = null;
            for (Formula f: motions) if (f.name.toLowerCase().contains(mode.toLowerCase().split(" ")[0])) { bestId = f.id; break; }
            if (bestId == null) bestId = motions.get((idx*7)%motions.size()).id;
            else {
                // Try to find exact like Zoom In etc.
                for (Formula f: motions) if (f.name.equalsIgnoreCase(mode)) { bestId = f.id; break; }
            }
            project.clips.get(idx).formula = formulas.byId(bestId);
            i++;
        }
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Auto motion \u2192 " + sel.size() + " clips (balanced cinematic)");
    }

    private void applyRandomMotionToSelection(boolean balanced) {
        Set<Integer> sel = effectiveSelection();
        if (sel.isEmpty()) {
            if (project.clips.isEmpty()) { toast("No clips"); return; }
            sel = new HashSet<>();
            for (int i=0;i<project.clips.size();i++) sel.add(i);
        }
        List<Formula> motions = formulas.motions();
        Random rnd = new Random();
        pushUndo();
        List<Integer> ordered = new ArrayList<>(sel);
        Collections.sort(ordered);
        String prevId = null;
        for (int idx : ordered) {
            Formula pick;
            int tries=0;
            do {
                pick = motions.get(rnd.nextInt(motions.size()));
                tries++;
            } while (balanced && pick.id.equals(prevId) && tries<5);
            project.clips.get(idx).formula = formulas.byId(pick.id);
            prevId = pick.id;
        }
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Random motion \u2192 " + sel.size() + " clips");
    }

    private void applyMinimalMotionToSelection() {
        Set<Integer> sel = effectiveSelection();
        if (sel.isEmpty()) {
            if (project.clips.isEmpty()) { toast("No clips"); return; }
            sel = new HashSet<>();
            for (int i=0;i<project.clips.size();i++) sel.add(i);
        }
        String[] minimalIds = {"33","18","34","00"};
        pushUndo();
        int idx=0;
        List<Integer> ordered = new ArrayList<>(sel);
        Collections.sort(ordered);
        for (int clipIdx : ordered) {
            String mid = minimalIds[idx % minimalIds.length];
            project.clips.get(clipIdx).formula = formulas.byId(mid);
            idx++;
        }
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Minimal motion \u2192 " + sel.size() + " clips (subtle)");
    }

    private void fitImagesToAudio() {
        AudioTrack t = project.primaryAudio();
        if (t == null) { toast("Add audio first"); return; }
        float audioSec = t.effectiveDurationSec();
        if (audioSec <= 0) audioSec = audioLengthSec(t);
        if (audioSec <= 0 || project.clips.isEmpty()) { toast("No audio duration"); return; }
        pushUndo();
        float perClip = audioSec / project.clips.size();
        perClip = Math.max(0.5f, Math.min(60f, perClip));
        for (TimelineClip c : project.clips) c.setDurationSeconds(perClip);
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Fitted " + project.clips.size() + " clips to audio (" + String.format(Locale.US,"%.1fs", perClip) + " each \u2192 " + String.format(Locale.US,"%.1fs", perClip*project.clips.size()) + " total)");
    }

    private void saveProject(boolean visible) {
        if (store != null && project != null) {
            if (visible && saveStatus != null) saveStatus.setText("Saving...");
            store.save(project);
            if (visible && saveStatus != null) handler.postDelayed(() -> saveStatus.setText("Saved"), 350);
        }
    }

    private String fmt(float sec) { int s = Math.round(sec); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }
    private int dp(int v) { return AeDesign.dp(this, v); }
    private int calcMonitorHeight(float ratio) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int availW = Math.max(dp(100), screenW - dp(32));
        int h = (int) (availW / Math.max(0.3f, Math.min(3f, ratio)));
        return Math.max(dp(180), Math.min(dp(360), h));
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
