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

public class MainActivity extends Activity {
    private static final int PICK_IMAGES = 10, PICK_AUDIO = 11, REQ_CUSTOM_FORMULA = 12;
    private static final String TAG = "AutoEditMain";

    private EditProject project;
    private ProjectStore store;
    private FormulaEngine formulas;
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
    private final Map<String, ToolTile> tiles = new HashMap<>();

    // Bottom-sheet overlay (floats over the editor; never resizes the monitor)
    private PanelSheet sheet;
    private String selectedMotionId = null;
    private String selectedFormulaId = null;
    private EffectType selectedEffect = null;
    private TransitionType selectedTransition = null;

    // --- export progress screen state (survives activity recreation; the
    //     service keeps exporting independently of the UI)
    private boolean exportRunning = false;
    private int lastExportPct = 0;
    private String lastExportMsg = "";
    private ExportRingView ring;
    private NeonProgressBar neonBar;
    private TextView pctBig, statusBig;

    // export completion widgets (kept for the async permission-grant refresh)
    private static final int REQ_VIDEO_PERM = 20;
    private ImageView completionThumb;
    private Button completionPlay, completionShare;
    private Uri completionUri;
    private String completionFileName;

    // audio preview (real playback in preview; export is video-only)
    private MediaPlayer audioPlayer;

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
            int p = i.getIntExtra("percent", 0);
            String m = i.getStringExtra("message");
            updateExportProgress(p, m);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
    }

    // ------------------------------------------------- update system (mandatory)

    private static long lastUpdateCheckMs = 0;

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
            if (local < cfg.minimumSupportedVersionCode) {
                Intent i = new Intent(this, UpdateActivity.class);
                i.putExtra(UpdateActivity.EXTRA_LATEST_CODE, cfg.latestVersionCode);
                i.putExtra(UpdateActivity.EXTRA_LATEST_NAME, cfg.latestVersionName);
                i.putExtra(UpdateActivity.EXTRA_MIN_CODE, cfg.minimumSupportedVersionCode);
                i.putExtra(UpdateActivity.EXTRA_DOWNLOAD_URL, cfg.downloadUrl);
                if (!cfg.releaseNotes.isEmpty()) i.putStringArrayListExtra(UpdateActivity.EXTRA_NOTES, new ArrayList<>(cfg.releaseNotes));
                startActivity(i);
            }
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
        if (project.clips.isEmpty()) emptyState(); else projectCard(project);

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
        TextView thumb = label(String.format(Locale.US, "%d", p.clips.size()), 26, AeDesign.ACCENT, Typeface.BOLD);
        thumb.setGravity(Gravity.CENTER);
        thumb.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(18), AeDesign.STROKE, 1));
        top.addView(thumb, new LinearLayout.LayoutParams(dp(86), dp(72)));
        LinearLayout info = col();
        info.setPadding(dp(14), 0, 0, 0);
        info.addView(label(p.name, 19, AeDesign.TEXT, Typeface.BOLD));
        info.addView(label(p.clips.size() + " clips • " + fmt(p.totalDurationSec()) + " • " + p.width + "×" + p.height + " • " + p.fitMode.label, 13, AeDesign.MUTED, Typeface.NORMAL));
        info.addView(label("Auto saved • " + (p.audioUri == null ? "no audio" : "audio linked"), 12, 0xff6f8ca4, Typeface.NORMAL));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView more = AeDesign.iconButton(this, R.drawable.ic_settings, "Project menu", false);
        AeDesign.press(more, () -> projectMenu());
        top.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        card.addView(top);
        Button cont = AeDesign.button(this, "Continue Editing", true);
        AeDesign.press(cont, () -> showEditor());
        LinearLayout.LayoutParams lpbtn = new LinearLayout.LayoutParams(-1, dp(52));
        lpbtn.setMargins(0, dp(16), 0, 0);
        card.addView(cont, lpbtn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        root.addView(card, lp);
    }

    private void projectMenu() {
        String[] ops = {"Rename", "Duplicate", "Delete", "Project Settings"};
        new AlertDialog.Builder(this).setTitle("Project").setItems(ops, (d, w) -> {
            if (w == 0) renameProject();
            if (w == 1) { project.name = project.name + " Copy"; saveProject(true); showHome(); }
            if (w == 2) { project = new EditProject(); saveProject(true); showHome(); }
            if (w == 3) showCreateProject(true);
        }).show();
    }

    private void renameProject() {
        final EditText e = new EditText(this);
        e.setText(project.name);
        new AlertDialog.Builder(this).setTitle("Rename project").setView(e)
                .setPositiveButton("Save", (d, w) -> { project.name = e.getText().toString(); saveProject(true); showHome(); }).show();
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

        // --- monitor: aspect-correct centered preview (single source of truth)
        monitor = new MonitorLayout(this);
        monitor.setPadding(dp(8), dp(8), dp(8), dp(8));
        monitor.setBackground(AeDesign.bg(0xff03070d, dp(22), 0x22334a68, 1));
        monitor.setRatio(project.width / (float) Math.max(1, project.height));
        preview = new PreviewView(this);
        preview.project = project;
        monitor.addView(preview, new MonitorLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, 0, 1);
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
        metaLabel = label(project.clips.size() + " clips • " + project.fps + " FPS • " + project.fitMode.label, 12, AeDesign.MUTED, Typeface.NORMAL);
        metaLabel.setPadding(dp(10), 0, 0, 0);
        player.addView(metaLabel, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(player, new LinearLayout.LayoutParams(-1, -2));

        // --- timeline: real ruler + playhead + chips (one px-per-second geometry)
        LinearLayout tbox = AeDesign.card(this);
        tbox.setPadding(dp(8), dp(6), dp(8), dp(6));
        ruler = new TimelineRulerView(this);
        ruler.setProject(project);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout scrollContent = col();
        ruler.setLayoutParams(new LinearLayout.LayoutParams((int) TimelineRulerView.contentWidthPx(this, project), dp(30)));
        timeline = row();
        scrollContent.addView(ruler);
        scrollContent.addView(timeline);
        hsv.addView(scrollContent);
        tbox.addView(hsv, new LinearLayout.LayoutParams(-1, dp(118)));
        LinearLayout tracks = col();
        tracks.addView(trackLabel("Audio track", project.audioUri == null ? "no audio" : "linked — plays with preview • export: COMING SOON", project.audioUri != null));
        tracks.addView(trackLabel("Text track", project.texts.size() + " text block(s)", false));
        tbox.addView(tracks);
        root.addView(tbox, new LinearLayout.LayoutParams(-1, -2));

        // --- tools: compact icon toolbar (every tool is real; no fakes)
        GridLayout tools = new GridLayout(this);
        tools.setColumnCount(4);
        addToolTile(tools, "images", R.drawable.ic_images, "Images", () -> { openTool("images"); pickImages(); });
        addToolTile(tools, "motion", R.drawable.ic_motion, "Motion", () -> motionPanel());
        addToolTile(tools, "formula", R.drawable.ic_formula, "Formula", () -> formulaBatchPanel());
        addToolTile(tools, "transition", R.drawable.ic_transition, "Transition", () -> transitionPanel());
        addToolTile(tools, "duration", R.drawable.ic_timer, "Duration", () -> durationBatchPanel());
        addToolTile(tools, "text", R.drawable.ic_text, "Text", () -> textStudio());
        addToolTile(tools, "audio", R.drawable.ic_audio, "Audio", () -> audioPanel());
        addToolTile(tools, "canvas", R.drawable.ic_canvas, "Canvas", () -> canvasPanel());
        addToolTile(tools, "filters", R.drawable.ic_filters, "Filters", () -> filtersPanel());
        addToolTile(tools, "effects", R.drawable.ic_effects, "Effects", () -> effectsPanel());
        addToolTile(tools, "adjust", R.drawable.ic_adjust, "Adjust", () -> adjustPanel());
        addToolTile(tools, "autoedit", R.drawable.ic_autoedit, "Auto Edit", () -> autoEditPanel());
        HorizontalScrollView toolsScroll = new HorizontalScrollView(this);
        toolsScroll.setHorizontalScrollBarEnabled(false);
        toolsScroll.addView(tools, new FrameLayout.LayoutParams(-2, -2));
        root.addView(toolsScroll, new LinearLayout.LayoutParams(-1, -2));

        // --- panel host
        ScrollView ps = new ScrollView(this);
        ps.setFillViewport(false);
        panelHost = col();
        ps.addView(panelHost);
        root.addView(ps, new LinearLayout.LayoutParams(-1, -2));

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
            if (idx != lastActiveChip) highlightPlayheadChip(idx);
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

    private void openTool(String tag) {
        for (Map.Entry<String, ToolTile> e : tiles.entrySet()) e.getValue().setActive(e.getKey().equals(tag));
    }

    // ---------------------------------------------------------------- bottom sheet
    private PanelSheet sheet() {
        if (sheet == null) sheet = new PanelSheet(this);
        return sheet;
    }

    /** Opens the floating bottom sheet (editor keeps its full size behind it). */
    private void openSheet(String title) {
        selectedMotionId = null; selectedFormulaId = null; selectedEffect = null; selectedTransition = null;
        PanelSheet s = sheet();
        s.setOnDismiss(() -> clearActiveTool());
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

    /** A card with a live preview, title + subtitle; tap = SELECT only (no mutation). */
    interface CardOnTap { void onTap(); }
    private LinearLayout previewCard(View preview, String title, String subtitle, boolean selected) {
        LinearLayout card = col();
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.addView(preview, new LinearLayout.LayoutParams(dp(112), dp(132)));
        TextView nm = label(title, 12, AeDesign.TEXT, Typeface.BOLD);
        nm.setGravity(Gravity.CENTER);
        card.addView(nm, new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null) {
            TextView sub = label(subtitle, 10, AeDesign.MUTED, Typeface.NORMAL);
            sub.setGravity(Gravity.CENTER);
            card.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        card.setBackground(AeDesign.bg(AeDesign.SURFACE, dp(18), selected ? AeDesign.ACCENT : AeDesign.STROKE, selected ? 2 : 1));
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
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp((int) (c.durationSec * TimelineRulerView.VEL_DP)), dp(84));
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
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.width = dp((int) (c.durationSec * TimelineRulerView.VEL_DP));
            v.requestLayout();
            styleChip(i);
        }
        if (ruler != null) {
            ruler.setProject(project);
            LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) ruler.getLayoutParams();
            rlp.width = (int) TimelineRulerView.contentWidthPx(this, project);
            ruler.requestLayout();
        }
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
                boolean isSel = sameFormulaId(selected >= 0 && selected < project.clips.size() ? project.clips.get(selected).formula : null, m.id);
                final String id = m.id;
                LinearLayout card = previewCard(mpv, m.name, cat, isSel);
                AeDesign.press(card, () -> {
                    selectedMotionId = id;
                    motionPanel(); // rebuild to show selection ring
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
        String sub = f.isPattern() ? f.category + " • " + f.patternSize() + "-clip" : "Single";
        LinearLayout card = previewCard(pv, f.name, sub, formulaApplied(id));
        AeDesign.press(card, () -> { selectedFormulaId = id; selectedMotionId = null; formulaBatchPanel(); });
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
        LinearLayout card = previewCard(pv, name, o.optString("category", "Custom") + " • " + steps + "-clip", formulaApplied(id));
        AeDesign.press(card, () -> { selectedFormulaId = id; formulaBatchPanel(); });
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

    private void transitionPanel() {
        openTool("transition");
        boolean junctionScoped = transitionScopeClip >= 0 && transitionScopeClip + 1 < project.clips.size();
        String scope;
        if (junctionScoped) scope = "Junction clip " + (transitionScopeClip + 1) + " → " + (transitionScopeClip + 2);
        else scope = selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL clips";
        PanelSheet s = sheet();
        openSheet("Transition → " + scope);

        TransitionType current = null;
        if (junctionScoped) current = project.clips.get(transitionScopeClip).transition;
        else if (selected >= 0) current = project.clips.get(selected).transition;

        TransitionType[] vals = TransitionEngine.rendered();
        LinearLayout row = sheetCardsRow(s);
        for (TransitionType t : vals) {
            TransitionPreviewView tpv = new TransitionPreviewView(this);
            tpv.setTransition(t);
            LinearLayout card = previewCard(tpv, TransitionEngine.label(t),
                    t == TransitionType.NONE ? "No transition" : "Preview", current == t);
            AeDesign.press(card, () -> { selectedTransition = t; transitionPanel(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(4), dp(4), dp(4), dp(6));
            row.addView(card, lp);
        }
        sheetHint(s, "Tap a card to SELECT, then APPLY. Every transition is rendered in preview AND export with shared math.");
        if (junctionScoped) {
            Button apply = AeDesign.button(this, "APPLY TO JUNCTION", true);
            AeDesign.press(apply, () -> {
                if (selectedTransition != null) { applyTransitionAt(transitionScopeClip, selectedTransition); afterApply("Transition set"); }
            });
            s.applyBar().addView(apply, new LinearLayout.LayoutParams(-1, dp(48)));
        } else {
            addApplyButtons(s, "APPLY TRANSITION TO",
                    () -> { if (selectedTransition != null) { applyTransitionAt(selected, selectedTransition); afterApply("Transition set"); } },
                    () -> { if (selectedTransition != null) { applyTransitionToAll(selectedTransition); afterApply("Transition applied to all"); } });
        }
    }

    private void applyTransitionToAll(TransitionType t) {
        pushUndo();
        for (TimelineClip c : project.clips) c.transition = t;
        saveProject(true);
        if (preview != null) preview.invalidate();
    }

    private void textStudio() {
        openTool("text");
        showPanel("Text (shown in preview + export)",
                new String[]{"Title", "Subtitle", "Caption", "YouTube Title", "Shorts Caption", "Documentary Lower Third", "End Card"},
                new Runnable[]{() -> addText("Title"), () -> addText("Subtitle"), () -> addText("Caption"), () -> addText("YouTube Title"), () -> addText("Shorts Caption"), () -> addText("Documentary Lower Third"), () -> addText("Thanks for watching")});
    }

    private void audioPanel() {
        openTool("audio");
        if (panelHost == null) return;
        panelHost.removeAllViews();
        panelHost.addView(label("Audio / Voice-over", 16, AeDesign.TEXT, Typeface.BOLD));
        LinearLayout grid = rowWrap();
        addAction(grid, project.audioUri == null ? "Import audio" : "Change audio", this::pickAudio);
        addAction(grid, "Mute / remove", () -> { pushUndo(); project.audioUri = null; saveProject(true); showEditor(); });
        if (project.audioUri != null) {
            TextView info = label("Audio is linked. Press Play in the transport bar — the voice-over plays in sync with the preview timeline.\nExport: video-only in this build — audio muxing is COMING SOON (never faked).", 12, AeDesign.MUTED, Typeface.NORMAL);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
            ilp.setMargins(dp(4), dp(8), dp(4), dp(8));
            grid.addView(info, ilp);
        }
        panelHost.addView(grid);
    }

    private void canvasPanel() {
        openTool("canvas");
        showPanel("Canvas",
                new String[]{"Fit (letterbox)", "Fill (crop)", "16:9", "9:16", "1:1", "4:5"},
                new Runnable[]{
                        () -> { project.fitMode = FitMode.FIT; saveProject(true); refreshAfterCanvasChange(); },
                        () -> { project.fitMode = FitMode.FILL; saveProject(true); refreshAfterCanvasChange(); },
                        () -> setPreset(ExportPreset.LANDSCAPE_16_9),
                        () -> setPreset(ExportPreset.PORTRAIT_9_16),
                        () -> setPreset(ExportPreset.SQUARE_1_1),
                        () -> setPreset(ExportPreset.PORTRAIT_4_5)
                });
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
            LinearLayout card = previewCard(epv, EffectEngine.label(t),
                    t == EffectType.NONE ? "Reset" : "Preview", current == t);
            AeDesign.press(card, () -> { selectedEffect = t; effectsPanelOrRefresh(title); });
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
        project.clips.get(clipIdx).effect = e;
        saveProject(true);
        if (preview != null) preview.invalidate();
    }

    private void applyEffectToAll(EffectType e) {
        pushUndo();
        for (TimelineClip c : project.clips) c.effect = e;
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
    private void applyFormulaSequenceToAll(String mode) {
        pushUndo();
        String[] story = {"06", "08", "07", "09"};
        String[] doc = {"14", "04", "15", "02"};
        String[] cine = {"06", "05", "07", "01"};
        String[] pan = {"04", "02", "01", "05"};
        String[] slow = {"18", "14", "18", "15"};
        String[] use = story;
        if ("documentary".equals(mode)) use = doc;
        else if ("cinematic".equals(mode)) use = cine;
        else if ("pan".equals(mode)) use = pan;
        else if ("slow".equals(mode)) use = slow;
        for (int i = 0; i < project.clips.size(); i++) {
            project.clips.get(i).formula = formulas.byId("none".equals(mode) ? "00" : use[i % use.length]);
        }
        saveProject(true);
        buildTimeline(false);
        if (preview != null) preview.invalidate();
        toast("Formula applied to " + project.clips.size() + " clips (state only)");
    }

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
        if (selected >= 0) project.clips.get(selected).effect = e;
        else for (TimelineClip c : project.clips) c.effect = e;
        saveProject(true);
        if (preview != null) preview.invalidate();
        toast("Effect: " + e.name() + " → " + (selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL clips"));
    }

    private void duplicateClip() {
        if (selected < 0) return;
        pushUndo();
        TimelineClip c = project.clips.get(selected);
        TimelineClip n = new TimelineClip(c.uri, selected + 2, c.formula);
        n.setDurationMs(c.durationMs);
        n.effect = c.effect;
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

    /** Real preview audio: MediaPlayer bound to the same timeline time. */
    private void startAudioAt(float t) {
        releaseAudio();
        if (project.audioUri == null) return;
        try {
            audioPlayer = MediaPlayer.create(this, Uri.parse(project.audioUri));
            if (audioPlayer == null) throw new IOException("MediaPlayer unavailable");
            int dur = audioPlayer.getDuration();
            if (t * 1000f > 300 && t * 1000f < dur - 300) audioPlayer.seekTo((int) (t * 1000f));
            audioPlayer.start();
        } catch (Exception e) {
            audioPlayer = null;
            Log.e(TAG, "Audio preview failed", e);
            toast("Audio preview failed: " + (e.getMessage() == null ? "error" : e.getMessage()));
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

    private void pickImages() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, PICK_IMAGES);
        } catch (Exception e) {
            Log.e(TAG, "Image picker failed", e);
            toast("Could not open image picker");
        }
    }

    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_AUDIO);
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
        if (req == PICK_AUDIO && data.getData() != null) {
            pushUndo();
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            project.audioUri = u.toString();
            saveProject(true);
            if ("editor".equals(screen)) showEditor();
            toast("Audio linked — press Play to hear it");
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
        summary.addView(label("Audio export: COMING SOON — this pipeline encodes the video track only.", 12, 0xffe0b46b, Typeface.NORMAL));
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
        AeDesign.press(go, () -> startExistingExport());
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
        startService(i);
        exportRunning = true;
        lastExportPct = 1;
        lastExportMsg = "Preparing...";
        showExportProgressScreen();
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
        ring.setProgress(exportRunning ? lastExportPct / 100f : 1f);
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
        pctBig = label(exportRunning ? lastExportPct + "%" : "100%", 52, AeDesign.ACCENT, Typeface.BOLD);
        pctBig.setGravity(Gravity.CENTER);
        center.addView(pctBig, new LinearLayout.LayoutParams(-1, -2));
        neonBar = new NeonProgressBar(this);
        neonBar.setRunning(exportRunning);
        neonBar.setProgress(exportRunning ? lastExportPct / 100f : 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(18));
        blp.topMargin = dp(10);
        center.addView(neonBar, blp);
        statusBig = label(exportRunning ? exportStatusText(lastExportPct) : "Export complete!", 13, AeDesign.MUTED, Typeface.NORMAL);
        statusBig.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(12);
        center.addView(statusBig, slp);
        l.addView(center, new LinearLayout.LayoutParams(-1, -2));
        l.addView(col(), new LinearLayout.LayoutParams(-1, 0, 0.6f));

        if (!exportRunning) showExportComplete(lastExportMsg);
    }

    private String exportStatusText(int p) {
        if (p < 30) return "Preparing your video...";
        if (p < 70) return "Processing your video...";
        if (p < 95) return "Almost done... Please wait";
        return "Finalizing your video...";
    }

    private void updateExportProgress(int p, String m) {
        lastExportPct = p;
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
            if (exportProgress != null) { exportPercent.setText("Failed"); exportStage.setText(m); }
            return;
        }
        if ("exporting".equals(screen)) {
            if (pctBig != null) pctBig.setText(p + "%");
            if (neonBar != null) neonBar.setProgress(p / 100f);
            if (ring != null) ring.setProgress(p / 100f);
            if (statusBig != null) {
                String s = exportStatusText(p);
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
        if (p < 10) return "Optimizing images... " + m;
        if (p < 45) return "Rendering frames... " + m;
        if (p < 85) return "Encoding... " + m;
        if (p < 100) return "Saving to Gallery... " + m;
        return m;
    }

    // ------------------------------------------------- export completion

    private void showExportComplete(String m) {
        if (ring != null) { ring.setRunning(false); ring.setDone(true); }
        if (neonBar != null) { neonBar.setRunning(false); neonBar.setProgress(1f); }
        if (pctBig != null) pctBig.setText("100%");
        if (statusBig != null) { statusBig.setText("Export complete!"); statusBig.setTextColor(0xff7ce0a2); }

        // find the saved video in the Gallery (MediaStore) for thumbnail/actions
        String fileName = null;
        if (m != null) { int i = m.lastIndexOf('/'); if (i >= 0) fileName = m.substring(i + 1).trim(); }
        completionFileName = fileName;
        completionUri = fileName == null ? null : findExportedVideo(fileName);

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
        TextView saved = label(fileName != null ? fileName : "Saved to Movies/AutoEdit", 12, AeDesign.MUTED, Typeface.NORMAL);
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
        if (!"exporting".equals(screen) || completionFileName == null) return;
        if (completionUri == null) completionUri = findExportedVideo(completionFileName);
        if (completionUri == null && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_VIDEO}, REQ_VIDEO_PERM);
            return;
        }
        if (completionThumb == null) return;
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
    }

    private void showExportFailed(String m) {
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
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "video/mp4");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            PackageManager pm = getPackageManager();
            if (i.resolveActivity(pm) == null) {
                toast("No video player installed. Find it in Movies/AutoEdit.");
                return;
            }
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "Play failed", e);
            toast("Could not open video: " + e.getMessage());
        }
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
        showPanelIntoRoot("Export", new String[]{"Resolution: " + project.width + "×" + project.height, "Pipeline: MediaCodec H.264 → MediaMuxer MP4 (protected)", "Audio export: COMING SOON (video-only)"});
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
        if (store != null && project != null) {
            if (visible && saveStatus != null) saveStatus.setText("Saving...");
            store.save(project);
            if (visible && saveStatus != null) handler.postDelayed(() -> saveStatus.setText("Saved"), 350);
        }
    }

    private String fmt(float sec) { int s = Math.round(sec); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }
    private int dp(int v) { return AeDesign.dp(this, v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
