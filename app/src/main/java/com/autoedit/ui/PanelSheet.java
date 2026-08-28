package com.autoedit.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.autoedit.R;

/**
 * CapCut-style bottom sheet overlay.
 *
 * The sheet is attached to the activity's DecorView as a full-screen overlay:
 * a dim scrim on top and a rounded bottom card. Crucially the MAIN EDITOR IS
 * NEVER RESIZED — the preview monitor keeps its exact size while a tool panel
 * is open; the sheet floats over the lower part of the screen. Tapping the
 * scrim (or pressing Back, via {@link #isShowing()}) dismisses it and the
 * editor is exactly as it was.
 *
 * Callers populate {@link #content()} with (horizontally scrolling) cards and
 * {@link #applyBar()} with APPLY controls. Card selection is a pure UI state;
 * only an APPLY button mutates the project.
 */
public class PanelSheet {
    private final Activity activity;
    private FrameLayout overlay;
    private LinearLayout sheet;
    private TextView titleView;
    private LinearLayout content;
    private LinearLayout applyBar;
    private boolean showing = false;
    private Runnable onDismiss;

    public PanelSheet(Activity activity) {
        this.activity = activity;
        build();
    }

    private int dp(int v) { return AeDesign.dp(activity, v); }

    private void build() {
        overlay = new FrameLayout(activity);
        overlay.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);

        // dim scrim
        View scrim = new View(activity);
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> dismiss());
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // bottom card
        sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = AeDesign.bg(AeDesign.SURFACE, 26, AeDesign.STROKE, 1);
        bg.setColor(AeDesign.SURFACE);
        sheet.setBackground(bg);
        sheet.setPadding(dp(14), dp(8), dp(14), dp(14));
        sheet.setElevation(dp(18));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2);
        slp.gravity = Gravity.BOTTOM;
        sheet.setLayoutParams(slp);
        sheet.setClickable(true); // swallow taps so they don't hit the scrim

        // drag handle
        View handle = new View(activity);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0x449eb8cc);
        hb.setCornerRadius(dp(3));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(44), dp(5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(8);
        sheet.addView(handle, hlp);

        // title row
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        titleView = AeDesign.text(activity, "", 16, AeDesign.TEXT, Typeface.BOLD);
        head.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView close = AeDesign.iconButton(activity, R.drawable.ic_close, "Close", false);
        close.setColorFilter(AeDesign.MUTED);
        AeDesign.press(close, this::dismiss);
        head.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        sheet.addView(head);

        // scrollable content
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, dp(6));
        scroll.addView(content);
        LinearLayout.LayoutParams scrlp = new LinearLayout.LayoutParams(-1, 0, 1);
        sheet.addView(scroll, scrlp);

        // apply bar (filled by caller)
        applyBar = new LinearLayout(activity);
        applyBar.setOrientation(LinearLayout.VERTICAL);
        applyBar.setPadding(0, dp(6), 0, 0);
        sheet.addView(applyBar, new LinearLayout.LayoutParams(-1, -2));

        overlay.addView(sheet);

        // cap sheet height to 82% of the screen once laid out
        overlay.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                int maxH = (int) (overlay.getHeight() * 0.82f);
                if (scroll.getHeight() > maxH || sheet.getHeight() > maxH) {
                    ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                    if (lp.height != maxH) { lp.height = maxH; sheet.setLayoutParams(lp); }
                }
            }
        });
    }

    public LinearLayout content() { return content; }
    public LinearLayout applyBar() { return applyBar; }
    public void setTitle(CharSequence t) { titleView.setText(t); }

    public void setOnDismiss(Runnable r) { this.onDismiss = r; }

    public boolean isShowing() { return showing; }

    public void show() {
        content.removeAllViews();
        applyBar.removeAllViews();
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (overlay.getParent() == null) decor.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        overlay.setVisibility(View.VISIBLE);
        showing = true;
    }

    public void dismiss() {
        overlay.setVisibility(View.GONE);
        showing = false;
        if (overlay.getParent() != null) ((ViewGroup) overlay.getParent()).removeView(overlay);
        if (onDismiss != null) { Runnable r = onDismiss; onDismiss = null; r.run(); }
    }
}
