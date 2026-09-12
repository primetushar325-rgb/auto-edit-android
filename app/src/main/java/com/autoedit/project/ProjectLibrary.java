package com.autoedit.project;

import android.content.Context;
import android.content.SharedPreferences;

import com.autoedit.model.EditProject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Multi-project library stored alongside the legacy single-project key.
 *
 * <p>Each project is a full JSON blob (same schema as {@link ProjectStore})
 * keyed by a stable id. An ordered index keeps recency. Opening a project
 * also writes it into the legacy {@code current} slot so the existing
 * editor / export / autosave path keeps working unchanged.
 *
 * <p>Backward compatible: on first use, if the library is empty but a legacy
 * current project exists, it is imported as the sole entry.
 */
public final class ProjectLibrary {

    private static final String PREF = "auto_edit_library";
    private static final String KEY_INDEX = "index";
    private static final String KEY_PREFIX = "p:";
    private static final String KEY_ACTIVE = "activeId";

    private final Context ctx;
    private final ProjectStore store;

    public ProjectLibrary(Context c) {
        this.ctx = c.getApplicationContext();
        this.store = new ProjectStore(ctx);
    }

    /** One row for the Home screen. */
    public static final class Entry {
        public final String id;
        public final String name;
        public final int clipCount;
        public final float durationSec;
        public final int width;
        public final int height;
        public final long updatedAt;
        public final boolean hasAudio;

        public Entry(String id, String name, int clipCount, float durationSec,
                     int width, int height, long updatedAt, boolean hasAudio) {
            this.id = id;
            this.name = name == null ? "Untitled" : name;
            this.clipCount = clipCount;
            this.durationSec = durationSec;
            this.width = width;
            this.height = height;
            this.updatedAt = updatedAt;
            this.hasAudio = hasAudio;
        }
    }

