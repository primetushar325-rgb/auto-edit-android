package com.autoedit.model;

public class KeyframeState {
    public float x, y, scale, rotation, opacity;
    public KeyframeState(float x, float y, float scale, float rotation, float opacity) {
        this.x = x; this.y = y; this.scale = scale; this.rotation = rotation; this.opacity = opacity;
    }
    public KeyframeState copy() { return new KeyframeState(x, y, scale, rotation, opacity); }
    public static KeyframeState lerp(KeyframeState a, KeyframeState b, float t) {
        return new KeyframeState(a.x + (b.x-a.x)*t, a.y + (b.y-a.y)*t, a.scale + (b.scale-a.scale)*t, a.rotation + (b.rotation-a.rotation)*t, a.opacity + (b.opacity-a.opacity)*t);
    }
}
