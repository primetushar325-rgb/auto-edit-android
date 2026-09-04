package com.autoedit.engine;

import com.autoedit.model.TransitionPreset;
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
        // ---- CapCut library families (v1.7) ----
        public float rotZ = 0f;            // 2D spin (degrees)
        public float rotX = 0f;            // 3D pitch (perspective)
        public float rotY = 0f;            // 3D yaw (perspective)
        public float shakeX = 0f, shakeY = 0f;   // frame jitter (normalised)
        public float chroma = 0f;          // RGB-split / chromatic aberration 0..1
        public float grain = 0f;           // film grain / noise 0..1
        public float squeezeX = 1f, squeezeY = 1f; // single-axis scale (squeeze/elastic)
        public float strip = 0f;           // glitch tear band 0..1
        /** Shape variant for shape reveals: heart/star/diamond/triangle/hexagon/rect/roundrect. */
        public String shape = "";
        public float seed = 0f;            // deterministic pseudo-random from mix
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static float ease(float p) { return p * p * (3f - 2f * p); }
    private static float easeOut(float p) { return 1f - (1f - p) * (1f - p); }
    private static float bell(float p) { return 1f - Math.abs(p - 0.5f) * 2f; }
    private static float tri(float p) { return 1f - Math.abs((p % 1f) - 0.5f) * 2f; }
    private static float hash(float n) { float s = (float) Math.sin(n * 127.1f) * 43758.5453f; return s - (float) Math.floor(s); }
    private void jitter(Transform tr, float p, float amp) {
        tr.shakeX = (hash(p * 91f) - 0.5f) * 2f * amp;
        tr.shakeY = (hash(p * 57f + 11f) - 0.5f) * 2f * amp;
    }

    // ---- preset-aware overloads (library); resolve direction/tint/intensity ----
    public Transform outgoing(TransitionPreset pr, float pRaw) {
        if (pr == null) return outgoing(TransitionType.CROSS_DISSOLVE, pRaw);
        Transform t = outgoing(pr.type, pRaw);
        applyDirection(t, pr);
        applyPreset(t, pr, pRaw);
        return t;
    }
    public Transform incoming(TransitionPreset pr, float pRaw) {
        if (pr == null) return incoming(TransitionType.CROSS_DISSOLVE, pRaw);
        Transform t = incoming(pr.type, pRaw);
        applyDirection(t, pr);
        applyPreset(t, pr, pRaw);
        return t;
    }

    private void applyDirection(Transform tr, TransitionPreset pr) {
        String d = pr.direction == null ? "" : pr.direction;
        switch (d) {
            case "right": tr.dx = -tr.dx; tr.wipeSign = -tr.wipeSign; tr.blurDirection = -tr.blurDirection; break;
            case "up":    tr.dy = -tr.dy; tr.wipeSign = -tr.wipeSign; break;
            case "down":  tr.dy = -tr.dy; tr.wipeSign = -tr.wipeSign; break;
            case "horizontal": tr.rotX = -tr.rotX; break;
            case "vertical":   tr.rotY = -tr.rotY; break;
            case "cw":  tr.rotZ = Math.abs(tr.rotZ); break;
            case "ccw": tr.rotZ = -Math.abs(tr.rotZ); break;
            case "out": tr.revealRadius = 1f - tr.revealRadius; tr.revealInverse = true; break;
            default: break;
        }
        if ((d.equals("up") || d.equals("down")) && tr.wipeAxis == 0 && tr.revealRadius > 0f) tr.wipeAxis = 1;
        if ((d.equals("left") || d.equals("right")) && tr.wipeAxis == 2) { /* diagonal stays */ }
    }

    private void applyPreset(Transform tr, TransitionPreset pr, float p) {
        if (pr.type == TransitionType.SHAPE_REVEAL && pr.direction != null && !pr.direction.isEmpty()) {
            tr.shape = pr.direction;
        }
        float k = pr.intensity <= 0f ? 1f : Math.max(0.2f, pr.intensity / 0.6f);
        tr.chroma = clamp01(tr.chroma * k);
        tr.blurAmount = clamp01(tr.blurAmount * (0.5f + 0.5f * k));
        tr.grain = clamp01(tr.grain * k);
        tr.rotZ *= (0.5f + 0.5f * k);
        tr.rotX *= k; tr.rotY *= k;
        // tinted / coloured overlays for flash/light families
        if (pr.type.isOverlay() && tr.overlayColor != 0) {
            if (pr.tint != 0 && tr.overlayColor == 0xFFFFFFFF) tr.overlayColor = pr.tint;
            if (pr.intensity > 0f) tr.overlayAlpha = clamp01(tr.overlayAlpha * Math.max(0.3f, pr.intensity));
        }
    }

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
            default:
                fillFamilyOutgoing(tr, t, p);
                break;
        }
        tr.alpha = outgoingAlpha(t, p);
        return tr;
    }

    /** CapCut library renderer math for the OUTGOING (old) clip. */
    private void fillFamilyOutgoing(Transform tr, TransitionType t, float pRaw) {
        float p = clamp01(pRaw), e = ease(p);
        tr.seed = hash(pRaw * 31f + 7f);
        switch (t) {
            case FADE_SCAN: case FADE_DIRECTIONAL: case FADE_WIPE: case GRADUAL_FADE:
            case CINEMATIC_FADE: case VINTAGE_FADE:
                tr.alpha = 1f - easeOut(p); break;
            case FAKE_ZOOM: tr.scale = 1f + 0.35f * bell(p); break;
            case ZOOM_IN: tr.scale = 1f + 0.25f * e; break;
            case ZOOM_OUT: tr.scale = 1f - 0.2f * e; break;
            case ZOOM_SWITCH: tr.scale = 1f + 0.22f * bell(p); break;
            case MIRROR_ZOOM: tr.scale = 1f + 0.2f * e; tr.rotY = 90f * e; tr.alpha = e < .5f ? 1f : 0f; return;
            case QUAKY_ZOOM: tr.scale = 1f + 0.2f * e; jitter(tr, p, 0.04f); break;
            case DIAGONAL_WIPE: case CORNER_WIPE: case LIQUID_WIPE: tr.alpha = 1f; break;
            case COVER: tr.alpha = 1f; break;
            case REVEAL_SLIDE: tr.dx = -0.6f * e; break;
            case SPLIT_WIPE: case CENTER_WIPE: case CINEMATIC_WIPE: case COMPARISON: tr.alpha = 1f; break;
            case SHAPE_REVEAL: case FUZZY_CIRCLE: case MESSY_CIRCLES: tr.alpha = 1f; break;
            case TELEPORT_SHAKE: jitter(tr, p, 0.06f); tr.scale = 1f + 0.15f * bell(p); break;
            case SPIN_SLAM: tr.rotZ = 120f * e; tr.scale = 1f + 0.3f * bell(p); break;
            case SQUEEZE_SNAP: case COMPRESSION_SPIN: tr.squeezeX = 1f - 0.5f * bell(p);
                if (t == TransitionType.COMPRESSION_SPIN) tr.rotZ = 90f * e; break;
            case ASH_SPREAD: case PETAL_WIND: tr.scale = 1f + 0.15f * e; break;
            case DRAG_SWITCH: tr.dx = -e; tr.squeezeX = 1f - 0.3f * bell(p); break;

            case CAMERA_PUSH: tr.scale = 1f + 0.4f * e; break;
            case CAMERA_PULL: tr.scale = 1f - 0.3f * e; break;
            case ZOOM_SNAP: tr.scale = 1f + 0.5f * bell(p); break;
            case DOLLY_ZOOM: tr.scale = 1f + 0.5f * e; tr.squeezeY = 1f + 0.12f * e; break;
            case CAMERA_SHAKE: case FILM_SHAKE: jitter(tr, p, t == TransitionType.CAMERA_SHAKE ? 0.05f : 0.035f);
                if (t == TransitionType.FILM_SHAKE) tr.grain = 0.3f; break;
            case SHAKE_SHIFT: jitter(tr, p, 0.06f); tr.dx = -0.8f * e; break;
            case CAMERA_ROTATE: tr.rotZ = 90f * e; break;
            case CAMERA_ROLL: tr.rotZ = 180f * e; tr.scale = 1f - 0.2f * e; break;
            case ORBIT_SPIN: tr.rotY = 180f * e; tr.scale = 1f - 0.3f * e; tr.alpha = e < .5f ? 1f : 0f; return;

            case CUBE_3D: tr.rotY = 90f * e; tr.dx = 0.5f * e; tr.alpha = e < .5f ? 1f : 0f; return;
            case FLIP_3D: tr.rotX = 90f * e; tr.alpha = e < .5f ? 1f : 0f; return;
            case ROTATE_3D: tr.rotY = 180f * e; tr.rotZ = 90f * e; tr.alpha = e < .5f ? 1f : 0f; return;
            case PAGE_TURN_3D: tr.rotY = 100f * e; tr.dx = 0.3f * e; tr.alpha = e < .6f ? 1f : 0f; return;
            case CARD_3D: tr.rotX = 90f * e; tr.scale = 1f - 0.2f * e; tr.alpha = e < .5f ? 1f : 0f; return;
            case DOOR_3D: tr.rotY = 100f * e; tr.alpha = e < .6f ? 1f : 0f; return;
            case FOLD_3D: tr.rotX = 90f * e; tr.squeezeY = 1f - 0.5f * e; tr.alpha = e < .5f ? 1f : 0f; return;
            case TUNNEL_3D: case WISP_PORTAL: tr.scale = 1f - 0.6f * e; tr.rotZ = 20f * e;
                if (t == TransitionType.WISP_PORTAL) tr.blurAmount = 0.4f * bell(p); break;
            case CAROUSEL_3D: tr.rotY = 120f * e; tr.dx = 0.4f * e; tr.alpha = e < .5f ? 1f : 0f; return;
            case PARALLAX_3D: tr.rotY = 45f * e; tr.dx = 0.25f * e; break;
            case DEPTH_ZOOM_3D: case DARK_SCALE: tr.scale = 1f - 0.5f * e;
                if (t == TransitionType.DEPTH_ZOOM_3D) tr.rotY = 25f * e; break;

            case MOTION_BLUR_X: case BLUR_PUSH:
                tr.blurAmount = 0.9f * bell(p); tr.dx = -0.4f * e; tr.blurDirection = -1f; break;
            case RADIAL_BLUR: tr.blurAmount = 0.9f * bell(p); tr.rotZ = 15f * bell(p); tr.scale = 1f + 0.1f * bell(p); break;
            case ZOOM_BLUR_X: tr.blurAmount = 0.9f * bell(p); tr.scale = 1f + 0.25f * e; break;
            case SOFT_BLUR: case DREAM_BLUR: case DEFOCUS: tr.blurAmount = 0.8f * bell(p); break;
            case FAST_BLUR: tr.blurAmount = bell(p); tr.dx = -0.6f * e; tr.blurDirection = -1f; break;
            case BLUR_SWITCH: tr.blurAmount = 0.8f * bell(p); tr.scale = 1f + 0.2f * bell(p); break;
            case BLUR_FADE: tr.blurAmount = 0.7f * bell(p); break;
            case BLUR_WIPE: tr.alpha = 1f; tr.blurAmount = 0.6f * bell(p); break;
            case BLUR_SPIN: tr.blurAmount = 0.7f * bell(p); tr.rotZ = 60f * e; break;

            case RGB_SPLIT: case RGB_WAVE: case CHROME_WAVE: tr.chroma = 0.9f * bell(p);
                jitter(tr, p, 0.02f); if (t == TransitionType.CHROME_WAVE) tr.squeezeX = 1f + 0.1f * (float) Math.sin(p * 18.8f); break;
            case GLITCH: case PIXEL_GLITCH: tr.chroma = 0.7f * bell(p); tr.strip = bell(p); jitter(tr, p, 0.05f); break;
            case VHS_GLITCH: tr.chroma = 0.5f * bell(p); tr.strip = 0.8f * bell(p); jitter(tr, p, 0.03f); break;
            case SCANLINE_GLITCH: case DIGITAL_NOISE: tr.grain = (t == TransitionType.DIGITAL_NOISE ? 0.9f : 0.6f) * bell(p);
                if (t == TransitionType.SCANLINE_GLITCH) tr.strip = 0.5f * bell(p); else tr.chroma = 0.3f * bell(p); break;
            case TEAR_H: tr.strip = bell(p); tr.dx = 0.08f * tri(p * 6f); break;
            case TEAR_V: tr.strip = bell(p); tr.dy = 0.06f * tri(p * 6f); break;
            case PIXEL_STRETCH: tr.strip = bell(p); tr.squeezeX = 1f + 0.4f * bell(p); break;

            case WHITE_FLASH: case CAMERA_FLASH: case GLOW_FLASH: case NEON_FLASH:
            case FILM_FLASH: case SOFT_FLASH: case SUNSET_FLASH: case LENS_FLARE:
            case LIGHT_SWEEP: case FLASH_WIPE: case GLARE:
                tr.alpha = p < 0.4f ? 1f : 1f - (p - 0.4f) / 0.6f; break;
            case BLACK_FLASH: case BLACKOUT_SWIPE:
                tr.alpha = p < 0.5f ? 1f : 1f - (p - 0.5f) * 2f; break;
            case STROBE: tr.alpha = tri(p * 6f) > 0.5f ? 1f : 0.4f; break;

            case FILM_BURN: tr.grain = 0.4f * bell(p); break;
            case FILM_ROLL: tr.dy = -e; tr.rotZ = 8f * e; break;
            case FILM_GRAIN_X: case DUST_X: case SCRATCH_X: case DUST_FLURRY:
                tr.grain = 0.7f * bell(p); break;
            case CINEMATIC_ZOOM: tr.scale = 1f + 0.3f * e; tr.blurAmount = 0.3f * bell(p); break;
            case CINEMATIC_PUSH: tr.dx = -0.8f * e; tr.scale = 1f + 0.1f * e; break;
            case FILM_ERASE: tr.grain = 0.8f * bell(p); break;

            case LIQUID_STRETCH: tr.squeezeX = 1f - 0.4f * bell(p); tr.dx = -0.3f * e; break;
            case RIPPLE_X: tr.scale = 1f + 0.08f * (float) Math.sin(p * 18.8f); break;
            case WAVE_WARP: tr.squeezeX = 1f + 0.12f * (float) Math.sin(p * 12.6f); jitter(tr, p, 0.015f); break;
            case LENS_WARP: tr.scale = 1f + 0.25f * bell(p); tr.blurAmount = 0.2f * bell(p); break;
            case BULGE: case BULGE_BLING: tr.scale = 1f + 0.35f * bell(p); tr.squeezeX = 1f + 0.15f * bell(p);
                if (t == TransitionType.BULGE_BLING) tr.grain = 0.5f * tri(p * 10f); break;
            case PINCH: tr.scale = 1f - 0.3f * bell(p); break;
            case SWIRL: tr.rotZ = 120f * bell(p); tr.scale = 1f - 0.2f * bell(p); break;
            case TWIST: tr.rotZ = 90f * e; tr.squeezeX = 1f - 0.3f * bell(p); break;
            case HEAT_WAVE: tr.squeezeY = 1f + 0.08f * (float) Math.sin(p * 25.1f); tr.blurAmount = 0.15f * bell(p); break;
            case ELASTIC: tr.scale = 1f + 0.25f * Math.abs((float) Math.sin(p * 12.6f)) * (1f - p); break;
            case MELT: tr.squeezeY = 1f - 0.5f * e; tr.dy = 0.3f * e; break;

            case TWINKLE_ZOOM: tr.scale = 1f + 0.3f * bell(p); tr.grain = 0.5f * tri(p * 8f); break;
            case GALLERY_SLIDE: tr.dx = -e; break;
            case GALLERY_ZOOM: case RANDOM_GALLERY: tr.scale = 1f - 0.3f * e;
                if (t == TransitionType.RANDOM_GALLERY) jitter(tr, p, 0.03f); break;
            // Multi-panel gallery families (v1.8): the full draw happens in
            // TransitionDraw.drawGallery (both clip bitmaps). These in/out
            // transforms are the safe crossfade fallback for any renderer
            // that takes the single-transform path, so alpha stays valid.
            case GALLERY_MOTION: case GALLERY_WALL: case GALLERY_WALL_V: case GALLERY_SCROLL_3D:
            case GALLERY_ALIGN: case GALLERY_SOCIAL: case GALLERY_FRAME: case GALLERY_CAM:
            case GALLERY_SPACE: case GALLERY_PREVIEW: case GALLERY_GRID: case GALLERY_MESSY:
            case GALLERY_MORPH: case GALLERY_CAROUSEL: case GALLERY_COLUMNS:
                tr.alpha = 1f - p;
                tr.scale = 1f + 0.06f * p;
                break;
            case WILDFIRE_SCAN: tr.alpha = 1f; tr.overlayColor = 0xFFFF5A2C; tr.overlayAlpha = 0.5f * bell(p); break;
            default: break;
        }
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
                fillFamilyIncoming(tr, t, p);
                break;
        }
        return tr;
    }

    /** CapCut library renderer math for the INCOMING (new) clip. */
    private void fillFamilyIncoming(Transform tr, TransitionType t, float pRaw) {
        float p = clamp01(pRaw), e = ease(p);
        tr.seed = hash(pRaw * 53f + 3f);
        switch (t) {
            case FADE_SCAN: tr.alpha = e; tr.revealRadius = e; tr.wipeAxis = 0; tr.wipeSign = 1f; break;
            case FADE_DIRECTIONAL: tr.alpha = e; tr.dx = 0.2f * (1f - e); break;
            case FADE_WIPE: case SMOOTH_REVEAL: tr.alpha = e; tr.revealRadius = e; tr.wipeAxis = 0; tr.wipeSign = 1f; break;
            case GRADUAL_FADE: tr.alpha = e; tr.scale = 1.05f - 0.05f * e; break;
            case CINEMATIC_FADE: tr.alpha = easeOut(p); tr.scale = 1.1f - 0.1f * e; break;
            case VINTAGE_FADE: tr.alpha = e; tr.grain = 0.3f * bell(p); break;
            case FAKE_ZOOM: tr.alpha = e; tr.scale = 1.4f - 0.4f * e; break;
            case ZOOM_IN: tr.alpha = e; tr.scale = 0.8f + 0.2f * e; break;
            case ZOOM_OUT: tr.alpha = e; tr.scale = 1.3f - 0.3f * e; break;
            case ZOOM_SWITCH: case CINEMATIC_ZOOM: tr.alpha = 1f; tr.scale = (t == TransitionType.ZOOM_SWITCH ? 1.35f : 1.3f) - 0.35f * e;
                if (t == TransitionType.CINEMATIC_ZOOM) tr.blurAmount = 0.3f * bell(p); break;
            case MIRROR_ZOOM: tr.rotY = -90f + 90f * e; tr.alpha = e > .5f ? 1f : 0f; tr.scale = 1.1f - 0.1f * e; return;
            case QUAKY_ZOOM: tr.alpha = e; tr.scale = 1.3f - 0.3f * e; jitter(tr, p, 0.04f); break;
            case DIAGONAL_WIPE: tr.revealRadius = e; tr.wipeAxis = 2; tr.wipeSign = 1f; tr.alpha = 1f; break;
            case CORNER_WIPE: tr.revealRadius = e; tr.wipeAxis = 2; tr.wipeSign = -1f; tr.alpha = 1f; break;
            case LIQUID_WIPE: tr.revealRadius = e; tr.wipeAxis = 0; tr.wipeSign = 1f; tr.squeezeX = 0.8f + 0.2f * e; tr.alpha = 1f; break;
            case COVER: tr.dx = 1f - e; tr.alpha = 1f; break;
            case REVEAL_SLIDE: tr.revealRadius = e; tr.wipeAxis = 0; tr.wipeSign = 1f; tr.dx = 0.2f * (1f - e); tr.alpha = 1f; break;
            case SPLIT_WIPE: tr.revealRadius = e; tr.wipeAxis = 1; tr.wipeSign = 1f; tr.alpha = 1f; break;
            case LINEAR_WIPE: tr.revealRadius = e; tr.wipeAxis = 0; tr.wipeSign = 1f; tr.feather = 0.15f; tr.alpha = 1f; break;
            case FEATHER_MASK: tr.revealRadius = e; tr.circleReveal = true; tr.feather = 0.35f; tr.alpha = 1f; break;
            case CENTER_WIPE: tr.revealRadius = e; tr.wipeAxis = 1; tr.wipeSign = 0f; tr.alpha = 1f; break;
            case CINEMATIC_WIPE: case COMPARISON: tr.revealRadius = e; tr.wipeAxis = 0; tr.wipeSign = 1f; tr.alpha = 1f; break;
            case SHAPE_REVEAL: tr.revealRadius = e; tr.circleReveal = true; tr.shape = "shape"; tr.alpha = 1f; break;
            case FUZZY_CIRCLE: tr.revealRadius = e; tr.circleReveal = true; tr.blurAmount = 0.25f; tr.alpha = 1f; break;
            case MESSY_CIRCLES: tr.revealRadius = e; tr.circleReveal = true; tr.scale = 1f + 0.1f * tri(p * 5f); tr.alpha = 1f; break;
            case TELEPORT_SHAKE: tr.alpha = e; jitter(tr, p, 0.06f); tr.scale = 1.25f - 0.25f * e; break;
            case SPIN_SLAM: tr.alpha = e; tr.rotZ = -120f + 120f * e; tr.scale = 1.3f - 0.3f * e; break;
            case SQUEEZE_SNAP: tr.alpha = e; tr.squeezeX = 0.5f + 0.5f * e; break;
            case COMPRESSION_SPIN: tr.alpha = e; tr.rotZ = -90f + 90f * e; tr.squeezeX = 0.6f + 0.4f * e; break;
            case ASH_SPREAD: case PETAL_WIND: tr.alpha = e; tr.revealRadius = e; tr.circleReveal = true; tr.grain = 0.4f * bell(p); break;
            case DRAG_SWITCH: tr.dx = 1f - e; tr.squeezeX = 0.7f + 0.3f * e; tr.alpha = 1f; break;

            case CAMERA_PUSH: case ZOOM_CAMERA: case FAST_PUSH: tr.alpha = 1f; tr.scale = 1.4f - 0.4f * e;
                tr.blurAmount = (t == TransitionType.FAST_PUSH ? 0.6f : 0.3f) * bell(p); tr.dx = (t == TransitionType.FAST_PUSH ? 0.5f : 0f) * (1f - e); break;
            case CAMERA_PULL: case FAST_PULL: tr.alpha = 1f; tr.scale = 0.7f + 0.3f * e;
                tr.blurAmount = (t == TransitionType.FAST_PULL ? 0.5f : 0.2f) * bell(p); break;
            case WHIP_PAN: tr.alpha = e; tr.dx = 1f - e; tr.blurAmount = 0.9f * bell(p); tr.blurDirection = 1f; tr.scale = 1.08f - 0.08f * e; break;
            case ZOOM_SNAP: tr.alpha = 1f; tr.scale = 1.5f - 0.5f * e; break;
            case DOLLY_ZOOM: tr.alpha = e; tr.scale = 0.6f + 0.4f * e; tr.squeezeY = 1.12f - 0.12f * e; break;
            case CAMERA_SHAKE: case FILM_SHAKE: tr.alpha = e; jitter(tr, p, t == TransitionType.CAMERA_SHAKE ? 0.05f : 0.035f);
                if (t == TransitionType.FILM_SHAKE) tr.grain = 0.3f; break;
            case SHAKE_SHIFT: tr.alpha = 1f; jitter(tr, p, 0.06f); tr.dx = 0.8f * (1f - e); break;
            case CAMERA_ROTATE: tr.alpha = e; tr.rotZ = -90f + 90f * e; break;
            case CAMERA_ROLL: tr.alpha = e; tr.rotZ = -180f + 180f * e; tr.scale = 0.8f + 0.2f * e; break;
            case ORBIT_SPIN: tr.rotY = -180f + 180f * e; tr.scale = 0.7f + 0.3f * e; tr.alpha = e > .5f ? 1f : 0f; return;

            case CUBE_3D: tr.rotY = -90f + 90f * e; tr.dx = -0.5f + 0.5f * e; tr.alpha = e > .5f ? 1f : 0f; return;
            case FLIP_3D: tr.rotX = -90f + 90f * e; tr.alpha = e > .5f ? 1f : 0f; return;
            case ROTATE_3D: tr.rotY = -180f + 180f * e; tr.rotZ = -90f + 90f * e; tr.alpha = e > .5f ? 1f : 0f; return;
            case PAGE_TURN_3D: tr.rotY = -80f + 80f * e; tr.dx = -0.3f + 0.3f * e; tr.alpha = e > .4f ? 1f : 0f; return;
            case CARD_3D: tr.rotX = -90f + 90f * e; tr.scale = 0.8f + 0.2f * e; tr.alpha = e > .5f ? 1f : 0f; return;
            case DOOR_3D: tr.rotY = -80f + 80f * e; tr.alpha = e > .4f ? 1f : 0f; return;
            case FOLD_3D: tr.rotX = -90f + 90f * e; tr.squeezeY = 0.5f + 0.5f * e; tr.alpha = e > .5f ? 1f : 0f; return;
            case TUNNEL_3D: case WISP_PORTAL: tr.alpha = e; tr.scale = 0.4f + 0.6f * e; tr.rotZ = -20f + 20f * e;
                if (t == TransitionType.WISP_PORTAL) tr.blurAmount = 0.4f * bell(p); break;
            case CAROUSEL_3D: tr.rotY = -120f + 120f * e; tr.dx = -0.4f + 0.4f * e; tr.alpha = e > .5f ? 1f : 0f; return;
            case PARALLAX_3D: tr.rotY = -45f + 45f * e; tr.dx = -0.25f + 0.25f * e; tr.alpha = e; break;
            case DEPTH_ZOOM_3D: tr.alpha = e; tr.scale = 0.5f + 0.5f * e; tr.rotY = -25f + 25f * e; break;
            case DARK_SCALE: tr.alpha = e; tr.scale = 1.35f - 0.35f * e; break;

            case MOTION_BLUR_X: case BLUR_PUSH: case BLUR_DIRECTIONAL:
                tr.alpha = e; tr.blurAmount = 0.9f * bell(p); tr.dx = 0.4f * (1f - e); tr.blurDirection = 1f; break;
            case RADIAL_BLUR: tr.alpha = e; tr.blurAmount = 0.9f * bell(p); tr.rotZ = -15f * bell(p); tr.scale = 1.1f - 0.1f * e; break;
            case ZOOM_BLUR_X: tr.alpha = e; tr.blurAmount = 0.9f * bell(p); tr.scale = 1.25f - 0.25f * e; break;
            case SOFT_BLUR: case DREAM_BLUR: case DEFOCUS: tr.alpha = e; tr.blurAmount = 0.8f * bell(p); break;
            case FAST_BLUR: tr.alpha = e; tr.blurAmount = bell(p); tr.dx = 0.6f * (1f - e); tr.blurDirection = 1f; break;
            case BLUR_SWITCH: tr.alpha = e; tr.blurAmount = 0.8f * bell(p); tr.scale = 1.2f - 0.2f * e; break;
            case BLUR_FADE: tr.alpha = e; tr.blurAmount = 0.7f * bell(p); break;
            case BLUR_WIPE: tr.revealRadius = e; tr.wipeAxis = 0; tr.wipeSign = 1f; tr.blurAmount = 0.6f * bell(p); tr.alpha = 1f; break;
            case BLUR_SPIN: tr.alpha = e; tr.blurAmount = 0.7f * bell(p); tr.rotZ = -60f + 60f * e; break;

            case RGB_SPLIT: case RGB_WAVE: case CHROME_WAVE: tr.alpha = e; tr.chroma = 0.9f * bell(p); jitter(tr, p, 0.02f);
                if (t == TransitionType.CHROME_WAVE) tr.squeezeX = 1f - 0.1f * (float) Math.sin(p * 18.8f); break;
            case GLITCH: case PIXEL_GLITCH: tr.alpha = e; tr.chroma = 0.7f * bell(p); tr.strip = bell(p); jitter(tr, p, 0.05f); break;
            case VHS_GLITCH: tr.alpha = e; tr.chroma = 0.5f * bell(p); tr.strip = 0.8f * bell(p); jitter(tr, p, 0.03f); break;
            case SCANLINE_GLITCH: tr.alpha = e; tr.grain = 0.6f * bell(p); tr.strip = 0.5f * bell(p); break;
            case DIGITAL_NOISE: tr.alpha = e; tr.grain = 0.9f * bell(p); tr.chroma = 0.3f * bell(p); break;
            case TEAR_H: tr.alpha = e; tr.strip = bell(p); tr.dx = -0.08f * tri(p * 6f); break;
            case TEAR_V: tr.alpha = e; tr.strip = bell(p); tr.dy = -0.06f * tri(p * 6f); break;
            case PIXEL_STRETCH: tr.alpha = e; tr.strip = bell(p); tr.squeezeX = 1.4f - 0.4f * e; break;

            case WHITE_FLASH: case CAMERA_FLASH: case GLOW_FLASH: case NEON_FLASH:
            case FILM_FLASH: case SOFT_FLASH: case SUNSET_FLASH: case LENS_FLARE:
            case LIGHT_SWEEP: case FLASH_WIPE: case GLARE:
                tr.alpha = p < 0.35f ? 0f : (p - 0.35f) / 0.65f; tr.scale = 1.1f - 0.1f * e;
                tr.overlayColor = 0xFFFFFFFF; tr.overlayAlpha = bell(p); break;
            case BLACK_FLASH: case BLACKOUT_SWIPE:
                tr.alpha = p < 0.5f ? 0f : (p - 0.5f) * 2f;
                tr.overlayColor = 0xFF000000; tr.overlayAlpha = bell(p); break;
            case STROBE: tr.alpha = tri(p * 6f) > 0.5f ? 1f : 0.4f; break;

            case FILM_BURN: tr.alpha = e; tr.grain = 0.4f * bell(p); break;
            case FILM_ROLL: tr.alpha = e; tr.dy = 1f - e; tr.rotZ = -8f + 8f * e; break;
            case FILM_GRAIN_X: case DUST_X: case SCRATCH_X: case DUST_FLURRY: tr.alpha = e; tr.grain = 0.7f * bell(p); break;
            case CINEMATIC_PUSH: tr.alpha = 1f; tr.dx = 0.8f * (1f - e); tr.scale = 1.1f - 0.1f * e; break;
            case FILM_ERASE: tr.alpha = e; tr.grain = 0.8f * bell(p); break;

            case LIQUID_STRETCH: tr.alpha = e; tr.squeezeX = 0.6f + 0.4f * e; tr.dx = 0.3f * (1f - e); break;
            case RIPPLE_X: tr.alpha = e; tr.scale = 1f + 0.08f * (float) Math.sin((1f - p) * 18.8f); break;
            case WAVE_WARP: tr.alpha = e; tr.squeezeX = 1f - 0.12f * (float) Math.sin(p * 12.6f); jitter(tr, p, 0.015f); break;
            case LENS_WARP: tr.alpha = e; tr.scale = 1.25f - 0.25f * e; tr.blurAmount = 0.2f * bell(p); break;
            case BULGE: case BULGE_BLING: tr.alpha = e; tr.scale = 1.35f - 0.35f * bell(p); tr.squeezeX = 1.15f - 0.15f * bell(p);
                if (t == TransitionType.BULGE_BLING) tr.grain = 0.5f * tri(p * 10f); break;
            case PINCH: tr.alpha = e; tr.scale = 0.7f + 0.3f * bell(p); break;
            case SWIRL: tr.alpha = e; tr.rotZ = -120f * bell(p) + 24f * e; tr.scale = 0.8f + 0.2f * e; break;
            case TWIST: tr.alpha = e; tr.rotZ = -90f + 90f * e; tr.squeezeX = 0.7f + 0.3f * e; break;
            case HEAT_WAVE: tr.alpha = e; tr.squeezeY = 1f - 0.08f * (float) Math.sin(p * 25.1f); tr.blurAmount = 0.15f * bell(p); break;
            case ELASTIC: tr.alpha = e; tr.scale = 0.75f + 0.25f * e + 0.2f * Math.abs((float) Math.sin((1f - p) * 12.6f)) * (1f - p); break;
            case MELT: tr.alpha = e; tr.squeezeY = 0.5f + 0.5f * e; tr.dy = -0.3f * (1f - e); break;

            case TWINKLE_ZOOM: tr.alpha = e; tr.scale = 1.3f - 0.3f * e; tr.grain = 0.5f * tri(p * 8f); break;
            case GALLERY_SLIDE: tr.alpha = 1f; tr.dx = 1f - e; break;
            case GALLERY_ZOOM: tr.alpha = e; tr.scale = 1.3f - 0.3f * e; break;
            case RANDOM_GALLERY: tr.alpha = e; tr.scale = 1.3f - 0.3f * e; jitter(tr, p, 0.03f); break;
            case GALLERY_MOTION: case GALLERY_WALL: case GALLERY_WALL_V: case GALLERY_SCROLL_3D:
            case GALLERY_ALIGN: case GALLERY_SOCIAL: case GALLERY_FRAME: case GALLERY_CAM:
            case GALLERY_SPACE: case GALLERY_PREVIEW: case GALLERY_GRID: case GALLERY_MESSY:
            case GALLERY_MORPH: case GALLERY_CAROUSEL: case GALLERY_COLUMNS:
                tr.alpha = p;
                tr.scale = 1.06f - 0.06f * e;
                break;
            case WILDFIRE_SCAN: tr.revealRadius = e; tr.wipeAxis = 0; tr.wipeSign = 1f; tr.overlayColor = 0xFFFF5A2C; tr.overlayAlpha = 0.5f * bell(p); tr.alpha = 1f; break;
            default: tr.alpha = e; break;
        }
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
                TransitionType.FLASH, TransitionType.WHIP,
                TransitionType.GALLERY_MOTION, TransitionType.GALLERY_WALL, TransitionType.GALLERY_WALL_V,
                TransitionType.GALLERY_SCROLL_3D, TransitionType.GALLERY_ALIGN, TransitionType.GALLERY_SOCIAL,
                TransitionType.GALLERY_FRAME, TransitionType.GALLERY_CAM, TransitionType.GALLERY_SPACE,
                TransitionType.GALLERY_PREVIEW, TransitionType.GALLERY_GRID, TransitionType.GALLERY_MESSY,
                TransitionType.GALLERY_MORPH, TransitionType.GALLERY_CAROUSEL, TransitionType.GALLERY_COLUMNS
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
            case GALLERY_MOTION: return "Motion Gallery";
            case GALLERY_WALL: return "Wall Gallery";
            case GALLERY_WALL_V: return "Gallery Wall";
            case GALLERY_SCROLL_3D: return "3D Gallery Scroll";
            case GALLERY_ALIGN: return "Gallery Alignment";
            case GALLERY_SOCIAL: return "Social Gallery";
            case GALLERY_FRAME: return "Gallery Frame";
            case GALLERY_CAM: return "Cam Gallery";
            case GALLERY_SPACE: return "Space Gallery";
            case GALLERY_PREVIEW: return "Gallery Preview";
            case GALLERY_GRID: return "Gallery Grid";
            case GALLERY_MESSY: return "Messy Gallery";
            case GALLERY_MORPH: return "Gallery Morph";
            case GALLERY_CAROUSEL: return "Gallery Carousel";
            case GALLERY_COLUMNS: return "Gallery Columns";
            default: return t.name();
        }
    }
}
