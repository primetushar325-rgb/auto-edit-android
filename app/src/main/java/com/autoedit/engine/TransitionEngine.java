package com.autoedit.engine;

import com.autoedit.model.TransitionType;

/**
 * Transition math shared by live preview and export renderer. At mix p (0..1
 * across a junction) produces an INCOMING transform for the new clip and an
 * OUTGOING transform for the old clip, plus fadesThroughBackground (FADE) and
 * flashes (FLASH) flags. Reveal/wipe transitions use revealRadius +
 * circleReveal/wipeAxis/wipeSign so both renderers clip identically.
 */
public class TransitionEngine {

    public static class Transform {
        public float alpha = 1f;
        public float scale = 1f;
        public float dx = 0f;
        public float dy = 0f;
        public float revealRadius = 0f;
        public boolean circleReveal = false;
        public int wipeAxis = 0;
        public float wipeSign = 1f;
        public float blurAmount = 0f;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    public float outgoingAlpha(TransitionType t, float p) {
        switch (t) {
            case FADE:
            case CROSS_DISSOLVE:
                return 1f - p;
            case FLASH:
                return p < 0.5f ? 1f : 1f - (p - 0.5f) * 2f;
            case ZOOM:
            case ZOOM_BLUR:
            case BLUR_TRANSITION:
            case CINEMATIC_BLUR:
                return 1f - p * 0.6f;
            default:
                return 1f;
        }
    }

    public boolean fadesThroughBackground(TransitionType t) { return t == TransitionType.FADE; }
    public boolean flashes(TransitionType t) { return t == TransitionType.FLASH; }

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
            case SLIDE_LEFT:  case PUSH_LEFT:  tr.dx = -p; break;
            case SLIDE_RIGHT: case PUSH_RIGHT: tr.dx =  p; break;
            case SLIDE_UP:    case PUSH_UP:    tr.dy = -p; break;
            case SLIDE_DOWN:  case PUSH_DOWN:  tr.dy =  p; break;
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
                tr.alpha = 1f;
                break;
            case FADE:
            case CROSS_DISSOLVE:
                tr.alpha = p;
                break;
            case FLASH:
                tr.alpha = p < 0.5f ? p * 2f : 1f;
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
            case SLIDE_LEFT:
                tr.dx = 1f - p; tr.alpha = 1f; break;
            case SLIDE_RIGHT:
                tr.dx = -(1f - p); tr.alpha = 1f; break;
            case SLIDE_UP:
                tr.dy = p - 1f; tr.alpha = 1f; break;
            case SLIDE_DOWN:
                tr.dy = 1f - p; tr.alpha = 1f; break;
            case PUSH_LEFT:
                tr.dx = (1f - p) * 0.35f; tr.alpha = 1f; break;
            case PUSH_RIGHT:
                tr.dx = -(1f - p) * 0.35f; tr.alpha = 1f; break;
            case PUSH_UP:
                tr.dy = (1f - p) * 0.35f; tr.alpha = 1f; break;
            case PUSH_DOWN:
                tr.dy = -(1f - p) * 0.35f; tr.alpha = 1f; break;
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

    public static TransitionType[] rendered() {
        return new TransitionType[] {
                TransitionType.NONE, TransitionType.FADE, TransitionType.CROSS_DISSOLVE,
                TransitionType.ZOOM, TransitionType.ZOOM_BLUR,
                TransitionType.SLIDE_LEFT, TransitionType.SLIDE_RIGHT,
                TransitionType.SLIDE_UP, TransitionType.SLIDE_DOWN,
                TransitionType.PUSH_LEFT, TransitionType.PUSH_RIGHT,
                TransitionType.PUSH_UP, TransitionType.PUSH_DOWN,
                TransitionType.WIPE_LEFT, TransitionType.WIPE_RIGHT,
                TransitionType.WIPE_UP, TransitionType.WIPE_DOWN,
                TransitionType.CIRCLE_REVEAL, TransitionType.RADIAL_REVEAL,
                TransitionType.BLUR_TRANSITION, TransitionType.CINEMATIC_BLUR,
                TransitionType.FLASH
        };
    }

    public static String label(TransitionType t) {
        switch (t) {
            case NONE: return "None";
            case FADE: return "Fade";
            case CROSS_DISSOLVE: return "Cross Dissolve";
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
            case RADIAL_REVEAL: return "Radial Reveal";
            case BLUR_TRANSITION: return "Blur";
            case CINEMATIC_BLUR: return "Cinematic Blur";
            case FLASH: return "Flash";
            case SMOOTH_LIGHT: return "Smooth Light";
            default: return t.name();
        }
    }
}
