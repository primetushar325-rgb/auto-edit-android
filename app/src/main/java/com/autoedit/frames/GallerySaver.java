package com.autoedit.frames;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Saves extracted frames straight into the phone's Gallery (spec §23).
 *
 * On Android 10+ this inserts a MediaStore row under {@code Pictures/AutoEdit}
 * (or {@code DCIM/AutoEdit} when the caller asks for DCIM), streams the encoded
 * image into it and clears {@code IS_PENDING} so the system gallery indexes it
 * immediately. On Android 9 and below it writes the real file and triggers
 * {@code MediaScannerConnection.scanFile}, which is what makes it show up.
 *
 * Nothing is left in app-private storage: the returned {@link Uri} is a real
 * MediaStore/file URI the user can see in their Gallery app.
 */
public final class GallerySaver {

    /**
     * Where a saved image lands.
     *
     * {@link #FRAMES} is the spec §24 location for extracted video frames:
     * {@code Pictures/AutoEdit/Frames}, so frames are grouped separately from
     * anything else the app saves and are trivially findable in Gallery.
     */
    public enum Folder {
        PICTURES(Environment.DIRECTORY_PICTURES, "Pictures/AutoEdit", "AutoEdit"),
        FRAMES(Environment.DIRECTORY_PICTURES, "Pictures/AutoEdit/Frames", "AutoEdit/Frames"),
        DCIM(Environment.DIRECTORY_DCIM, "DCIM/AutoEdit", "AutoEdit");

        /** Root public directory, e.g. {@code Environment.DIRECTORY_PICTURES}. */
        public final String dir;
        /** Full relative path used for MediaStore {@code RELATIVE_PATH}. */
        public final String label;
        /**
         * The same location expressed below the public root, e.g.
         * {@code AutoEdit/Frames}. Used by the pre-Android-10 branch, which
         * builds a real {@link File} rather than a RELATIVE_PATH.
         *
         * It is stored explicitly instead of being sliced out of {@link #label},
         * so path construction never depends on the value of a platform
         * constant and the two branches cannot drift apart.
         */
        public final String sub;

        Folder(String dir, String label, String sub) {
            this.dir = dir; this.label = label; this.sub = sub;
        }

        /** @see #sub */
        public String subPath() { return sub; }
    }

    /** One saved frame. */
    public static class Saved {
        public final Uri uri;
        public final String displayName;
        public final String folderLabel;
        public final long bytes;

        Saved(Uri uri, String displayName, String folderLabel, long bytes) {
            this.uri = uri; this.displayName = displayName; this.folderLabel = folderLabel; this.bytes = bytes;
        }
    }

    private GallerySaver() {}

    /**
     * Encodes and saves one bitmap to the Gallery.
     *
     * @param format  "JPG", "PNG" or "WEBP"
     * @param quality 1..100 (ignored for PNG)
     * @throws IOException when the image cannot be written; the message is
     *                   user-facing and never a raw stack trace (spec §48)
     */
    public static Saved save(Context ctx, Bitmap bmp, String displayName, String format,
                             int quality, Folder folder) throws IOException {
        if (bmp == null || bmp.isRecycled()) throw new IOException("The frame is no longer available.");
        String ext = extFor(format);
        String name = displayName.toLowerCase().endsWith("." + ext) ? displayName : displayName + "." + ext;
        Bitmap.CompressFormat cf = compressFor(format);
        int q = Math.max(1, Math.min(100, quality));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, mimeFor(ext));
            values.put(MediaStore.Images.Media.RELATIVE_PATH, folder.label + "/");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = ctx.getContentResolver().insert(collection, values);
            if (item == null) throw new IOException("Unable to create the Gallery entry.");
            long bytes = 0;
            try (OutputStream os = ctx.getContentResolver().openOutputStream(item)) {
                if (os == null) throw new IOException("Unable to write to the Gallery.");
                bmp.compress(cf, q, os);
                os.flush();
            } catch (IOException e) {
                try { ctx.getContentResolver().delete(item, null, null); } catch (Exception ignored) {}
                throw new IOException("Could not save the frame to the Gallery.", e);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(item, done, null, null);
            bytes = sizeOf(ctx, item);
            return new Saved(item, name, folder.label, bytes);
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(folder.dir), folder.subPath());
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create " + folder.label + ".");
        File out = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(cf, q, fos);
            fos.flush();
        } catch (IOException e) {
            throw new IOException("Could not save the frame to the Gallery.", e);
        }
        MediaScannerConnection.scanFile(ctx, new String[]{out.getAbsolutePath()},
                new String[]{mimeFor(ext)}, null);
        return new Saved(Uri.fromFile(out), name, folder.label, out.length());
    }

    private static long sizeOf(Context ctx, Uri uri) {
        try (android.os.ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd == null ? 0 : pfd.getStatSize();
        } catch (Exception e) { return 0; }
    }

    public static String extFor(String format) {
        if (format == null) return "jpg";
        switch (format.toUpperCase()) {
            case "PNG": return "png";
            case "WEBP": return "webp";
            default: return "jpg";
        }
    }

    public static String mimeFor(String ext) {
        switch (ext) {
            case "png": return "image/png";
            case "webp": return "image/webp";
            default: return "image/jpeg";
        }
    }

    private static Bitmap.CompressFormat compressFor(String format) {
        if (format == null) return Bitmap.CompressFormat.JPEG;
        switch (format.toUpperCase()) {
            case "PNG": return Bitmap.CompressFormat.PNG;
            case "WEBP": return Bitmap.CompressFormat.WEBP;
            default: return Bitmap.CompressFormat.JPEG;
        }
    }
}
