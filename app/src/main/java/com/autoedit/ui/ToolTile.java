package com.autoedit.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Compact icon+label editor tool tile.
 * States: normal / active (its panel is open) / pressed.
 */
public class ToolTile extends LinearLayout {
    private final ImageView icon;
    private final TextView text;
    private boolean active;

    public ToolTile(Context c, int iconRes, String label, Runnable onTap) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setPadding(AeDesign.dp(c, 4), AeDesign.dp(c, 6), AeDesign.dp(c, 4), AeDesign.dp(c, 5));
        icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(AeDesign.MUTED);
        addView(icon, new LayoutParams(AeDesign.dp(c, 34), AeDesign.dp(c, 34)));
        text = AeDesign.text(c, label, 10, AeDesign.TEXT, Typeface.NORMAL);
        text.setGravity(Gravity.CENTER);
        LayoutParams tl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tl.topMargin = AeDesign.dp(c, 4);
        addView(text, tl);
        AeDesign.press(this, onTap);
        applyState();
    }

    public void setActive(boolean a) {
        active = a;
        applyState();
    }

    public boolean isActive() { return active; }

    private void applyState() {
        GradientDrawable bg = AeDesign.bg(active ? 0xff12395c : AeDesign.SURFACE_2, AeDesign.dp(getContext(), 16),
                active ? AeDesign.ACCENT : AeDesign.STROKE, active ? 2 : 1);
        setBackground(bg);
        icon.setColorFilter(active ? AeDesign.ACCENT : AeDesign.MUTED);
        text.setTextColor(active ? Color.WHITE : AeDesign.TEXT);
    }
}
