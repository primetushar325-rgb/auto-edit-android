package com.autoedit.project;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recently-used items (transitions, and reusable for effects/motions). Stores
 * only IDs + metadata in SharedPreferences — never full objects (spec 38):
 * recent id → registry → preset. Invalid ids are ignored on load. Persists
 * across app restarts. One entry per id; sorted most-recent-first; capped.
 */
public class RecentsStore {
    private static final String PREF = "auto_edit_recents";
    private static final int MAX = 20;

    private RecentsStore() {}

    public static class Entry {
        public final String id;
        public long lastUsed;
        public int usageCount;
        Entry(String id, long lastUsed, int usageCount) {
            this.id = id; this.lastUsed = lastUsed; this.usageCount = usageCount;
        }
    }

    /** Record a use: moves id to front, increments count, updates timestamp. */
    public static void record(Context ctx, String kind, String id) {
        if (id == null) return;
        String k = kind(kind);
        LinkedHashMap<String, Entry> map = load(ctx, k);
        Entry e = map.get(id);
        if (e == null) e = new Entry(id, System.currentTimeMillis(), 0);
        e.lastUsed = System.currentTimeMillis();
        e.usageCount++;
        map.remove(id);
        // re-insert first (iteration order = recency)
        LinkedHashMap<String, Entry> ordered = new LinkedHashMap<>();
        ordered.put(id, e);
        for (Map.Entry<String, Entry> me : map.entrySet()) ordered.put(me.getKey(), me.getValue());
        save(ctx, k, ordered);
    }

    /** Most-recent-first list of ids for a kind, capped to MAX. */
    public static List<String> recentIds(Context ctx, String kind) {
        ArrayList<String> out = new ArrayList<>();
        for (Entry e : load(ctx, kind(kind)).values()) { out.add(e.id); if (out.size() >= MAX) break; }
        return out;
    }

    public static int usageCount(Context ctx, String kind, String id) {
        Entry e = load(ctx, kind(kind)).get(id);
        return e == null ? 0 : e.usageCount;
    }

    public static void clear(Context ctx, String kind) {
        prefs(ctx).edit().remove(kind(kind)).apply();
    }

    // ---- internals ----
    private static String kind(String kind) { return (kind == null ? "item" : kind).toLowerCase().trim(); }

    private static LinkedHashMap<String, Entry> load(Context ctx, String kind) {
        LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(kind, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", null);
                if (id == null) continue;
                map.put(id, new Entry(id, o.optLong("ts", 0L), o.optInt("n", 1)));
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static void save(Context ctx, String kind, LinkedHashMap<String, Entry> map) {
        JSONArray arr = new JSONArray();
        int n = 0;
        for (Iterator<Entry> it = map.values().iterator(); it.hasNext() && n < MAX; n++) {
            Entry e = it.next();
            JSONObject o = new JSONObject();
            try { o.put("id", e.id); o.put("ts", e.lastUsed); o.put("n", e.usageCount); arr.put(o); }
            catch (Exception ignored) {}
        }
        prefs(ctx).edit().putString(kind, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
