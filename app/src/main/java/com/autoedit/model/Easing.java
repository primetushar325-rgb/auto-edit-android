package com.autoedit.model;

public enum Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT;
    public float apply(float t) {
        if (t < 0) return 0; if (t > 1) return 1;
        switch (this) {
            case EASE_IN: return t * t;
            case EASE_OUT: return 1f - (1f - t) * (1f - t);
            case EASE_IN_OUT: return t < .5f ? 2f*t*t : 1f - (float)Math.pow(-2f*t + 2f, 2f)/2f;
            default: return t;
        }
    }
}
