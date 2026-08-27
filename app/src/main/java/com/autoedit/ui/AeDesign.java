package com.autoedit.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import android.content.Context;

public final class AeDesign {
    public static final int BG = 0xff020409;
    public static final int SURFACE = 0xff071422;
    public static final int SURFACE_2 = 0xff0b1d31;
    public static final int ACCENT = 0xff49A8FF;
    public static final int ACCENT_2 = 0xff7C5CFF;
    public static final int TEXT = 0xffffffff;
    public static final int MUTED = 0xff9eb8cc;
    public static final int STROKE = 0x3349A8FF;
    public static final int DANGER = 0xffff5a6b;
    public static final int RADIUS = 26;

    private AeDesign() {}

    public static TextView text(Context c, String s, int sp, int color, int style) {
        TextView v = new TextView(c); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setTypeface(Typeface.DEFAULT, style); v.setIncludeFontPadding(true); return v;
    }

    public static GradientDrawable bg(int color, float radius, int strokeColor, int strokeWidth) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); if(strokeWidth>0) d.setStroke(strokeWidth, strokeColor); return d;
    }

    public static GradientDrawable gradient(int start, int end, float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{start,end}); d.setCornerRadius(radius); return d;
    }

    public static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setPadding(22, 12, 22, 12);
        b.setBackground(primary ? gradient(ACCENT, ACCENT_2, RADIUS) : bg(SURFACE_2, RADIUS, STROKE, 1));
        b.setMinHeight(dp(c,48)); b.setContentDescription(label); return b;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(c,16), dp(c,14), dp(c,16), dp(c,14)); l.setBackground(bg(SURFACE, RADIUS, STROKE, 1)); return l;
    }

    /** Round icon button for headers and transport controls. */
    public static ImageView iconButton(Context c, int res, String desc, boolean primary) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int s = dp(c, 44);
        v.setPadding(dp(c, 11), dp(c, 11), dp(c, 11), dp(c, 11));
        v.setBackground(primary ? gradient(ACCENT, ACCENT_2, dp(c, 16)) : bg(SURFACE_2, dp(c, 16), STROKE, 1));
        v.setColorFilter(primary ? Color.WHITE : MUTED);
        v.setContentDescription(desc);
        press(v, () -> {});
        return v;
    }

    public static void press(View v, Runnable action) {
        v.setOnClickListener(x -> x.animate().scaleX(.96f).scaleY(.96f).alpha(.86f).setDuration(55).withEndAction(() -> x.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(85).withEndAction(action).start()).start());
    }

    public static int dp(Context c, int v) { return (int)(v * c.getResources().getDisplayMetrics().density + .5f); }
}
