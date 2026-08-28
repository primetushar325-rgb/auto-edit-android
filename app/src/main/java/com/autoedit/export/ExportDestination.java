package com.autoedit.export;

import android.content.*;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;

import java.io.*;

/**
 * Where an export lands, and how it becomes visible to the Gallery (spec §24).
 *
 * <h3>Android 10+</h3>
 * A MediaStore row is inserted into {@code Movies/AutoEdit/} with
 * {@code IS_PENDING = 1}. Nothing else can see it while we write. Only after
 * the muxer is closed <em>and</em> the container is verified do we clear
 * {@code IS_PENDING}. If anything fails the row is deleted, so a broken file
 * never appears in the Gallery.
 *
 * <h3>Android 9 and below</h3>
 * A real file in the public {@code Movies/AutoEdit} directory, published with
 * {@code MediaScannerConnection.scanFile} so it shows up immediately.
 *
 * The published {@link #uri} is what the completion screen must play and share
 * — never a temp path and never a lookup by display name (spec §19).
 */
public class ExportDestination implements Closeable {
    public final Uri uri;
    public final File file;
    public final ParcelFileDescriptor parcelFileDescriptor;
    public final String displayName;
    private final Context context;
    private boolean success;
    private boolean writerClosed;

    private ExportDestination(Context context, Uri uri, File file, ParcelFileDescriptor pfd, String displayName) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.file = file;
        this.parcelFileDescriptor = pfd;
        this.displayName = displayName;
    }

    public static ExportDestination create(Context context, String displayName) throws IOException {
        String safeName = displayName.endsWith(".mp4") ? displayName : displayName + ".mp4";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, safeName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AutoEdit");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = context.getContentResolver().insert(collection, values);
            if (item == null) throw new IOException("Unable to create MediaStore export destination");
            ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(item, "w");
            if (pfd == null) throw new IOException("Unable to open MediaStore file descriptor");
            return new ExportDestination(context, item, null, pfd, safeName);
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AutoEdit");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Unable to create Movies/AutoEdit directory");
        File f = new File(dir, safeName);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f,
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE);
        return new ExportDestination(context, Uri.fromFile(f), f, pfd, safeName);
    }

    public FileDescriptor fileDescriptor() { return parcelFileDescriptor.getFileDescriptor(); }

    /** Closes the write handle so the finished file can be re-opened for reading. */
    public void closeWriter() throws IOException {
        if (!writerClosed && parcelFileDescriptor != null) {
            parcelFileDescriptor.close();
            writerClosed = true;
        }
    }

    /**
     * Re-opens the finished file read-only so the container can be verified
     * before it is published. Returns null when the file cannot be re-opened.
     */
    public ParcelFileDescriptor openForVerify() {
        try {
            closeWriter();
            if (uri == null) return null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.getContentResolver().openFileDescriptor(uri, "r");
            }
            if (file != null && file.exists() && file.length() > 0) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void markSuccess() { success = true; }

    /** Publishes on success, deletes on failure. Always closes the writer. */
    public void publishOrDelete() {
        try { closeWriter(); } catch (IOException ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (uri == null) return;
            try {
                if (success) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                } else {
                    context.getContentResolver().delete(uri, null, null);
                }
            } catch (Exception ignored) {}
        } else if (file != null) {
            if (success)
                MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()},
                        new String[]{"video/mp4"}, null);
            else //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
    }

    @Override public void close() throws IOException { closeWriter(); }
}
