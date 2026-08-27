package com.autoedit.model;

import java.util.*;

public class EditProject {
    public String name = "Untitled Auto Edit";
    public ArrayList<TimelineClip> clips = new ArrayList<>();
    public ArrayList<TextOverlay> texts = new ArrayList<>();
    public String audioUri = null;
    public AspectRatio aspectRatio = AspectRatio.R16_9;
    public int fps = 30;
    public int width = 1920, height = 1080;
    public String quality = "High";
    public float defaultDuration = 3f;
    public long totalFrames() { return Math.round(totalDurationSec() * fps); }
    public float totalDurationSec() { float s=0; for (TimelineClip c:clips) s += c.durationSec; return s; }
    public void renumber(){ for(int i=0;i<clips.size();i++) clips.get(i).index=i+1; }
    public void updateSizeForAspect(int baseHeight){ height = baseHeight; width = Math.round(baseHeight * aspectRatio.w / (float)aspectRatio.h); if (width % 2 == 1) width++; if (height % 2 == 1) height++; }
}
