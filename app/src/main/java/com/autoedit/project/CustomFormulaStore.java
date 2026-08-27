package com.autoedit.project;

import android.content.Context;
import android.content.SharedPreferences;
import com.autoedit.engine.FormulaEngine;
import com.autoedit.model.*;
import org.json.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Persistence for user-created Custom Formulas.
 *
 * Custom formulas are stored as JSON in SharedPreferences, one JSONArray of
 * formula objects. Each entry keeps the user's exact keyframes (time, scale,
 * panX, panY, rotation, opacity, easing, transition, effect) plus the preview
 * image URI. {@link #toFormula(JSONObject)} rebuilds a standard {@link Formula}
 * sequence (id "C<timestamp>") whose steps interpolate between the saved
 * keyframes — so the EXISTING FormulaEngine / preview / export pipeline render
 * custom formulas with zero new engines.
 *
 * Backward compatibility: built-in formula ids (00..20, S1..S4) are untouched;
 * {@link #resolve(Context, String)} only intercepts ids that start with "C".
 */
public class CustomFormulaStore {
    private static final String PREF = "auto_edit_custom_formulas";
    private static final String KEY = "formulas";

    private CustomFormulaStore() {}

    public static List<JSONObject> all(Context ctx) {
        ArrayList<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
            out.sort(Comparator.comparingLong(o -> -o.optLong("savedAt", 0L)));
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject byId(Context ctx, String id) {
        if (id == null || !id.startsWith("C")) return null;
        for (JSONObject o : all(ctx)) if (id.equals(o.optString("id"))) return o;
        return null;
    }

    /** Resolves a clip formula id → Formula. Custom ids load from the store;
     *  anything else falls back to the built-in FormulaEngine (safe default). */
    public static Formula resolve(Context ctx, String id, FormulaEngine builtin) {
        JSONObject o = byId(ctx, id);
        return o == null ? builtin.byId(id) : toFormula(o);
    }

    public static void save(Context ctx, JSONObject formula) {
        try {
            ArrayList<JSONObject> list = new ArrayList<>(all(ctx));
            String id = formula.optString("id");
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (id.equals(list.get(i).optString("id"))) { list.set(i, formula); replaced = true; break; }
            }
            if (!replaced) list.add(0, formula);
            JSONArray arr = new JSONArray();
            for (JSONObject o : list) arr.put(o);
            prefs(ctx).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void delete(Context ctx, String id) {
        try {
            ArrayList<JSONObject> list = new ArrayList<>(all(ctx));
            for (int i = 0; i < list.size(); i++) {
                if (id.equals(list.get(i).optString("id"))) { list.remove(i); break; }
            }
            JSONArray arr = new JSONArray();
            for (JSONObject o : list) arr.put(o);
            prefs(ctx).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Rebuilds the real Formula object from a stored custom-formula JSON. */
    public static Formula toFormula(JSONObject o) {
        String id = o.optString("id", "C0");
        String name = o.optString("name", "Custom Formula");
        String category = o.optString("category", "Custom");
        KeyframeState identity = new KeyframeState(0, 0, 1f, 0, 1);
        Formula f = new Formula(id, name, category, identity.copy(), identity.copy());
        f.steps = new ArrayList<>();
        JSONArray kfs = o.optJSONArray("keyframes");
        if (kfs == null || kfs.length() < 2) {
            // Degenerate case — one static step so the formula is still valid.
            FormulaStep st = new FormulaStep(0f, 1f, new Formula("K0", name, "Custom", identity.copy(), identity.copy()));
            f.steps.add(st);
            return f;
        }
        for (int i = 0; i < kfs.length() - 1; i++) {
            try {
                JSONObject a = kfs.getJSONObject(i);
                JSONObject b = kfs.getJSONObject(i + 1);
                float t0 = (float) a.optDouble("time", i);
                float t1 = (float) b.optDouble("time", i + 1);
                float dur = Math.max(0.05f, t1 - t0);
                KeyframeState start = state(a);
                KeyframeState end = state(b);
                Formula m = new Formula("K" + i, name, "Custom", start.copy(), end.copy());
                m.easing = easing(a);
                FormulaStep st = new FormulaStep(t0, dur, m);
                st.easing = m.easing;
                st.zoomAmount = start.scale; st.panX = start.x; st.panY = start.y;
                st.rotation = start.rotation; st.opacity = start.opacity;
                st.transition = TransitionType.valueOf(a.optString("transition", TransitionType.NONE.name()));
                st.effect = EffectType.valueOf(a.optString("effect", EffectType.NONE.name()));
                st.effectIntensity = (float) a.optDouble("effectIntensity", 0.6);
                f.steps.add(st);
            } catch (Exception ignored) {}
        }
        if (f.steps.isEmpty()) {
            FormulaStep st = new FormulaStep(0f, 1f, new Formula("K0", name, "Custom", identity.copy(), identity.copy()));
            f.steps.add(st);
        }
        return f;
    }

    public static String previewUri(JSONObject o) { return o.optString("previewUri", null); }

    private static KeyframeState state(JSONObject kf) {
        return new KeyframeState(
                (float) kf.optDouble("panX", 0),
                (float) kf.optDouble("panY", 0),
                (float) kf.optDouble("zoom", 1.0),
                (float) kf.optDouble("rotation", 0),
                (float) kf.optDouble("opacity", 1.0));
    }

    private static Easing easing(JSONObject kf) {
        try { return Easing.valueOf(kf.optString("easing", Easing.EASE_IN_OUT.name())); }
        catch (Exception e) { return Easing.EASE_IN_OUT; }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
