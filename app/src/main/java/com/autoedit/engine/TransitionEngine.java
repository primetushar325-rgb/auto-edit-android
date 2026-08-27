package com.autoedit.engine;

import com.autoedit.model.Easing;
import com.autoedit.model.TransitionType;

/**
 * Single source of truth for transition math. Used by BOTH the live preview
 * (PreviewView) and the export frame renderer (FrameRenderer) so that what the
 * user sees in the preview is exactly what the exported video contains.
 *
 * A transition runs during the last `transitionDurationSec` of the outgoing
 * clip and reveals the incoming clip. All transforms are expressed in
 * fractions of the canvas so they are resolution independent.
 */
public class TransitionEngine {

    /** Extra transform + opacity applied to the incoming clip during a transition. */
    public static class Transform {
        public float alpha = 1f;
        public float scale = 1f;
        public float dx = 0f; // fraction of canvas width
        public float dy = 0f; // fraction of canvas height
    }

    /** Extra opacity applied to the outgoing clip. 1f = unchanged. */
    public float outgoingAlpha(TransitionType t, float progress) {
        if (t == null) return 1f;
        switch (t) {
            case FADE: return 1f - ease(progress); // outgoing fades to background
            case NONE: return 1f;
            default: return 1f;
        }
    }

    /** True when the background should be drawn between outgoing and incoming clips. */
    public boolean fadesThroughBackground(TransitionType t) {
        return t == TransitionType.FADE;
    }

    /**
     * Transform for the incoming clip at raw progress p (0..1).
     * Internally eased so both renderers stay in sync.
     * Unknown/future types gracefully fall back to a plain cross-dissolve.
     */
    public Transform incoming(TransitionType t, float pRaw) {
        Transform tr = new Transform();
        if (t == null || t == TransitionType.NONE) return tr;
        float p = ease(pRaw);
        switch (t) {
            case FADE:
            case CROSS_DISSOLVE:
            case SMOOTH_LIGHT:
                tr.alpha = p;
                break;
            case ZOOM:
            case ZOOM_BLUR:
                tr.alpha = 1f;
                tr.scale = 1.35f - 0.35f * p; // zoom in over the outgoing clip
                break;
            case SLIDE_LEFT:
            case PUSH_LEFT:
                tr.dx = 1f - p; // enters from the right, moves left
                break;
            case SLIDE_RIGHT:
            case PUSH_RIGHT:
                tr.dx = p - 1f; // enters from the left, moves right
                break;
            case SLIDE_UP:
            case PUSH_UP:
                tr.dy = p - 1f; // enters from the bottom
                break;
            case SLIDE_DOWN:
            case PUSH_DOWN:
                tr.dy = 1f - p; // enters from the top
                break;
            default:
                tr.alpha = p; // safe fallback: dissolve
                break;
        }
        return tr;
    }

    /** Transitions that are actually rendered (exposed in the UI). */
    public static TransitionType[] rendered() {
        return new TransitionType[]{
                TransitionType.NONE,
                TransitionType.FADE,
                TransitionType.CROSS_DISSOLVE,
                TransitionType.ZOOM,
                TransitionType.SLIDE_LEFT,
                TransitionType.SLIDE_RIGHT,
                TransitionType.SLIDE_UP,
                TransitionType.SLIDE_DOWN
        };
    }

    public static String label(TransitionType t) {
        switch (t) {
            case NONE: return "None";
            case FADE: return "Fade (through black)";
            case CROSS_DISSOLVE: return "Cross Dissolve";
            case ZOOM: return "Zoom";
            case SLIDE_LEFT: return "Slide Left";
            case SLIDE_RIGHT: return "Slide Right";
            case SLIDE_UP: return "Slide Up";
            case SLIDE_DOWN: return "Slide Down";
            default: return t.name();
        }
    }

    private static float ease(float p) {
        return Easing.EASE_IN_OUT.apply(p);
    }
}
