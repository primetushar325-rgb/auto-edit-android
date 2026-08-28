package com.autoedit.ads;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

/**
 * Interstitial gate in front of the Export button.
 *
 * <h3>Scope</h3>
 * This class is deliberately self-contained and knows nothing about exporting.
 * It is handed the existing export call as a plain {@link Runnable} and invokes
 * it exactly once when the gate clears. No rendering, encoding, muxing or
 * MediaStore code is referenced here, and nothing in {@code export/} had to
 * change to add this.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Check connectivity. No connection -> a non-cancelable dialog with a
 *       single "Try Again" button. Export cannot start and the dialog cannot be
 *       dismissed until connectivity returns.</li>
 *   <li>Show a full-screen loading overlay.</li>
 *   <li>Load and show {@value #TOTAL_ADS} interstitials back to back. Each
 *       dismissal advances the counter and loads the next one; a load or show
 *       failure skips forward immediately so the user is never stranded.</li>
 *   <li>When the count is reached (or every attempt failed), remove the overlay
 *       and run the export.</li>
 * </ol>
 *
 * <h3>Why there is a timeout</h3>
 * The AdMob callbacks are the only thing that normally advances the sequence,
 * so if one never fires the user would sit on the overlay forever. A watchdog
 * forces the sequence to finish. This exists purely to guarantee the export
 * always becomes reachable.
 */
public final class AdGate {

    private static final String TAG = "AutoEditAdGate";
    private static final String AD_UNIT_ID = "ca-app-pub-7712127801733980/9754186146";
    private static final int TOTAL_ADS = 3;
    /** Upper bound for the whole ad sequence, so it can never hang. */
    private static final long SEQUENCE_TIMEOUT_MS = 60_000L;
    /** Upper bound for a single ad load. */
    private static final long LOAD_TIMEOUT_MS = 15_000L;

    private final Activity activity;

    private InterstitialAd interstitial;
    private int adsShown;
    private View overlay;
    private TextView overlayText;
    private Runnable proceed;
    private boolean settled;      // proceed has been invoked
    private boolean loading;      // a load is in flight
    private long deadline;

    private AlertDialog netDialog;

    public AdGate(Activity activity) {
        this.activity = activity;
    }

    // ---------------------------------------------------------------- entry

    /**
     * Runs the gate. {@code proceed} is the pre-existing export call and is
     * invoked exactly once, on the main thread, only after the gate clears.
     */
    public void start(Runnable proceed) {
        if (settled) return;                 // never start twice
        this.proceed = proceed;
        if (!isInternetAvailable()) {
            showMandatoryInternetDialog();
            return;
        }
        adsShown = 0;
        MobileAds.initialize(activity);      // idempotent, async
        showLoadingOverlay();
        deadline = System.currentTimeMillis() + SEQUENCE_TIMEOUT_MS;
        loadAndShowAd();
    }

    // ------------------------------------------------------------ internet

