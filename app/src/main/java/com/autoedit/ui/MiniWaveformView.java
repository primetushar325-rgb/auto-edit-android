package com.autoedit.ui;

import android.content.Context;import android.graphics.*;import android.view.View;

public class MiniWaveformView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public MiniWaveformView(Context c){ super(c); setMinimumHeight(AeDesign.dp(c,34)); }
    @Override protected void onDraw(Canvas c){ super.onDraw(c); int w=getWidth(), h=getHeight(); paint.setColor(0xff0b1d31); c.drawRoundRect(0,0,w,h,18,18,paint); paint.setColor(0xff49A8FF); for(int x=8; x<w-8; x+=8){ float amp=(float)(Math.sin(x*.09)+1)/2f; float line=8+amp*(h-16); c.drawLine(x,h/2f-line/2f,x,h/2f+line/2f,paint);} }
}
