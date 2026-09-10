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
    CIRCLE_CLOSE, SMOOTH_REVEAL, CINEMATIC_DISSOLVE,

    // ===== CapCut library render families (v1.7) — see TransitionRegistry =====
    // fades / wipes / masks / shapes
    FADE_SCAN, FADE_DIRECTIONAL, FADE_WIPE, GRADUAL_FADE, FAKE_ZOOM, ZOOM_IN, ZOOM_OUT,
    ZOOM_SWITCH, MIRROR_ZOOM, QUAKY_ZOOM, DIAGONAL_WIPE, CORNER_WIPE, COVER,
    REVEAL_SLIDE, SPLIT_WIPE, CENTER_WIPE, SHAPE_REVEAL, FUZZY_CIRCLE,
    TELEPORT_SHAKE, SPIN_SLAM, SQUEEZE_SNAP, ASH_SPREAD, DRAG_SWITCH,
    // camera
    CAMERA_PUSH, CAMERA_PULL, ZOOM_SNAP, DOLLY_ZOOM, CAMERA_SHAKE, CAMERA_ROTATE,
    CAMERA_ROLL, ORBIT_SPIN,
    // 3D (real perspective)
    CUBE_3D, FLIP_3D, ROTATE_3D, PAGE_TURN_3D, CARD_3D, DOOR_3D, FOLD_3D,
    TUNNEL_3D, CAROUSEL_3D, PARALLAX_3D, DEPTH_ZOOM_3D,
    // blur
    MOTION_BLUR_X, RADIAL_BLUR, ZOOM_BLUR_X, SOFT_BLUR, DREAM_BLUR, FAST_BLUR,
    BLUR_SWITCH, BLUR_FADE, BLUR_WIPE, BLUR_SPIN, BLUR_PUSH, DEFOCUS,
    // glitch
    RGB_SPLIT, GLITCH, VHS_GLITCH, SCANLINE_GLITCH, PIXEL_GLITCH, TEAR_H, TEAR_V,
    RGB_WAVE, DIGITAL_NOISE, PIXEL_STRETCH,
    // flash / light
    WHITE_FLASH, BLACK_FLASH, CAMERA_FLASH, SUNSET_FLASH, STROBE, SOFT_FLASH,
    GLOW_FLASH, LENS_FLARE, LIGHT_SWEEP, NEON_FLASH, FILM_FLASH, FLASH_WIPE, GLARE,
    BLACKOUT_SWIPE,
    // cinematic / film
    CINEMATIC_FADE, FILM_BURN, FILM_ROLL, FILM_SHAKE, FILM_GRAIN_X, VINTAGE_FADE,
    DUST_X, SCRATCH_X, CINEMATIC_ZOOM, CINEMATIC_WIPE, CINEMATIC_PUSH, FILM_ERASE,
    // liquid / distortion
    LIQUID_WIPE, LIQUID_STRETCH, RIPPLE_X, WAVE_WARP, LENS_WARP, BULGE, PINCH,
    SWIRL, TWIST, HEAT_WAVE, ELASTIC, MELT,
    // dynamic
    WISP_PORTAL, PETAL_WIND, DUST_FLURRY, TWINKLE_ZOOM, COMPARISON, CHROME_WAVE,
    BULGE_BLING, MESSY_CIRCLES, WILDFIRE_SCAN, GALLERY_SLIDE, GALLERY_ZOOM,
    COMPRESSION_SPIN, SHAKE_SHIFT, DARK_SCALE, RANDOM_GALLERY,

    // --- library aliases (v1.7): extra UI names that reuse an existing
    //     renderer family through TransitionEngine.fillFamily*, so they never
    //     duplicate math — they map onto a real renderer below.
    WHIP_PAN,        // → directional whip blur motion
    ZOOM_CAMERA,     // → zoom camera push
    LINEAR_WIPE,     // → axis linear wipe
    FEATHER_MASK,    // → feathered shape reveal
    BLUR_DIRECTIONAL,// → directional motion blur
    FAST_PUSH,       // → snappy push
    FAST_PULL,

    // ===== multi-panel GALLERY families (v1.8) — rendered by
    //     TransitionDraw.drawGallery with BOTH clip bitmaps (16 presets,
    //     15 types: the left/right 3D-scroll pair shares one type via
    //     the preset's direction field). 2.5D Canvas, stable on low-end.
    GALLERY_MOTION,     // panels moving with coordinated offsets
    GALLERY_WALL,       // tiled image wall, diagonal wave
    GALLERY_WALL_V,     // tiled image wall, vertical wave ("Gallery Wall")
    GALLERY_SCROLL_3D,  // perspective filmstrip scroll (real Camera 3D)
    GALLERY_ALIGN,      // scattered panels align into a grid
    GALLERY_SOCIAL,     // social-feed cards, staggered
    GALLERY_FRAME,      // framed panel presentation
    GALLERY_CAM,        // camera viewfinder (brackets + zoom settle)
    GALLERY_SPACE,      // depth planes flying through 3D space
    GALLERY_PREVIEW,    // editor preview-card with scrub line
    GALLERY_GRID,       // 3×3 tile cascade
    GALLERY_MESSY,      // controlled irregular panels
    GALLERY_MORPH,      // quadrant matrix morph
    GALLERY_CAROUSEL,   // 3D ring swing (real Camera perspective)
    GALLERY_COLUMNS,

    // ===== 3D / Perspective Detailed (prompt 2026-09-10 Bangladesh) — 27 precise specs =====
    PAGE_TURN,          // 1 Page Turn Y 0→180 with edge gradient
    PAGE_CURL,          // 2 Page Curl bezier diagonal + shadow
    BOOK_OPEN,          // 3 Book Open split middle Y ±120 with spine shadow
    CUBE_H,             // 4 Cube Horizontal Y -90→0 with perspective 800-1200
    CUBE_V,             // 5 Cube Vertical X
    CUBE_INSIDE_OUT,    // 6 Cube Inside Out scale 1→2.5 vs 0.3→1 with blur
    DOOR_OPEN,          // 7 Door Open Y ±90 hinge shadow
    FOLD_ACCORDION,     // 8 Fold Accordion 3-5 strips staggered shadow
    ORIGAMI_FOLD,       // 9 Origami triangular multi-axis
    FLIP_H,             // 10 Horizontal Flip Y centre 0→180
    FLIP_V,             // 11 Vertical Flip X centre 0→180
    FLIP_SCALE,         // 12 Flip with Scale 0.7→1 bouncy
    ZOOM_DEPTH,         // 13 Zoom Depth push Z 1→1.3 vs 0.7→1
    PARALLAX_ZOOM,      // 14 Parallax Zoom bg 1.1 vs fg 1.4
    PERSPECTIVE_PULL,   // 15 Perspective Pull corner anchored distort
    SLIDE_3D,           // 16 Slide 3D rotate ±15 + slide
    CAROUSEL_ROTATE,    // 17 Carousel cylinder 360/N
    COVERFLOW,          // 18 Coverflow 45-60° + reflection
    VENETIAN_BLINDS,    // 19 Venetian blinds horizontal strips staggered 50-100ms
    SHUTTER_SPLIT,      // 20 Shutter split vertical/horizontal
    CIRCLE_REVEAL_3D,   // 21 Circle Reveal 3D bulge lens distortion
    POLYGON_REVEAL,     // 22 Polygon reveal hexagon/diamond rotate
    SWING_PENDULUM,     // 23 Swing pendulum elastic damped 15→-10→5
    RIPPLE_WAVE_3D,     // 24 Ripple/Wave 3D sine displacement
    EXPLODE_SHATTER,    // 25 Explode shatter voronoi 30-50 pieces gravity
    SPIN_ZOOM,          // 26 Spin & Zoom Z 180-360 + scale 1→0 / 0→1
    MIRROR_SPLIT;       // 27 Mirror split symmetric Y    // vertical column reveal

    /**
     * Multi-panel gallery transitions (v1.8). These are rendered by
     * {@code TransitionDraw.drawGallery} with BOTH clip bitmaps; they do not
     * use the single-transform in/out path.
     */
    public boolean isGallery() {
        switch (this) {
            case GALLERY_MOTION: case GALLERY_WALL: case GALLERY_WALL_V:
            case GALLERY_SCROLL_3D: case GALLERY_ALIGN: case GALLERY_SOCIAL:
            case GALLERY_FRAME: case GALLERY_CAM: case GALLERY_SPACE:
            case GALLERY_PREVIEW: case GALLERY_GRID: case GALLERY_MESSY:
            case GALLERY_MORPH: case GALLERY_CAROUSEL: case GALLERY_COLUMNS:
                return true;
            default:
                return false;
        }
    }

    /** Renderers that paint a full-frame colour wash (flash/light/dip). */
    public boolean isOverlay() {
        switch (this) {
            case FLASH: case WHITE_FLASH: case BLACK_FLASH: case CAMERA_FLASH:
            case DIP_TO_WHITE: case STROBE: case SOFT_FLASH: case GLOW_FLASH:
            case NEON_FLASH: case FILM_FLASH: case SUNSET_FLASH: case LENS_FLARE:
            case LIGHT_LEAK: case LIGHT_SWEEP: case FLASH_WIPE: case GLARE:
            case BLACKOUT_SWIPE: case DIP_TO_BLACK: case CINEMATIC_FADE:
            case FILM_BURN: case VINTAGE_FADE:
                return true;
            default:
                return false;
        }
    }
}
