package com.autoedit.export;

import android.content.*;
import android.graphics.*;
import android.util.LruCache;
import java.io.*;
import com.autoedit.model.*;
import com.autoedit.engine.*;

/**
 * Export frame renderer.
 *
 * Like the preview, it owns NO drawing logic: it owns the bitmap caches and
 * hands each frame to {@link FrameComposer}, the exact same composer the
 * preview uses. Preview == MP4 is therefore structural (spec §16, §37).
 *
 * Memory (§38): sources come from a disk cache of pre-scaled JPEGs plus a small
 * LRU of decoded bitmaps, and the output frame bitmap is reused for every
 * frame. A 1000-clip project never holds full-resolution originals.
 */
public class FrameRenderer {
    private final FrameComposer composer;
    private final DiskBitmapCache diskCache;
    private final LruCache<String, Bitmap> memoryCache;
    private Bitmap frameBitmap;
    private Canvas frameCanvas;

    private final FrameComposer.BitmapSource source = new FrameComposer.BitmapSource() {
        @Override public Bitmap get(String uri, int w, int h) throws IOException {
            return getBitmap(uri, w, h);
        }
    };

    public FrameRenderer(Context c) { this(c, new FormulaEngine(), new EffectEngine(), new TransitionEngine()); }

    public FrameRenderer(Context c, FormulaEngine f, EffectEngine e, TransitionEngine t) {
        this.composer = new FrameComposer(f, e, t);
        this.diskCache = new DiskBitmapCache(c.getApplicationContext());
        int maxKb = (int) Math.min(96 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 4);
        memoryCache = new LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
        };
    }

    /** Exposed so the exporter can report the motion actually being rendered. */
    public FormulaEngine formulas() { return composer.formulas(); }

    /** Text sizes are authored for the export canvas, so the scale is 1. */
    public Bitmap renderAtTime(EditProject project, float timeSec, int width, int height,
                               FitMode fitMode) throws IOException {
        ensure(width, height);
        EditProject effective = project;
        if (effective.fitMode != fitMode) {
            // The exporter may be asked for a different fit mode than the saved
            // project; render against a shallow view so the project is not mutated.
            effective = withFitMode(project, fitMode);
        }
        composer.compose(effective, timeSec, frameCanvas, width, height, source, 1f);
        return frameBitmap;
    }

    private EditProject withFitMode(EditProject p, FitMode fitMode) {
        EditProject v = new EditProject();
        v.clips = p.clips; v.texts = p.texts; v.audioTracks = p.audioTracks;
        v.width = p.width; v.height = p.height; v.fps = p.fps;
        v.aspectRatio = p.aspectRatio; v.exportPreset = p.exportPreset;
        v.fitMode = fitMode; v.name = p.name;
        return v;
    }

    private void ensure(int width, int height) {
        if (frameBitmap == null || frameBitmap.getWidth() != width || frameBitmap.getHeight() != height) {
            if (frameBitmap != null && !frameBitmap.isRecycled()) frameBitmap.recycle();
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frameCanvas = new Canvas(frameBitmap);
        }
    }

    private Bitmap getBitmap(String uri, int w, int h) throws IOException {
        String key = uri + "_" + w + "x" + h;
        Bitmap b = memoryCache.get(key);
        if (b != null && !b.isRecycled()) return b;
        b = diskCache.decodeForRender(uri, w, h);
        if (b == null) throw new IOException("Invalid source image: " + uri);
        memoryCache.put(key, b);
        return b;
    }

    public void release() {
        if (frameBitmap != null && !frameBitmap.isRecycled()) frameBitmap.recycle();
        frameBitmap = null;
        frameCanvas = null;
        memoryCache.evictAll();
    }
}
