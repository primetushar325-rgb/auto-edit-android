package com.autoedit.frames;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Shared FileProvider for local file sharing/install flows:
 * - Frame Extractor ZIP (share / open)
 * - Mandatory update APK (install intent)
 * Uses content:// URIs only (no file://).
 */
public class ZipProvider extends FileProvider {
    public static Uri uriFor(Context c, File f) {
        return FileProvider.getUriForFile(c, "com.autoedit.fileprovider", f);
    }
}
