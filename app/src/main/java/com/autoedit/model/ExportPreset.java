package com.autoedit.model;

public enum ExportPreset {
    LANDSCAPE_16_9("16:9", 1920, 1080, "YouTube, landscape"),
    PORTRAIT_9_16("9:16", 1080, 1920, "Reels, Shorts, TikTok"),
    SQUARE_1_1("1:1", 1080, 1080, "Instagram feed square"),
    PORTRAIT_4_5("4:5", 1080, 1350, "Instagram portrait"),
    CLASSIC_4_3("4:3", 1440, 1080, "Classic/legacy"),
    CUSTOM("Custom", 1920, 1080, "Advanced users");

    public final String label;
    public final int width;
    public final int height;
    public final String useCase;
    ExportPreset(String label, int width, int height, String useCase){
        this.label = label; this.width = width; this.height = height; this.useCase = useCase;
    }
}
