package com.autoedit.engine;

import com.autoedit.model.TransitionType;

/**
 * Transition math shared by live preview and export (spec §9, §16).
 *
 * At junction mix {@code p} (0 → the outgoing clip is still alone, 1 → the
 * incoming clip has fully taken over) this produces an INCOMING transform for
 * the new clip and an OUTGOING transform for the old one. A transition occupies
 * only the tail of the outgoing clip and the head of the incoming one — it is
 * never a second motion inside a clip (spec §46).
 *
 * Both renderers read exactly these fields, so preview and MP4 cannot diverge.
 */
public class TransitionEngine {

    public static class Transform {
        public float alpha = 1f;
        public float scale = 1f;
        public float dx = 0f;
        public float dy = 0f;
        /** 0..1 reveal mask radius; 0 means "no mask". */
        public float revealRadius = 0f;
        public boolean circleReveal = false;
        /** True when the mask shrinks instead of growing (Circle Close). */
        public boolean revealInverse = false;
        /** 0 = horizontal wipe, 1 = vertical. */
        public int wipeAxis = 0;
        public float wipeSign = 1f;
        /** Soft edge width for the reveal/wipe mask, 0..0.35 of the canvas. */
        public float feather = 0f;
        public float blurAmount = 0f;
        /** Horizontal blur bias for directional/whip blurs, -1..1. */
        public float blurDirection = 0f;
        /** Full-frame colour wash (dip to black/white, light leak, flash). */
        public int overlayColor = 0;
        public float overlayAlpha = 0f;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** True when the junction fades through a solid background (FADE, dips). */
    public boolean fadesThroughBackground(TransitionType t) {
        return t == TransitionType.FADE || t == TransitionType.DIP_TO_BLACK
                || t == TransitionType.DIP_TO_WHITE;
    }

    /** True when a full-frame flash is drawn at the midpoint. */
    public boolean flashes(TransitionType t) {
        return t == TransitionType.FLASH || t == TransitionType.WHIP
                || t == TransitionType.LIGHT_LEAK;
    }

    /** Colour of the midpoint flash, per transition. */
    public int flashColor(TransitionType t) {
        switch (t) {
            case LIGHT_LEAK: return 0xFFFFC46B;
            case WHIP:       return 0xFFFFFFFF;
            case FLASH:
            default:         return 0xFFFFFFFF;
        }
    }

    public float outgoingAlpha(TransitionType t, float p) {
        switch (t) {
            case FADE:
            case CROSS_DISSOLVE:
            case CINEMATIC_DISSOLVE:
            case SMOOTH_REVEAL:
                return 1f - p;
            case FLASH:
            case WHIP:
            case LIGHT_LEAK:
                return p < 0.5f ? 1f : 1f - (p - 0.5f) * 2f;
            case DIP_TO_BLACK:
            case DIP_TO_WHITE:
                return p < 0.5f ? 1f : 0f;
            case ZOOM:
            case ZOOM_BLUR:
            case BLUR_TRANSITION:
            case CINEMATIC_BLUR:
            case DIRECTIONAL_BLUR:
                return 1f - p * 0.6f;
            default:
                return 1f;
        }
    }

    public Transform outgoing(TransitionType t, float pRaw) {
        float p = clamp01(pRaw);
        Transform tr = new Transform();
        switch (t) {
            case ZOOM:
            case ZOOM_BLUR:
                tr.scale = 1f + 0.18f * p;
                tr.blurAmount = t == TransitionType.ZOOM_BLUR ? 0.5f * p : 0f;
                break;
            case BLUR_TRANSITION:
            case CINEMATIC_BLUR:
                tr.blurAmount = 0.6f * (1f - Math.abs(p - 0.5f) * 2f);
                tr.scale = 1f + 0.06f * p;
                break;
            case DIRECTIONAL_BLUR:
                tr.blurAmount = 0.7f * (1f - Math.abs(p - 0.5f) * 2f);
                tr.blurDirection = -1f;
                tr.scale = 1f + 0.04f * p;
                break;
            // A SLIDE leaves the outgoing clip in place while the incoming one
            // wipes over it, so the outgoing transform is identity.
            case SLIDE_LEFT: case SLIDE_RIGHT:
            case SLIDE_UP:   case SLIDE_DOWN:
                break;
            case WHIP:        tr.dx = -1.4f * p; tr.blurAmount = 0.8f * p; tr.blurDirection = -1f; break;
            // A PUSH carries the outgoing clip fully off while the incoming
            // clip enters from the opposite edge at exactly the same rate, so
            // the two panels always tile the canvas with no gap (spec §7).
            case PUSH_LEFT:   tr.dx = -p; break;
            case PUSH_RIGHT:  tr.dx =  p; break;
            case PUSH_UP:     tr.dy = -p; break;
            case PUSH_DOWN:   tr.dy =  p; break;
            case CINEMATIC_DISSOLVE:
                tr.scale = 1f + 0.04f * p;
                break;
            case DIP_TO_BLACK:
                tr.overlayColor = 0xFF000000;
                tr.overlayAlpha = p < 0.5f ? p * 2f : 1f;
                break;
            case DIP_TO_WHITE:
                tr.overlayColor = 0xFFFFFFFF;
                tr.overlayAlpha = p < 0.5f ? p * 2f : 1f;
                break;
            default: break;
        }
        tr.alpha = outgoingAlpha(t, p);
        return tr;
    }

