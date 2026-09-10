package com.autoedit.project;

import android.content.*;
import com.autoedit.model.*;
import com.autoedit.engine.FormulaEngine;
import org.json.*;

/**
 * JSON persistence for the current project (SharedPreferences).
 *
 * Everything that is editing STATE is stored: clips (uri, duration, formula,
 * transition, effect stack), text tracks, audio tracks and the canvas/export
 * settings. No media bytes are stored, so the originals stay untouched.
 *
 * Every enum is parsed defensively: an unknown name falls back to a safe
 * default instead of throwing and silently emptying the project.
 *
 * v1.8+ multi-project index: every save() also upserts the project into
 * "projects_index_v2" so Home can show Recent / All Projects with
 * name, thumbnail, clip count, duration, lastModified, rename/duplicate/delete.
 * The "current" key remains the single source of truth for the editor.
 * Old installs (only "current") are migrated on first loadAll().
 */
public class ProjectStore {
    private static final String PREF = "auto_edit_projects", KEY = "current";
    private static final String KEY_INDEX = "projects_index_v2";
    private final Context ctx;
    private final FormulaEngine formulas = new FormulaEngine();

    public ProjectStore(Context c) { ctx = c.getApplicationContext(); }

    public void save(EditProject p) {
        if (p.id == null || p.id.isEmpty()) p.id = java.util.UUID.randomUUID().toString();
        p.lastModified = System.currentTimeMillis();
        try { ctx.getSharedPreferences(PREF, 0).edit().putString(KEY, toJsonString(p)).apply(); }
        catch (Exception ignored) {}
        saveToIndex(p);
    }

    /** Save without touching lastModified/index — for undo/redo internal restores. */
    public void saveQuiet(EditProject p) {
        try { ctx.getSharedPreferences(PREF, 0).edit().putString(KEY, toJsonString(p)).apply(); }
        catch (Exception ignored) {}
    }

