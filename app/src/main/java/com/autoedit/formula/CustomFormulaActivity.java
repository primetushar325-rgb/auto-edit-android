package com.autoedit.formula;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import com.autoedit.R;
import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.*;
import com.autoedit.project.CustomFormulaStore;
import com.autoedit.ui.AeDesign;
import com.autoedit.ui.KeyframePreviewView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Custom Formula library + creator.
 *
 * Library: lists saved custom formulas (Apply / Edit / Duplicate / Delete).
 * Creator: name, category, one preview image (reference only — never modified),
 * total duration, 2–4 keyframes (time auto-spaced, editable scale/pan/rotation/
 * opacity/easing + optional per-step transition/effect + motion presets that
 * fill values from the EXISTING built-in motions), and a live looping preview
 * rendered through the same FormulaEngine math as editor preview/export.
 *
 * Applying: returns the formula id to the caller (MainActivity) which applies
 * it through its own undo/redo-safe state operation — no duplicate systems.
 */
public class CustomFormulaActivity extends Activity {
    public static final String EXTRA_FORMULA_ID = "formula_id";
    private static final int PICK_PREVIEW = 30;

    private final FormulaEngine builtin = new FormulaEngine();
    private LinearLayout root;
    private String screen = "list";
    private String editingId = null;

    // ---- editor state (rebuilt on every render; the preview always uses these) ----
    private String eName = "My Formula";
    private String eCategory = "Custom";
    private String ePreviewUri = null;
    private float eTotalSec = 8f;
    private final ArrayList<Kf> kfs = new ArrayList<>();

    private static class Kf {
        float time, zoom = 1f, panX, panY, rotation, opacity = 1f;
        Easing easing = Easing.EASE_IN_OUT;
        TransitionType transition = TransitionType.NONE;
        EffectType effect = EffectType.NONE;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        kfs.add(new Kf());
        kfs.add(new Kf());
        if (getIntent() != null && getIntent().hasExtra("edit_id")) {
            String id = getIntent().getStringExtra("edit_id");
            JSONObject o = CustomFormulaStore.byId(this, id);
            if (o != null) loadIntoEditor(o);
        }
        showList();
    }

    // ===================================================================== LIST

    private void showList() {
        screen = "list";
        base();
        header("Custom Formulas", "Create, preview and manage your own motion formulas", true, () -> finish());
        Button create = AeDesign.button(this, "+  CREATE NEW FORMULA", true);
        AeDesign.press(create, () -> { editingId = null; resetEditor(); showEdit(); });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(58));
        clp.setMargins(0, dp(14), 0, dp(10));
        root.addView(create, clp);

