package com.autoedit.model;

public enum AspectRatio {
    R16_9(16,9,"16:9 YouTube"), R9_16(9,16,"9:16 Shorts/TikTok"), R1_1(1,1,"1:1 Instagram"), R4_5(4,5,"4:5 Instagram"), R4_3(4,3,"4:3"), R3_4(3,4,"3:4");
    public final int w,h; public final String label;
    AspectRatio(int w,int h,String label){this.w=w;this.h=h;this.label=label;}
}
