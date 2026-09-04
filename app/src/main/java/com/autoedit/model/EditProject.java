package com.autoedit.model;

import java.util.*;

/**
 * The whole edit. Editing STATE only — no media bytes are ever stored here, so
 * the imported originals are never touched (non-destructive, spec §35).
 *
 * <pre>
 *   PROJECT
 *    ├── Canvas        width / height / aspectRatio / fitMode
 *    ├── FPS
 *    ├── Clips[]       media uri, duration, motion(Formula), effect[], transition
 *    ├── Audio Tracks[]  real tracks that are muxed into the MP4
 *    ├── Text Tracks[]   overlays drawn in preview AND export
 *    └── Export settings preset / fps / fit
 * </pre>
 */
public class EditProject {
    public String name = "Untitled Auto Edit";
    public ArrayList<TimelineClip> clips = new ArrayList<>();
    public ArrayList<TextOverlay> texts = new ArrayList<>();
    /**
     * Independent overlay layers (v1.8): images/logos/text drawn ABOVE the
     * transition. Rendered by FrameComposer after the text tracks, in list
     * order (back to front).
     */
    public ArrayList<OverlayLayer> overlays = new ArrayList<>();
    /** Real audio tracks (spec §20). Ordered back-to-front for mixing. */
    public ArrayList<AudioTrack> audioTracks = new ArrayList<>();
    /**
     * Legacy single-audio field. Kept so projects saved before v1.3 still load;
     * {@link #primaryAudio()} prefers {@link #audioTracks} and migrates this
     * value into the list on first use.
     */
    public String audioUri = null;
    public AspectRatio aspectRatio = AspectRatio.R9_16;
    public int fps = 30;
    public int width = 1080, height = 1920;
    public String quality = "High";
    public ExportPreset exportPreset = ExportPreset.PORTRAIT_9_16;
    public FitMode fitMode = FitMode.FILL;
    public float defaultDuration = 5f;

    // ------------------------------------------------------------- durations

    public long totalFrames() { return Math.round(totalDurationSec() * fps); }

    public long totalDurationMs() {
        long s = 0;
        for (TimelineClip c : clips) { c.setDurationSeconds(c.durationSec); s += c.durationMs; }
        return s;
    }

    public float totalDurationSec() { return totalDurationMs() / 1000f; }

    /**
     * Start time (seconds) of clip {@code index} on the project timeline.
     * Single source of truth for BOTH preview and export so they resolve the
     * same (clipIndex, progress) for a given time (spec §16, §37).
     */
    public float clipStartSec(int index) {
        float t = 0f;
        for (int i = 0; i < index && i < clips.size(); i++) {
            TimelineClip c = clips.get(i);
            c.setDurationSeconds(c.durationSec);
            t += c.durationSec;
        }
        return t;
    }

    public void renumber() { for (int i = 0; i < clips.size(); i++) clips.get(i).index = i + 1; }

    // ---------------------------------------------------------------- audio

    /** Migrates the legacy {@link #audioUri} into {@link #audioTracks} once. */
    public void migrateLegacyAudio() {
        if (audioUri != null && !audioUri.isEmpty() && !"null".equals(audioUri)) {
            boolean present = false;
            for (AudioTrack t : audioTracks) if (audioUri.equals(t.uri)) { present = true; break; }
            if (!present) audioTracks.add(0, new AudioTrack(audioUri));
        }
        audioUri = null;
    }

    /** The track the audio panel edits; null when the project has no audio. */
    public AudioTrack primaryAudio() {
        migrateLegacyAudio();
        return audioTracks.isEmpty() ? null : audioTracks.get(0);
    }

    /** Tracks that actually contribute samples (not muted, have a URI). */
    public List<AudioTrack> activeAudio() {
        migrateLegacyAudio();
        List<AudioTrack> out = new ArrayList<>();
        for (AudioTrack t : audioTracks) if (!t.isSilent()) out.add(t);
        return out;
    }

    /**
     * True when the export must produce an audio stream. This is what the
     * exporter checks before it marks a file as "verified with audio".
     */
    public boolean hasAudio() { return !activeAudio().isEmpty(); }

    // -------------------------------------------------------------- presets

    public void applyExportPreset(ExportPreset preset) {
        exportPreset = preset;
        if (preset != ExportPreset.CUSTOM) { width = preset.width; height = preset.height; }
        if (width % 2 == 1) width++;
        if (height % 2 == 1) height++;
    }

    public void updateSizeForAspect(int baseHeight) {
        height = baseHeight;
        if (aspectRatio.isFixed()) width = Math.round(baseHeight * aspectRatio.w / (float) aspectRatio.h);
        if (width % 2 == 1) width++;
        if (height % 2 == 1) height++;
    }
}
