package com.autoedit.model;

/** Easing curves applied to normalized motion progress 0..1. */
public enum Easing {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT,
    CUBIC, QUINT, SINE, EXPO;

    public float apply(float t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        switch (this) {
            case EASE_IN:    return t * t;
            case EASE_OUT:   return 1f - (1f - t) * (1f - t);
            case EASE_IN_OUT:return t < .5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;
            case CUBIC:      return t < .5f ? 4f * t * t * t
                                           : 1f - (float) Math.pow(-2f * t + 2f, 3f) / 2f;
            case QUINT:      return t < .5f ? 16f * t * t * t * t * t
                                           : 1f - (float) Math.pow(-2f * t + 2f, 5f) / 2f;
            case SINE:       return (float) (1f - Math.cos((t * Math.PI) / 2f));
            case EXPO:       return (float) (1f - Math.pow(2f, -10f * t));
            case LINEAR:
            default:         return t;
        }
    }
}