        List<JSONObject> all = CustomFormulaStore.all(this);
        if (all.isEmpty()) {
            LinearLayout c = AeDesign.card(this);
            c.setGravity(Gravity.CENTER);
            c.addView(label("No custom formulas yet", 17, AeDesign.TEXT, Typeface.BOLD));
            TextView hint = label("Create one from your own image and keyframes — it will appear in the editor Formula panel next to the built-in sequences.", 13, AeDesign.MUTED, Typeface.NORMAL);
            hint.setGravity(Gravity.CENTER);
            c.addView(hint);
            root.addView(c, new LinearLayout.LayoutParams(-1, -2));
        } else {
            ScrollView sv = new ScrollView(this);
            LinearLayout col = col();
            for (JSONObject o : all) col.addView(card(o));
            sv.addView(col);
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    private LinearLayout card(JSONObject o) {
        LinearLayout card = AeDesign.card(this);
        LinearLayout top = row();
        LinearLayout thumb = new LinearLayout(this);
        thumb.setGravity(Gravity.CENTER);
        thumb.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(16), AeDesign.STROKE, 1));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(loadThumb(o));
        thumb.addView(iv, new LinearLayout.LayoutParams(dp(84), dp(84)));
        top.addView(thumb, new LinearLayout.LayoutParams(dp(84), dp(84)));
        LinearLayout info = col();
        info.setPadding(dp(12), 0, 0, 0);
        LinearLayout nameRow = row();
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(label(o.optString("name", "Custom"), 16, AeDesign.TEXT, Typeface.BOLD));
        TextView badge = label("CUSTOM", 9, AeDesign.ACCENT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(AeDesign.bg(0xff12395c, dp(10), AeDesign.ACCENT, 1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = dp(8);
        nameRow.addView(badge, blp);
        info.addView(nameRow);
        int kfCount = o.optJSONArray("keyframes") != null ? o.optJSONArray("keyframes").length() : 0;
        info.addView(label((o.optString("category", "Custom")) + " • " + Math.round(o.optDouble("totalDuration", 8)) + "s • " + kfCount + " keyframes • " + (kfCount - 1) + " steps", 12, AeDesign.MUTED, Typeface.NORMAL));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);

        LinearLayout btns = rowWrap();
        addBtn(btns, "Apply", true, () -> { Intent r = new Intent(); r.putExtra(EXTRA_FORMULA_ID, o.optString("id")); setResult(RESULT_OK, r); finish(); });
        addBtn(btns, "Edit", false, () -> { editingId = o.optString("id"); loadIntoEditor(o); showEdit(); });
        addBtn(btns, "Duplicate", false, () -> duplicate(o));
        addBtn(btns, "Delete", false, () -> confirmDelete(o));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(10);
        card.addView(btns, bl);
        return card;
    }

    private Bitmap loadThumb(JSONObject o) {
        try {
            String uri = CustomFormulaStore.previewUri(o);
            if (uri == null) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 8;
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(uri))) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void duplicate(JSONObject o) {
        try {
            JSONObject copy = new JSONObject(o.toString());
            copy.put("id", "C" + System.currentTimeMillis());
            copy.put("name", o.optString("name") + " Copy");
            copy.put("savedAt", System.currentTimeMillis());
            CustomFormulaStore.save(this, copy);
            showList();
            toast("Duplicated");
        } catch (Exception e) {
            toast("Duplicate failed");
        }
    }

    private void confirmDelete(JSONObject o) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Custom Formula?")
                .setMessage("\"" + o.optString("name") + "\" will be removed. Clips already using it keep their motion (the formula state is stored on the clip).")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    CustomFormulaStore.delete(this, o.optString("id"));
                    showList();
                    toast("Deleted");
                }).show();
    }

    // ===================================================================== EDIT

    private void resetEditor() {
        eName = "My Formula";
        eCategory = "Custom";
        ePreviewUri = null;
        eTotalSec = 8f;
        kfs.clear();
        kfs.add(new Kf());
        kfs.add(new Kf());
        spreadTimes();
    }

    private void loadIntoEditor(JSONObject o) {
        eName = o.optString("name", "My Formula");
        eCategory = o.optString("category", "Custom");
        ePreviewUri = CustomFormulaStore.previewUri(o);
        eTotalSec = (float) o.optDouble("totalDuration", 8);
        kfs.clear();
        JSONArray arr = o.optJSONArray("keyframes");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject k = arr.optJSONObject(i);
                Kf f = new Kf();
                f.time = (float) k.optDouble("time");
                f.zoom = (float) k.optDouble("zoom", 1);
                f.panX = (float) k.optDouble("panX", 0);
                f.panY = (float) k.optDouble("panY", 0);
                f.rotation = (float) k.optDouble("rotation", 0);
                f.opacity = (float) k.optDouble("opacity", 1);
                f.easing = safeEasing(k.optString("easing"));
                f.transition = safeTransition(k.optString("transition"));
                f.effect = safeEffect(k.optString("effect"));
                kfs.add(f);
            }
        }
        if (kfs.size() < 2) { kfs.clear(); kfs.add(new Kf()); kfs.add(new Kf()); }
        spreadTimes();
    }

    private void showEdit() {
        screen = "edit";
        base();
        header(editingId == null ? "Create Custom Formula" : "Edit Custom Formula", "Keyframes interpolate smoothly through the existing FormulaEngine", true, () -> showList());

        ScrollView sv = new ScrollView(this);
        LinearLayout col = col();
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- fields
        col.addView(label("Formula Name", 13, AeDesign.MUTED, Typeface.BOLD));
        EditText name = new EditText(this);
        name.setText(eName);
        name.setTextColor(AeDesign.TEXT);
        name.setTextSize(15);
        name.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(16), AeDesign.STROKE, 1));
        name.setPadding(dp(12), dp(10), dp(12), dp(10));
        name.setSingleLine(true);
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));

        col.addView(label("Category", 13, AeDesign.MUTED, Typeface.BOLD));
        EditText category = new EditText(this);
        category.setText(eCategory);
        category.setTextColor(AeDesign.TEXT);
        category.setTextSize(15);
        category.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(16), AeDesign.STROKE, 1));
        category.setPadding(dp(12), dp(10), dp(12), dp(10));
        category.setSingleLine(true);
        col.addView(category, new LinearLayout.LayoutParams(-1, -2));

        // ---- preview image + live preview
        col.addView(label("Preview Image (reference only — never modified)", 13, AeDesign.MUTED, Typeface.BOLD));
        KeyframePreviewView pv = new KeyframePreviewView(this);
        pv.setImageUri(ePreviewUri == null ? null : Uri.parse(ePreviewUri));
        pv.setFormula(buildFormula());
        LinearLayout.LayoutParams pvlp = new LinearLayout.LayoutParams(-1, dp(220));
        pvlp.topMargin = dp(6);
        col.addView(pv, pvlp);
        LinearLayout prevBtns = row();
        Button pick = AeDesign.button(this, ePreviewUri == null ? "Choose image" : "Change image", true);
        AeDesign.press(pick, () -> pickPreview());
        prevBtns.addView(pick, new LinearLayout.LayoutParams(-2, dp(46)));
        Button play = AeDesign.button(this, "Pause", false);
        AeDesign.press(play, () -> {
            if (pv.isPlaying()) { pv.setPlaying(false); play.setText("Play"); } else { pv.setPlaying(true); play.setText("Pause"); }
        });
        LinearLayout.LayoutParams playlp = new LinearLayout.LayoutParams(-2, dp(46));
        playlp.leftMargin = dp(8);
        prevBtns.addView(play, playlp);
        prevBtns.addView(label("Live loop — same interpolation as preview/export", 11, AeDesign.MUTED, Typeface.NORMAL), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(prevBtns, new LinearLayout.LayoutParams(-1, -2));

        // ---- duration
        col.addView(label("Total Duration", 13, AeDesign.MUTED, Typeface.BOLD));
        LinearLayout durs = row();
        int[] dv = {4, 6, 8, 10, 12, 16};
        for (int v : dv) {
            final int sec = v;
            TextView t = label(sec + "s", 13, AeDesign.TEXT, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            boolean on = Math.round(eTotalSec) == sec;
            t.setBackground(AeDesign.bg(on ? 0xff12395c : AeDesign.SURFACE_2, dp(14), on ? AeDesign.ACCENT : AeDesign.STROKE, on ? 2 : 1));
            AeDesign.press(t, () -> { eTotalSec = sec; spreadTimes(); showEdit(); });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(40), 1);
            tlp.setMargins(dp(3), dp(3), dp(3), dp(3));
            durs.addView(t, tlp);
        }
        col.addView(durs);

        // ---- keyframes
        LinearLayout kfHead = row();
        kfHead.setGravity(Gravity.CENTER_VERTICAL);
        kfHead.addView(label("Keyframes (" + kfs.size() + ")", 15, AeDesign.TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        addBtn(kfHead, "+ Add", kfs.size() < 4, () -> { if (kfs.size() < 4) { kfs.add(new Kf()); spreadTimes(); showEdit(); } });
        addBtn(kfHead, "− Remove", kfs.size() > 2, () -> { if (kfs.size() > 2) { kfs.remove(kfs.size() - 1); spreadTimes(); showEdit(); } });
        col.addView(kfHead);

        for (int i = 0; i < kfs.size(); i++) col.addView(keyframeCard(i, pv));

        // ---- save
        Button save = AeDesign.button(this, "SAVE FORMULA", true);
        AeDesign.press(save, () -> {
            eName = name.getText().toString().trim();
            eCategory = category.getText().toString().trim();
            if (eCategory.isEmpty()) eCategory = "Custom";
            String err = validate();
            if (err != null) { toast(err); return; }
            saveFormula();
            showList();
            toast("Formula saved — it now appears in the editor Formula panel");
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(56));
        slp.setMargins(0, dp(16), 0, dp(10));
        col.addView(save, slp);
    }

    private LinearLayout keyframeCard(int i, KeyframePreviewView pv) {
        Kf k = kfs.get(i);
        LinearLayout card = AeDesign.card(this);
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("KEYFRAME " + (i + 1), 14, AeDesign.TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        TextView time = label(String.format(Locale.US, "%.1f sec", k.time), 13, AeDesign.ACCENT, Typeface.BOLD);
        head.addView(time);
        card.addView(head);

        card.addView(slider(card, pv, "Zoom", "%.2f×", k.zoom, 0.5f, 2.0f, v -> k.zoom = v));
        card.addView(slider(card, pv, "Pan X", "%+.2f", k.panX, -0.5f, 0.5f, v -> k.panX = v));
        card.addView(slider(card, pv, "Pan Y", "%+.2f", k.panY, -0.5f, 0.5f, v -> k.panY = v));
        card.addView(slider(card, pv, "Rotation", "%+.0f°", k.rotation, -45f, 45f, v -> k.rotation = v));
        card.addView(slider(card, pv, "Opacity", "%.0f%%", k.opacity, 0f, 1f, v -> k.opacity = v));

        // motion presets: fill this keyframe's segment (this → next) from built-in motions
        if (i < kfs.size() - 1) {
            card.addView(label("Motion preset for this segment (fills values from built-in motions)", 11, AeDesign.MUTED, Typeface.NORMAL));
            LinearLayout presets = rowWrap();
            addBtn(presets, "No Motion", true, () -> fillSegment(i, "00", pv));
            addBtn(presets, "Zoom In", false, () -> fillSegment(i, "06", pv));
            addBtn(presets, "Zoom Out", false, () -> fillSegment(i, "07", pv));
            addBtn(presets, "Pan Left", false, () -> fillSegment(i, "02", pv));
            addBtn(presets, "Pan Right", false, () -> fillSegment(i, "04", pv));
            addBtn(presets, "Pan Up", false, () -> fillSegment(i, "05", pv));
            addBtn(presets, "Pan Down", false, () -> fillSegment(i, "01", pv));
            addBtn(presets, "Slow Push In", false, () -> fillSegment(i, "14", pv));
            addBtn(presets, "Slow Pull Out", false, () -> fillSegment(i, "15", pv));
            addBtn(presets, "Ken Burns", false, () -> fillSegment(i, "17", pv));
            card.addView(presets);
        }

        if (i < kfs.size() - 1) {
            card.addView(label("Easing → next keyframe", 11, AeDesign.MUTED, Typeface.NORMAL));
            LinearLayout eases = row();
            for (Easing e : Easing.values()) {
                TextView t = label(e.name().replace("_", " "), 10, AeDesign.TEXT, Typeface.BOLD);
                t.setGravity(Gravity.CENTER);
                boolean on = k.easing == e;
                t.setBackground(AeDesign.bg(on ? 0xff12395c : AeDesign.SURFACE_2, dp(12), on ? AeDesign.ACCENT : AeDesign.STROKE, on ? 2 : 1));
                AeDesign.press(t, () -> { k.easing = e; showEdit(); });
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(36), 1);
                tlp.setMargins(dp(2), dp(2), dp(2), dp(2));
                eases.addView(t, tlp);
            }
            card.addView(eases);

            card.addView(label("Step transition (played into the next keyframe)", 11, AeDesign.MUTED, Typeface.NORMAL));
            LinearLayout trans = rowWrap();
            addBtn(trans, "None", k.transition == TransitionType.NONE, () -> { k.transition = TransitionType.NONE; showEdit(); });
            addBtn(trans, "Fade", k.transition == TransitionType.FADE, () -> { k.transition = TransitionType.FADE; showEdit(); });
            addBtn(trans, "Cross", k.transition == TransitionType.CROSS_DISSOLVE, () -> { k.transition = TransitionType.CROSS_DISSOLVE; showEdit(); });
            addBtn(trans, "Zoom", k.transition == TransitionType.ZOOM, () -> { k.transition = TransitionType.ZOOM; showEdit(); });
            addBtn(trans, "Flash", k.transition == TransitionType.FLASH, () -> { k.transition = TransitionType.FLASH; showEdit(); });
            addBtn(trans, "Slide L", k.transition == TransitionType.SLIDE_LEFT, () -> { k.transition = TransitionType.SLIDE_LEFT; showEdit(); });
            card.addView(trans);

            card.addView(label("Step effect (applied while this segment plays)", 11, AeDesign.MUTED, Typeface.NORMAL));
            LinearLayout eff = rowWrap();
            addBtn(eff, "None", k.effect == EffectType.NONE, () -> { k.effect = EffectType.NONE; showEdit(); });
            addBtn(eff, "Cinematic", k.effect == EffectType.CINEMATIC, () -> { k.effect = EffectType.CINEMATIC; showEdit(); });
            addBtn(eff, "Glow", k.effect == EffectType.GLOW, () -> { k.effect = EffectType.GLOW; showEdit(); });
            addBtn(eff, "Vignette", k.effect == EffectType.VIGNETTE, () -> { k.effect = EffectType.VIGNETTE; showEdit(); });
            addBtn(eff, "B&W", k.effect == EffectType.BLACK_WHITE, () -> { k.effect = EffectType.BLACK_WHITE; showEdit(); });
            card.addView(eff);
        }
        return card;
    }

    /** Fills keyframe i from motion.start and keyframe i+1 from motion.end (built-in). */
    private void fillSegment(int i, String motionId, KeyframePreviewView pv) {
        Formula m = builtin.byId(motionId);
        Kf a = kfs.get(i), b = kfs.get(i + 1);
        a.zoom = m.start.scale; a.panX = m.start.x; a.panY = m.start.y; a.rotation = m.start.rotation; a.opacity = m.start.opacity;
        b.zoom = m.end.scale; b.panX = m.end.x; b.panY = m.end.y; b.rotation = m.end.rotation; b.opacity = m.end.opacity;
        if (m.id.equals("00")) { a.easing = Easing.LINEAR; b.easing = Easing.LINEAR; }
        pv.setFormula(buildFormula());
        showEdit();
    }

    private interface F1 { void set(float v); }

    private LinearLayout slider(LinearLayout parent, KeyframePreviewView pv, String name, String fmt, float cur, float min, float max, F1 set) {
        LinearLayout wrap = col();
        LinearLayout head = row();
        head.addView(label(name, 12, AeDesign.TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = label(String.format(Locale.US, fmt, cur), 12, AeDesign.ACCENT, Typeface.BOLD);
        head.addView(val);
        wrap.addView(head);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress((int) ((cur - min) / (max - min) * 100));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float v = min + (max - min) * p / 100f;
                set.set(v);
                val.setText(String.format(Locale.US, fmt, v));
                if (fromUser) pv.setFormula(buildFormula());
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { showEdit(); }
        });
        wrap.addView(sb);
        return wrap;
    }

    // ===================================================================== model

    private void spreadTimes() {
        int n = kfs.size();
        for (int i = 0; i < n; i++) kfs.get(i).time = n == 1 ? 0f : eTotalSec * i / (n - 1f);
    }

    /** Rebuilds the real Formula from the current keyframes — the SAME object
     *  shape the store writes, so preview == saved == applied == exported. */
    private Formula buildFormula() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", editingId == null ? "C" + System.currentTimeMillis() : editingId);
            o.put("name", eName);
            o.put("category", eCategory);
            o.put("totalDuration", eTotalSec);
            JSONArray arr = new JSONArray();
            for (Kf k : kfs) {
                JSONObject j = new JSONObject();
                j.put("time", k.time);
                j.put("zoom", k.zoom); j.put("panX", k.panX); j.put("panY", k.panY);
                j.put("rotation", k.rotation); j.put("opacity", k.opacity);
                j.put("easing", k.easing.name());
                j.put("transition", k.transition.name());
                j.put("effect", k.effect.name());
                arr.put(j);
            }
            o.put("keyframes", arr);
        } catch (Exception ignored) {}
        return CustomFormulaStore.toFormula(o);
    }

    private String validate() {
        if (eName.isEmpty()) return "Please enter a formula name";
        if (ePreviewUri == null || ePreviewUri.isEmpty()) return "Please choose a preview image";
        if (kfs.size() < 2) return "At least 2 keyframes are required";
        if (eTotalSec < 1f || eTotalSec > 120f) return "Duration must be 1–120 seconds";
        for (int i = 1; i < kfs.size(); i++) if (kfs.get(i).time <= kfs.get(i - 1).time) return "Keyframe times must be chronological";
        return null;
    }

    private void saveFormula() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", editingId == null ? "C" + System.currentTimeMillis() : editingId);
            o.put("name", eName);
            o.put("category", eCategory);
            o.put("previewUri", ePreviewUri);
            o.put("totalDuration", eTotalSec);
            o.put("savedAt", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            for (Kf k : kfs) {
                JSONObject j = new JSONObject();
                j.put("time", k.time);
                j.put("zoom", k.zoom); j.put("panX", k.panX); j.put("panY", k.panY);
                j.put("rotation", k.rotation); j.put("opacity", k.opacity);
                j.put("easing", k.easing.name());
                j.put("transition", k.transition.name());
                j.put("effect", k.effect.name());
                j.put("effectIntensity", 0.6);
                arr.put(j);
            }
            o.put("keyframes", arr);
            CustomFormulaStore.save(this, o);
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    // ===================================================================== pickers

    private void pickPreview() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, PICK_PREVIEW);
        } catch (Exception e) {
            toast("Could not open image picker");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_PREVIEW && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            ePreviewUri = u.toString();
            showEdit();
        }
    }

    @Override public void onBackPressed() {
        if ("edit".equals(screen)) showList();
        else super.onBackPressed();
    }

    // ===================================================================== helpers

    private void header(String title, String sub, boolean back, Runnable onBack) {
        LinearLayout h = row();
        h.setGravity(Gravity.CENTER_VERTICAL);
        ImageView b = AeDesign.iconButton(this, R.drawable.ic_back, "Back", false);
        AeDesign.press(b, onBack);
        h.addView(b, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout t = col();
        t.addView(label(title, 22, AeDesign.TEXT, Typeface.BOLD));
        t.addView(label(sub, 12, AeDesign.MUTED, Typeface.NORMAL));
        h.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(h);
        View sep = new View(this);
        sep.setBackgroundColor(0x3349A8FF);
        root.addView(sep, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void base() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackgroundColor(AeDesign.BG);
        setContentView(root);
    }

    private void addBtn(LinearLayout p, String s, boolean accent, Runnable r) {
        TextView v = label(s, 11, AeDesign.TEXT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(AeDesign.bg(accent ? 0xff12395c : AeDesign.SURFACE_2, dp(16), accent ? AeDesign.ACCENT : AeDesign.STROKE, accent ? 2 : 1));
        AeDesign.press(v, r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        p.addView(v, lp);
    }

    private Easing safeEasing(String s) { try { return Easing.valueOf(s); } catch (Exception e) { return Easing.EASE_IN_OUT; } }
    private TransitionType safeTransition(String s) { try { return TransitionType.valueOf(s); } catch (Exception e) { return TransitionType.NONE; } }
    private EffectType safeEffect(String s) { try { return EffectType.valueOf(s); } catch (Exception e) { return EffectType.NONE; } }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout rowWrap() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView label(String s, int sp, int color, int style) { return AeDesign.text(this, s, sp, color, style); }
    private int dp(int v) { return AeDesign.dp(this, v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
