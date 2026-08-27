package com.autoedit.model;

public enum FitMode {
    FILL("Fill (crop)"),
    FIT("Fit (letterbox)");
    public final String label;
    FitMode(String label){ this.label = label; }
}