    /**
     * True when the device has a validated network capable of reaching the
     * internet.
     *
     * Uses {@link NetworkCapabilities} rather than the reference snippet's
     * {@code getActiveNetworkInfo()}, which has been deprecated since API 29 and
     * reports a connection as available even when it cannot actually reach
     * anything. Behaviour for the user is the same; the answer is just correct
     * on modern Android.
     */
    private boolean isInternetAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            Log.w(TAG, "Connectivity check failed", e);
            return false;
        }
    }

    /**
     * Non-cancelable blocker shown when there is no connection.
     *
     * There is exactly one button, "Try Again", which re-runs the check. The
     * dialog cannot be dismissed by Back or by touching outside it, and export
     * is not reachable from it. When "Try Again" finds a connection the dialog
     * closes and the ad sequence begins.
     */
    private void showMandatoryInternetDialog() {
        if (!isAlive()) return;
        if (netDialog != null && netDialog.isShowing()) return;
        netDialog = new AlertDialog.Builder(activity)
                .setTitle("ইন্টারনেট দরকার")
                .setMessage("এক্সপোর্ট করতে ইন্টারনেট কানেকশন চালু করুন")
                .setCancelable(false)
                .setPositiveButton("Try Again", null)   // overridden below so it stays open
                .create();
        netDialog.setCanceledOnTouchOutside(false);
        netDialog.setOnKeyListener((d, keyCode, event) ->
                keyCode == android.view.KeyEvent.KEYCODE_BACK);  // swallow Back
        netDialog.show();
        // setPositiveButton's listener dismisses by default; replacing the
        // button's own listener keeps the dialog up until a check succeeds.
        netDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (isInternetAvailable()) {
                netDialog.dismiss();
                netDialog = null;
                start(proceed);
            }
        });
    }

    // -------------------------------------------------------------- overlay

    private void showLoadingOverlay() {
        if (!isAlive()) return;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        FrameLayout box = new FrameLayout(activity);
        box.setBackgroundColor(0xCC020409);                 // semi-transparent layer
        box.setClickable(true);                             // swallow touches
        box.setFocusable(true);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        ProgressBar spin = new ProgressBar(activity);
        col.addView(spin, new LinearLayout.LayoutParams(dp(44), dp(44)));

        overlayText = new TextView(activity);
        overlayText.setText("বিজ্ঞাপন লোড হচ্ছে...");
        overlayText.setTextColor(Color.WHITE);
        overlayText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        overlayText.setTypeface(Typeface.DEFAULT_BOLD);
        overlayText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(16);
        col.addView(overlayText, tlp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        box.addView(col, clp);
        decor.addView(box, new ViewGroup.LayoutParams(-1, -1));
        overlay = box;
    }

    private void updateOverlayText(String s) {
        if (overlayText != null) overlayText.setText(s);
    }

    private void hideLoadingOverlay() {
        if (overlay == null) return;
        ViewGroup parent = (ViewGroup) overlay.getParent();
        if (parent != null) parent.removeView(overlay);
        overlay = null;
        overlayText = null;
    }

    // ------------------------------------------------------------------ ads

    private void loadAndShowAd() {
        if (!isAlive()) { finish(); return; }

        // Whole-sequence watchdog.
        if (System.currentTimeMillis() > deadline) {
            Log.w(TAG, "Ad sequence hit its time limit at ad " + (adsShown + 1));
            finish();
            return;
        }

        if (adsShown >= TOTAL_ADS) {
            finish();
            return;
        }

        updateOverlayText("বিজ্ঞাপন লোড হচ্ছে... (" + (adsShown + 1) + "/" + TOTAL_ADS + ")");
        loading = true;

        // Per-ad watchdog: if neither callback fires, skip forward anyway.
        final int myIndex = adsShown;
        activity.getWindow().getDecorView().postDelayed(() -> {
            if (settled) return;
            if (loading && adsShown == myIndex) {
                Log.w(TAG, "Ad " + (myIndex + 1) + " load timed out, skipping");
                loading = false;
                adsShown++;
                loadAndShowAd();
            }
        }, LOAD_TIMEOUT_MS);

        try {
            InterstitialAd.load(activity, AD_UNIT_ID, new AdRequest.Builder().build(),
                    new InterstitialAdLoadCallback() {
                        @Override public void onAdLoaded(InterstitialAd ad) {
                            loading = false;
                            if (settled || !isAlive()) return;
                            interstitial = ad;
                            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                                @Override public void onAdDismissedFullScreenContent() {
                                    interstitial = null;
                                    adsShown++;
                                    loadAndShowAd();
                                }
                                @Override public void onAdFailedToShowFullScreenContent(AdError e) {
                                    Log.w(TAG, "Ad show failed: " + e.getMessage());
                                    interstitial = null;
                                    adsShown++;
                                    loadAndShowAd();
                                }
                            });
                            try {
                                ad.show(activity);
                            } catch (Exception e) {
                                Log.w(TAG, "show() threw", e);
                                interstitial = null;
                                adsShown++;
                                loadAndShowAd();
                            }
                        }

                        @Override public void onAdFailedToLoad(LoadAdError e) {
                            loading = false;
                            Log.w(TAG, "Ad load failed: " + e.getMessage());
                            adsShown++;          // skip straight to the next one
                            loadAndShowAd();
                        }
                    });
        } catch (Exception e) {
            // Missing Google Play services, bad unit id, etc. - never block export.
            loading = false;
            Log.w(TAG, "InterstitialAd.load threw", e);
            adsShown++;
            loadAndShowAd();
        }
    }

    /** Ends the gate: overlay down, export runs exactly once. */
    private void finish() {
        if (settled) return;
        settled = true;
        interstitial = null;
        hideLoadingOverlay();
        if (netDialog != null) { try { netDialog.dismiss(); } catch (Exception ignored) {} netDialog = null; }
        if (proceed == null) return;
        if (isAlive()) {
            activity.runOnUiThread(proceed);
        } else {
            proceed.run();
        }
    }

    private boolean isAlive() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