    // ---------------------------------------------------------------- index

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private List<String> readIndex() {
        ArrayList<String> out = new ArrayList<>();
        try {
            String raw = prefs().getString(KEY_INDEX, "[]");
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                String id = a.optString(i, null);
                if (id != null && !id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void writeIndex(List<String> ids) {
        JSONArray a = new JSONArray();
        for (String id : ids) a.put(id);
        prefs().edit().putString(KEY_INDEX, a.toString()).apply();
    }

    // ---------------------------------------------------------------- CRUD

    /**
     * Ensures the library has at least the legacy current project, and
     * returns the active project id (creating one if needed).
     */
    public String ensureBootstrapped() {
        List<String> ids = readIndex();
        if (ids.isEmpty()) {
            // Import legacy single project if any.
            EditProject legacy = store.load();
            String id = newId();
            saveProject(id, legacy == null ? new EditProject() : legacy);
            ids = new ArrayList<>();
            ids.add(id);
            writeIndex(ids);
            prefs().edit().putString(KEY_ACTIVE, id).apply();
            return id;
        }
        String active = prefs().getString(KEY_ACTIVE, null);
        if (active == null || !ids.contains(active)) {
            active = ids.get(0);
            prefs().edit().putString(KEY_ACTIVE, active).apply();
        }
        return active;
    }

    public String activeId() {
        return prefs().getString(KEY_ACTIVE, null);
    }

    public EditProject loadActive() {
        String id = ensureBootstrapped();
        EditProject p = loadById(id);
        if (p == null) p = new EditProject();
        // Keep the legacy "current" slot in sync for export service / autosave.
        store.save(p);
        return p;
    }

    public EditProject loadById(String id) {
        if (id == null) return null;
        String json = prefs().getString(KEY_PREFIX + id, null);
        if (json == null) return null;
        return store.fromJsonString(json);
    }

    /** Persists a project under its id AND mirrors it to the legacy current slot when active. */
    public void saveProject(String id, EditProject p) {
        if (id == null || p == null) return;
        try {
            String json = store.toJsonString(p);
            JSONObject meta = new JSONObject(json);
            meta.put("_updatedAt", System.currentTimeMillis());
            prefs().edit().putString(KEY_PREFIX + id, meta.toString()).apply();
            // Keep in index (move to front = most recent).
            List<String> ids = readIndex();
            ids.remove(id);
            ids.add(0, id);
            writeIndex(ids);
            if (id.equals(activeId())) store.save(p);
        } catch (Exception ignored) {}
    }

    /** Saves the currently active project (used by MainActivity autosave). */
    public void saveActive(EditProject p) {
        String id = activeId();
        if (id == null) id = ensureBootstrapped();
        saveProject(id, p);
    }

    public void setActive(String id) {
        if (id == null) return;
        List<String> ids = readIndex();
        if (!ids.contains(id)) return;
        prefs().edit().putString(KEY_ACTIVE, id).apply();
        EditProject p = loadById(id);
        if (p != null) store.save(p);
        // Bump recency.
        ids.remove(id);
        ids.add(0, id);
        writeIndex(ids);
    }

    /** Creates a brand-new empty project, makes it active, returns its id. */
    public String createNew(EditProject seed) {
        String id = newId();
        EditProject p = seed == null ? new EditProject() : seed;
        saveProject(id, p);
        prefs().edit().putString(KEY_ACTIVE, id).apply();
        store.save(p);
        return id;
    }

    public void delete(String id) {
        if (id == null) return;
        List<String> ids = readIndex();
        ids.remove(id);
        prefs().edit().remove(KEY_PREFIX + id).apply();
        writeIndex(ids);
        if (id.equals(activeId())) {
            if (ids.isEmpty()) {
                // Always keep at least one project.
                String nid = createNew(new EditProject());
                prefs().edit().putString(KEY_ACTIVE, nid).apply();
            } else {
                setActive(ids.get(0));
            }
        }
    }

    public String duplicate(String id) {
        EditProject src = loadById(id);
        if (src == null) return null;
        try {
            EditProject copy = store.fromJsonString(store.toJsonString(src));
            copy.name = (copy.name == null ? "Untitled" : copy.name) + " Copy";
            return createNew(copy);
        } catch (Exception e) {
            return null;
        }
    }

    public void rename(String id, String name) {
        EditProject p = loadById(id);
        if (p == null) return;
        p.name = name == null || name.trim().isEmpty() ? p.name : name.trim();
        saveProject(id, p);
    }

    /** All projects, most-recent first. */
    public List<Entry> list() {
        ensureBootstrapped();
        List<String> ids = readIndex();
        ArrayList<Entry> out = new ArrayList<>();
        for (String id : ids) {
            Entry e = entryFor(id);
            if (e != null) out.add(e);
        }
        // Defensive: sort by updatedAt desc if index order got stale.
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return out;
    }

    private Entry entryFor(String id) {
        String json = prefs().getString(KEY_PREFIX + id, null);
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            String name = o.optString("name", "Untitled");
            int w = o.optInt("width", 1080);
            int h = o.optInt("height", 1920);
            long updated = o.optLong("_updatedAt", 0L);
            JSONArray clips = o.optJSONArray("clips");
            int n = clips == null ? 0 : clips.length();
            float dur = 0f;
            if (clips != null) {
                for (int i = 0; i < clips.length(); i++) {
                    JSONObject c = clips.optJSONObject(i);
                    if (c == null) continue;
                    if (c.has("durationMs")) dur += c.optLong("durationMs", 5000) / 1000f;
                    else dur += (float) c.optDouble("duration", 5);
                }
            }
            boolean hasAudio = false;
            JSONArray audio = o.optJSONArray("audioTracks");
            if (audio != null && audio.length() > 0) hasAudio = true;
            else {
                String legacy = o.optString("audio", null);
                if (legacy != null && !legacy.isEmpty() && !"null".equals(legacy)) hasAudio = true;
            }
            return new Entry(id, name, n, dur, w, h, updated, hasAudio);
        } catch (Exception e) {
            return new Entry(id, "Untitled", 0, 0f, 1080, 1920, 0L, false);
        }
    }

    private static String newId() {
        return "p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
