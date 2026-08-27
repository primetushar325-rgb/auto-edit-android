package com.autoedit.ui;

import android.content.Context;import android.graphics.*;import android.view.View;

public class TimelineRulerView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    public TimelineRulerView(Context c){ super(c); }
    @Override protected void onDraw(Canvas c){ super.onDraw(c); int w=getWidth(),h=getHeight(); p.setColor(0x5549A8FF); c.drawLine(w/2f,0,w/2f,h,p); p.setColor(0xff49A8FF); p.setStrokeWidth(4); c.drawLine(w/2f,0,w/2f,h,p); p.setStrokeWidth(1); }
}
