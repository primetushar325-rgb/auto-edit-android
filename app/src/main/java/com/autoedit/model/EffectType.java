package com.autoedit.model;

/**
 * Layered, non-destructive effects (spec §10, §45). A clip carries an ordered
 * list of these; each one is a colour-matrix pass and/or a post overlay drawn
 * by {@code EffectEngine}. Nothing here ever writes back to the source media.
 *
 * The original 24 constants keep their order so {@code ordinal()} stays stable
 * for the EffectEngine paint cache.
 */
public enum EffectType {
    NONE, BLUR, MOTION_BLUR, GLOW, SOFT_GLOW, BLOOM, VIGNETTE, FILM_GRAIN, SHARPEN,
    BRIGHTNESS, CONTRAST, SATURATION, TEMPERATURE, EXPOSURE, HIGHLIGHTS, SHADOWS,
    FADE, BLACK_WHITE, SEPIA, CINEMATIC, DREAM, VINTAGE, FILM, SOFT_FOCUS,
    // ---- added (spec §10) ----
    CINEMATIC_GLOW, DREAM_GLOW, GAUSSIAN_BLUR, DIRECTIONAL_BLUR, COLOR_BOOST,
    WARM, COOL, LIGHT_LEAK, LENS_FLARE, DUST, PARTICLES, FILM_FLICKER,
    CHROMATIC_ABERRATION, RGB_SHIFT, SUBTLE_NOISE, CINEMATIC_SHADOWS, HIGHLIGHT_GLOW
}
