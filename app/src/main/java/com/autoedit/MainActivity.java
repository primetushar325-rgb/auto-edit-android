package com.autoedit;

import android.app.*;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.content.*;
import android.graphics.Typeface;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.*;

import com.autoedit.model.*;
import com.autoedit.engine.*;
import com.autoedit.project.*;
import com.autoedit.export.*;
import com.autoedit.ui.*;

public class MainActivity extends Activity {
    private static final int PICK_IMAGES = 10, PICK_AUDIO = 11;
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
    private int lastActiveChip = -1;
    private float lastFrameT = 0f;
    private int batchDur = 5;
    private final Map<String, ToolTile> tiles = new HashMap<>();

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

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ProjectStore(this);
        formulas = new FormulaEngine();
        project = store.load();
        draftPreset = project.exportPreset;
        draftFps = project.fps;
        draftFit = project.fitMode;
        showHome();
        handler.postDelayed(autosave, 30000);
    }

    @Override protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(exportReceiver, new IntentFilter(ExportService.ACTION_PROGRESS), RECEIVER_NOT_EXPORTED);
        else registerReceiver(exportReceiver, new IntentFilter(ExportService.ACTION_PROGRESS));
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
        if ("editor".equals(screen)) showHome();
        else if ("create".equals(screen) || "export".equals(screen) || "settings".equals(screen)) showEditor();
        else super.onBackPressed();
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
        logo.setImageResource(getResources().getIdentifier("ic_auto_edit", "drawable", getPackageName()));
        header.addView(logo, new LinearLayout.LayoutParams(dp(58), dp(58)));
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
            for (int i = 0; i < project.clips.size(); i++) {
                TimelineClip c = project.clips.get(i);
                TextView v = label("", 10, AeDesign.TEXT, Typeface.BOLD);
                v.setGravity(Gravity.CENTER);
                v.setMinWidth(dp(28));
                final int ix = i;
                AeDesign.press(v, () -> {
                    selected = ix;
                    if (preview != null) preview.seekTo(project.clips.get(ix).startTimeMsIn(project) / 1000f);
                    refreshSelection();
                    showClipPanel();
                });
                v.setOnLongClickListener(x -> { removeOrMoveDialog(ix); return true; });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp((int) (c.durationSec * TimelineRulerView.VEL_DP)), dp(84));
                lp.leftMargin = dp((int) TimelineRulerView.GAP_DP);
                timeline.addView(v, lp);
                chips.add(v);
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
        if (preview != null) preview.invalidate();
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
        showPanel("Motion → " + scope,
                new String[]{"Zoom In", "Zoom Out", "Pan Left", "Pan Right", "Pan Up", "Pan Down", "Slow Push In", "No Motion"},
                new Runnable[]{() -> applyFormula("06"), () -> applyFormula("07"), () -> applyFormula("02"), () -> applyFormula("04"), () -> applyFormula("05"), () -> applyFormula("01"), () -> applyFormula("14"), () -> applyFormula("00")});
    }

    private void formulaBatchPanel() {
        openTool("formula");
        showPanel("Motion Formula (apply to ALL)",
                new String[]{"Story Zoom → All", "Documentary → All", "Cinematic → All", "Pan Mix → All", "Slow Motion → All", "Remove from All"},
                new Runnable[]{
                        () -> applyFormulaSequenceToAll("story"),
                        () -> applyFormulaSequenceToAll("documentary"),
                        () -> applyFormulaSequenceToAll("cinematic"),
                        () -> applyFormulaSequenceToAll("pan"),
                        () -> applyFormulaSequenceToAll("slow"),
                        () -> applyFormulaSequenceToAll("none")
                });
    }

    private void transitionPanel() {
        openTool("transition");
        TransitionType[] vals = TransitionEngine.rendered();
        String[] n = new String[vals.length];
        Runnable[] r = new Runnable[vals.length];
        for (int i = 0; i < vals.length; i++) {
            n[i] = TransitionEngine.label(vals[i]);
            final TransitionType t = vals[i];
            r[i] = () -> applyTransition(t);
        }
        showPanel("Transition → " + (selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL clips") + " (all rendered in preview + export)", n, r);
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
        showPanel("Filters → " + scopeLabel(),
                new String[]{"Cinematic", "Warm", "Cool", "Vintage", "Film", "B&W", "Dramatic", "Portrait", "HDR-style"},
                new Runnable[]{() -> applyEffect(EffectType.CINEMATIC), () -> applyEffect(EffectType.TEMPERATURE), () -> applyEffect(EffectType.SOFT_FOCUS), () -> applyEffect(EffectType.VINTAGE), () -> applyEffect(EffectType.FILM), () -> applyEffect(EffectType.BLACK_WHITE), () -> applyEffect(EffectType.CONTRAST), () -> applyEffect(EffectType.DREAM), () -> applyEffect(EffectType.SHARPEN)});
    }

    private void effectsPanel() {
        openTool("effects");
        showPanel("Effects → " + scopeLabel(),
                new String[]{"Glow", "Flash", "Vignette", "Blur", "Motion Blur", "Grain", "Vintage", "Cinematic", "Reset"},
                new Runnable[]{() -> applyEffect(EffectType.GLOW), () -> applyTransition(TransitionType.FLASH), () -> applyEffect(EffectType.VIGNETTE), () -> applyEffect(EffectType.BLUR), () -> applyEffect(EffectType.MOTION_BLUR), () -> applyEffect(EffectType.FILM_GRAIN), () -> applyEffect(EffectType.VINTAGE), () -> applyEffect(EffectType.CINEMATIC), () -> applyEffect(EffectType.NONE)});
    }

    private void adjustPanel() {
        openTool("adjust");
        showPanel("Color Adjust → " + scopeLabel(),
                new String[]{"Brightness", "Contrast", "Saturation", "Exposure", "Temperature", "Highlights", "Shadows", "Sharpen", "Reset"},
                new Runnable[]{() -> applyEffect(EffectType.BRIGHTNESS), () -> applyEffect(EffectType.CONTRAST), () -> applyEffect(EffectType.SATURATION), () -> applyEffect(EffectType.EXPOSURE), () -> applyEffect(EffectType.TEMPERATURE), () -> applyEffect(EffectType.HIGHLIGHTS), () -> applyEffect(EffectType.SHADOWS), () -> applyEffect(EffectType.SHARPEN), () -> applyEffect(EffectType.NONE)});
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
        LinearLayout grid = rowWrap();
        for (int i = 0; i < items.length; i++) {
            final Runnable r = actions[i];
            addAction(grid, items[i], () -> { r.run(); if (preview != null) preview.invalidate(); });
        }
        panelHost.addView(grid);
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

    private void applyFormula(String id) {
        pushUndo();
        Formula f = formulas.byId(id);
        if (selected >= 0) project.clips.get(selected).formula = f;
        else for (int i = 0; i < project.clips.size(); i++) project.clips.get(i).formula = f;
        saveProject(true);
        buildTimeline(false);
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
        pushUndo();
        if (selected >= 0) project.clips.get(selected).transition = t;
        else for (TimelineClip c : project.clips) c.transition = t;
        saveProject(true);
        if (preview != null) preview.invalidate();
        toast("Transition: " + TransitionEngine.label(t) + " → " + (selected >= 0 ? "Clip " + project.clips.get(selected).index : "ALL clips"));
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

    private void autoEdit(int mode) {
        pushUndo();
        for (int i = 0; i < project.clips.size(); i++) {
            TimelineClip c = project.clips.get(i);
            c.setDurationSeconds(mode == 2 ? 3f : mode == 3 ? 4f : 5f);
            c.formula = mode == 2 ? formulas.byId("16") : mode == 3 ? formulas.byId("14") : mode == 4 ? formulas.byId("17") : formulas.randomFor(i);
            c.transition = TransitionType.CROSS_DISSOLVE;
            c.effect = mode == 0 ? EffectType.SOFT_FOCUS : EffectType.CINEMATIC;
        }
        saveProject(true);
        buildTimeline(false);
        toast("Auto Edit generated");
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

    private void startExistingExport() {
        if (project.clips.isEmpty()) { toast("Import images first"); return; }
        int width = draftPreset.width, height = draftPreset.height;
        if (draftPreset == ExportPreset.CUSTOM) { width = project.width; height = project.height; }
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
        updateExportProgress(1, "Preparing...");
    }

    private void updateExportProgress(int p, String m) {
        if (exportProgress != null) {
            exportProgress.setProgress(Math.max(0, p));
            exportPercent.setText(p < 0 ? "Failed" : p + "%");
            exportStage.setText(p < 0 ? m : stage(p, m));
            if (p == 100) exportStage.setText("✓ Export Complete • " + m);
        }
        if (p == 100) toast("✓ Export complete: " + m);
        else if (p < 0) toast("Export failed: " + m);
    }

    private String stage(int p, String m) {
        if (p < 0) return m;
        if (p < 10) return "Optimizing images... " + m;
        if (p < 45) return "Rendering frames... " + m;
        if (p < 85) return "Encoding... " + m;
        if (p < 100) return "Saving to Gallery... " + m;
        return m;
    }

    // ---------------------------------------------------------------- settings

    private void showSettings() {
        String from = screen;
        screen = "settings";
        base();
        addHeader("Settings", "Editor • Playback • Export • Storage • About", () -> { if ("home".equals(from)) showHome(); else showEditor(); });
        showPanelIntoRoot("Editor", new String[]{"Default aspect ratio: " + draftPreset.label, "Default FPS: " + draftFps, "Auto-save: ON (every 30s)", "Undo / Redo: 40 steps"});
        showPanelIntoRoot("Playback", new String[]{"Preview quality: Optimized (sampled decode + LRU)", "Preview FPS: " + project.fps, "Audio in preview: ON (plays with Play)"});
        showPanelIntoRoot("Export", new String[]{"Resolution: " + project.width + "×" + project.height, "Pipeline: MediaCodec H.264 → MediaMuxer MP4 (protected)", "Audio export: COMING SOON (video-only)"});
        showPanelIntoRoot("Storage", new String[]{"Export location: Movies/AutoEdit", "Image cache: app cache dir (auto-cleaned)"});
        showPanelIntoRoot("About", new String[]{"Auto-Edit v1.0.5", "Offline-first: media never leaves the device"});
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
