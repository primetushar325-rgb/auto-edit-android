package com.autoedit.export;

/**
 * Immutable progress report. {@code percent} is monotonically increasing and
 * derived from real work; {@code stage} names what is happening right now.
 */
public class ExportProgress {
    public int percent;
    public ExportStage stage = ExportStage.PREPARING;
    public int currentClip;
    public long currentFrame;
    public long totalFrames;
    public String message = "";

    public ExportProgress() {}

    public ExportProgress(ExportStage stage, float fraction, String message) {
        this.stage = stage;
        this.percent = stage.percent(fraction);
        this.message = message == null ? stage.label : message;
    }
}
