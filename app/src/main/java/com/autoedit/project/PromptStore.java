package com.autoedit.project;

import android.content.Context;
import android.content.SharedPreferences;
import com.autoedit.model.PromptItem;
import org.json.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt Library persistence (SharedPreferences JSON).
 *
 * The library is intentionally EMPTY for now — the schema and UI are in place
 * so prompts (name, description, preview image, associated formula, action)
 * can be added later without rewriting the Home screen. No fake entries.
 */
public class PromptStore {
    private static final String PREF = "auto_edit_prompts";
    private static final String KEY = "prompts";

    private PromptStore() {}

    public static List<PromptItem> all(Context ctx) {
        ArrayList<PromptItem> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                PromptItem p = new PromptItem();
                p.id = o.optString("id");
                p.name = o.optString("name");
                p.description = o.optString("description", "");
                p.previewUri = o.optString("previewUri", null);
                p.formulaId = o.optString("formulaId", null);
                p.action = o.optString("action", null);
                out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void save(Context ctx, List<PromptItem> items) {
        try {
            JSONArray arr = new JSONArray();
            for (PromptItem p : items) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("description", p.description == null ? "" : p.description);
                o.put("previewUri", p.previewUri == null ? JSONObject.NULL : p.previewUri);
                o.put("formulaId", p.formulaId == null ? JSONObject.NULL : p.formulaId);
                o.put("action", p.action == null ? JSONObject.NULL : p.action);
                arr.put(o);
            }
            prefs(ctx).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