    private void saveToIndex(EditProject p) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, 0);
            String raw = sp.getString(KEY_INDEX, null);
            JSONArray arr = raw == null ? new JSONArray() : new JSONArray(raw);
            // remove existing entry with same id
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String oid = o.optString("id", "");
                if (!oid.equals(p.id)) out.put(o);
            }
            JSONObject entry = new JSONObject();
            entry.put("id", p.id);
            entry.put("name", p.name);
            entry.put("lastModified", p.lastModified);
            entry.put("clipCount", p.clips.size());
            entry.put("durationSec", p.totalDurationSec());
            entry.put("width", p.width);
            entry.put("height", p.height);
            entry.put("aspect", p.aspectRatio.name());
            entry.put("fitMode", p.fitMode.name());
            // store full json for quick load (no extra IO)
            entry.put("json", toJsonString(p));
            // keep thumbnail uri (first clip) for Home card
            String thumb = p.clips.isEmpty() ? null : p.clips.get(0).uri;
            if (thumb != null) entry.put("thumbUri", thumb);
            out.put(entry);
            // cap at 50 most recent (prevent unbounded growth)
            // sort by lastModified desc before cap
            java.util.List<JSONObject> tmp = new java.util.ArrayList<>();
            for (int i = 0; i < out.length(); i++) tmp.add(out.getJSONObject(i));
            tmp.sort((a, b) -> Long.compare(b.optLong("lastModified", 0), a.optLong("lastModified", 0)));
            JSONArray capped = new JSONArray();
            for (int i = 0; i < Math.min(tmp.size(), 50); i++) capped.put(tmp.get(i));
            sp.edit().putString(KEY_INDEX, capped.toString()).apply();
        } catch (Exception ignored) {}
    }

    public java.util.List<EditProject> loadAll() {
        java.util.List<EditProject> out = new java.util.ArrayList<>();
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, 0);
            String raw = sp.getString(KEY_INDEX, null);
            if (raw != null) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    String j = e.optString("json", null);
                    EditProject p = null;
                    if (j != null) p = deserialize(j, ctx);
                    else {
                        // fallback: reconstruct minimal
                        p = new EditProject();
                        p.id = e.optString("id", p.id);
                        p.name = e.optString("name", p.name);
                        p.lastModified = e.optLong("lastModified", p.lastModified);
                    }
                    if (p != null) out.add(p);
                }
            }
            // migration: if index empty but "current" exists, seed it
            if (out.isEmpty()) {
                EditProject cur = load();
                if (cur != null && !cur.clips.isEmpty()) {
                    out.add(cur);
                    saveToIndex(cur);
                }
            }
        } catch (Exception ignored) {}
        out.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));
        return out;
    }

    public void deleteProject(String id) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, 0);
            String raw = sp.getString(KEY_INDEX, null);
            if (raw == null) return;
            JSONArray arr = new JSONArray(raw);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (!id.equals(o.optString("id", ""))) out.put(o);
            }
            sp.edit().putString(KEY_INDEX, out.toString()).apply();
            // if deleted was current, keep current on disk but remove from index
            EditProject cur = load();
            if (cur != null && id.equals(cur.id)) {
                // keep cur as orphan, but clear its id so next save creates new entry
                // do not delete current key
            }
        } catch (Exception ignored) {}
    }

    public EditProject loadProject(String id) {
        for (EditProject p : loadAll()) if (id.equals(p.id)) return p;
        return null;
    }

    public void renameProject(String id, String newName) {
        try {
            for (EditProject p : loadAll()) if (id.equals(p.id)) {
                p.name = newName;
                p.lastModified = System.currentTimeMillis();
                // update current if matches
                EditProject cur = load();
                if (cur != null && id.equals(cur.id)) {
                    cur.name = newName;
                    cur.lastModified = p.lastModified;
                    save(cur);
                } else {
                    saveToIndex(p);
                }
                return;
            }
        } catch (Exception ignored) {}
    }

    public EditProject duplicateProject(String id) {
        EditProject src = loadProject(id);
        if (src == null) return null;
        try {
            String json = toJsonString(src);
            EditProject dup = deserialize(json, ctx);
            dup.id = java.util.UUID.randomUUID().toString();
            dup.name = src.name + " Copy";
            dup.lastModified = System.currentTimeMillis();
            save(dup);
            return dup;
        } catch (Exception e) { return null; }
    }

    public String toJsonString(EditProject p) throws JSONException {
        return serialize(p);
    }

    /**
     * Pure JSON serialization (no Context) — also used by the undo/redo
     * snapshot mechanism and by the unit tests.
     */
    public static String serialize(EditProject p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", p.id);
        o.put("lastModified", p.lastModified);
        o.put("name", p.name);
        o.put("fps", p.fps);
        o.put("width", p.width);
        o.put("height", p.height);
        o.put("quality", p.quality);
        o.put("defaultDuration", p.defaultDuration);
        o.put("aspect", p.aspectRatio.name());
        o.put("exportPreset", p.exportPreset.name());
        o.put("fitMode", p.fitMode.name());

        JSONArray clips = new JSONArray();
        for (TimelineClip c : p.clips) clips.put(c.toJson());
        o.put("clips", clips);

        JSONArray texts = new JSONArray();
        for (TextOverlay t : p.texts) texts.put(t.toJson());
        o.put("texts", texts);

        JSONArray overlays = new JSONArray();
        for (OverlayLayer ovl : p.overlays) overlays.put(ovl.toJson());
        o.put("overlays", overlays);

        p.migrateLegacyAudio();
        JSONArray audio = new JSONArray();
        for (AudioTrack t : p.audioTracks) audio.put(t.toJson());
        o.put("audioTracks", audio);
        return o.toString();
    }

    public EditProject fromJsonString(String s) {
        return deserialize(s, ctx);
    }

    /** Pure JSON deserialization (no Context) — defensive, never throws. */
    public static EditProject deserialize(String s) {
        return deserialize(s, null);
    }

    /** Deserialization with optional Context (needed only for custom formulas). */
    private static EditProject deserialize(String s, Context ctx) {
        EditProject p = new EditProject();
        if (s == null) return p;
        try {
            JSONObject o = new JSONObject(s);
            p.id = o.optString("id", p.id);
            p.lastModified = o.optLong("lastModified", p.lastModified);
            p.name = o.optString("name", p.name);
            p.fps = o.optInt("fps", 30);
            p.width = o.optInt("width", 1080);
            p.height = o.optInt("height", 1920);
            p.quality = o.optString("quality", p.quality);
            p.defaultDuration = (float) o.optDouble("defaultDuration", p.defaultDuration);
            p.aspectRatio = aspect(o.optString("aspect", AspectRatio.R9_16.name()));
            p.exportPreset = preset(o.optString("exportPreset", ExportPreset.PORTRAIT_9_16.name()));
            p.fitMode = fit(o.optString("fitMode", FitMode.FILL.name()));

            JSONArray arr = o.optJSONArray("clips");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                String uri = c.optString("uri", null);
                if (uri == null || uri.isEmpty()) continue;
                TimelineClip clip = new TimelineClip(uri, c.optInt("index", i + 1),
                        resolveFormula(c.optString("formula", "17"), ctx));
                if (c.has("durationMs")) clip.setDurationMs(c.optLong("durationMs", 5000));
                else clip.setDurationSeconds((float) c.optDouble("duration", 5));
                clip.transition = transition(c.optString("transition", TransitionType.CROSS_DISSOLVE.name()));
                clip.transitionDurationSec = (float) c.optDouble("transitionDuration", .5);
                String _pid = c.optString("transitionPreset", ""); if (!_pid.isEmpty() && !"null".equals(_pid)) clip.transitionPresetId = _pid;
                clip.effect = effect(c.optString("effect", EffectType.NONE.name()));
                clip.effectIntensity = (float) c.optDouble("effectIntensity", .6);
                JSONArray layers = c.optJSONArray("effectLayers");
                if (layers != null) {
                    for (int k = 0; k < layers.length(); k++) {
                        EffectLayer l = EffectLayer.fromJson(layers.optJSONObject(k));
                        if (l != null && l.isActive()) clip.effectLayers.add(l);
                    }
                    if (!clip.effectLayers.isEmpty()) {
                        clip.effect = clip.effectLayers.get(0).type;
                        clip.effectIntensity = clip.effectLayers.get(0).intensity;
                    }
                }
                // ── MASTER BORDER / FRAME (per-clip) ──
                JSONObject be = c.optJSONObject("borderEffect");
                if (be == null) be = c.optJSONObject("border");
                if (be == null) be = c.optJSONObject("frameEffect");
                BorderEffectConfig bc = BorderEffectConfig.fromJson(be);
                if (bc != null) clip.borderEffect = bc;
                p.clips.add(clip);
            }

            JSONArray texts = o.optJSONArray("texts");
            if (texts != null) for (int i = 0; i < texts.length(); i++) {
                TextOverlay t = TextOverlay.fromJson(texts.optJSONObject(i));
                if (t != null) p.texts.add(t);
            }

            JSONArray overlays = o.optJSONArray("overlays");
            if (overlays != null) for (int i = 0; i < overlays.length(); i++) {
                OverlayLayer ovl = OverlayLayer.fromJson(overlays.optJSONObject(i));
                if (ovl != null) p.overlays.add(ovl);
            }

            JSONArray audio = o.optJSONArray("audioTracks");
            if (audio != null) for (int i = 0; i < audio.length(); i++) {
                AudioTrack t = AudioTrack.fromJson(audio.optJSONObject(i));
                if (t != null) p.audioTracks.add(t);
            }
            // Legacy single-audio field from builds before v1.3.
            String legacy = o.optString("audio", null);
            if (legacy != null && !legacy.isEmpty() && !"null".equals(legacy) && p.audioTracks.isEmpty())
                p.audioTracks.add(new AudioTrack(legacy));
            p.audioUri = null;
        } catch (Exception ignored) {}
        p.renumber();
        return p;
    }

    public EditProject load() { return fromJsonString(ctx.getSharedPreferences(PREF, 0).getString(KEY, null)); }

    /** Resolves a saved formula id. Custom formulas (ids starting with "C")
     *  load from CustomFormulaStore so projects keep rendering them across
     *  restarts; built-in ids fall back to FormulaEngine (old saves intact).
     *  Missing/deleted custom ids safely fall back to the default motion. */
    private Formula resolveFormula(String id) {
        return resolveFormula(id, ctx);
    }

    /** Context-free variant (used by the pure static deserializer). */
    private static Formula resolveFormula(String id, Context ctx) {
        FormulaEngine fe = new FormulaEngine();
        if (id != null && id.startsWith("C")) {
            if (ctx != null) {
                Formula cf = CustomFormulaStore.resolve(ctx, id, fe);
                if (cf != null && cf.id != null && cf.id.equals(id)) return cf;
            }
            return fe.byId("17");
        }
        return fe.byId(id);
    }

    // ------------------------------------------------------- defensive enums

    private static AspectRatio aspect(String n) {
        try { return AspectRatio.valueOf(n); } catch (Exception e) { return AspectRatio.R9_16; }
    }
    private static ExportPreset preset(String n) {
        try { return ExportPreset.valueOf(n); } catch (Exception e) { return ExportPreset.PORTRAIT_9_16; }
    }
    private static FitMode fit(String n) {
        try { return FitMode.valueOf(n); } catch (Exception e) { return FitMode.FILL; }
    }
    private static TransitionType transition(String n) {
        try { return TransitionType.valueOf(n); } catch (Exception e) { return TransitionType.NONE; }
    }
    private static EffectType effect(String n) {
        try { return EffectType.valueOf(n); } catch (Exception e) { return EffectType.NONE; }
    }
}
