package com.autoedit.formula;

import android.app.Activity;
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
import com.autoedit.engine.MotionCatalog;
import com.autoedit.model.*;
import com.autoedit.project.CustomFormulaStore;
import com.autoedit.ui.AeDesign;
import com.autoedit.ui.EasingPreviewView;
import com.autoedit.ui.FormulaPreviewView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom Formula library + creator.
 *
 * CONCEPT (Parts 3 & 16): a custom formula is a REPEATING PER-CLIP PATTERN.
 * The creator defines PATTERN STEPS, and every step = ONE CLIP:
 *
 *   Step 1: Motion + Easing + Effect + Effect Intensity + optional Transition
 *   Step 2: ...
 *
 * Applied to N clips, clip i uses step (i % stepCount). There is NO notion of
 * multiple motions inside one clip. Unlimited steps (ADD/DELETE/MOVE/
 * DUPLICATE). One reference preview image (never modified) loops the pattern.
 *
 * Saved as steps[] JSON. Legacy keyframes[] custom formulas still open
 * (migrated to one step per adjacent keyframe pair via CustomFormulaStore).
 *
 * Applying returns the formula id to MainActivity, which applies it through
 * its undo/redo-safe state operation — no duplicate system.
 */
public class CustomFormulaActivity extends Activity {
    public static final String EXTRA_FORMULA_ID = "formula_id";
    private static final int PICK_PREVIEW = 30;

    private final FormulaEngine builtin = new FormulaEngine();
    private LinearLayout root;
    private String screen = "list";
    private String editingId = null;

    // ---- editor state ----
    private String eName = "My Formula";
    private String eCategory = "Custom";
    private String ePreviewUri = null;
    private final ArrayList<Step> steps = new ArrayList<>();

