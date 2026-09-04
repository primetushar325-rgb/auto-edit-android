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
 */
public class ProjectStore {
    private static final String PREF = "auto_edit_projects", KEY = "current";
    private final Context ctx;
    private final FormulaEngine formulas = new FormulaEngine();

    public ProjectStore(Context c) { ctx = c.getApplicationContext(); }

    public void save(EditProject p) {
        try { ctx.getSharedPreferences(PREF, 0).edit().putString(KEY, toJsonString(p)).apply(); }
        catch (Exception ignored) {}
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
