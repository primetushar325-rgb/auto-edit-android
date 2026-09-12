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

import com.autoedit.model.*;
import com.autoedit.engine.*;
import com.autoedit.project.*;
import com.autoedit.export.*;
import com.autoedit.ui.*;
import com.autoedit.formula.CustomFormulaActivity;
import com.autoedit.frames.FrameExtractorActivity;
import com.autoedit.update.UpdateActivity;
import com.autoedit.update.UpdateChecker;
import com.autoedit.update.VersionConfig;
import com.autoedit.update.SemVer;
import com.autoedit.zip.ZipBatchImporter;
import com.autoedit.zip.ZipBatchModels;
import com.autoedit.zip.ZipBatchModels.BatchResult;
import com.autoedit.zip.ZipBatchModels.ReadyImage;
import com.autoedit.zip.ZipBatchModels.UnnumberedPolicy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_IMAGES = 10, PICK_AUDIO = 11, REQ_CUSTOM_FORMULA = 12,
            PICK_OVERLAY_IMAGE = 13, PICK_LOGO = 14, PICK_ZIP_BATCH = 15, PICK_DEVICE_FILES = 16;
    private static final String TAG = "AutoEditMain";

    private EditProject project;
    private ProjectStore store;
    private ProjectLibrary library;
    private String activeProjectId;
    private FormulaEngine formulas;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private ZipBatchImporter zipImporter;
    private AlertDialog zipProgressDialog;
    private TextView zipProgressLabel;
    private ProgressBar zipProgressBar;
    private BatchResult pendingZipResult;
    private boolean zipKeepDuplicates = true;
    private UnnumberedPolicy zipUnnumberedPolicy = UnnumberedPolicy.SKIP;
    private LinearLayout root;
    private TextView saveStatus;
    private String screen = "home";
    private int selected = -1;

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
        store = new ProjectStore(this);
        library = new ProjectLibrary(this);
        formulas = new FormulaEngine();
        // Multi-project library: bootstrap legacy single-project into the list
        // and load the active one. Editor/export still use the same EditProject.
        activeProjectId = library.ensureBootstrapped();
        project = library.loadActive();
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
        if (zipImporter != null) zipImporter.cancel();
        try { bg.shutdownNow(); } catch (Exception ignored) {}
    }

    @Override public void onBackPressed() {
        if ("exporting".equals(screen)) {
            if (exportRunning) confirmCancelExport();
            else showEditor();
            return;
        }
        if ("zip_import".equals(screen) || "zip_summary".equals(screen)) {
            if (zipImporter != null) zipImporter.cancel();
            dismissZipProgress();
            showEditor();
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

    /** Status bar / nav bar / notch via window insets — no hardcoded heights. */
    private void applySystemInsets(View v) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            view.setPadding(dp(16) + left, dp(14) + top, dp(16) + right, dp(14) + bottom);
            return insets;
        });
    }

    // ---------------------------------------------------------------- home

    /**
     * Home is a single vertical ScrollView so every saved project is reachable.
     * Status-bar / nav-bar insets stay on the outer root via {@link #applySystemInsets};
     * the scroll content adds extra bottom padding so the last card clears the
     * gesture/nav area. No nested conflicting scroll containers.
     */
    private void showHome() {
        screen = "home";
        base();

        // Outer root holds only the scroll surface (fill height). Insets already
        // applied by base() — do NOT add a second nested ScrollView with its own
        // conflicting height weight that would clip lower projects.
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.setVerticalScrollBarEnabled(true);
        LinearLayout content = col();
        // Extra bottom padding so the final project card clears gesture nav.
        content.setPadding(0, 0, 0, dp(48));

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_autoedit_alpha);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAdjustViewBounds(true);
        header.addView(logo, new LinearLayout.LayoutParams(dp(66), dp(48)));
        LinearLayout titles = col();
        titles.addView(label("Auto-Edit", 30, AeDesign.TEXT, Typeface.BOLD));
        titles.addView(label("Create. Edit. Export.", 14, AeDesign.MUTED, Typeface.NORMAL));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView gear = AeDesign.iconButton(this, R.drawable.ic_settings, "Settings", false);
        AeDesign.press(gear, () -> showSettings());
        header.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        content.addView(header);

        Button create = AeDesign.button(this, "+ Create Project", true);
        AeDesign.press(create, () -> showCreateProject(false));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(62));
        cp.setMargins(0, dp(22), 0, dp(16));
        content.addView(create, cp);

        List<ProjectLibrary.Entry> entries = library == null
                ? Collections.<ProjectLibrary.Entry>emptyList()
                : library.list();
        content.addView(label("Recent Projects  ·  " + entries.size(), 20, AeDesign.TEXT, Typeface.BOLD));

        if (entries.isEmpty()) {
            content.addView(buildEmptyProjectsCard());
        } else {
            for (int i = 0; i < entries.size(); i++) {
                content.addView(buildProjectCard(entries.get(i), i == 0));
            }
        }

        // ---- Prompt Library entry ----
        content.addView(buildHomeFeatureCard(
                R.drawable.ic_formula, "Prompts",
                "Prompt name • description • preview • formula",
                "OPEN", false, () -> showPrompts()));

        // ---- Video Frame Extractor entry ----
        content.addView(buildHomeFeatureCard(
                R.drawable.ic_images, "🎬 Video Frame Extractor",
                "Extract frames from your video automatically — 100% offline",
                "OPEN", true, () -> {
                    try {
                        startActivity(new Intent(this, FrameExtractorActivity.class));
                    } catch (Exception e) {
                        Log.e(TAG, "Frame extractor failed", e);
                        toast("Could not open Frame Extractor");
                    }
                }));

        scroller.addView(content);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private LinearLayout buildEmptyProjectsCard() {
        LinearLayout c = AeDesign.card(this);
        c.setGravity(Gravity.CENTER);
        TextView icon = label("No projects yet", 20, AeDesign.TEXT, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        c.addView(icon);
        TextView b = label("Create your first video from images and make it move.", 14, AeDesign.MUTED, Typeface.NORMAL);
        b.setGravity(Gravity.CENTER);
        c.addView(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout buildHomeFeatureCard(int iconRes, String title, String sub,
                                              String btnLabel, boolean primary, Runnable action) {
        LinearLayout card = AeDesign.card(this);
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(AeDesign.ACCENT);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(16), AeDesign.STROKE, 1));
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout info = col();
        info.setPadding(dp(12), 0, 0, 0);
        info.addView(label(title, 17, AeDesign.TEXT, Typeface.BOLD));
        info.addView(label(sub, 12, AeDesign.MUTED, Typeface.NORMAL));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        Button open = AeDesign.button(this, btnLabel, primary);
        AeDesign.press(open, action);
        row.addView(open, new LinearLayout.LayoutParams(-2, dp(44)));
        card.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(lp);
        return card;
    }

    /** One project row on the Home list. Fully visible when scrolled into view. */
    private LinearLayout buildProjectCard(ProjectLibrary.Entry e, boolean isActive) {
        LinearLayout card = AeDesign.card(this);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (isActive) {
            card.setBackground(AeDesign.bg(0xff0c2238, dp(26), AeDesign.ACCENT, 2));
        }
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView thumb = label(String.format(Locale.US, "%d", e.clipCount), 22, AeDesign.ACCENT, Typeface.BOLD);
        thumb.setGravity(Gravity.CENTER);
        thumb.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(16), AeDesign.STROKE, 1));
        top.addView(thumb, new LinearLayout.LayoutParams(dp(72), dp(60)));
        LinearLayout info = col();
        info.setPadding(dp(12), 0, 0, 0);
        String title = e.name + (isActive ? "  ·  Active" : "");
        info.addView(label(title, 17, AeDesign.TEXT, Typeface.BOLD));
        info.addView(label(e.clipCount + " clips • " + fmt(e.durationSec)
                + " • " + e.width + "×" + e.height
                + (e.hasAudio ? " • audio" : ""), 12, AeDesign.MUTED, Typeface.NORMAL));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView more = AeDesign.iconButton(this, R.drawable.ic_settings, "Project menu", false);
        AeDesign.press(more, () -> projectMenuFor(e.id));
        top.addView(more, new LinearLayout.LayoutParams(dp(40), dp(40)));
        card.addView(top);

        Button open = AeDesign.button(this, isActive ? "Continue Editing" : "Open Project", true);
        AeDesign.press(open, () -> openProject(e.id));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(48));
        blp.setMargins(0, dp(12), 0, 0);
        card.addView(open, blp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(lp);
        return card;
    }

    private void openProject(String id) {
        if (library == null || id == null) return;
        // Persist the current project before switching.
        if (project != null && activeProjectId != null) library.saveProject(activeProjectId, project);
        library.setActive(id);
        activeProjectId = id;
        project = library.loadActive();
        draftPreset = project.exportPreset;
        draftFps = project.fps;
        draftFit = project.fitMode;
        undoStack.clear();
        redoStack.clear();
        selected = -1;
        showEditor();
    }

    private void projectMenuFor(String id) {
        final String pid = id;
        String[] ops = {"Open", "Rename", "Duplicate", "Delete", "Project Settings"};
        new AlertDialog.Builder(this).setTitle("Project").setItems(ops, (d, w) -> {
            if (w == 0) openProject(pid);
            if (w == 1) renameProjectId(pid);
            if (w == 2) {
                String nid = library.duplicate(pid);
                if (nid != null) { toast("Project duplicated"); showHome(); }
            }
            if (w == 3) {
                new AlertDialog.Builder(this)
                        .setTitle("Delete project?")
                        .setMessage("This cannot be undone.")
                        .setPositiveButton("Delete", (dd, ww) -> {
                            boolean wasActive = pid.equals(activeProjectId);
                            library.delete(pid);
                            if (wasActive) {
                                activeProjectId = library.activeId();
                                project = library.loadActive();
                            }
                            showHome();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
            if (w == 4) {
                openProject(pid);
                showCreateProject(true);
            }
        }).show();
    }

    private void renameProjectId(String id) {
        EditProject p = library.loadById(id);
        if (p == null) return;
        final EditText e = new EditText(this);
        e.setText(p.name);
        e.setTextColor(AeDesign.TEXT);
        e.setHintTextColor(AeDesign.MUTED);
        e.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(10), AeDesign.STROKE, 1));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        new AlertDialog.Builder(this).setTitle("Rename project").setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    library.rename(id, e.getText().toString());
                    if (id.equals(activeProjectId) && project != null) {
                        project.name = e.getText().toString();
                        saveProject(true);
                    }
                    showHome();
                }).setNegativeButton("Cancel", null).show();
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

    private void projectMenu() {
        if (activeProjectId != null) projectMenuFor(activeProjectId);
    }

    private void renameProject() {
        if (activeProjectId != null) renameProjectId(activeProjectId);
    }

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
        AeDesign.press(create, () -> {
            if (!settingsOnly) {
                // Persist the current project before opening a brand-new one.
                if (project != null && activeProjectId != null) {
                    library.saveProject(activeProjectId, project);
                }
                EditProject fresh = new EditProject();
                applyDraftTo(fresh);
                activeProjectId = library.createNew(fresh);
                project = fresh;
                undoStack.clear();
                redoStack.clear();
                selected = -1;
            } else {
                applyDraftToProject();
                saveProject(true);
            }
            showEditor();
        });
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

    private void applyDraftToProject() { applyDraftTo(project); }

    private void applyDraftTo(EditProject p) {
        if (p == null) return;
        int h = draftQuality;
        int w = Math.round(h * draftPreset.width / (float) draftPreset.height);
        if (draftPreset == ExportPreset.PORTRAIT_9_16) { w = 1080; h = draftQuality == 2160 ? 3840 : 1920; }
        else if (draftPreset == ExportPreset.SQUARE_1_1) { w = h = draftQuality; }
        else if (draftPreset == ExportPreset.PORTRAIT_4_5) { w = 1080; h = 1350; }
        else if (draftPreset == ExportPreset.CLASSIC_4_3) { w = 1440; h = 1080; }
        p.exportPreset = draftPreset;
        p.width = w;
        p.height = h;
        p.fps = draftFps;
        p.fitMode = draftFit;
    }

    // ---------------------------------------------------------------- editor

    /**
     * Editor layout (stable preview — never collapses):
     * <pre>
     *  Header
     *  LARGE PREVIEW  (explicit height floor; weight fills leftover)
     *  Transport
     *  Compact timeline (fixed height — zoom does NOT resize preview)
     *  Single-row tool strip
     *  Bounded panel host (max height; scrolls inside)
     * </pre>
     * Motion/Formula/Effects/Transitions open as floating {@link PanelSheet}
     * overlays and never shrink the monitor.
     */
    private void showEditor() {
        screen = "editor";
        base();
        // Editor uses slightly tighter side padding so the monitor is wider.
        root.setPadding(dp(10), root.getPaddingTop(), dp(10), root.getPaddingBottom());
        tiles.clear();
        transitionScopeClip = -1;
        sheet = null; // rebuild sheet against the new decor after setContentView

        // --- header: back | title+save | undo | redo | EXPORT
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = AeDesign.iconButton(this, R.drawable.ic_back, "Back", false);
        AeDesign.press(back, () -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout title = col();
        title.addView(label(project.name, 16, AeDesign.TEXT, Typeface.BOLD));
        saveStatus = label("Saved", 11, 0xff7ce0a2, Typeface.NORMAL);
        title.addView(saveStatus);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView undo = AeDesign.iconButton(this, R.drawable.ic_undo, "Undo", false);
        AeDesign.press(undo, () -> undo());
        header.addView(undo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        ImageView redo = AeDesign.iconButton(this, R.drawable.ic_redo, "Redo", false);
        AeDesign.press(redo, () -> redo());
        header.addView(redo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        Button export = AeDesign.button(this, "EXPORT", true);
        AeDesign.press(export, () -> showExportScreen());
        header.addView(export, new LinearLayout.LayoutParams(-2, dp(40)));
        root.addView(header);

        // --- monitor: LARGE stable preview (single source of truth)
        // ROOT CAUSE: timeline (≈280dp) + multi-row GridLayout tools + unbounded
        // panelHost were all WRAP_CONTENT siblings of a weight=1 monitor. LinearLayout
        // allocates wrap_content first → leftover height near-zero → PreviewView collapsed.
        // FIX: EXPLICIT monitor height (~48% of screen) so chrome can never steal it.
        // Bottom chrome lives in a weight=1 vertical ScrollView — if tools/panel are
        // tall, THEY scroll; the preview stays put. Zoom only changes timeline px/sec.
        monitor = new MonitorLayout(this);
        monitor.setPadding(dp(6), dp(6), dp(6), dp(6));
        monitor.setBackground(AeDesign.bg(0xff03070d, dp(18), 0x22334a68, 1));
        monitor.setRatio(project.width / (float) Math.max(1, project.height));
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        // Dominant preview: ~48% of screen height, clamped to a usable band.
        // Portrait 9:16 canvas still fits aspect-correct inside this box (letterboxed).
        int monH = Math.max(dp(200), Math.min(dp(520), (int) (screenH * 0.48f)));
        // Never taller than ~92% of width-derived box for landscape-ish screens.
        monH = Math.min(monH, Math.max(dp(200), (int) (screenW * 1.15f)));
        monitor.setMinPreviewHeightPx(monH);
        monitor.setMinimumHeight(monH);
        preview = new PreviewView(this);
        preview.project = project;
        monitor.addView(preview, new MonitorLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, monH);
        mlp.setMargins(0, dp(6), 0, dp(2));
        // Explicit height, weight 0 — siblings cannot collapse this.
        root.addView(monitor, mlp);

        // --- bottom chrome (transport + timeline + tools + panel) fills leftover and scrolls
        ScrollView chromeScroll = new ScrollView(this);
        chromeScroll.setFillViewport(true);
        chromeScroll.setVerticalScrollBarEnabled(false);
        chromeScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout chrome = col();
        chromeScroll.addView(chrome, new FrameLayout.LayoutParams(-1, -2));
        root.addView(chromeScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // --- transport: play/pause | time | meta (compact single row)
        LinearLayout player = row();
        player.setGravity(Gravity.CENTER_VERTICAL);
        playButton = AeDesign.iconButton(this, R.drawable.ic_play, "Play", true);
        AeDesign.press(playButton, () -> togglePlay());
        player.addView(playButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        playLabel = label("00:00 / " + fmt(project.totalDurationSec()), 14, AeDesign.TEXT, Typeface.BOLD);
        playLabel.setPadding(dp(8), 0, 0, 0);
        player.addView(playLabel);
        metaLabel = label(project.clips.size() + " clips • " + project.fps + " FPS • " + project.fitMode.label,
                11, AeDesign.MUTED, Typeface.NORMAL);
        metaLabel.setPadding(dp(8), 0, 0, 0);
        player.addView(metaLabel, new LinearLayout.LayoutParams(0, -2, 1));
        chrome.addView(player, new LinearLayout.LayoutParams(-1, -2));

        // --- timeline: COMPACT fixed height (zoom only changes px-per-sec, never preview size)
        LinearLayout tbox = AeDesign.card(this);
        tbox.setPadding(dp(6), dp(4), dp(6), dp(4));
        LinearLayout tHead = row();
        tHead.setGravity(Gravity.CENTER_VERTICAL);
        tHead.addView(iconButton(R.drawable.ic_zoom_out, () -> setTlZoom(tlZoom / 1.25f)));
        ImageView zIn = iconButton(R.drawable.ic_zoom_in, () -> setTlZoom(tlZoom * 1.25f));
        LinearLayout.LayoutParams zilp = new LinearLayout.LayoutParams(-2, -2);
        zilp.leftMargin = dp(4);
        tHead.addView(zIn, zilp);
        ImageView sp = iconButton(R.drawable.ic_split, this::splitAtPlayhead);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-2, -2);
        splp.leftMargin = dp(8);
        tHead.addView(sp, splp);
        tHead.addView(label("Timeline", 11, AeDesign.MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, -2, 1));
        tbox.addView(tHead, new LinearLayout.LayoutParams(-1, -2));
        ruler = new TimelineRulerView(this);
        ruler.setProject(project);
        ruler.setZoom(tlZoom);
        timelineScroll = new HorizontalScrollView(this);
        timelineScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout scrollContent = col();
        float pad0 = TimelineRulerView.PAD_DP * getResources().getDisplayMetrics().density;
        scrollContent.setPadding((int) pad0, 0, (int) pad0, 0);
        ruler.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (TimelineRulerView.contentWidthPx(this, project, tlZoom) - 2 * pad0), dp(22)));
        timeline = row();
        waveTrack = new WaveformTrackView(this);
        ovlTrack = new OverlayTrackView(this);
        scrollContent.addView(ruler);
        // Clip lane + compact waveform/overlay — total body fixed, independent of zoom.
        scrollContent.addView(timeline, new LinearLayout.LayoutParams(-1, dp(56)));
        scrollContent.addView(waveTrack, new LinearLayout.LayoutParams(-1, dp(22)));
        scrollContent.addView(ovlTrack, new LinearLayout.LayoutParams(-1, dp(22)));
        timelineScroll.addView(scrollContent);
        tbox.addView(timelineScroll, new LinearLayout.LayoutParams(-1, dp(122)));
        ovlTrack.setOnSelect(ix -> { selectedOverlay = ix; if (panelHost != null) overlaysPanel(); });
        // Track status as one compact line (was 3 separate rows that ate preview height).
        project.migrateLegacyAudio();
        String trackLine = (project.audioTracks.isEmpty() ? "no audio" : audioTrackSummary(project.primaryAudio()))
                + "  ·  " + project.texts.size() + " text  ·  " + project.overlays.size() + " overlay";
        tbox.addView(trackLabel("Tracks", trackLine, !project.audioTracks.isEmpty() || !project.overlays.isEmpty()));
        LinearLayout.LayoutParams tboxLp = new LinearLayout.LayoutParams(-1, -2);
        tboxLp.topMargin = dp(4);
        chrome.addView(tbox, tboxLp);

        // --- tools: SINGLE horizontal row (was 4-col GridLayout → multi-row height steal)
        LinearLayout toolsRow = row();
        toolsRow.setGravity(Gravity.CENTER_VERTICAL);
        tileCol = 0;
        addToolTileRow(toolsRow, "images", R.drawable.ic_images, "Images", () -> { openTool("images"); showImageImportMenu(); });
        addToolTileRow(toolsRow, "motion", R.drawable.ic_motion, "Motion", () -> motionPanel());
        addToolTileRow(toolsRow, "formula", R.drawable.ic_formula, "Formula", () -> formulaBatchPanel());
        addToolTileRow(toolsRow, "transition", R.drawable.ic_transition, "Transition", () -> transitionPanel());
        addToolTileRow(toolsRow, "duration", R.drawable.ic_timer, "Duration", () -> durationBatchPanel());
        addToolTileRow(toolsRow, "text", R.drawable.ic_text, "Text", () -> textStudio());
        addToolTileRow(toolsRow, "layers", R.drawable.ic_layer, "Layers", () -> overlaysPanel());
        addToolTileRow(toolsRow, "audio", R.drawable.ic_audio, "Audio", () -> audioPanel());
        addToolTileRow(toolsRow, "canvas", R.drawable.ic_canvas, "Canvas", () -> canvasPanel());
        addToolTileRow(toolsRow, "filters", R.drawable.ic_filters, "Filters", () -> filtersPanel());
        addToolTileRow(toolsRow, "effects", R.drawable.ic_effects, "Effects", () -> effectsPanel());
        addToolTileRow(toolsRow, "adjust", R.drawable.ic_adjust, "Adjust", () -> adjustPanel());
        addToolTileRow(toolsRow, "autoedit", R.drawable.ic_autoedit, "Auto Edit", () -> autoEditPanel());
        HorizontalScrollView toolsScroll = new HorizontalScrollView(this);
        toolsScroll.setHorizontalScrollBarEnabled(false);
        toolsScroll.setFillViewport(false);
        toolsScroll.addView(toolsRow, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-1, -2);
        toolsLp.topMargin = dp(4);
        chrome.addView(toolsScroll, toolsLp);

        // --- panel host: in-tree panels (Duration/Layers/Audio/clip). Heavy tools
        // (Motion/Formula/Effects/Transitions) use PanelSheet overlay and never land here.
        // Host is wrap_content inside chromeScroll — grows downward, never upward into preview.
        // No nested ScrollView (chromeScroll already scrolls the whole bottom chrome).
        panelHost = col();
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(-1, -2);
        phLp.topMargin = dp(4);
        chrome.addView(panelHost, phLp);

        buildTimeline(true);
        showClipPanel();
        wirePreview();
        bindAudio();
        root.post(this::ensureMonitorDominant);
    }

    /**
     * Safety net after first layout: if the monitor was still squeezed (rare OEM
     * LinearLayout quirks / inset changes), force the explicit floor height again.
     * Timeline zoom and tool sheets never call this — only editor open / canvas change.
     */
    private void ensureMonitorDominant() {
        if (monitor == null || root == null) return;
        int floor = monitor.getMinPreviewHeightPx();
        if (floor <= 0) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) monitor.getLayoutParams();
        if (lp == null) return;
        int h = monitor.getHeight();
        // Keep explicit height; never fall back to weight=1 (that was the collapse path).
        if (lp.height != floor || lp.weight != 0f || (h > 0 && h < floor - dp(4))) {
            lp.height = floor;
            lp.weight = 0f;
            monitor.setLayoutParams(lp);
            monitor.requestLayout();
        }
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

    /**
     * Single-row toolbar tile — fixed compact height so tools never wrap into
     * multi-row GridLayout columns that steal monitor height.
     */
    private void addToolTileRow(LinearLayout parent, String tag, int icon, String label, Runnable onTap) {
        ToolTile t = new ToolTile(this, icon, label, onTap);
        // Tighter padding for the horizontal strip.
        t.setPadding(dp(2), dp(3), dp(2), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), -2);
        lp.setMargins(dp(2), 0, dp(2), 0);
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
        s.setOnDismiss(() -> { clearActiveTool(); resetSheetSelection(); });
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
    private void buildTimeline(boolean structural) {
        if (timeline == null) return;
        project.renumber();
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
                    selected = ix;
                    transitionScopeClip = -1;
                    if (preview != null) preview.seekTo(project.clips.get(ix).startTimeMsIn(project) / 1000f);
                    refreshSelection();
                    showClipPanel();
                });
                v.setOnLongClickListener(x -> { removeOrMoveDialog(ix); return true; });
                // v1.8: drag the right edge to resize (clamps to the 0.5s-60s safe range)
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
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp((int) (c.durationSec * TimelineRulerView.VEL_DP)), dp(56));
                lp.leftMargin = dp((int) TimelineRulerView.GAP_DP);
                timeline.addView(v, lp);
                chips.add(v);
                // CapCut-style junction control: between clip i-1 and clip i ONLY
                // (never before the first clip / after the last). Zero net width
                // (negative margins) so the ruler playhead geometry is untouched.
                if (i > 0) {
                    final int junctionClip = i - 1; // clip whose .transition defines this junction
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

    /** Junction k sits between clip k and k+1; its state is clips[k].transition. */
    private void refreshJunctionIcons() {
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
    private void applyTimelineGeometry() {
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
        // keep junction icons centred on the compact 56dp clip lane
        for (ImageView j : junctions) {
            LinearLayout.LayoutParams jlp = (LinearLayout.LayoutParams) j.getLayoutParams();
            jlp.topMargin = (dp(56) - dp(28)) / 2;
        }
    }

    private void setTlZoom(float z) {
        tlZoom = Math.max(0.5f, Math.min(4f, z));
        if (ruler != null) ruler.setZoom(tlZoom);
        applyTimelineGeometry();
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
        if (i < 0 || i >= chips.size()) return;
        TextView v = chips.get(i);
        boolean sel = i == selected;
        v.setBackground(AeDesign.bg(sel ? 0xff12395c : AeDesign.SURFACE_2, dp(14), sel ? AeDesign.ACCENT : AeDesign.STROKE, sel ? 2 : 1));
        v.setTextColor(sel ? 0xffffffff : AeDesign.TEXT);
    }

    private void refreshSelection() {
        if (selected >= 0 && selected < chips.size()) styleChip(selected);
    }

    private void highlightPlayheadChip(int idx) {
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
        effectsSheet("Color Adjust", new EffectType[]{
                EffectType.NONE, EffectType.BRIGHTNESS, EffectType.CONTRAST, EffectType.SATURATION,
                EffectType.EXPOSURE, EffectType.TEMPERATURE, EffectType.HIGHLIGHTS, EffectType.SHADOWS,
                EffectType.SHARPEN});
    }

    private void autoEditPanel() {
        openTool("autoedit");
        showPanel("Auto Edit (fills duration + motion + transition)",
                new String[]{"Cinematic", "Fast", "Smooth", "Shorts", "Documentary", "Vlog"},
                new Runnable[]{() -> autoEdit(1), () -> autoEdit(2), () -> autoEdit(0), () -> autoEdit(3), () -> autoEdit(4), () -> autoEdit(5)});
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
        if (monitor != null) monitor.setRatio(project.width / (float) Math.max(1, project.height));
        if (metaLabel != null) metaLabel.setText(project.clips.size() + " clips • " + project.fps + " FPS • " + project.fitMode.label);
        if (preview != null) preview.invalidate();
        // Canvas ratio change must not collapse the monitor — re-assert floor after layout.
        if (root != null) root.post(this::ensureMonitorDominant);
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
     * Images tool entry: Gallery / Device Files / Import ZIP.
     * Existing gallery picker is preserved; ZIP is additive.
     */
    private void showImageImportMenu() {
        String[] ops = {
                "🖼  Gallery",
                "📁  Device Files",
                "📦  Import ZIP"
        };
        new AlertDialog.Builder(this)
                .setTitle("Add Images")
                .setItems(ops, (d, w) -> {
                    if (w == 0) pickImages();
                    else if (w == 1) pickDeviceImageFiles();
                    else if (w == 2) pickZipBatch();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

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

    /** SAF document picker for image files on device storage (no legacy storage perm). */
    private void pickDeviceImageFiles() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, PICK_DEVICE_FILES);
        } catch (Exception e) {
            Log.e(TAG, "Device file picker failed", e);
            toast("Could not open the file picker");
        }
    }

    /**
     * Multi-ZIP picker via Storage Access Framework. User can select 1..N
     * ZIP files at once; they are treated as ONE combined image batch.
     * SAF does not require legacy storage permissions.
     */
    private void pickZipBatch() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            // Also accept common ZIP MIME variants some providers use.
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream"
            });
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(i, "Select ZIP file(s)"), PICK_ZIP_BATCH);
        } catch (Exception e) {
            Log.e(TAG, "ZIP picker failed", e);
            toast("Could not open the ZIP picker");
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
        if (req == PICK_IMAGES || req == PICK_DEVICE_FILES) {
            ArrayList<Uri> uris = collectUris(data);
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
            return;
        }
        if (req == PICK_ZIP_BATCH) {
            ArrayList<Uri> uris = collectUris(data);
            if (uris.isEmpty()) { toast("No ZIP selected"); return; }
            ArrayList<String> names = new ArrayList<>();
            for (Uri u : uris) {
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                names.add(displayNameOf(u));
            }
            startZipImport(uris, names);
            return;
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

    private void saveProject(boolean visible) {
        if (project == null) return;
        if (visible && saveStatus != null) saveStatus.setText("Saving...");
        // Multi-project library is the source of truth; also mirror to the
        // legacy ProjectStore "current" slot so export / older paths keep working.
        if (library != null && activeProjectId != null) library.saveProject(activeProjectId, project);
        else if (store != null) store.save(project);
        if (visible && saveStatus != null) handler.postDelayed(() -> saveStatus.setText("Saved"), 350);
    }

    // ============================================================ ZIP import

    private ArrayList<Uri> collectUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (data == null) return uris;
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                Uri u = data.getClipData().getItemAt(k).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private String displayNameOf(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            String p = u.getLastPathSegment();
            name = p == null ? "archive.zip" : p;
        }
        return name;
    }

    /** Kicks off background multi-ZIP scan+extract with a cancelable progress UI. */
    private void startZipImport(List<Uri> uris, List<String> names) {
        screen = "zip_import";
        pendingZipResult = null;
        zipKeepDuplicates = true;
        zipUnnumberedPolicy = UnnumberedPolicy.SKIP;
        if (zipImporter != null) zipImporter.cancel();
        zipImporter = new ZipBatchImporter(this);
        showZipProgressUi(uris.size());

        final List<Uri> zipUris = new ArrayList<>(uris);
        final List<String> zipNames = new ArrayList<>(names);
        final ZipBatchImporter importer = zipImporter;
        bg.execute(() -> {
            BatchResult result = importer.importZips(zipUris, zipNames, p ->
                    handler.post(() -> updateZipProgress(p)));
            handler.post(() -> {
                dismissZipProgress();
                if (result.cancelled) {
                    toast("ZIP import cancelled");
                    if ("zip_import".equals(screen)) showEditor();
                    return;
                }
                if (result.error != null) {
                    new AlertDialog.Builder(this)
                            .setTitle("ZIP Import")
                            .setMessage(result.error)
                            .setPositiveButton("OK", (d, w) -> showEditor())
                            .show();
                    return;
                }
                pendingZipResult = result;
                showZipSummary(result);
            });
        });
    }

    private void showZipProgressUi(int zipCount) {
        LinearLayout box = col();
        box.setPadding(dp(20), dp(16), dp(20), dp(8));
        box.addView(label("📦  Importing ZIP...", 18, AeDesign.TEXT, Typeface.BOLD));
        box.addView(label(zipCount + " archive" + (zipCount == 1 ? "" : "s") + " selected", 13, AeDesign.MUTED, Typeface.NORMAL));
        zipProgressLabel = label("Scanning files...", 14, AeDesign.MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(12);
        box.addView(zipProgressLabel, tlp);
        zipProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        zipProgressBar.setMax(100);
        zipProgressBar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(10));
        blp.topMargin = dp(10);
        box.addView(zipProgressBar, blp);

        zipProgressDialog = new AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> {
                    if (zipImporter != null) zipImporter.cancel();
                    toast("Cancelling…");
                })
                .create();
        try { zipProgressDialog.show(); } catch (Exception e) { Log.e(TAG, "progress dialog", e); }
    }

    private void updateZipProgress(ZipBatchModels.Progress p) {
        if (zipProgressLabel != null) {
            String line = p.stage;
            if (p.total > 0) line += "\n" + p.done + " / " + p.total;
            line += "\nImages found: " + p.imagesFound;
            zipProgressLabel.setText(line);
        }
        if (zipProgressBar != null && p.total > 0) {
            int pct = Math.max(1, Math.min(99, (int) (100f * p.done / (float) p.total)));
            zipProgressBar.setProgress(pct);
        }
    }

    private void dismissZipProgress() {
        try { if (zipProgressDialog != null && zipProgressDialog.isShowing()) zipProgressDialog.dismiss(); } catch (Exception ignored) {}
        zipProgressDialog = null;
        zipProgressLabel = null;
        zipProgressBar = null;
    }

    /**
     * Premium dark summary screen before timeline insertion.
     * Missing serials are a warning only — never invent blank clips.
     */
    private void showZipSummary(BatchResult r) {
        screen = "zip_summary";
        base();
        addHeader("ZIP Batch Import", "Numeric serial order • combined archives", () -> {
            pendingZipResult = null;
            showEditor();
        });

        ScrollView sv = new ScrollView(this);
        LinearLayout body = col();

        // Hero stats card
        LinearLayout hero = AeDesign.card(this);
        hero.addView(label("ZIP BATCH IMPORT", 12, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout stats = row();
        stats.setPadding(0, dp(8), 0, dp(4));
        stats.addView(statBlock("📦", r.zipCount + "", "ZIP FILES"), new LinearLayout.LayoutParams(0, -2, 1));
        stats.addView(statBlock("🖼", r.imagesFound + "", "IMAGES"), new LinearLayout.LayoutParams(0, -2, 1));
        hero.addView(stats);
        if (r.serialMin >= 0) {
            hero.addView(label("SERIAL ORDER", 12, AeDesign.MUTED, Typeface.BOLD));
            TextView range = label(r.serialMin + "  ────────  " + r.serialMax, 22, AeDesign.ACCENT, Typeface.BOLD);
            range.setGravity(Gravity.CENTER);
            hero.addView(range);
            hero.addView(label("Order: Numeric Serial  ·  " + r.ordered.size() + " numbered"
                    + (r.unnumberedCount > 0 ? "  ·  " + r.unnumberedCount + " unnumbered" : ""),
                    12, AeDesign.MUTED, Typeface.NORMAL));
        }
        body.addView(hero, cardLp(0));

        // Warnings
        if (!r.missing.isEmpty()) {
            body.addView(warnCard("⚠  Missing Serial",
                    formatMissing(r.missing)
                            + "\n\nThe timeline will continue directly across the gap. No blank clips will be created."),
                    cardLp(10));
        }
        if (!r.duplicateSerials.isEmpty()) {
            body.addView(warnCard("⚠  Duplicate Serial",
                    "Serials shared by more than one image: " + joinInts(r.duplicateSerials, 12)
                            + "\n\nKeep Both preserves every image (ZIP order)."),
                    cardLp(10));
            LinearLayout dupRow = row();
            addChoice(dupRow, zipKeepDuplicates ? "✓ Keep Both" : "Keep Both", zipKeepDuplicates, () -> {
                zipKeepDuplicates = true; showZipSummary(r);
            });
            addChoice(dupRow, !zipKeepDuplicates ? "✓ First Only" : "First Only", !zipKeepDuplicates, () -> {
                zipKeepDuplicates = false; showZipSummary(r);
            });
            body.addView(dupRow);
        }
        if (r.unnumberedCount > 0) {
            body.addView(warnCard("⚠  Unnumbered Images",
                    r.unnumberedCount + " image" + (r.unnumberedCount == 1 ? " has" : "s have")
                            + " no serial number and will not be mixed into the numbered sequence."),
                    cardLp(10));
            LinearLayout urow = row();
            addChoice(urow, zipUnnumberedPolicy == UnnumberedPolicy.SKIP ? "✓ Skip" : "Skip",
                    zipUnnumberedPolicy == UnnumberedPolicy.SKIP, () -> {
                        zipUnnumberedPolicy = UnnumberedPolicy.SKIP; showZipSummary(r);
                    });
            addChoice(urow, zipUnnumberedPolicy == UnnumberedPolicy.APPEND ? "✓ Append at End" : "Append at End",
                    zipUnnumberedPolicy == UnnumberedPolicy.APPEND, () -> {
                        zipUnnumberedPolicy = UnnumberedPolicy.APPEND; showZipSummary(r);
                    });
            body.addView(urow);
        }

        if (!r.hasProblems()) {
            LinearLayout ok = AeDesign.card(this);
            ok.addView(label("✓  Ready to Import", 16, 0xff7ce0a2, Typeface.BOLD));
            ok.addView(label(r.zipCount + " ZIP  ·  " + r.imagesFound + " images  ·  serial "
                    + r.serialMin + " → " + r.serialMax, 13, AeDesign.MUTED, Typeface.NORMAL));
            body.addView(ok, cardLp(10));
        }

        if (r.invalidImage > 0 || r.skippedNonImage > 0) {
            body.addView(label("Skipped non-images: " + r.skippedNonImage
                    + "  ·  Invalid images: " + r.invalidImage, 12, AeDesign.MUTED, Typeface.NORMAL));
        }

        body.addView(label("Clips append to the current timeline. One Undo removes the whole batch.",
                12, AeDesign.MUTED, Typeface.NORMAL));

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = row();
        Button cancel = AeDesign.button(this, "CANCEL", false);
        AeDesign.press(cancel, () -> { pendingZipResult = null; showEditor(); });
        bar.addView(cancel, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button add = AeDesign.button(this, "ADD TO TIMELINE", true);
        AeDesign.press(add, () -> commitZipImport());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(54), 1.4f);
        alp.leftMargin = dp(10);
        bar.addView(add, alp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(10);
        root.addView(bar, blp);
    }

    private LinearLayout.LayoutParams cardLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(top), 0, 0);
        return lp;
    }

    private LinearLayout statBlock(String emoji, String value, String caption) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER);
        TextView e = label(emoji + "  " + value, 22, AeDesign.TEXT, Typeface.BOLD);
        e.setGravity(Gravity.CENTER);
        c.addView(e);
        TextView cap = label(caption, 11, AeDesign.MUTED, Typeface.BOLD);
        cap.setGravity(Gravity.CENTER);
        c.addView(cap);
        return c;
    }

    private LinearLayout warnCard(String title, String body) {
        LinearLayout c = AeDesign.card(this);
        c.setBackground(AeDesign.bg(0xff1a1420, dp(22), 0x66ffc84d, 1));
        c.addView(label(title, 15, 0xffffc84d, Typeface.BOLD));
        c.addView(label(body, 13, AeDesign.MUTED, Typeface.NORMAL));
        return c;
    }

    private String formatMissing(List<Integer> missing) {
        if (missing == null || missing.isEmpty()) return "None";
        if (missing.size() == 1) return "Serial " + missing.get(0) + " is missing.";
        return "Missing: " + joinInts(missing, 16);
    }

    private String joinInts(List<Integer> vals, int maxShow) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(vals.size(), maxShow);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(vals.get(i));
        }
        if (vals.size() > maxShow) sb.append(" … (+").append(vals.size() - maxShow).append(" more)");
        return sb.toString();
    }

    /**
     * Commits the ZIP batch as ONE undo entry. Appends real image clips only —
     * never invents blanks for missing serials. Imported files live under
     * app-controlled storage so they survive project reopen / ZIP deletion.
     */
    private void commitZipImport() {
        BatchResult r = pendingZipResult;
        if (r == null) { showEditor(); return; }
        List<ReadyImage> finalOrder = ZipBatchModels.finalizeOrder(r, zipKeepDuplicates, zipUnnumberedPolicy);
        if (finalOrder.isEmpty()) {
            toast("Nothing to add");
            return;
        }
        pushUndo(); // ONE undo for the whole batch
        int before = project.clips.size();
        for (ReadyImage img : finalOrder) {
            try {
                TimelineClip clip = new TimelineClip(img.fileUri, project.clips.size() + 1, formulas.defaultFormula());
                clip.setDurationMs(5000L);
                project.clips.add(clip);
            } catch (Exception e) {
                Log.e(TAG, "ZIP clip failed: " + img.fileUri, e);
            }
        }
        project.renumber();
        pendingZipResult = null;
        saveProject(true);
        showEditor();
        toast("Added " + (project.clips.size() - before) + " image(s) from ZIP");
    }

    private String fmt(float sec) { int s = Math.round(sec); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }
    private int dp(int v) { return AeDesign.dp(this, v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
