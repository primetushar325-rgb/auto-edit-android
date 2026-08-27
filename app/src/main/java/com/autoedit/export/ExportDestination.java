package com.autoedit.export;

import android.content.*;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;

public class ExportDestination implements Closeable {
    public final Uri uri;
    public final File file;
    public final ParcelFileDescriptor parcelFileDescriptor;
    public final String displayName;
    private final Context context;
    private boolean success;

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
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AutoEdit");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Unable to create Movies/AutoEdit directory");
            File f = new File(dir, safeName);
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE);
            return new ExportDestination(context, Uri.fromFile(f), f, pfd, safeName);
        }
    }

    public FileDescriptor fileDescriptor() { return parcelFileDescriptor.getFileDescriptor(); }
    public void markSuccess() { success = true; }

    public void publishOrDelete() {
        try { close(); } catch (IOException ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (uri == null) return;
            if (success) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            } else {
                context.getContentResolver().delete(uri, null, null);
            }
        } else if (file != null) {
            if (success) MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            else //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
    }

    @Override public void close() throws IOException { if (parcelFileDescriptor != null) parcelFileDescriptor.close(); }
}
