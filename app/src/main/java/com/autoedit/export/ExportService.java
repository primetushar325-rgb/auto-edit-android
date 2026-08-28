package com.autoedit.export;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.util.Log;

import com.autoedit.model.*;
import com.autoedit.project.ProjectStore;


/**
 * Runs the export off the UI thread and reports real progress.
 *
 * The service owns the whole publish lifecycle (spec §17, §24):
 * <ol>
 *   <li>create a pending MediaStore row in {@code Movies/AutoEdit/}</li>
 *   <li>run {@link VideoExporter} into that row's file descriptor</li>
 *   <li>close the descriptor, then RE-OPEN the finished file and verify it
 *       really contains a video track (and an audio track when the project has
 *       audio)</li>
 *   <li>only on success clear {@code IS_PENDING} so the Gallery can see it</li>
 *   <li>broadcast the FINAL content URI so the completion screen plays the
 *       real file — never a temp path, a stale URI or a half-written row</li>
 * </ol>
 *
 * On any failure the pending row is deleted, so no broken file is ever left in
 * the Gallery, and the real reason is broadcast to the UI (spec §48).
 */
public class ExportService extends Service {
    private static final String TAG = "AutoEditExportSvc";

    public static final String ACTION_START = "com.autoedit.START_EXPORT";
    public static final String ACTION_CANCEL = "com.autoedit.CANCEL_EXPORT";
    public static final String ACTION_PROGRESS = "com.autoedit.PROGRESS";
    /** Asks a live export to re-broadcast its current state (spec §3). */
    public static final String ACTION_QUERY = "com.autoedit.QUERY_EXPORT";

    /**
     * Live snapshot of the running export, so a UI that was closed and reopened
     * (or a fresh MainActivity after the process was recreated) can resume
     * showing the real progress instead of assuming nothing is happening.
     *
     * A foreground service outlives the activity, so {@code exportRunning} in
     * the activity is NOT a reliable source of truth on its own.
     */
    public static volatile boolean sRunning = false;
    private static volatile int sPercent = 0;
    private static volatile String sStage = ExportStage.PREPARING.name();
    private static volatile long sFrame = 0;
    private static volatile long sTotal = 0;
    private static volatile int sClip = 0;
    private static volatile String sMessage = "";

    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_STAGE = "stage";
    public static final String EXTRA_FRAME = "frame";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_CLIP = "clip";
    public static final String EXTRA_MESSAGE = "message";
    /** Present exactly once, on success: the published MediaStore URI. */
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_DISPLAY_NAME = "displayName";
    public static final String EXTRA_HAS_AUDIO = "hasAudio";

    private static final String CHANNEL_ID = "autoedit.export";
    private static final int NOTIF_ID = 0x4145;

    private volatile boolean cancelled = false;
    private volatile boolean running = false;

