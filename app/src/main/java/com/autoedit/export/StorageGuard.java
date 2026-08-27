package com.autoedit.export;

import java.io.File;

public class StorageGuard {
    public static boolean hasSpace(File dir, long estimatedBytes){ return dir != null && dir.getUsableSpace() > estimatedBytes + 128L*1024L*1024L; }
    public static long estimateBytes(float durationSec, int bitrate){ return (long)(durationSec * bitrate / 8f * 1.20f); }
}
