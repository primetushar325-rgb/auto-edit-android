package com.autoedit.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ONE real audio track on the project (spec §20, §47).
 *
 * This is editing STATE only — the media itself is never modified or copied
 * into the project (non-destructive, §35). Export decodes {@link #uri} on the
 * fly and writes a mixed AAC stream into the MP4.
 *
 * Timing model (all seconds, all optional):
 * <pre>
 *   project timeline :  0 ─────────────────────────────── totalDurationSec
 *   audio in project :      [startSec ......................]
 *   source used      :      [trimStartSec .... trimEndSec]  (looped if short)
 *   gain envelope    :      fade-in / sustain / fade-out, times 0..1
 * </pre>
 */
public class AudioTrack {
    public String uri;
    /** Volume 0..1 applied to the decoded samples. */
    public float volume = 1f;
    public boolean muted = false;
    /** Where the track starts on the project timeline. */
    public float startSec = 0f;
    /** Source region used; trimEndSec &lt;= 0 means "to the end of the file". */
    public float trimStartSec = 0f;
    public float trimEndSec = 0f;
    /** Repeat the trimmed region until the project ends. */
    public boolean loop = true;
    /** Fade envelope, in seconds, applied inside the region actually used. */
    public float fadeInSec = 0f;
    public float fadeOutSec = 0f;
    /** Cached duration of the source file, filled in when first probed. */
    public long sourceDurationMs = 0L;

    public AudioTrack() {}

    public AudioTrack(String uri) { this.uri = uri; }

    /** Effective volume, 0 when muted or the URI is missing. */
    public float effectiveVolume() {
        if (muted || uri == null || uri.isEmpty()) return 0f;
        return volume < 0f ? 0f : (volume > 1f ? 1f : volume);
    }

    /** True when this track contributes silence for the whole project. */
    public boolean isSilent() { return effectiveVolume() <= 0f; }

    /**
     * Gain multiplier for a sample that sits {@code tSec} seconds after the
     * track's start point on the project timeline, given that {@code usedSec}
     * seconds of audio are being laid down. Fades are clamped so they can
     * never overlap into negative gain.
     */
    public float gainAt(float tSec, float usedSec) {
        float g = effectiveVolume();
        if (g <= 0f) return 0f;
        float fi = Math.max(0f, Math.min(fadeInSec, usedSec * 0.5f));
        float fo = Math.max(0f, Math.min(fadeOutSec, usedSec * 0.5f));
        if (fi > 0f && tSec < fi) g *= tSec / fi;
        if (fo > 0f && tSec > usedSec - fo) g *= Math.max(0f, (usedSec - tSec) / fo);
        return g < 0f ? 0f : (g > 1f ? 1f : g);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("uri", uri);
        o.put("volume", volume);
        o.put("muted", muted);
        o.put("startSec", startSec);
        o.put("trimStartSec", trimStartSec);
        o.put("trimEndSec", trimEndSec);
        o.put("loop", loop);
        o.put("fadeInSec", fadeInSec);
        o.put("fadeOutSec", fadeOutSec);
        o.put("sourceDurationMs", sourceDurationMs);
        return o;
    }

    public static AudioTrack fromJson(JSONObject o) {
        if (o == null) return null;
        String u = o.optString("uri", null);
        if (u == null || u.isEmpty() || "null".equals(u)) return null;
        AudioTrack t = new AudioTrack(u);
        t.volume = (float) o.optDouble("volume", 1d);
        t.muted = o.optBoolean("muted", false);
        t.startSec = (float) o.optDouble("startSec", 0d);
        t.trimStartSec = (float) o.optDouble("trimStartSec", 0d);
        t.trimEndSec = (float) o.optDouble("trimEndSec", 0d);
        t.loop = o.optBoolean("loop", true);
        t.fadeInSec = (float) o.optDouble("fadeInSec", 0d);
        t.fadeOutSec = (float) o.optDouble("fadeOutSec", 0d);
        t.sourceDurationMs = o.optLong("sourceDurationMs", 0L);
        return t;
    }
}
