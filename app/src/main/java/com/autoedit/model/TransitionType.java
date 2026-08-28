package com.autoedit.model;

/**
 * Transitions applied BETWEEN two clips (spec §9, §46). A transition never
 * becomes a second motion inside a clip: it only occupies the tail of the
 * outgoing clip and the head of the incoming one.
 *
 * The first 23 constants keep their order; {@code NONE} means "no transition"
 * and is the default for a new project.
 */
public enum TransitionType {
    NONE, FADE, CROSS_DISSOLVE, ZOOM, ZOOM_BLUR, SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN,
    PUSH_LEFT, PUSH_RIGHT, PUSH_UP, PUSH_DOWN, WIPE_LEFT, WIPE_RIGHT, WIPE_UP, WIPE_DOWN,
    CIRCLE_REVEAL, RADIAL_REVEAL, BLUR_TRANSITION, FLASH, SMOOTH_LIGHT, CINEMATIC_BLUR,
    // ---- added (spec §9) ----
    CUT, DIP_TO_BLACK, DIP_TO_WHITE, LIGHT_LEAK, WHIP, DIRECTIONAL_BLUR,
    CIRCLE_CLOSE, SMOOTH_REVEAL, CINEMATIC_DISSOLVE
}
