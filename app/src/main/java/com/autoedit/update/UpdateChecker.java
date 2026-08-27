package com.autoedit.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Version check against the remote version.json (GitHub-hosted, stable URL).
 *
 * Network-failure safety: if the remote file cannot be reached (offline,
 * GitHub outage, DNS/timeout/server error) the LAST successfully cached
 * configuration is used. If nothing was ever cached, the app opens normally
 * (never permanently locked out) and the check runs again next time.
 */
public class UpdateChecker {
    private static final String PREF = "auto_edit_update";
    private static final String KEY_CACHE = "version_json";

    /** Stable public location of version.json (kept current by the release workflow). */
    private static final String VERSION_JSON_URL =
            "https://raw.githubusercontent.com/primetushar325-rgb/auto-edit-android/main/version.json";
    /** Fallback: version.json uploaded as a GitHub Release asset. */
    private static final String VERSION_JSON_URL_FALLBACK =
            "https://github.com/primetushar325-rgb/auto-edit-android/releases/latest/download/version.json";

    public interface Callback {
        /** cfg is null only when no remote data and no cache exist (app opens normally). */
        void onResult(VersionConfig cfg, boolean fromCache);
    }

    private static volatile boolean checking = false;

    /** Runs the check on a background thread; result delivered on the main thread. */
    public static void checkAsync(Context ctx, Callback cb) {
        if (checking) return; // never run multiple checks simultaneously
        checking = true;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            VersionConfig cfg = null;
            boolean fromCache = false;
            try {
                String json = fetch(VERSION_JSON_URL);
                if (json != null) {
                    cfg = VersionConfig.parse(json);
                    prefs(app).edit().putString(KEY_CACHE, json).apply();
                }
            } catch (Exception ignored) {}
            if (cfg == null) {
                try {
                    String json = fetch(VERSION_JSON_URL_FALLBACK);
                    if (json != null) {
                        cfg = VersionConfig.parse(json);
                        prefs(app).edit().putString(KEY_CACHE, json).apply();
                    }
                } catch (Exception ignored) {}
            }
            if (cfg == null) {
                String cached = prefs(app).getString(KEY_CACHE, null);
                if (cached != null) {
                    try { cfg = VersionConfig.parse(cached); fromCache = true; } catch (Exception ignored) {}
                }
            }
            final VersionConfig result = cfg;
            final boolean fc = fromCache;
            new Handler(Looper.getMainLooper()).post(() -> {
                checking = false;
                cb.onResult(result, fc);
            });
        }, "AutoEditUpdateCheck").start();
    }

    private static String fetch(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setRequestProperty("User-Agent", "AutoEdit-Android");
            con.setInstanceFollowRedirects(true);
            int code = con.getResponseCode();
            if (code != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (InputStream in = con.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    public static int localVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    public static String localVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