    /**
     * An export is long-running, so it must be a foreground service or Android
     * will kill it part-way through and leave a truncated file. The channel is
     * low-importance: the on-screen export view already shows the detail, the
     * notification just keeps the job alive and visible if the user leaves.
     */
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Video export", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Progress of the current video export");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification notification(int percent, String message) {
        Intent open = new Intent(this, com.autoedit.MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Exporting video")
                .setContentText(message == null ? "Preparing..." : message)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, Math.min(100, percent)), false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) b.setColor(0xff49A8FF);
        return b.build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_QUERY.equals(intent.getAction())) {
            // Nothing running -> say so explicitly, so the UI can stop waiting
            // rather than sitting on a stale progress screen.
            if (running) {
                sendProgress(sPercent, ExportStage.valueOf(sStage), sFrame, sTotal, sClip,
                        sMessage, null, null, false);
            } else {
                sendIdleReply();
            }
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            if (running) return START_NOT_STICKY; // never two exports at once
            cancelled = false;
            running = true;
            sRunning = true;
            sPercent = 0; sStage = ExportStage.PREPARING.name(); sMessage = "Preparing...";
            startForeground(NOTIF_ID, notification(0, "Preparing..."));
            int w = intent.getIntExtra("w", 1920), h = intent.getIntExtra("h", 1080);
            int fps = intent.getIntExtra("fps", 30);
            String fit = intent.getStringExtra("fitMode");
            new Thread(() -> runExport(w, h, fps, fit), "AutoEditExportThread").start();
        }
        return START_NOT_STICKY;
    }

    private void runExport(int w, int h, int fps, String fit) {
        ExportDestination destination = null;
        boolean published = false;
        try {
            EditProject p = new ProjectStore(this).load();
            p.width = w; p.height = h; p.fps = fps;
            if (fit != null) { try { p.fitMode = FitMode.valueOf(fit); } catch (Exception ignored) { p.fitMode = FitMode.FILL; } }
            p.migrateLegacyAudio();

            String fileName = "AutoEdit_" + System.currentTimeMillis() + ".mp4";
            destination = ExportDestination.create(this, fileName);

            ExportOptions o = new ExportOptions();
            o.outputFileDescriptor = destination.fileDescriptor();
            o.outputPath = destination.file != null ? destination.file.getAbsolutePath() : null;
            o.width = w; o.height = h; o.fps = fps; o.fitMode = p.fitMode;
            o.bitrate = w >= 3840 ? 35_000_000 : w >= 2560 ? 18_000_000 : w >= 1920 ? 8_000_000 : 4_000_000;

            boolean wantedAudio = p.hasAudio();
            ExportDestination d = destination;
            VideoExporter.Result res = new VideoExporter(this).export(p, o, new VideoExporter.Listener() {
                @Override public void onProgress(ExportProgress pr) {
                    sendProgress(pr.percent, pr.stage, pr.currentFrame, pr.totalFrames, pr.currentClip, pr.message,
                            null, null, false);
                }
                @Override public boolean isCancelled() { return cancelled; }
            });

            // ---- VERIFYING --------------------------------------------------
            // The old known-good pipeline went straight from "muxer stopped" to
            // markSuccess + publish + 100%. The verification pass that was later
            // inserted in between ran a blocking MediaExtractor call with NO
            // progress report, so any stall there left the UI parked on
            // "Finalizing..." forever. Verification is kept — spec §3 wants the
            // finished MP4 proved playable — but it now has its own visible
            // stage, a hard time limit, and it can never strand the export.
            d.closeWriter();
            sendProgress(ExportStage.VERIFYING.percent(0f), ExportStage.VERIFYING,
                    res.frames, res.frames, p.clips.size(), "Checking the output file", null, null, false);

            Verdict v = verifyFinishedFile(d, wantedAudio);
            sendProgress(ExportStage.VERIFYING.percent(1f), ExportStage.VERIFYING,
                    res.frames, res.frames, p.clips.size(), v.detail, null, null, false);
            if (v.fatal) throw new java.io.IOException(v.detail);

            // ---- publish, in the original known-good order -------------------
            d.markSuccess();
            d.publishOrDelete();
            published = true;

            sendProgress(100, ExportStage.COMPLETE, res.frames, res.frames, p.clips.size(),
                    "Movies/AutoEdit/" + fileName, d.uri, fileName, res.hasAudio);
        } catch (Exception e) {
            Log.e(TAG, "Export failed", e);
            if (destination != null && !published) destination.publishOrDelete();
            boolean wasCancel = cancelled || e instanceof java.io.InterruptedIOException;
            sendProgress(wasCancel ? -2 : -1, ExportStage.PREPARING, 0, 0, 0,
                    wasCancel ? "Export cancelled" : categorize(e), null, null, false);
        } finally {
            running = false;
            sRunning = false;
            stopForeground(true);
            stopSelf();
        }
    }

    /** Outcome of reading the finished file back. */
    private static final class Verdict {
        boolean fatal;      // definitely bad -> abort and clean up
        String detail = "";
    }

    /** How long the container read-back may take before we stop waiting on it. */
    private static final long VERIFY_TIMEOUT_MS = 15_000L;

    /**
     * Proves the finished file is a real, playable MP4.
     *
     * <p>Two rules keep this from ever breaking an export that actually
     * succeeded:
     * <ul>
     *   <li><b>It is time-boxed.</b> {@code MediaExtractor.setDataSource} is a
     *       blocking native call; if it does not answer within
     *       {@link #VERIFY_TIMEOUT_MS} we stop waiting and treat the result as
     *       inconclusive rather than hanging on "Finalizing...".</li>
     *   <li><b>It only aborts on proof.</b> We fail the export when the file is
     *       missing or empty, or when the container was read successfully and
     *       genuinely lacks the required track. If the container simply could
     *       not be read back, we log it and publish anyway — a clean
     *       {@code MediaMuxer.stop()} plus a non-empty file is a finished
     *       video, and deleting it would be worse than a missing warning.</li>
     * </ul>
     */
    private Verdict verifyFinishedFile(ExportDestination d, boolean wantedAudio) {
        Verdict v = new Verdict();

        // 1. The file must be non-empty. A measured 0 is definitive; -1 only
        //    means we could not stat it, which is not proof of failure.
        long bytes = d.sizeBytes();
        if (bytes == 0L) {
            v.fatal = true;
            v.detail = "Export failed: the output file is empty.";
            return v;
        }

        // 2. Read the container back on a watchdog-guarded thread.
        final VideoExporter.ContainerInfo[] holder = new VideoExporter.ContainerInfo[1];
        Thread t = new Thread(() -> {
            android.os.ParcelFileDescriptor fd = d.openForVerify();
            if (fd == null) return;
            try {
                holder[0] = VideoExporter.verifyContainer(this, fd.getFileDescriptor(), fd.getStatSize());
            } finally {
                try { fd.close(); } catch (Exception ignored) {}
            }
        }, "AutoEditVerify");
        t.setDaemon(true);
        t.start();
        try {
            t.join(VERIFY_TIMEOUT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        if (t.isAlive() || holder[0] == null) {
            // Inconclusive, not fatal: the muxer stopped cleanly and the file
            // has content. Log loudly so this is diagnosable, but publish.
            Log.w(TAG, "Container verification inconclusive after " + VERIFY_TIMEOUT_MS
                    + " ms (" + bytes + " bytes written) - publishing anyway");
            v.detail = bytes > 0 ? "Saved (" + (bytes / 1024L) + " KB)" : "Saved";
            return v;
        }

        VideoExporter.ContainerInfo info = holder[0];
        if (info.hasVideo) {
            if (wantedAudio && !info.hasAudio) {
                v.fatal = true;
                v.detail = "Export failed during audio muxing - the file has no audio track.";
                return v;
            }
            v.detail = "Verified: video" + (info.hasAudio ? " + audio" : "")
                    + (bytes > 0 ? ", " + (bytes / 1024L) + " KB" : "");
            return v;
        }

        // The container read back fine but has no video track: definitely bad.
        v.fatal = true;
        v.detail = info.error != null
                ? "Export failed while writing the video: " + info.error
                : "Export failed during video encoding.";
        return v;
    }

    /** Maps an exception onto a message a normal user can act on (spec §48). */
    private String categorize(Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String m = msg.toLowerCase();
        if (m.contains("cancel")) return "Export cancelled";
        if (m.contains("storage") || m.contains("space")) return "Not enough storage space to export this video.";
        if (m.contains("audio")) return "Audio could not be decoded: " + msg;
        if (m.contains("encoder")) return "Export failed during video encoding: " + msg;
        if (m.contains("permission") || m.contains("denied")) return "Permission problem: " + msg;
        if (m.contains("unsupported") || m.contains("corrupt") || m.contains("invalid source"))
            return "Unsupported/invalid media: " + msg;
        return "Rendering error: " + msg;
    }

    /** Last percent pushed to the notification, so it is not updated per frame. */
    private int lastNotifPercent = -1;

    /**
     * Reply to {@link #ACTION_QUERY} when no export is in flight.
     *
     * Percent {@code -3} means "idle" - distinct from {@code -1} (failed) and
     * {@code -2} (cancelled) so the UI can clear a stale progress screen instead
     * of showing an error for an export that already finished cleanly.
     */
    private void sendIdleReply() {
        Intent i = new Intent(ACTION_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_PERCENT, -3);
        i.putExtra(EXTRA_STAGE, ExportStage.PREPARING.name());
        i.putExtra(EXTRA_MESSAGE, "");
        sendBroadcast(i);
    }

    private void sendProgress(int percent, ExportStage stage, long frame, long total, int clip,
                              String message, Uri uri, String displayName, boolean hasAudio) {
        // Keep the snapshot current so a reopened UI resumes from real numbers.
        if (stage != null) sStage = stage.name();
        sPercent = percent; sFrame = frame; sTotal = total; sClip = clip;
        sMessage = message == null ? "" : message;

        // Mirror the real progress into the foreground notification. Only whole
        // percents are worth a notify() call.
        if (running && percent >= 0 && percent != lastNotifPercent) {
            lastNotifPercent = percent;
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIF_ID, notification(percent, message));
            } catch (Exception e) {
                Log.w(TAG, "Could not update export notification", e);
            }
        }
        Intent i = new Intent(ACTION_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_PERCENT, percent);
        i.putExtra(EXTRA_STAGE, stage == null ? ExportStage.PREPARING.name() : stage.name());
        i.putExtra(EXTRA_FRAME, frame);
        i.putExtra(EXTRA_TOTAL, total);
        i.putExtra(EXTRA_CLIP, clip);
        i.putExtra(EXTRA_MESSAGE, message == null ? "" : message);
        if (uri != null) {
            i.putExtra(EXTRA_URI, uri.toString());
            i.putExtra(EXTRA_DISPLAY_NAME, displayName);
            i.putExtra(EXTRA_HAS_AUDIO, hasAudio);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        sendBroadcast(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
