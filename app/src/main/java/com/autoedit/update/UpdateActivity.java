package com.autoedit.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import com.autoedit.R;
import com.autoedit.frames.ZipProvider;
import com.autoedit.ui.AeDesign;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Mandatory update screen (blocking).
 *
 * Shown when the installed versionCode is below minimumSupportedVersionCode
 * from the remote version.json. No Skip / Later — the editor stays
 * unreachable until the app is updated. UPDATE NOW downloads the APK from the
 * URL given by version.json (real progress, single download, reuses an
 * already-downloaded APK) and hands it to the Android package installer via a
 * FileProvider content:// URI. Handles the Android 8+ "install unknown apps"
 * permission and never attempts silent installation.
 */
public class UpdateActivity extends Activity {
    public static final String EXTRA_LATEST_CODE = "latestCode";
    public static final String EXTRA_LATEST_NAME = "latestName";
    public static final String EXTRA_MIN_CODE = "minCode";
    public static final String EXTRA_DOWNLOAD_URL = "downloadUrl";
    public static final String EXTRA_NOTES = "notes";

    private int latestCode, minCode;
    private String latestName, downloadUrl;
    private List<String> notes;
    private LinearLayout root;
    private Button updateBtn;
    private ProgressBar bar;
    private TextView pctLabel, statusLabel;
    private boolean downloading = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        latestCode = i.getIntExtra(EXTRA_LATEST_CODE, 0);
        latestName = i.getStringExtra(EXTRA_LATEST_NAME);
        minCode = i.getIntExtra(EXTRA_MIN_CODE, latestCode);
        downloadUrl = i.getStringExtra(EXTRA_DOWNLOAD_URL);
        notes = i.getStringArrayListExtra(EXTRA_NOTES);
        buildUi();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(AeDesign.dp(this, 22), AeDesign.dp(this, 18), AeDesign.dp(this, 22), AeDesign.dp(this, 18));
        root.setBackgroundColor(AeDesign.BG);
        setContentView(root);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_autoedit_alpha);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo, new LinearLayout.LayoutParams(AeDesign.dp(this, 96), AeDesign.dp(this, 96)));

        TextView title = AeDesign.text(this, "New Version Available", 26, AeDesign.TEXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = AeDesign.text(this, "A new version of AutoEdit is required to continue.", 14, AeDesign.MUTED, Typeface.NORMAL);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        LinearLayout card = AeDesign.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView cur = AeDesign.text(this, "Current version:  " + UpdateChecker.localVersionName(this)
                + "  (code " + UpdateChecker.localVersionCode(this) + ")", 14, AeDesign.MUTED, Typeface.NORMAL);
        cur.setGravity(Gravity.CENTER);
        card.addView(cur);
        TextView latest = AeDesign.text(this, "Latest version:  " + (latestName == null ? "—" : latestName)
                + "  (code " + latestCode + ")", 14, AeDesign.TEXT, Typeface.BOLD);
        latest.setGravity(Gravity.CENTER);
        card.addView(latest);
        if (notes != null && !notes.isEmpty()) {
            TextView wn = AeDesign.text(this, "What's new", 13, AeDesign.TEXT, Typeface.BOLD);
            card.addView(wn);
            for (String n : notes) card.addView(AeDesign.text(this, "• " + n, 12, AeDesign.MUTED, Typeface.NORMAL));
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = AeDesign.dp(this, 18);
        root.addView(card, clp);

        updateBtn = AeDesign.button(this, "UPDATE NOW", true);
        updateBtn.setOnClickListener(v -> startUpdate());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, AeDesign.dp(this, 58));
        blp.topMargin = AeDesign.dp(this, 20);
        root.addView(updateBtn, blp);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(-1, AeDesign.dp(this, 12)));

        pctLabel = AeDesign.text(this, "", 13, AeDesign.ACCENT, Typeface.BOLD);
        pctLabel.setGravity(Gravity.CENTER);
        pctLabel.setVisibility(View.GONE);
        root.addView(pctLabel);

        statusLabel = AeDesign.text(this, "", 12, AeDesign.MUTED, Typeface.NORMAL);
        statusLabel.setGravity(Gravity.CENTER);
        root.addView(statusLabel);

        TextView note = AeDesign.text(this, "Updating keeps all your projects, formulas and settings.", 11, AeDesign.MUTED, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = AeDesign.dp(this, 14);
        root.addView(note, nlp);
    }

    private void startUpdate() {
        if (downloading) return; // duplicate-download protection
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            statusLabel.setText("No download link available. Please install the latest AutoEdit from GitHub Releases.");
            return;
        }
        // Reuse an already-downloaded APK for this exact version.
        File apk = apkFile();
        if (apk.exists() && apk.length() > 0) {
            install(apk);
            return;
        }
        downloading = true;
        updateBtn.setEnabled(false);
        updateBtn.setAlpha(.55f);
        bar.setVisibility(View.VISIBLE);
        pctLabel.setVisibility(View.VISIBLE);
        pctLabel.setText("Downloading update... 0%");
        statusLabel.setText("");
        new Thread(() -> {
            boolean ok = download(apk);
            handler.post(() -> {
                downloading = false;
                if (ok) {
                    pctLabel.setText("Download complete — Update ready");
                    bar.setProgress(100);
                    install(apk);
                } else {
                    pctLabel.setText("");
                    statusLabel.setText("Download failed — check your connection and try again.");
                    updateBtn.setEnabled(true);
                    updateBtn.setAlpha(1f);
                }
            });
        }, "AutoEditUpdateDownload").start();
    }

    private boolean download(File apk) {
        HttpURLConnection con = null;
        try {
            File parent = apk.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(apk.getAbsolutePath() + ".part");
            con = (HttpURLConnection) new URL(downloadUrl).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "AutoEdit-Android");
            if (con.getResponseCode() != 200) return false;
            long total = con.getContentLengthLong();
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        final int pct = (int) (got * 100 / total);
                        handler.post(() -> {
                            bar.setProgress(pct);
                            pctLabel.setText("Downloading update... " + pct + "%");
                        });
                    }
                }
            }
            if (tmp.length() == 0) { tmp.delete(); return false; }
            return tmp.renameTo(apk);
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private File apkFile() {
        return new File(getFilesDir(), "updates/AutoEdit-" + latestCode + ".apk");
    }

    private void install(File apk) {
        // Android 8+ unknown-source permission — guide the user, then retry.
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow AutoEdit to install updates")
                    .setMessage("Android needs your permission for AutoEdit to install this update. Tap Allow, enable \"Install unknown apps\", then come back.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open Settings", (d, w) -> {
                        try {
                            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                            startActivity(s);
                        } catch (ActivityNotFoundException e) {
                            statusLabel.setText("Please enable \"Install unknown apps\" for AutoEdit in Android Settings.");
                        }
                    }).show();
            return;
        }
        try {
            Uri uri = ZipProvider.uriFor(this, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            statusLabel.setText("Installing — after installation, open AutoEdit again.");
        } catch (ActivityNotFoundException e) {
            statusLabel.setText("No installer found on this device — please install the APK manually from GitHub Releases.");
        } catch (Exception e) {
            statusLabel.setText("Could not start the installer: " + e.getMessage());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // If the user installed the new version and returned, we're no longer needed.
        if (UpdateChecker.localVersionCode(this) >= minCode) finish();
    }

    @Override public void onBackPressed() {
        // Mandatory update: no bypass.
        Toast.makeText(this, "AutoEdit must be updated to continue.", Toast.LENGTH_SHORT).show();
    }
}