    public Transform incoming(TransitionType t, float pRaw) {
        float p = clamp01(pRaw);
        Transform tr = new Transform();
        switch (t) {
            case NONE:
            case CUT:
                tr.alpha = 1f;
                break;
            case FADE:
            case CROSS_DISSOLVE:
                tr.alpha = p;
                break;
            case CINEMATIC_DISSOLVE:
                tr.alpha = p;
                tr.scale = 1.06f - 0.06f * p;
                break;
            case SMOOTH_REVEAL:
                tr.alpha = p;
                tr.revealRadius = p;
                tr.wipeAxis = 0;
                tr.wipeSign = 1f;
                tr.feather = 0.22f;
                break;
            case FLASH:
                tr.alpha = p < 0.5f ? p * 2f : 1f;
                break;
            case LIGHT_LEAK:
                tr.alpha = p < 0.5f ? p * 2f : 1f;
                tr.scale = 1.05f - 0.05f * p;
                break;
            case WHIP:
                tr.alpha = p < 0.5f ? p * 2f : 1f;
                tr.dx = (1f - p) * 1.4f;
                tr.blurAmount = 0.8f * (1f - p);
                tr.blurDirection = -1f;
                break;
            case DIP_TO_BLACK:
            case DIP_TO_WHITE:
                tr.alpha = 1f;
                tr.overlayColor = t == TransitionType.DIP_TO_WHITE ? 0xFFFFFFFF : 0xFF000000;
                tr.overlayAlpha = p < 0.5f ? 1f : (1f - p) * 2f;
                break;
            case ZOOM:
                tr.alpha = 1f;
                tr.scale = 1.25f - 0.25f * p;
                break;
            case ZOOM_BLUR:
                tr.alpha = p;
                tr.scale = 1.3f - 0.3f * p;
                tr.blurAmount = 0.5f * (1f - p);
                break;
            case BLUR_TRANSITION:
            case CINEMATIC_BLUR:
                tr.alpha = p;
                tr.blurAmount = 0.6f * (1f - Math.abs(p - 0.5f) * 2f);
                break;
            case DIRECTIONAL_BLUR:
                tr.alpha = p;
                tr.blurAmount = 0.7f * (1f - Math.abs(p - 0.5f) * 2f);
                tr.blurDirection = 1f;
                break;
            // Slides enter from the edge their name says, and always fully
            // cover the frame by p=1.
            case SLIDE_LEFT:                     // enters from the right
                tr.dx = 1f - p; tr.alpha = 1f; break;
            case SLIDE_RIGHT:                    // enters from the left
                tr.dx = -(1f - p); tr.alpha = 1f; break;
            case SLIDE_UP:                       // enters from the bottom
                tr.dy = 1f - p; tr.alpha = 1f; break;
            case SLIDE_DOWN:                     // enters from the top
                tr.dy = p - 1f; tr.alpha = 1f; break;
            // A push abuts the outgoing panel exactly, so the two tile.
            case PUSH_LEFT:
                tr.dx = 1f - p; tr.alpha = 1f; break;
            case PUSH_RIGHT:
                tr.dx = p - 1f; tr.alpha = 1f; break;
            case PUSH_UP:
                tr.dy = 1f - p; tr.alpha = 1f; break;
            case PUSH_DOWN:
                tr.dy = p - 1f; tr.alpha = 1f; break;
            case WIPE_LEFT:
                tr.revealRadius = p; tr.wipeAxis = 0; tr.wipeSign = 1f; tr.alpha = 1f; break;
            case WIPE_RIGHT:
                tr.revealRadius = p; tr.wipeAxis = 0; tr.wipeSign = -1f; tr.alpha = 1f; break;
            case WIPE_UP:
                tr.revealRadius = p; tr.wipeAxis = 1; tr.wipeSign = 1f; tr.alpha = 1f; break;
            case WIPE_DOWN:
                tr.revealRadius = p; tr.wipeAxis = 1; tr.wipeSign = -1f; tr.alpha = 1f; break;
            case CIRCLE_REVEAL:
                tr.revealRadius = p; tr.circleReveal = true; tr.alpha = 1f; break;
            case CIRCLE_CLOSE:
                tr.revealRadius = 1f - p; tr.circleReveal = true; tr.revealInverse = true; tr.alpha = 1f; break;
            case RADIAL_REVEAL:
                tr.revealRadius = p; tr.circleReveal = true; tr.scale = 1.15f - 0.15f * p; tr.alpha = 1f; break;
            case SMOOTH_LIGHT:
                tr.alpha = p; tr.scale = 1.08f - 0.08f * p; break;
            default:
                tr.alpha = p;
                break;
        }
        return tr;
    }

