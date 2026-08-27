package com.autoedit.export;

import android.graphics.Bitmap;import android.media.MediaCodecInfo;

public class Yuv420Converter {
    public static void bitmapToYuv420(Bitmap bmp, byte[] out, int colorFormat) {
        boolean semi = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        int w=bmp.getWidth(), h=bmp.getHeight(); int frameSize=w*h; int uIndex=frameSize; int vIndex=semi?frameSize+1:frameSize+frameSize/4;
        int[] row = new int[w];
        for(int y=0;y<h;y++){
            bmp.getPixels(row,0,w,0,y,w,1);
            for(int x=0;x<w;x++){
                int c=row[x]; int r=(c>>16)&0xff, g=(c>>8)&0xff, b=c&0xff;
                int yy=((66*r+129*g+25*b+128)>>8)+16; int uu=((-38*r-74*g+112*b+128)>>8)+128; int vv=((112*r-94*g-18*b+128)>>8)+128;
                out[y*w+x]=(byte)clamp(yy);
                if((y&1)==0 && (x&1)==0){
                    int uv=(y/2)*(w/2)+(x/2);
                    if(semi){ int pos=frameSize+uv*2; out[pos]=(byte)clamp(uu); out[pos+1]=(byte)clamp(vv); }
                    else { out[uIndex+uv]=(byte)clamp(uu); out[vIndex+uv]=(byte)clamp(vv); }
                }
            }
        }
    }
    private static int clamp(int v){ return v<0?0:(v>255?255:v); }
}
