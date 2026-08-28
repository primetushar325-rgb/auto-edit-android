package com.autoedit.project;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Favorites for motions ("motion:id"), formulas ("formula:id"), effects
 *  ("effect:id"), transitions ("transition:id"). Stored as a JSONArray of
 *  "kind:id" strings in SharedPreferences. */
public class FavoritesStore {
    private static final String PREF = "auto_edit_favorites";
    private static final String KEY = "ids";
    private FavoritesStore() {}

    public static boolean isFavorite(Context ctx, String kind, String id) {
        String k = key(kind, id);
        for (String s : all(ctx)) if (s.equals(k)) return true;
        return false;
    }

    /** Returns the new favorite state (true = now favorite). */
    public static boolean toggle(Context ctx, String kind, String id) {
        String k = key(kind, id);
        List<String> list = all(ctx);
        boolean now;
        if (list.contains(k)) { list.remove(k); now = false; } else { list.add(k); now = true; }
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        prefs(ctx).edit().putString(KEY, arr.toString()).apply();
        return now;
    }

    public static List<String> all(Context ctx) {
        ArrayList<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Exception ignored) {}
        return out;
    }

    public static String key(String kind, String id) {
        if (kind == null) kind = "item";
        return kind.toLowerCase().trim() + ":" + id;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
