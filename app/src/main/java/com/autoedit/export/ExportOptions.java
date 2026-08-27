package com.autoedit.export;

import java.io.FileDescriptor;
import com.autoedit.model.FitMode;

public class ExportOptions {
    public int width = 1920, height = 1080, fps = 30, bitrate = 8_000_000;
    public String outputPath;
    public FileDescriptor outputFileDescriptor;
    public String quality = "High";
    public FitMode fitMode = FitMode.FILL;
}
