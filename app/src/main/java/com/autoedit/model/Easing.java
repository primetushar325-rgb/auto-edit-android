package com.autoedit.model;

/**
 * Easing curves applied to normalized motion progress 0..1.
 *
 * Every curve is monotonic on [0,1], pinned to f(0)=0 and f(1)=1, and returns
 * exactly 0 / 1 outside the range so a clip never drifts past its keyframes.
 * BACK_IN_OUT is the only curve that intentionally overshoots (it dips below 0
 * and above 1 inside the interval) — callers must treat it as a spring, which
 * is why SafeTransform reserves headroom for it.
 *
 * The names LINEAR/EASE_IN/EASE_OUT/EASE_IN_OUT/CUBIC/QUINT/SINE/EXPO are
 * persisted in saved projects and custom formulas, so they are kept as stable
 * aliases and must never be renamed.
 */
public enum Easing {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT,
    /** Legacy aliases kept for saved-project compatibility. */
    CUBIC, QUINT, SINE, EXPO,
    /** Explicit curve families (spec §11). */
    CUBIC_IN, CUBIC_OUT, CUBIC_IN_OUT,
    QUART_IN, QUART_OUT, QUART_IN_OUT,
    QUINT_IN, QUINT_OUT, QUINT_IN_OUT,
    SINE_IN, SINE_OUT, SINE_IN_OUT,
    EXPO_IN, EXPO_OUT, EXPO_IN_OUT,
    BACK_IN, BACK_OUT, BACK_IN_OUT;

    /** The curve a newly authored motion gets when the user does not pick one. */
    public static final Easing DEFAULT = CUBIC_IN_OUT;

    public float apply(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        switch (this) {
            // EASE_* is the QUADRATIC family. Its names are persisted in saved
            // projects, so the curves must not change shape — the cubic family
            // lives under its own CUBIC_* names below.
            case EASE_IN:
                return t * t;
            case EASE_OUT:
                return 1f - (1f - t) * (1f - t);
            case EASE_IN_OUT:
                return t < .5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;

            case CUBIC_IN:
                return t * t * t;
            case CUBIC_OUT:
                return 1f - cube(1f - t);
            case CUBIC:
            case CUBIC_IN_OUT:
                return t < .5f ? 4f * t * t * t : 1f - cube(-2f * t + 2f) / 2f;

            case QUART_IN:
                return pow4(t);
            case QUART_OUT:
                return 1f - pow4(1f - t);
            case QUART_IN_OUT:
                return t < .5f ? 8f * pow4(t) : 1f - pow4(-2f * t + 2f) / 2f;

            case QUINT:
            case QUINT_IN_OUT:
                return t < .5f ? 16f * pow5(t) : 1f - pow5(-2f * t + 2f) / 2f;
            case QUINT_IN:
                return pow5(t);
            case QUINT_OUT:
                return 1f - pow5(1f - t);

            case SINE:
            case SINE_IN:
                return 1f - (float) Math.cos(t * Math.PI / 2.0);
            case SINE_OUT:
                return (float) Math.sin(t * Math.PI / 2.0);
            case SINE_IN_OUT:
                return -(float) (Math.cos(Math.PI * t) - 1.0) / 2f;

            case EXPO:
            case EXPO_IN:
                return (float) Math.pow(2.0, 10.0 * t - 10.0);
            case EXPO_OUT:
                return 1f - (float) Math.pow(2.0, -10.0 * t);
            case EXPO_IN_OUT:
                return t < .5f
                        ? (float) Math.pow(2.0, 20.0 * t - 10.0) / 2f
                        : (2f - (float) Math.pow(2.0, -20.0 * t + 10.0)) / 2f;

            // Standard easeInBack/OutBack with c1 = 1.70158, whose extremum is
            // exactly -0.10 / +1.10 — matching overshootAmount().
            case BACK_IN:
                return back(t, 1.70158f, true);
            case BACK_OUT:
                return 1f - back(1f - t, 1.70158f, true);
            case BACK_IN_OUT: {
                float c2 = 1.70158f * 1.525f;
                if (t < .5f) {
                    float u = 2f * t;
                    return (u * u * ((c2 + 1f) * u - c2)) / 2f;
                }
                float u = 2f * t - 2f;
                return (u * u * ((c2 + 1f) * u + c2) + 2f) / 2f;
            }

            case LINEAR:
            default:
                return t;
        }
    }

    /** True for curves that intentionally leave the 0..1 output band (overshoot). */
    public boolean overshoots() { return this == BACK_IN || this == BACK_OUT || this == BACK_IN_OUT; }

    /**
     * Largest amount this curve can exceed its output band, as a fraction.
     * 0 for monotonic curves, ~0.10 for the Back family. SafeTransform uses it
     * so an overshoot never reveals an edge.
     */
    public float overshootAmount() { return overshoots() ? 0.10f : 0f; }

    /** easeInBack shape: c3*t^3 - c1*t^2 with c3 = c1 + 1. */
    private static float back(float t, float c1, boolean in) {
        float c3 = c1 + 1f;
        return in ? t * t * (c3 * t - c1)
                  : 1f + c3 * cube(t - 1f) + c1 * (t - 1f) * (t - 1f);
    }

    private static float cube(float v) { return v * v * v; }
    private static float pow4(float v) { float s = v * v; return s * s; }
    private static float pow5(float v) { return v * v * v * v * v; }

    /** Human label for the UI cards. */
    public String label() {
        switch (this) {
            case LINEAR: return "Linear";
            case EASE_IN: return "Ease In";
            case EASE_OUT: return "Ease Out";
            case EASE_IN_OUT: return "Ease In Out";
            case CUBIC: return "Cubic";
            case CUBIC_IN: return "Cubic In";
            case CUBIC_OUT: return "Cubic Out";
            case CUBIC_IN_OUT: return "Cubic In Out";
            case QUART_IN: return "Quart In";
            case QUART_OUT: return "Quart Out";
            case QUART_IN_OUT: return "Quart In Out";
            case QUINT: return "Quint";
            case QUINT_IN: return "Quint In";
            case QUINT_OUT: return "Quint Out";
            case QUINT_IN_OUT: return "Quint In Out";
            case SINE: return "Sine";
            case SINE_IN: return "Sine In";
            case SINE_OUT: return "Sine Out";
            case SINE_IN_OUT: return "Sine In Out";
            case EXPO: return "Expo";
            case EXPO_IN: return "Expo In";
            case EXPO_OUT: return "Expo Out";
            case EXPO_IN_OUT: return "Expo In Out";
            case BACK_IN: return "Back In";
            case BACK_OUT: return "Back Out";
            case BACK_IN_OUT: return "Back In Out";
            default: return name();
        }
    }

    /** Easing the user gets for a card when nothing is chosen; never null. */
    public static Easing orDefault(Easing e) { return e == null ? DEFAULT : e; }
}