    /**
     * Transitions that the renderers actually implement. The card browser only
     * ever offers these, so every visible option is a real, rendered transition
     * (spec §57 — no fake UI options).
     */
    public static TransitionType[] rendered() {
        return new TransitionType[] {
                TransitionType.NONE, TransitionType.CUT,
                TransitionType.FADE, TransitionType.CROSS_DISSOLVE, TransitionType.CINEMATIC_DISSOLVE,
                TransitionType.DIP_TO_BLACK, TransitionType.DIP_TO_WHITE,
                TransitionType.SLIDE_LEFT, TransitionType.SLIDE_RIGHT,
                TransitionType.SLIDE_UP, TransitionType.SLIDE_DOWN,
                TransitionType.PUSH_LEFT, TransitionType.PUSH_RIGHT,
                TransitionType.PUSH_UP, TransitionType.PUSH_DOWN,
                TransitionType.ZOOM, TransitionType.ZOOM_BLUR,
                TransitionType.BLUR_TRANSITION, TransitionType.CINEMATIC_BLUR,
                TransitionType.DIRECTIONAL_BLUR,
                TransitionType.WIPE_LEFT, TransitionType.WIPE_RIGHT,
                TransitionType.WIPE_UP, TransitionType.WIPE_DOWN,
                TransitionType.CIRCLE_REVEAL, TransitionType.CIRCLE_CLOSE,
                TransitionType.RADIAL_REVEAL, TransitionType.SMOOTH_REVEAL,
                TransitionType.SMOOTH_LIGHT, TransitionType.LIGHT_LEAK,
                TransitionType.FLASH, TransitionType.WHIP
        };
    }

    public static String label(TransitionType t) {
        if (t == null) return "None";
        switch (t) {
            case NONE: return "None";
            case CUT: return "Cut";
            case FADE: return "Fade";
            case CROSS_DISSOLVE: return "Cross Dissolve";
            case CINEMATIC_DISSOLVE: return "Cinematic Dissolve";
            case DIP_TO_BLACK: return "Dip to Black";
            case DIP_TO_WHITE: return "Dip to White";
            case ZOOM: return "Zoom";
            case ZOOM_BLUR: return "Zoom Blur";
            case SLIDE_LEFT: return "Slide Left";
            case SLIDE_RIGHT: return "Slide Right";
            case SLIDE_UP: return "Slide Up";
            case SLIDE_DOWN: return "Slide Down";
            case PUSH_LEFT: return "Push Left";
            case PUSH_RIGHT: return "Push Right";
            case PUSH_UP: return "Push Up";
            case PUSH_DOWN: return "Push Down";
            case WIPE_LEFT: return "Wipe Left";
            case WIPE_RIGHT: return "Wipe Right";
            case WIPE_UP: return "Wipe Up";
            case WIPE_DOWN: return "Wipe Down";
            case CIRCLE_REVEAL: return "Circle Reveal";
            case CIRCLE_CLOSE: return "Circle Close";
            case RADIAL_REVEAL: return "Radial Reveal";
            case SMOOTH_REVEAL: return "Smooth Reveal";
            case BLUR_TRANSITION: return "Blur";
            case CINEMATIC_BLUR: return "Cinematic Blur";
            case DIRECTIONAL_BLUR: return "Directional Blur";
            case FLASH: return "Flash";
            case WHIP: return "Whip";
            case LIGHT_LEAK: return "Light Leak";
            case SMOOTH_LIGHT: return "Smooth Light";
            default: return t.name();
        }
    }
}
