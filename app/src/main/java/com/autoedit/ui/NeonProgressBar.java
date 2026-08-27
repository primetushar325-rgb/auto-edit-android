package com.autoedit.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Large rounded neon progress bar: dark transparent track, cyan→blue fill
 * with a soft outer glow. The fill eases toward the REAL export percentage
 * (setProgress) — the value shown is always the genuine pipeline progress,
 * only the visual fill eases between real values.
 */
public class NeonProgressBar extends View {
    private final Paint trackP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private float target = 0f;   // real progress 0..1
    private float shown = 0f;    // eased value used for drawing
    private boolean running = true;

    public NeonProgressBar(Context c) {
        super(c);
        trackP.setColor(0x330b1d31);
        trackP.setStyle(Paint.Style.FILL);
    }

    public void setProgress(float p0to1) { target = Math.max(0f, Math.min(1f, p0to1)); }
    public void setRunning(boolean b) { running = b; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // ease toward target so the bar feels alive between real updates
        float diff = target - shown;
        if (Math.abs(diff) > 0.0005f) shown += diff * 0.18f;
        else shown = target;

        int w = getWidth(), h = getHeight();
        float rad = h / 2f;
        r.set(0, 0, w, h);
        canvas.drawRoundRect(r, rad, rad, trackP);

        float fw = w * shown;
        if (fw > rad * 2f) {
            fillP.setShader(new LinearGradient(0, 0, fw, 0,
                    new int[]{0xff49A8FF, 0xff7C5CFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            fillP.setShadowLayer(dp(12), 0, 0, 0x9949A8FF);
            canvas.drawRoundRect(new RectF(0, 0, fw, h), rad, rad, fillP);
            fillP.setShadowLayer(0, 0, 0, 0);
            // head glow dot
            fillP.setColor(0xffbfe3ff);
            canvas.drawCircle(fw - rad, h / 2f, h * .34f, fillP);
        } else if (fw > 0f) {
            fillP.setShader(null);
            fillP.setColor(0xff49A8FF);
            canvas.drawRoundRect(new RectF(0, 0, fw, h), Math.min(rad, fw / 2f), Math.min(rad, fw / 2f), fillP);
        }
        if (running || Math.abs(target - shown) > 0.0005f) postDelayed(new Runnable() { public void run() { postInvalidate(); } }, 33);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
}
