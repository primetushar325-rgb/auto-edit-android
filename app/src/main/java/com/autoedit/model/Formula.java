package com.autoedit.model;

public class Formula {
    public String id, name, direction;
    public KeyframeState start, end;
    public float speed = 1f, zoomAmount = .08f, smoothness = 1f;
    public Easing easing = Easing.EASE_IN_OUT;
    public Formula(String id, String name, String direction, KeyframeState start, KeyframeState end) {
        this.id = id; this.name = name; this.direction = direction; this.start = start; this.end = end;
    }
}
