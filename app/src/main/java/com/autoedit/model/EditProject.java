package com.autoedit.model;

import java.util.*;

public class EditProject {
    public String name = "Untitled Auto Edit";
    public ArrayList<TimelineClip> clips = new ArrayList<>();
    public ArrayList<TextOverlay> texts = new ArrayList<>();
    public String audioUri = null;
    public AspectRatio aspectRatio = AspectRatio.R9_16;
    public int fps = 30;
    public int width = 1080, height = 1920;
    public String quality = "High";
    public ExportPreset exportPreset = ExportPreset.PORTRAIT_9_16;
    public FitMode fitMode = FitMode.FILL;
    public float defaultDuration = 5f;
    public long totalFrames() { return Math.round(totalDurationSec() * fps); }
    public long totalDurationMs() { long s=0; for (TimelineClip c:clips) { c.setDurationSeconds(c.durationSec); s += c.durationMs; } return s; }
    public float totalDurationSec() { return totalDurationMs() / 1000f; }
    public void renumber(){ for(int i=0;i<clips.size();i++) clips.get(i).index=i+1; }
    public void applyExportPreset(ExportPreset preset){ exportPreset = preset; if(preset != ExportPreset.CUSTOM){ width = preset.width; height = preset.height; } if (width % 2 == 1) width++; if (height % 2 == 1) height++; }
    public void updateSizeForAspect(int baseHeight){ height = baseHeight; width = Math.round(baseHeight * aspectRatio.w / (float)aspectRatio.h); if (width % 2 == 1) width++; if (height % 2 == 1) height++; }
}