    private static class Step {
        String motionId = "14";
        Easing easing = Easing.EASE_IN_OUT;
        EffectType effect = EffectType.NONE;
        float effectIntensity = 0.6f;
        TransitionType transition = TransitionType.NONE;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getIntent() != null && getIntent().hasExtra("edit_id")) {
            String id = getIntent().getStringExtra("edit_id");
            JSONObject o = CustomFormulaStore.byId(this, id);
            if (o != null) { editingId = id; loadIntoEditor(o); showEdit(); return; }
        }
        showList();
    }

    // ===================================================================== LIST
    private void showList() {
        screen = "list";
        base();
        header("Custom Formulas", "Per-clip motion patterns you create", false, null);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = col();
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        Button add = AeDesign.button(this, "+ CREATE NEW FORMULA", true);
        AeDesign.press(add, () -> { resetEditor(); showEdit(); });
        col.addView(add, new LinearLayout.LayoutParams(-1, dp(52)));

        List<JSONObject> all = CustomFormulaStore.all(this);
        col.addView(label(all.size() + " saved formula(s)", 13, AeDesign.MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(-1, -2));
        for (JSONObject o : all) col.addView(libraryCard(o));
    }

    private LinearLayout libraryCard(JSONObject o) {
        LinearLayout card = AeDesign.card(this);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        FormulaPreviewView pv = new FormulaPreviewView(this);
        pv.setFormula(CustomFormulaStore.toFormula(o));
        top.addView(pv, new LinearLayout.LayoutParams(dp(96), dp(96)));
        LinearLayout info = col();
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, -2, 1);
        grow.leftMargin = dp(10);
        info.addView(label(o.optString("name", "Custom"), 16, AeDesign.TEXT, Typeface.BOLD));
        info.addView(label(o.optString("category", "Custom") + " • " + CustomFormulaStore.stepCount(o) + "-clip pattern",
                12, AeDesign.MUTED, Typeface.NORMAL));
        top.addView(info, grow);
        card.addView(top);

        LinearLayout btns = row();
        addBtn(btns, "Apply", true, () -> {
            Intent r = new Intent();
            r.putExtra(EXTRA_FORMULA_ID, o.optString("id"));
            setResult(RESULT_OK, r);
            finish();
        });
        addBtn(btns, "Edit", false, () -> { editingId = o.optString("id"); loadIntoEditor(o); showEdit(); });
        addBtn(btns, "Duplicate", false, () -> {
            try {
                JSONObject copy = new JSONObject(o.toString());
                copy.put("id", "C" + System.currentTimeMillis());
                copy.put("name", o.optString("name") + " Copy");
                copy.put("savedAt", System.currentTimeMillis());
                CustomFormulaStore.save(this, copy);
                showList(); toast("Duplicated");
            } catch (Exception e) { toast("Duplicate failed"); }
        });
        addBtn(btns, "Delete", false, () -> new android.app.AlertDialog.Builder(this)
                .setTitle("Delete formula?").setMessage("\"" + o.optString("name") + "\" will be removed.")
                .setPositiveButton("Delete", (d, w) -> { CustomFormulaStore.delete(this, o.optString("id")); showList(); })
                .setNegativeButton("Cancel", null).show());
        card.addView(btns);
        return card;
    }

    // ===================================================================== EDIT
    private void resetEditor() {
        editingId = null;
        eName = "My Formula";
        eCategory = "Custom";
        ePreviewUri = null;
        steps.clear();
        steps.add(new Step());
        steps.add(new Step());
    }

    private void loadIntoEditor(JSONObject o) {
        eName = o.optString("name", "My Formula");
        eCategory = o.optString("category", "Custom");
        ePreviewUri = CustomFormulaStore.previewUri(o);
        steps.clear();
        JSONArray arr = o.optJSONArray("steps");
        if (arr != null && arr.length() > 0) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                Step st = new Step();
                st.motionId = s.optString("motionId", "14");
                if (MotionCatalog.indexOf(st.motionId) < 0) st.motionId = "14";
                st.easing = safeEasing(s.optString("easing"));
                st.transition = safeTransition(s.optString("transition"));
                st.effect = safeEffect(s.optString("effect"));
                st.effectIntensity = (float) s.optDouble("effectIntensity", 0.6);
                steps.add(st);
            }
        } else {
            // legacy keyframes[] -> one step per adjacent pair (migrate)
            JSONArray kfs = o.optJSONArray("keyframes");
            if (kfs != null) for (int i = 0; i < kfs.length() - 1; i++) steps.add(new Step());
        }
        if (steps.isEmpty()) steps.add(new Step());
    }

    private void showEdit() {
        screen = "edit";
        base();
        header(editingId == null ? "Create Custom Formula" : "Edit Custom Formula",
                "Each step = ONE clip. Clip i uses step (i % size).", true, this::showList);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = col();
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        col.addView(label("Formula Name", 13, AeDesign.MUTED, Typeface.BOLD));
        EditText name = editText(eName);
        col.addView(name);
        col.addView(label("Category", 13, AeDesign.MUTED, Typeface.BOLD));
        EditText category = editText(eCategory);
        col.addView(category);

        // live pattern preview (reference image only, never modifies project media)
        col.addView(label("Pattern Preview (reference image — never modified)", 13, AeDesign.MUTED, Typeface.BOLD));
        FormulaPreviewView pv = new FormulaPreviewView(this);
        pv.setFormula(buildFormula());
        col.addView(pv, new LinearLayout.LayoutParams(-1, dp(200)));
        LinearLayout pb = row();
        Button pick = AeDesign.button(this, ePreviewUri == null ? "Choose reference image" : "Change image", true);
        AeDesign.press(pick, this::pickPreview);
        pb.addView(pick, new LinearLayout.LayoutParams(-2, dp(44)));
        col.addView(pb);

        // step controls
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Pattern Steps (" + steps.size() + ")", 15, AeDesign.TEXT, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        addBtn(head, "+ ADD STEP", true, () -> { capture(name, category); steps.add(new Step()); showEdit(); });
        col.addView(head);

        for (int i = 0; i < steps.size(); i++) col.addView(stepCard(i, pv, name, category));

        Button save = AeDesign.button(this, "SAVE FORMULA", true);
        AeDesign.press(save, () -> {
            capture(name, category);
            String err = validate();
            if (err != null) { toast(err); return; }
            saveFormula();
            showList();
            toast("Formula saved — find it in the editor Formula panel");
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(54));
        slp.setMargins(0, dp(14), 0, dp(10));
        col.addView(save, slp);
    }

    private EditText editText(String val) {
        EditText e = new EditText(this);
        e.setText(val);
        e.setTextColor(AeDesign.TEXT);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setBackground(AeDesign.bg(AeDesign.SURFACE_2, dp(16), AeDesign.STROKE, 1));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private LinearLayout stepCard(int i, FormulaPreviewView pv, EditText nameF, EditText catF) {
        Step st = steps.get(i);
        LinearLayout card = AeDesign.card(this);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("STEP " + (i + 1) + "  →  Clips " + (i + 1) + ", " + (i + 1 + steps.size()) + ", " + (i + 1 + 2 * steps.size()) + "…",
                14, AeDesign.TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        addBtn(head, "▲", i > 0, () -> { capture(nameF, catF); if (i > 0) { java.util.Collections.swap(steps, i, i - 1); showEdit(); } });
        addBtn(head, "▼", i < steps.size() - 1, () -> { capture(nameF, catF); if (i < steps.size() - 1) { java.util.Collections.swap(steps, i, i + 1); showEdit(); } });
        addBtn(head, "⧉", true, () -> { capture(nameF, catF); steps.add(i + 1, dup(st)); showEdit(); });
        addBtn(head, "Delete", steps.size() > 1, () -> { capture(nameF, catF); if (steps.size() > 1) { steps.remove(i); showEdit(); } });
        card.addView(head);

        // Motion picker (horizontal motion chips from the real catalog)
        card.addView(label("Motion (one primary motion for the whole clip)", 11, AeDesign.MUTED, Typeface.NORMAL));
        HorizontalScrollView mh = new HorizontalScrollView(this);
        mh.setHorizontalScrollBarEnabled(false);
        LinearLayout mrow = row();
        for (Formula m : MotionCatalog.all()) {
            boolean on = st.motionId.equals(m.id);
            TextView t = label(m.name, 10, on ? 0xffffffff : AeDesign.TEXT, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            t.setBackground(AeDesign.bg(on ? 0xff12395c : AeDesign.SURFACE_2, dp(12), on ? AeDesign.ACCENT : AeDesign.STROKE, on ? 2 : 1));
            final String mid = m.id;
            AeDesign.press(t, () -> { capture(nameF, catF); st.motionId = mid; showEdit(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            mrow.addView(t, lp);
        }
        mh.addView(mrow);
        card.addView(mh);

        // Easing — cards with a live curve graph, not plain text chips (spec §12).
        card.addView(label("Easing", 11, AeDesign.MUTED, Typeface.NORMAL));
        HorizontalScrollView eh = new HorizontalScrollView(this);
        eh.setHorizontalScrollBarEnabled(false);
        LinearLayout erow = row();
        for (Easing e : Easing.values()) {
            erow.addView(easingCard(e, st.easing == e, nameF, catF, st));
        }
        eh.addView(erow);
        card.addView(eh);

        // Effect + intensity
        card.addView(label("Effect", 11, AeDesign.MUTED, Typeface.NORMAL));
        HorizontalScrollView fh = new HorizontalScrollView(this);
        fh.setHorizontalScrollBarEnabled(false);
        LinearLayout frow = row();
        for (EffectType e : new EffectType[]{EffectType.NONE, EffectType.CINEMATIC, EffectType.GLOW,
                EffectType.VIGNETTE, EffectType.BLACK_WHITE, EffectType.VINTAGE, EffectType.SEPIA,
                EffectType.BLUR, EffectType.DREAM, EffectType.SATURATION, EffectType.CONTRAST, EffectType.FILM}) {
            boolean on = st.effect == e;
            TextView t = label(effectLabel(e), 10, on ? 0xffffffff : AeDesign.TEXT, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            t.setBackground(AeDesign.bg(on ? 0xff12395c : AeDesign.SURFACE_2, dp(12), on ? AeDesign.ACCENT : AeDesign.STROKE, on ? 2 : 1));
            AeDesign.press(t, () -> { capture(nameF, catF); st.effect = e; showEdit(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            frow.addView(t, lp);
        }
        fh.addView(frow);
        card.addView(fh);

        card.addView(label("Effect intensity: " + Math.round(st.effectIntensity * 100) + "%", 11, AeDesign.MUTED, Typeface.NORMAL));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress((int) (st.effectIntensity * 100));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { st.effectIntensity = p / 100f; }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { capture(nameF, catF); showEdit(); }
        });
        card.addView(sb);

        // Transition (junction after this clip)
        card.addView(label("Transition into next clip (optional)", 11, AeDesign.MUTED, Typeface.NORMAL));
        HorizontalScrollView th = new HorizontalScrollView(this);
        th.setHorizontalScrollBarEnabled(false);
        LinearLayout trow = row();
        for (TransitionType t : new TransitionType[]{TransitionType.NONE, TransitionType.FADE,
                TransitionType.CROSS_DISSOLVE, TransitionType.ZOOM, TransitionType.SLIDE_LEFT,
                TransitionType.SLIDE_RIGHT, TransitionType.SLIDE_UP, TransitionType.SLIDE_DOWN,
                TransitionType.PUSH_LEFT, TransitionType.PUSH_RIGHT, TransitionType.WIPE_LEFT,
                TransitionType.CIRCLE_REVEAL, TransitionType.BLUR_TRANSITION, TransitionType.FLASH}) {
            boolean on = st.transition == t;
            TextView tx = label(com.autoedit.engine.TransitionEngine.label(t), 10, on ? 0xffffffff : AeDesign.TEXT, Typeface.BOLD);
            tx.setGravity(Gravity.CENTER);
            tx.setBackground(AeDesign.bg(on ? 0xff12395c : AeDesign.SURFACE_2, dp(12), on ? AeDesign.ACCENT : AeDesign.STROKE, on ? 2 : 1));
            AeDesign.press(tx, () -> { capture(nameF, catF); st.transition = t; showEdit(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            trow.addView(tx, lp);
        }
        th.addView(trow);
        card.addView(th);

        return card;
    }

    private Step dup(Step s) {
        Step n = new Step();
        n.motionId = s.motionId; n.easing = s.easing; n.effect = s.effect;
        n.effectIntensity = s.effectIntensity; n.transition = s.transition;
        return n;
    }

    private void capture(EditText nameF, EditText catF) {
        if (nameF != null) { String nm = nameF.getText().toString().trim(); if (!nm.isEmpty()) eName = nm; }
        if (catF != null) { String ct = catF.getText().toString().trim(); eCategory = ct.isEmpty() ? "Custom" : ct; }
    }

    /** Builds the live Formula (same shape saved) so the preview uses the real engine. */
    private Formula buildFormula() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", editingId == null ? "Cpreview" : editingId);
            o.put("name", eName);
            o.put("category", eCategory);
            JSONArray arr = new JSONArray();
            for (Step s : steps) {
                JSONObject j = new JSONObject();
                j.put("motionId", s.motionId);
                j.put("easing", s.easing.name());
                j.put("effect", s.effect.name());
                j.put("effectIntensity", s.effectIntensity);
                j.put("transition", s.transition.name());
                arr.put(j);
            }
            o.put("steps", arr);
        } catch (Exception ignored) {}
        return CustomFormulaStore.toFormula(o);
    }

    private String validate() {
        if (eName == null || eName.trim().isEmpty()) return "Please enter a formula name";
        if (steps.isEmpty()) return "Add at least one step";
        return null;
    }

    private void saveFormula() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", editingId == null ? "C" + System.currentTimeMillis() : editingId);
            o.put("name", eName.trim());
            o.put("category", eCategory == null || eCategory.trim().isEmpty() ? "Custom" : eCategory.trim());
            o.put("previewUri", ePreviewUri == null ? "" : ePreviewUri);
            o.put("savedAt", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            for (Step s : steps) {
                JSONObject j = new JSONObject();
                j.put("motionId", s.motionId);
                j.put("easing", s.easing.name());
                j.put("effect", s.effect.name());
                j.put("effectIntensity", s.effectIntensity);
                j.put("transition", s.transition.name());
                arr.put(j);
            }
            o.put("steps", arr);
            CustomFormulaStore.save(this, o);
            editingId = o.optString("id");
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
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
        } catch (Exception e) { return null; }
    }

    private void pickPreview() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, PICK_PREVIEW);
        } catch (Exception e) { toast("Could not open image picker"); }
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
        if (back) {
            ImageView b = AeDesign.iconButton(this, R.drawable.ic_back, "Back", false);
            AeDesign.press(b, onBack);
            h.addView(b, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        LinearLayout t = col();
        t.addView(label(title, 20, AeDesign.TEXT, Typeface.BOLD));
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

    private void addBtn(LinearLayout p, String s, boolean enabled, Runnable r) {
        TextView v = label(s, 11, enabled ? AeDesign.TEXT : AeDesign.MUTED, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(AeDesign.bg(enabled ? AeDesign.SURFACE_2 : 0x11000000, dp(16), enabled ? AeDesign.STROKE : 0x11000000, 1));
        if (enabled) AeDesign.press(v, r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        p.addView(v, lp);
    }

    private String effectLabel(EffectType e) {
        switch (e) {
            case NONE: return "None";
            case BLACK_WHITE: return "B&W";
            case MOTION_BLUR: return "Motion Blur";
            case SOFT_FOCUS: return "Soft Focus";
            case FILM_GRAIN: return "Grain";
            default: {
                String s = e.name().toLowerCase().replace('_', ' ');
                return Character.toUpperCase(s.charAt(0)) + s.substring(1);
            }
        }
    }

    /**
     * One easing card: looping curve graph + name + a one-line description of
     * what the curve does + a visible selected state (spec §12). Uses
     * {@link AeDesign#tap} rather than {@code press} so selecting a card while
     * the row is still scrolling is never dropped.
     */
    private View easingCard(Easing e, boolean on, EditText nameF, EditText catF, Step st) {
        LinearLayout c = col();
        c.setBackground(AeDesign.bg(on ? 0xff12395c : AeDesign.SURFACE_2, dp(14),
                on ? AeDesign.ACCENT : AeDesign.STROKE, on ? 2 : 1));
        c.setPadding(dp(6), dp(6), dp(6), dp(6));

        EasingPreviewView pv = new EasingPreviewView(this);
        pv.setEasing(e);
        c.addView(pv, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView nm = label(e.label(), 10, on ? 0xffffffff : AeDesign.TEXT, Typeface.BOLD);
        nm.setGravity(Gravity.CENTER);
        nm.setSingleLine(true);
        c.addView(nm, new LinearLayout.LayoutParams(-1, -2));

        TextView ds = label(easingDescription(e), 8, AeDesign.MUTED, Typeface.NORMAL);
        ds.setGravity(Gravity.CENTER);
        ds.setMaxLines(2);
        c.addView(ds, new LinearLayout.LayoutParams(-1, -2));

        c.setContentDescription("Easing " + e.label() + (on ? ", selected" : ""));
        AeDesign.tap(c, () -> { capture(nameF, catF); st.easing = e; showEdit(); });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), -2);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        c.setLayoutParams(lp);
        return c;
    }

    /** A short, human description of what each easing curve actually does. */
    private String easingDescription(Easing e) {
        switch (e) {
            case LINEAR:     return "Constant speed";
            case EASE_IN:    return "Starts slow, speeds up";
            case EASE_OUT:   return "Starts fast, eases to rest";
            case EASE_IN_OUT:return "Smooth both ends";
            case CUBIC_IN:   return "Strong slow start";
            case CUBIC_OUT:  return "Strong gentle stop";
            case CUBIC:
            case CUBIC_IN_OUT: return "Default, balanced";
            case QUART_IN:   return "Very slow start";
            case QUART_OUT:  return "Very soft stop";
            case QUART_IN_OUT: return "Dramatic both ends";
            case QUINT_IN:   return "Extreme slow start";
            case QUINT_OUT:  return "Extreme soft stop";
            case QUINT_IN_OUT: return "Extreme both ends";
            case EXPO_IN:    return "Near-static then bursts";
            case EXPO_OUT:   return "Bursts then settles";
            case EXPO_IN_OUT:return "Sharp snap both ends";
            case QUINT:      return "Legacy quintic curve";
            case SINE_IN:    return "Very gentle start";
            case SINE_OUT:   return "Very gentle stop";
            case SINE_IN_OUT:return "Subtle both ends";
            case BACK_IN:    return "Winds back, overshoots";
            case BACK_OUT:   return "Overshoots then settles";
            case BACK_IN_OUT:return "Overshoots both ends";
            default:         return e.label();
        }
    }

    private Easing safeEasing(String s) { try { return Easing.valueOf(s); } catch (Exception e) { return Easing.EASE_IN_OUT; } }
    private TransitionType safeTransition(String s) { try { return TransitionType.valueOf(s); } catch (Exception e) { return TransitionType.NONE; } }
    private EffectType safeEffect(String s) { try { return EffectType.valueOf(s); } catch (Exception e) { return EffectType.NONE; } }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView label(String s, int sp, int color, int style) { return AeDesign.text(this, s, sp, color, style); }
    private int dp(int v) { return AeDesign.dp(this, v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
