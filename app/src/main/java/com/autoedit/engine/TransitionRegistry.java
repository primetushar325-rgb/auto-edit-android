package com.autoedit.engine;

import com.autoedit.model.TransitionCategory;
import com.autoedit.model.TransitionPreset;
import com.autoedit.model.TransitionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SINGLE source of truth for the transition library UI. All cards, search,
 * recents and favourites read presets from here. A preset maps a named,
 * categorised UI transition onto a {@link TransitionType} renderer (which holds
 * the shared math). Directional/colour variants reuse one renderer via the
 * preset's direction/tint, so adding a transition is just one register(...) —
 * no UI rebuild and no duplicate engine.
 */
public class TransitionRegistry {
    private static final Map<String, TransitionPreset> BY_ID = new LinkedHashMap<>();
    private static final List<TransitionPreset> ALL = new ArrayList<>();

    private static TransitionPreset reg(TransitionPreset p) {
        BY_ID.put(p.id, p);
        ALL.add(p);
        return p;
    }

    static {
        final TransitionCategory C = TransitionCategory.BASIC;
        final TransitionCategory CLA = TransitionCategory.CLASSIC;
        final TransitionCategory CAM = TransitionCategory.CAMERA;
        final TransitionCategory TD = TransitionCategory.THREE_D;
        final TransitionCategory BL = TransitionCategory.BLUR;
        final TransitionCategory GL = TransitionCategory.GLITCH;
        final TransitionCategory FL = TransitionCategory.FLASH;
        final TransitionCategory MK = TransitionCategory.MASK;
        final TransitionCategory SH = TransitionCategory.SHAPE;
        final TransitionCategory SL = TransitionCategory.SLIDE;
        final TransitionCategory CI = TransitionCategory.CINEMATIC;
        final TransitionCategory LQ = TransitionCategory.LIQUID;
        final TransitionCategory DY = TransitionCategory.DYNAMIC;
        final TransitionCategory GAL = TransitionCategory.GALLERY;
        final TransitionCategory SO = TransitionCategory.SOCIAL;
        final TransitionCategory PH = TransitionCategory.PHOTO;

        // ============ BASIC ============
        reg(new TransitionPreset.B("none", "None", C, TransitionType.NONE).dur(0f,0f,0f).tags("reset","off").desc("No transition").build());
        reg(new TransitionPreset.B("fade", "Fade", C, TransitionType.FADE).dur(.5f,.2f,2f).trend().tags("cross","dissolve","smooth").desc("Classic cross fade").build());
        reg(new TransitionPreset.B("fade_scan", "Fade Scan", C, TransitionType.FADE_SCAN).dur(.5f,.2f,1.5f).tags("scan","sweep").build());
        reg(new TransitionPreset.B("fade_down", "Fade Down", C, TransitionType.FADE_DIRECTIONAL).dir("down").dur(.5f,.2f,1.5f).tags("fade").build());
        reg(new TransitionPreset.B("fade_up", "Fade Up", C, TransitionType.FADE_DIRECTIONAL).dir("up").dur(.5f,.2f,1.5f).tags("fade").build());
        reg(new TransitionPreset.B("fade_left", "Fade Left", C, TransitionType.FADE_DIRECTIONAL).dir("left").dur(.5f,.2f,1.5f).tags("fade").build());
        reg(new TransitionPreset.B("fade_right", "Fade Right", C, TransitionType.FADE_DIRECTIONAL).dir("right").dur(.5f,.2f,1.5f).tags("fade").build());
        reg(new TransitionPreset.B("fake_zoom", "Fake Zoom", C, TransitionType.FAKE_ZOOM).dur(.5f,.2f,1.5f).trend().tags("zoom","punch").build());
        reg(new TransitionPreset.B("zoom_in", "Zoom In", C, TransitionType.ZOOM_IN).dur(.5f,.2f,2f).tags("zoom").build());
        reg(new TransitionPreset.B("zoom_out", "Zoom Out", C, TransitionType.ZOOM_OUT).dur(.5f,.2f,2f).tags("zoom").build());
        reg(new TransitionPreset.B("zoom_switch", "Zoom Switch", C, TransitionType.ZOOM_SWITCH).dur(.5f,.2f,2f).trend().neu().tags("zoom","switch","punch").build());
        reg(new TransitionPreset.B("diagonal_fade", "Diagonal Fade", C, TransitionType.DIAGONAL_WIPE).dir("diag").dur(.6f,.2f,2f).tags("fade","wipe","diagonal").build());
        reg(new TransitionPreset.B("mirror_zoom", "Mirror Zoom", C, TransitionType.MIRROR_ZOOM).dur(.6f,.2f,2f).tags("zoom","mirror","flip").build());
        reg(new TransitionPreset.B("quaky_zoomout", "Quaky Zoomout", C, TransitionType.QUAKY_ZOOM).dur(.5f,.2f,1.5f).tags("zoom","shake","quaky").build());
        reg(new TransitionPreset.B("corner_wipe", "Corner Wipe", C, TransitionType.CORNER_WIPE).dur(.6f,.2f,2f).tags("wipe","corner").build());
        reg(new TransitionPreset.B("shaky_teleport", "Shaky Teleport", C, TransitionType.TELEPORT_SHAKE).dur(.4f,.2f,1.2f).tags("shake","teleport","glitch").build());
        reg(new TransitionPreset.B("ash_spread", "Ash Spread", C, TransitionType.ASH_SPREAD).dur(.7f,.3f,2f).tags("ash","particle","spread").build());
        reg(new TransitionPreset.B("drag_switch", "Drag Switch", C, TransitionType.DRAG_SWITCH).dir("left").dur(.5f,.2f,1.5f).tags("drag","switch").build());
        reg(new TransitionPreset.B("spin_slam", "Spin Slam", C, TransitionType.SPIN_SLAM).dur(.5f,.2f,1.5f).trend().tags("spin","slam","rotate","punch").build());
        reg(new TransitionPreset.B("squeeze_snap", "Squeeze & Snap", C, TransitionType.SQUEEZE_SNAP).dur(.5f,.2f,1.5f).tags("squeeze","snap","stretch").build());
        reg(new TransitionPreset.B("fuzzy_circle", "Fuzzy Circle Wipe", C, TransitionType.FUZZY_CIRCLE).dur(.6f,.2f,2f).tags("circle","wipe","soft","fuzzy").build());
        reg(new TransitionPreset.B("fade_wipe", "Fade Wipe", C, TransitionType.FADE_WIPE).dir("left").dur(.6f,.2f,2f).trend().tags("fade","wipe").build());
        reg(new TransitionPreset.B("cross_dissolve", "Cross Dissolve", CLA, TransitionType.CROSS_DISSOLVE).dur(.5f,.2f,2f).tags("dissolve","cross","classic").build());

        // ============ CLASSIC ============
        reg(new TransitionPreset.B("push_left", "Push Left", CLA, TransitionType.PUSH_LEFT).dur(.5f,.2f,2f).tags("push","classic").build());
        reg(new TransitionPreset.B("push_right", "Push Right", CLA, TransitionType.PUSH_RIGHT).dur(.5f,.2f,2f).tags("push","classic").build());
        reg(new TransitionPreset.B("push_up", "Push Up", CLA, TransitionType.PUSH_UP).dur(.5f,.2f,2f).tags("push","classic").build());
        reg(new TransitionPreset.B("push_down", "Push Down", CLA, TransitionType.PUSH_DOWN).dur(.5f,.2f,2f).tags("push","classic").build());
        reg(new TransitionPreset.B("slide_left", "Slide Left", CLA, TransitionType.SLIDE_LEFT).dur(.5f,.2f,2f).tags("slide","classic").build());
        reg(new TransitionPreset.B("slide_right", "Slide Right", CLA, TransitionType.SLIDE_RIGHT).dur(.5f,.2f,2f).tags("slide","classic").build());
        reg(new TransitionPreset.B("slide_up", "Slide Up", CLA, TransitionType.SLIDE_UP).dur(.5f,.2f,2f).tags("slide","classic").build());
        reg(new TransitionPreset.B("slide_down", "Slide Down", CLA, TransitionType.SLIDE_DOWN).dur(.5f,.2f,2f).tags("slide","classic").build());
        reg(new TransitionPreset.B("cover_left", "Cover Left", CLA, TransitionType.COVER).dir("left").dur(.5f,.2f,2f).tags("cover","classic").build());
        reg(new TransitionPreset.B("cover_right", "Cover Right", CLA, TransitionType.COVER).dir("right").dur(.5f,.2f,2f).tags("cover").build());
        reg(new TransitionPreset.B("reveal", "Reveal", CLA, TransitionType.REVEAL_SLIDE).dir("left").dur(.5f,.2f,2f).tags("reveal","classic").build());
        reg(new TransitionPreset.B("split", "Split", CLA, TransitionType.SPLIT_WIPE).dur(.6f,.2f,2f).tags("split","wipe").build());
        reg(new TransitionPreset.B("center_wipe", "Center Wipe", CLA, TransitionType.CENTER_WIPE).dur(.6f,.2f,2f).tags("wipe","center").build());
        reg(new TransitionPreset.B("linear_wipe", "Linear Wipe", CLA, TransitionType.LINEAR_WIPE).dir("left").dur(.6f,.2f,2f).tags("wipe","linear").build());
        reg(new TransitionPreset.B("radial_wipe", "Radial Wipe", CLA, TransitionType.RADIAL_REVEAL).dur(.6f,.2f,2f).tags("radial","wipe","circle").build());

        // ============ CAMERA ============
        reg(new TransitionPreset.B("camera_push", "Camera Push", CAM, TransitionType.CAMERA_PUSH).dur(.5f,.2f,1.5f).tags("camera","push","dolly").build());
        reg(new TransitionPreset.B("camera_pull", "Camera Pull", CAM, TransitionType.CAMERA_PULL).dur(.5f,.2f,1.5f).tags("camera","pull","dolly").build());
        reg(new TransitionPreset.B("fast_push", "Fast Push", CAM, TransitionType.FAST_PUSH).dur(.35f,.15f,1f).tags("camera","fast","push").build());
        reg(new TransitionPreset.B("fast_pull", "Fast Pull", CAM, TransitionType.FAST_PULL).dur(.35f,.15f,1f).tags("camera","fast","pull").build());
        reg(new TransitionPreset.B("zoom_camera", "Zoom Camera", CAM, TransitionType.ZOOM_CAMERA).dur(.5f,.2f,1.5f).tags("camera","zoom").build());
        reg(new TransitionPreset.B("zoom_snap", "Zoom Snap", CAM, TransitionType.ZOOM_SNAP).dur(.4f,.2f,1.2f).trend().tags("camera","zoom","snap","punch").build());
        reg(new TransitionPreset.B("dolly_zoom", "Dolly Zoom", CAM, TransitionType.DOLLY_ZOOM).dur(.7f,.3f,2f).tags("camera","dolly","vertigo","zoom").build());
        reg(new TransitionPreset.B("camera_shake", "Camera Shake", CAM, TransitionType.CAMERA_SHAKE).dur(.4f,.2f,1.2f).tags("camera","shake","impact").build());
        reg(new TransitionPreset.B("handheld", "Handheld", CAM, TransitionType.CAMERA_SHAKE).intensity(.35f).dur(.5f,.2f,1.5f).tags("camera","handheld","shake").build());
        reg(new TransitionPreset.B("micro_shake", "Micro Shake", CAM, TransitionType.CAMERA_SHAKE).intensity(.25f).dur(.3f,.15f,1f).tags("camera","micro","shake").build());
        reg(new TransitionPreset.B("impact_shake", "Impact Shake", CAM, TransitionType.CAMERA_SHAKE).intensity(.9f).dur(.4f,.2f,1.2f).trend().tags("camera","impact","shake","punch").build());
        reg(new TransitionPreset.B("whip_pan", "Whip Pan", CAM, TransitionType.WHIP_PAN).dir("right").dur(.35f,.15f,1f).trend().tags("camera","whip","pan","swipe").build());
        reg(new TransitionPreset.B("fast_whip", "Fast Whip", CAM, TransitionType.WHIP_PAN).dir("right").dur(.25f,.15f,.8f).tags("camera","fast","whip").build());
        reg(new TransitionPreset.B("pan_left", "Pan Left", CAM, TransitionType.WHIP_PAN).dir("left").dur(.4f,.2f,1.2f).tags("camera","pan").build());
        reg(new TransitionPreset.B("pan_right", "Pan Right", CAM, TransitionType.WHIP_PAN).dir("right").dur(.4f,.2f,1.2f).tags("camera","pan").build());
        reg(new TransitionPreset.B("tilt_up", "Tilt Up", CAM, TransitionType.WHIP_PAN).dir("up").dur(.4f,.2f,1.2f).tags("camera","tilt","pan").build());
        reg(new TransitionPreset.B("tilt_down", "Tilt Down", CAM, TransitionType.WHIP_PAN).dir("down").dur(.4f,.2f,1.2f).tags("camera","tilt","pan").build());
        reg(new TransitionPreset.B("rotate_camera", "Rotate Camera", CAM, TransitionType.CAMERA_ROTATE).dur(.6f,.2f,1.5f).tags("camera","rotate","spin").build());
        reg(new TransitionPreset.B("spin_camera", "Spin Camera", CAM, TransitionType.CAMERA_ROTATE).intensity(1f).dur(.6f,.2f,1.5f).tags("camera","spin","rotate").build());
        reg(new TransitionPreset.B("orbit", "Orbit", CAM, TransitionType.ORBIT_SPIN).dur(.7f,.3f,2f).tags("camera","orbit","3d","spin").build());
        reg(new TransitionPreset.B("spin_360", "360 Spin", CAM, TransitionType.CAMERA_ROLL).dur(.7f,.3f,2f).trend().tags("camera","360","roll","spin").build());
        reg(new TransitionPreset.B("camera_roll", "Camera Roll", CAM, TransitionType.CAMERA_ROLL).dur(.6f,.2f,1.5f).tags("camera","roll").build());
        reg(new TransitionPreset.B("snap_camera", "Snap Camera", CAM, TransitionType.ZOOM_SNAP).intensity(.8f).dur(.4f,.2f,1.2f).tags("camera","snap").build());

        // ============ 3D ============
        reg(new TransitionPreset.B("3d_zoom", "3D Zoom", TD, TransitionType.DEPTH_ZOOM_3D).dur(.6f,.2f,2f).tags("3d","zoom","perspective","depth").build());
        reg(new TransitionPreset.B("cube_left", "3D Cube Left", TD, TransitionType.CUBE_3D).dir("left").dur(.6f,.2f,2f).trend().neu().tags("3d","cube","perspective").build());
        reg(new TransitionPreset.B("cube_right", "3D Cube Right", TD, TransitionType.CUBE_3D).dir("right").dur(.6f,.2f,2f).tags("3d","cube").build());
        reg(new TransitionPreset.B("cube_up", "3D Cube Up", TD, TransitionType.CUBE_3D).dir("up").dur(.6f,.2f,2f).tags("3d","cube").build());
        reg(new TransitionPreset.B("cube_down", "3D Cube Down", TD, TransitionType.CUBE_3D).dir("down").dur(.6f,.2f,2f).tags("3d","cube").build());
        reg(new TransitionPreset.B("3d_flip", "3D Flip", TD, TransitionType.FLIP_3D).dur(.6f,.2f,2f).tags("3d","flip","perspective").build());
        reg(new TransitionPreset.B("flip_horizontal", "3D Flip Horizontal", TD, TransitionType.FLIP_3D).dir("horizontal").dur(.6f,.2f,2f).tags("3d","flip","horizontal").build());
        reg(new TransitionPreset.B("flip_vertical", "3D Flip Vertical", TD, TransitionType.FLIP_3D).dir("vertical").dur(.6f,.2f,2f).tags("3d","flip","vertical").build());
        reg(new TransitionPreset.B("3d_rotate", "3D Rotate", TD, TransitionType.ROTATE_3D).dur(.6f,.2f,2f).tags("3d","rotate","perspective").build());
        reg(new TransitionPreset.B("rotate_left", "3D Rotate Left", TD, TransitionType.ROTATE_3D).dir("left").dur(.6f,.2f,2f).tags("3d","rotate").build());
        reg(new TransitionPreset.B("rotate_right", "3D Rotate Right", TD, TransitionType.ROTATE_3D).dir("right").dur(.6f,.2f,2f).tags("3d","rotate").build());
        reg(new TransitionPreset.B("page_turn", "3D Page Turn", TD, TransitionType.PAGE_TURN_3D).dir("left").dur(.7f,.3f,2f).tags("3d","page","turn","book").build());
        reg(new TransitionPreset.B("3d_card", "3D Card", TD, TransitionType.CARD_3D).dur(.6f,.2f,2f).tags("3d","card","flip").build());
        reg(new TransitionPreset.B("3d_door", "3D Door", TD, TransitionType.DOOR_3D).dir("left").dur(.7f,.3f,2f).tags("3d","door","open").build());
        reg(new TransitionPreset.B("3d_fold", "3D Fold", TD, TransitionType.FOLD_3D).dir("up").dur(.7f,.3f,2f).tags("3d","fold","paper").build());
        reg(new TransitionPreset.B("3d_perspective", "3D Perspective", TD, TransitionType.PARALLAX_3D).dur(.6f,.2f,2f).tags("3d","perspective","parallax").build());
        reg(new TransitionPreset.B("3d_spin", "3D Spin", TD, TransitionType.ROTATE_3D).intensity(1f).dur(.7f,.3f,2f).tags("3d","spin").build());
        reg(new TransitionPreset.B("3d_tunnel", "3D Tunnel", TD, TransitionType.TUNNEL_3D).dur(.7f,.3f,2f).trend().tags("3d","tunnel","zoom","depth").build());
        reg(new TransitionPreset.B("3d_push", "3D Push", TD, TransitionType.CUBE_3D).dir("left").intensity(.6f).dur(.6f,.2f,2f).tags("3d","push").build());
        reg(new TransitionPreset.B("3d_pull", "3D Pull", TD, TransitionType.CUBE_3D).dir("right").intensity(.6f).dur(.6f,.2f,2f).tags("3d","pull").build());
        reg(new TransitionPreset.B("3d_carousel", "3D Carousel", TD, TransitionType.CAROUSEL_3D).dur(.8f,.3f,2.5f).tags("3d","carousel","gallery").build());
        reg(new TransitionPreset.B("3d_parallax", "3D Parallax", TD, TransitionType.PARALLAX_3D).dur(.6f,.2f,2f).tags("3d","parallax","depth").build());
        reg(new TransitionPreset.B("depth_zoom", "3D Depth Zoom", TD, TransitionType.DEPTH_ZOOM_3D).dur(.6f,.2f,2f).tags("3d","depth","zoom").build());
        reg(new TransitionPreset.B("3d_camera_roll", "3D Camera Roll", TD, TransitionType.DEPTH_ZOOM_3D).intensity(.8f).dur(.6f,.2f,2f).tags("3d","camera","roll").build());

        // ============ BLUR ============
        reg(new TransitionPreset.B("vertical_blur", "Vertical Blur", BL, TransitionType.BLUR_DIRECTIONAL).dir("down").dur(.5f,.2f,1.5f).trend().intensity(1f).tags("blur","vertical","motion").build());
        reg(new TransitionPreset.B("vertical_blur_2", "Vertical Blur II", BL, TransitionType.MOTION_BLUR_X).dir("down").dur(.45f,.2f,1.2f).trend().intensity(1f).tags("blur","vertical").build());
        reg(new TransitionPreset.B("horizontal_blur", "Horizontal Blur", BL, TransitionType.BLUR_DIRECTIONAL).dir("right").dur(.5f,.2f,1.5f).intensity(1f).tags("blur","horizontal").build());
        reg(new TransitionPreset.B("motion_blur_t", "Motion Blur", BL, TransitionType.MOTION_BLUR_X).dur(.45f,.2f,1.2f).tags("blur","motion").build());
        reg(new TransitionPreset.B("directional_blur", "Directional Blur", BL, TransitionType.BLUR_DIRECTIONAL).dir("right").dur(.5f,.2f,1.5f).tags("blur","directional").build());
        reg(new TransitionPreset.B("radial_blur", "Radial Blur", BL, TransitionType.RADIAL_BLUR).dur(.6f,.2f,2f).tags("blur","radial","spin").build());
        reg(new TransitionPreset.B("zoom_blur_t", "Zoom Blur", BL, TransitionType.ZOOM_BLUR_X).dur(.5f,.2f,1.5f).trend().tags("blur","zoom").build());
        reg(new TransitionPreset.B("soft_blur", "Soft Blur", BL, TransitionType.SOFT_BLUR).dur(.5f,.2f,1.5f).tags("blur","soft","dream").build());
        reg(new TransitionPreset.B("dream_blur", "Dream Blur", BL, TransitionType.DREAM_BLUR).dur(.7f,.3f,2f).tags("blur","dream","soft").build());
        reg(new TransitionPreset.B("gaussian_blur", "Gaussian Blur", BL, TransitionType.SOFT_BLUR).intensity(1f).dur(.5f,.2f,1.5f).tags("blur","gaussian","soft").build());
        reg(new TransitionPreset.B("fast_blur", "Fast Blur", BL, TransitionType.FAST_BLUR).dur(.3f,.15f,1f).tags("blur","fast","motion").build());
        reg(new TransitionPreset.B("blur_zoom", "Blur Zoom", BL, TransitionType.ZOOM_BLUR_X).intensity(.9f).dur(.5f,.2f,1.5f).trend().tags("blur","zoom").build());
        reg(new TransitionPreset.B("blur_switch", "Blur Switch", BL, TransitionType.BLUR_SWITCH).dur(.5f,.2f,1.5f).tags("blur","switch").build());
        reg(new TransitionPreset.B("blur_fade", "Blur Fade", BL, TransitionType.BLUR_FADE).dur(.6f,.2f,2f).tags("blur","fade").build());
        reg(new TransitionPreset.B("blur_wipe", "Blur Wipe", BL, TransitionType.BLUR_WIPE).dir("left").dur(.6f,.2f,2f).tags("blur","wipe").build());
        reg(new TransitionPreset.B("blur_spin", "Blur Spin", BL, TransitionType.BLUR_SPIN).dur(.6f,.2f,2f).tags("blur","spin","rotate").build());
        reg(new TransitionPreset.B("blur_push", "Blur Push", BL, TransitionType.BLUR_PUSH).dir("left").dur(.5f,.2f,1.5f).tags("blur","push").build());
        reg(new TransitionPreset.B("blur_pull", "Blur Pull", BL, TransitionType.BLUR_PUSH).dir("right").dur(.5f,.2f,1.5f).tags("blur","pull").build());
        reg(new TransitionPreset.B("focus_blur", "Focus Blur", BL, TransitionType.DEFOCUS).dur(.6f,.2f,2f).tags("blur","focus","defocus").build());
        reg(new TransitionPreset.B("defocus", "Defocus", BL, TransitionType.DEFOCUS).dur(.6f,.2f,2f).tags("blur","defocus","focus").build());
        reg(new TransitionPreset.B("lens_blur", "Lens Blur", BL, TransitionType.RADIAL_BLUR).intensity(.8f).dur(.6f,.2f,2f).tags("blur","lens","radial").build());

        // ============ GLITCH ============
        reg(new TransitionPreset.B("rgb_split", "RGB Split", GL, TransitionType.RGB_SPLIT).dur(.4f,.15f,1.2f).trend().tags("glitch","rgb","chromatic","split").build());
        reg(new TransitionPreset.B("rgb_shift", "RGB Shift", GL, TransitionType.RGB_SPLIT).intensity(1f).dur(.4f,.15f,1.2f).tags("glitch","rgb","shift").build());
        reg(new TransitionPreset.B("chromatic_aberration", "Chromatic Aberration", GL, TransitionType.RGB_SPLIT).intensity(.8f).dur(.5f,.2f,1.5f).tags("glitch","chromatic","aberration","rgb").build());
        reg(new TransitionPreset.B("digital_glitch", "Digital Glitch", GL, TransitionType.GLITCH).dur(.4f,.15f,1.2f).trend().tags("glitch","digital","datamosh").build());
        reg(new TransitionPreset.B("vhs_glitch", "VHS Glitch", GL, TransitionType.VHS_GLITCH).dur(.5f,.2f,1.5f).tags("glitch","vhs","retro","tape").build());
        reg(new TransitionPreset.B("scanline", "Scanline", GL, TransitionType.SCANLINE_GLITCH).dur(.5f,.2f,1.5f).tags("glitch","scanline","tv").build());
        reg(new TransitionPreset.B("pixel_glitch", "Pixel Glitch", GL, TransitionType.PIXEL_GLITCH).dur(.4f,.15f,1.2f).tags("glitch","pixel","block").build());
        reg(new TransitionPreset.B("signal_error", "Signal Error", GL, TransitionType.GLITCH).intensity(1f).dur(.4f,.15f,1f).tags("glitch","signal","error","tv").build());
        reg(new TransitionPreset.B("data_corruption", "Data Corruption", GL, TransitionType.DIGITAL_NOISE).dur(.4f,.15f,1.2f).tags("glitch","data","corruption","noise").build());
        reg(new TransitionPreset.B("glitch_shake", "Glitch Shake", GL, TransitionType.GLITCH).intensity(.9f).dur(.4f,.15f,1.2f).tags("glitch","shake").build());
        reg(new TransitionPreset.B("glitch_zoom", "Glitch Zoom", GL, TransitionType.GLITCH).intensity(.7f).dur(.5f,.2f,1.5f).tags("glitch","zoom").build());
        reg(new TransitionPreset.B("glitch_flash", "Glitch Flash", GL, TransitionType.RGB_WAVE).dur(.35f,.15f,1f).tags("glitch","flash","rgb").build());
        reg(new TransitionPreset.B("tv_distortion", "TV Distortion", GL, TransitionType.VHS_GLITCH).dur(.5f,.2f,1.5f).tags("glitch","tv","distortion").build());
        reg(new TransitionPreset.B("noise_glitch", "Noise Glitch", GL, TransitionType.DIGITAL_NOISE).dur(.4f,.15f,1.2f).tags("glitch","noise").build());
        reg(new TransitionPreset.B("horizontal_tear", "Horizontal Tear", GL, TransitionType.TEAR_H).dur(.4f,.15f,1.2f).trend().tags("glitch","tear","horizontal").build());
        reg(new TransitionPreset.B("vertical_tear", "Vertical Tear", GL, TransitionType.TEAR_V).dur(.4f,.15f,1.2f).tags("glitch","tear","vertical").build());
        reg(new TransitionPreset.B("rgb_wave", "RGB Wave", GL, TransitionType.RGB_WAVE).dur(.5f,.2f,1.5f).tags("glitch","rgb","wave").build());
        reg(new TransitionPreset.B("digital_noise", "Digital Noise", GL, TransitionType.DIGITAL_NOISE).dur(.4f,.15f,1.2f).tags("glitch","digital","noise").build());
        reg(new TransitionPreset.B("pixel_stretch", "Pixel Stretch", GL, TransitionType.PIXEL_STRETCH).dir("right").dur(.5f,.2f,1.5f).tags("glitch","pixel","stretch").build());

        // ============ FLASH / LIGHT ============
        reg(new TransitionPreset.B("white_flash", "White Flash", FL, TransitionType.WHITE_FLASH).dur(.4f,.15f,1.2f).trend().tags("flash","white","light").build());
        reg(new TransitionPreset.B("black_flash", "Black Flash", FL, TransitionType.BLACK_FLASH).dur(.4f,.15f,1.2f).trend().tags("flash","black","dark").build());
        reg(new TransitionPreset.B("camera_flash", "Camera Flash", FL, TransitionType.CAMERA_FLASH).dur(.35f,.15f,1f).tags("flash","camera","photo").build());
        reg(new TransitionPreset.B("sunset_flash", "Sunset Flash", FL, TransitionType.SUNSET_FLASH).tint(0xFFFF7A3D).dur(.5f,.2f,1.5f).trend().tags("flash","sunset","warm","orange").build());
        reg(new TransitionPreset.B("light_flash", "Light Flash", FL, TransitionType.WHITE_FLASH).intensity(.8f).dur(.4f,.15f,1.2f).tags("flash","light").build());
        reg(new TransitionPreset.B("flash_zoom", "Flash Zoom", FL, TransitionType.ZOOM_IN).dur(.4f,.2f,1.2f).tags("flash","zoom").build());
        reg(new TransitionPreset.B("flash_shake", "Flash Shake", FL, TransitionType.CAMERA_SHAKE).dur(.4f,.2f,1.2f).tags("flash","shake").build());
        reg(new TransitionPreset.B("strobe", "Strobe", FL, TransitionType.STROBE).dur(.5f,.2f,1.5f).tags("flash","strobe","blink").build());
        reg(new TransitionPreset.B("soft_flash", "Soft Flash", FL, TransitionType.SOFT_FLASH).dur(.5f,.2f,1.5f).tags("flash","soft","gentle").build());
        reg(new TransitionPreset.B("glow_flash", "Glow Flash", FL, TransitionType.GLOW_FLASH).dur(.5f,.2f,1.5f).tags("flash","glow").build());
        reg(new TransitionPreset.B("lens_flare", "Lens Flare", FL, TransitionType.LENS_FLARE).dur(.6f,.2f,2f).tags("flash","lens","flare","light").build());
        reg(new TransitionPreset.B("light_leak", "Light Leak", FL, TransitionType.LIGHT_LEAK).tint(0xFFFF5E7A).dur(.7f,.3f,2f).trend().tags("flash","light","leak","warm").build());
        reg(new TransitionPreset.B("light_sweep", "Light Sweep", FL, TransitionType.LIGHT_SWEEP).dur(.6f,.2f,2f).tags("flash","light","sweep","shine").build());
        reg(new TransitionPreset.B("neon_flash", "Neon Flash", FL, TransitionType.NEON_FLASH).tint(0xFF25F4EE).dur(.4f,.15f,1.2f).tags("flash","neon","cyber").build());
        reg(new TransitionPreset.B("exposure_flash", "Exposure Flash", FL, TransitionType.WHITE_FLASH).intensity(1f).dur(.35f,.15f,1f).tags("flash","exposure").build());
        reg(new TransitionPreset.B("film_flash", "Film Flash", FL, TransitionType.FILM_FLASH).dur(.4f,.2f,1.2f).tags("flash","film","analog").build());
        reg(new TransitionPreset.B("flash_wipe", "Flash Wipe", FL, TransitionType.FLASH_WIPE).dir("left").dur(.5f,.2f,1.5f).tags("flash","wipe").build());

        // ============ MASK ============
        reg(new TransitionPreset.B("circle_reveal", "Circle Reveal", MK, TransitionType.CIRCLE_REVEAL).dur(.6f,.2f,2f).tags("mask","circle","reveal").build());
        reg(new TransitionPreset.B("circle_hide", "Circle Hide", MK, TransitionType.CIRCLE_REVEAL).dir("out").dur(.6f,.2f,2f).tags("mask","circle","hide").build());
        reg(new TransitionPreset.B("fuzzy_circle_m", "Fuzzy Circle", MK, TransitionType.FUZZY_CIRCLE).dur(.6f,.2f,2f).tags("mask","circle","fuzzy","feather").build());
        reg(new TransitionPreset.B("heart_reveal", "Heart Reveal", MK, TransitionType.SHAPE_REVEAL).dir("heart").dur(.7f,.3f,2f).trend().tags("mask","heart","shape","love").build());
        reg(new TransitionPreset.B("star_reveal", "Star Reveal", MK, TransitionType.SHAPE_REVEAL).dir("star").dur(.7f,.3f,2f).tags("mask","star","shape").build());
        reg(new TransitionPreset.B("diamond_reveal", "Diamond Reveal", MK, TransitionType.SHAPE_REVEAL).dir("diamond").dur(.6f,.2f,2f).tags("mask","diamond","shape").build());
        reg(new TransitionPreset.B("rectangle_reveal", "Rectangle Reveal", MK, TransitionType.SHAPE_REVEAL).dir("rect").dur(.6f,.2f,2f).tags("mask","rectangle","rect").build());
        reg(new TransitionPreset.B("rounded_rect", "Rounded Rectangle", MK, TransitionType.SHAPE_REVEAL).dir("roundrect").dur(.6f,.2f,2f).tags("mask","rounded","rectangle").build());
        reg(new TransitionPreset.B("vertical_mask", "Vertical Mask", MK, TransitionType.FEATHER_MASK).dir("up").dur(.6f,.2f,2f).tags("mask","vertical","feather").build());
        reg(new TransitionPreset.B("horizontal_mask", "Horizontal Mask", MK, TransitionType.FEATHER_MASK).dir("right").dur(.6f,.2f,2f).tags("mask","horizontal","feather").build());
        reg(new TransitionPreset.B("diagonal_mask", "Diagonal Mask", MK, TransitionType.DIAGONAL_WIPE).dur(.6f,.2f,2f).tags("mask","diagonal").build());
        reg(new TransitionPreset.B("split_mask", "Split Mask", MK, TransitionType.SPLIT_WIPE).dur(.6f,.2f,2f).tags("mask","split").build());
        reg(new TransitionPreset.B("center_mask", "Center Mask", MK, TransitionType.CENTER_WIPE).dur(.6f,.2f,2f).tags("mask","center").build());
        reg(new TransitionPreset.B("radial_mask", "Radial Mask", MK, TransitionType.RADIAL_REVEAL).dur(.6f,.2f,2f).tags("mask","radial").build());
        reg(new TransitionPreset.B("spiral_mask", "Spiral Mask", MK, TransitionType.SWIRL).dir("mask").dur(.8f,.3f,2f).tags("mask","spiral","swirl").build());
        reg(new TransitionPreset.B("wave_mask", "Wave Mask", MK, TransitionType.WAVE_WARP).dir("mask").dur(.7f,.3f,2f).tags("mask","wave").build());
        reg(new TransitionPreset.B("feather_mask", "Feather Mask", MK, TransitionType.FEATHER_MASK).dir("right").dur(.6f,.2f,2f).tags("mask","feather","soft").build());

        // ============ SHAPE ============
        reg(new TransitionPreset.B("shape_circle", "Circle", SH, TransitionType.CIRCLE_REVEAL).dur(.6f,.2f,2f).tags("shape","circle").build());
        reg(new TransitionPreset.B("shape_diamond", "Diamond", SH, TransitionType.SHAPE_REVEAL).dir("diamond").dur(.6f,.2f,2f).tags("shape","diamond").build());
        reg(new TransitionPreset.B("shape_square", "Square", SH, TransitionType.SHAPE_REVEAL).dir("rect").dur(.6f,.2f,2f).tags("shape","square").build());
        reg(new TransitionPreset.B("shape_rectangle", "Rectangle", SH, TransitionType.SHAPE_REVEAL).dir("rect").dur(.6f,.2f,2f).tags("shape","rectangle").build());
        reg(new TransitionPreset.B("shape_triangle", "Triangle", SH, TransitionType.SHAPE_REVEAL).dir("triangle").dur(.6f,.2f,2f).tags("shape","triangle").build());
        reg(new TransitionPreset.B("shape_star", "Star", SH, TransitionType.SHAPE_REVEAL).dir("star").dur(.7f,.3f,2f).tags("shape","star").build());
        reg(new TransitionPreset.B("shape_heart", "Heart", SH, TransitionType.SHAPE_REVEAL).dir("heart").dur(.7f,.3f,2f).tags("shape","heart","love").build());
        reg(new TransitionPreset.B("shape_hexagon", "Hexagon", SH, TransitionType.SHAPE_REVEAL).dir("hexagon").dur(.6f,.2f,2f).tags("shape","hexagon","polygon").build());
        reg(new TransitionPreset.B("shape_polygon", "Polygon", SH, TransitionType.SHAPE_REVEAL).dir("hexagon").dur(.6f,.2f,2f).tags("shape","polygon").build());
        reg(new TransitionPreset.B("shape_spiral", "Spiral", SH, TransitionType.SWIRL).dur(.8f,.3f,2f).tags("shape","spiral","swirl").build());
        reg(new TransitionPreset.B("shape_wave", "Wave", SH, TransitionType.WAVE_WARP).dur(.7f,.3f,2f).tags("shape","wave").build());
        reg(new TransitionPreset.B("shape_blob", "Blob", SH, TransitionType.BULGE).dur(.7f,.3f,2f).tags("shape","blob","liquid").build());
        reg(new TransitionPreset.B("shape_liquid", "Liquid", SH, TransitionType.LIQUID_WIPE).dur(.7f,.3f,2f).tags("shape","liquid").build());
        reg(new TransitionPreset.B("shape_ripple", "Ripple", SH, TransitionType.RIPPLE_X).dur(.7f,.3f,2f).tags("shape","ripple","wave").build());

        // ============ SLIDE ============
        reg(new TransitionPreset.B("slide_cover_up", "Cover Up", SL, TransitionType.COVER).dir("up").dur(.5f,.2f,1.5f).tags("slide","cover").build());
        reg(new TransitionPreset.B("slide_cover_down", "Cover Down", SL, TransitionType.COVER).dir("down").dur(.5f,.2f,1.5f).tags("slide","cover").build());
        reg(new TransitionPreset.B("push", "Push", SL, TransitionType.PUSH_LEFT).dur(.5f,.2f,1.5f).tags("slide","push").build());
        reg(new TransitionPreset.B("slide", "Slide", SL, TransitionType.SLIDE_LEFT).dur(.5f,.2f,1.5f).tags("slide").build());

        // ============ CINEMATIC ============
        reg(new TransitionPreset.B("cinematic_fade", "Cinematic Fade", CI, TransitionType.CINEMATIC_FADE).dur(.8f,.3f,2.5f).trend().tags("cinematic","fade","film").build());
        reg(new TransitionPreset.B("film_fade", "Film Fade", CI, TransitionType.CINEMATIC_FADE).dur(.8f,.3f,2.5f).tags("film","fade","cinematic").build());
        reg(new TransitionPreset.B("film_burn", "Film Burn", CI, TransitionType.FILM_BURN).tint(0xFFFF9A3D).dur(.7f,.3f,2f).tags("film","burn","light","warm").build());
        reg(new TransitionPreset.B("film_erase", "Film Erase", CI, TransitionType.FILM_ERASE).dur(.6f,.2f,2f).tags("film","erase","grain").build());
        reg(new TransitionPreset.B("film_flash_cine", "Film Flash (cinematic)", CI, TransitionType.FILM_FLASH).dur(.4f,.2f,1.2f).tags("film","flash","cinematic").build());
        reg(new TransitionPreset.B("film_roll", "Film Roll", CI, TransitionType.FILM_ROLL).dir("up").dur(.6f,.2f,2f).tags("film","roll","analog").build());
        reg(new TransitionPreset.B("film_shake", "Film Shake", CI, TransitionType.FILM_SHAKE).dur(.5f,.2f,1.5f).tags("film","shake","handheld").build());
        reg(new TransitionPreset.B("film_grain_t", "Film Grain", CI, TransitionType.FILM_GRAIN_X).dur(.6f,.2f,2f).tags("film","grain","noise").build());
        reg(new TransitionPreset.B("vintage_fade", "Vintage Fade", CI, TransitionType.VINTAGE_FADE).tint(0xFFD9A86C).dur(.8f,.3f,2.5f).tags("vintage","fade","retro","warm").build());
        reg(new TransitionPreset.B("retro_split", "Retro Split", CI, TransitionType.SPLIT_WIPE).dur(.6f,.2f,2f).trend().tags("retro","split","vintage").build());
        reg(new TransitionPreset.B("dust_t", "Dust", CI, TransitionType.DUST_X).dur(.7f,.3f,2f).tags("dust","particle","film").build());
        reg(new TransitionPreset.B("scratch_t", "Scratch", CI, TransitionType.SCRATCH_X).dur(.7f,.3f,2f).tags("scratch","film","damage").build());
        reg(new TransitionPreset.B("light_leak_t", "Light Leak Transition", CI, TransitionType.LIGHT_LEAK).tint(0xFFFF5E7A).dur(.7f,.3f,2f).tags("light","leak","film","warm").build());
        reg(new TransitionPreset.B("cinematic_zoom", "Cinematic Zoom", CI, TransitionType.CINEMATIC_ZOOM).dur(.7f,.3f,2f).tags("cinematic","zoom","film").build());
        reg(new TransitionPreset.B("cinematic_blur_t", "Cinematic Blur", CI, TransitionType.CINEMATIC_BLUR).dur(.6f,.2f,2f).tags("cinematic","blur").build());
        reg(new TransitionPreset.B("cinematic_wipe", "Cinematic Wipe", CI, TransitionType.CINEMATIC_WIPE).dir("left").dur(.7f,.3f,2f).tags("cinematic","wipe").build());
        reg(new TransitionPreset.B("cinematic_push", "Cinematic Push", CI, TransitionType.CINEMATIC_PUSH).dir("left").dur(.7f,.3f,2f).tags("cinematic","push").build());

        // ============ LIQUID / DISTORTION ============
        reg(new TransitionPreset.B("liquid_wipe", "Liquid Wipe", LQ, TransitionType.LIQUID_WIPE).dir("left").dur(.7f,.3f,2f).trend().tags("liquid","wipe","water").build());
        reg(new TransitionPreset.B("liquid_stretch", "Liquid Stretch", LQ, TransitionType.LIQUID_STRETCH).dir("left").dur(.6f,.2f,2f).tags("liquid","stretch","goo").build());
        reg(new TransitionPreset.B("ripple", "Ripple", LQ, TransitionType.RIPPLE_X).dur(.7f,.3f,2f).tags("ripple","water","wave").build());
        reg(new TransitionPreset.B("wave", "Wave", LQ, TransitionType.WAVE_WARP).dur(.7f,.3f,2f).tags("wave","water").build());
        reg(new TransitionPreset.B("water_ripple", "Water Ripple", LQ, TransitionType.RIPPLE_X).intensity(.9f).dur(.7f,.3f,2f).tags("water","ripple").build());
        reg(new TransitionPreset.B("lens_warp", "Lens Warp", LQ, TransitionType.LENS_WARP).dur(.6f,.2f,2f).tags("lens","warp","distort").build());
        reg(new TransitionPreset.B("bulge", "Bulge", LQ, TransitionType.BULGE).dur(.6f,.2f,2f).tags("bulge","warp","distort").build());
        reg(new TransitionPreset.B("pinch", "Pinch", LQ, TransitionType.PINCH).dur(.6f,.2f,2f).tags("pinch","warp","distort").build());
        reg(new TransitionPreset.B("swirl", "Swirl", LQ, TransitionType.SWIRL).dur(.7f,.3f,2f).tags("swirl","spiral","distort").build());
        reg(new TransitionPreset.B("twist", "Twist", LQ, TransitionType.TWIST).dur(.7f,.3f,2f).tags("twist","swirl","rotate").build());
        reg(new TransitionPreset.B("wave_warp", "Wave Warp", LQ, TransitionType.WAVE_WARP).intensity(.9f).dur(.7f,.3f,2f).tags("wave","warp","distort").build());
        reg(new TransitionPreset.B("glass_warp", "Glass Warp", LQ, TransitionType.LENS_WARP).intensity(.7f).dur(.6f,.2f,2f).tags("glass","warp","refraction").build());
        reg(new TransitionPreset.B("heat_wave", "Heat Wave", LQ, TransitionType.HEAT_WAVE).dur(.7f,.3f,2f).tags("heat","wave","shimmer","distort").build());
        reg(new TransitionPreset.B("elastic", "Elastic", LQ, TransitionType.ELASTIC).dur(.6f,.2f,2f).tags("elastic","bounce","spring").build());
        reg(new TransitionPreset.B("rubber", "Rubber", LQ, TransitionType.ELASTIC).intensity(.8f).dur(.6f,.2f,2f).tags("rubber","elastic","stretch").build());
        reg(new TransitionPreset.B("stretch", "Stretch", LQ, TransitionType.LIQUID_STRETCH).intensity(.8f).dur(.6f,.2f,2f).tags("stretch","elastic").build());
        reg(new TransitionPreset.B("compress", "Compress", LQ, TransitionType.SQUEEZE_SNAP).dur(.5f,.2f,1.5f).tags("compress","squeeze").build());
        reg(new TransitionPreset.B("melt", "Melt", LQ, TransitionType.MELT).dir("down").dur(.7f,.3f,2f).tags("melt","liquid","drip").build());

        // ============ DYNAMIC / TRENDING ============
        reg(new TransitionPreset.B("wisp_portal", "Wisp Portal", DY, TransitionType.TUNNEL_3D).dur(.7f,.3f,2f).trend().neu().tags("portal","wisp","3d","smoke").build());
        reg(new TransitionPreset.B("film_erase_d", "Film Erase (trend)", DY, TransitionType.FILM_ERASE).dur(.6f,.2f,2f).trend().tags("film","erase","grain").build());
        reg(new TransitionPreset.B("petal_wind", "Petal Wind", DY, TransitionType.ASH_SPREAD).tint(0xFFFF9FCB).dur(.8f,.3f,2.5f).tags("petal","wind","flower","soft").build());
        reg(new TransitionPreset.B("dust_flurry", "Dust Flurry", DY, TransitionType.DUST_X).dur(.7f,.3f,2f).tags("dust","particle","flurry").build());
        reg(new TransitionPreset.B("twinkle_zoom", "Twinkle Zoom", DY, TransitionType.TWINKLE_ZOOM).dur(.6f,.2f,2f).trend().tags("twinkle","zoom","sparkle").build());
        reg(new TransitionPreset.B("then_and_now", "Then and Now", DY, TransitionType.COMPARISON).dir("left").dur(.8f,.3f,2.5f).tags("comparison","then","now","split").build());
        reg(new TransitionPreset.B("shadow_wipe", "Shadow Wipe", DY, TransitionType.CINEMATIC_WIPE).dir("left").dur(.7f,.3f,2f).tags("shadow","wipe","dark").build());
        reg(new TransitionPreset.B("comparison", "Comparison", DY, TransitionType.COMPARISON).dir("left").dur(.8f,.3f,2.5f).tags("comparison","split","before","after").build());
        reg(new TransitionPreset.B("gradual_fade", "Gradual Fade", DY, TransitionType.GRADUAL_FADE).dur(.9f,.4f,3f).tags("gradual","fade","slow","smooth").build());
        reg(new TransitionPreset.B("chrome_wave", "Chrome Wave", DY, TransitionType.CHROME_WAVE).dur(.6f,.2f,2f).trend().tags("chrome","wave","metallic","holographic").build());
        reg(new TransitionPreset.B("bulge_bling", "Bulge Bling", DY, TransitionType.BULGE_BLING).dur(.6f,.2f,2f).tags("bulge","bling","sparkle").build());
        reg(new TransitionPreset.B("messy_circles", "Messy Circles", DY, TransitionType.MESSY_CIRCLES).dur(.7f,.3f,2f).tags("circles","bubbles","playful").build());
        reg(new TransitionPreset.B("wildfire_scan", "Wildfire Scan", DY, TransitionType.WILDFIRE_SCAN).tint(0xFFFF5A2C).dir("right").dur(.6f,.2f,2f).tags("wildfire","scan","fire","energy").build());
        reg(new TransitionPreset.B("fast_gallery", "Fast Gallery", DY, TransitionType.GALLERY_ZOOM).dur(.4f,.15f,1f).tags("gallery","fast","slideshow").build());
        reg(new TransitionPreset.B("gallery_slide", "Gallery Slide", DY, TransitionType.GALLERY_SLIDE).dir("left").dur(.5f,.2f,1.5f).tags("gallery","slide","slideshow").build());
        reg(new TransitionPreset.B("gallery_wipe", "Gallery Wipe", DY, TransitionType.GALLERY_SLIDE).dir("left").intensity(.7f).dur(.5f,.2f,1.5f).tags("gallery","wipe").build());
        reg(new TransitionPreset.B("gallery_zoom", "Gallery Zoom", DY, TransitionType.GALLERY_ZOOM).dur(.5f,.2f,1.5f).tags("gallery","zoom","slideshow").build());
        reg(new TransitionPreset.B("retro_split_d", "Retro Split (trend)", DY, TransitionType.SPLIT_WIPE).dur(.6f,.2f,2f).tags("retro","split").build());
        reg(new TransitionPreset.B("blackout_swipe", "Blackout Swipe", DY, TransitionType.BLACKOUT_SWIPE).dir("left").dur(.5f,.2f,1.5f).trend().tags("blackout","swipe","dark").build());
        reg(new TransitionPreset.B("compression_spin", "Compression Spin", DY, TransitionType.COMPRESSION_SPIN).dur(.6f,.2f,2f).tags("compression","spin","squeeze").build());
        reg(new TransitionPreset.B("shake_shift", "Shake Shift", DY, TransitionType.SHAKE_SHIFT).dir("right").dur(.4f,.2f,1.2f).trend().tags("shake","shift","punch").build());
        reg(new TransitionPreset.B("glare", "Glare", DY, TransitionType.GLARE).dur(.5f,.2f,1.5f).trend().tags("glare","shine","light").build());
        reg(new TransitionPreset.B("glare_2", "Glare II", DY, TransitionType.GLARE).intensity(.8f).dur(.5f,.2f,1.5f).tags("glare","shine").build());
        reg(new TransitionPreset.B("glare_3", "Glare III", DY, TransitionType.GLARE).intensity(1f).dur(.4f,.2f,1.2f).tags("glare","shine").build());
        reg(new TransitionPreset.B("glare_sensation", "Glare Sensation", DY, TransitionType.LIGHT_SWEEP).intensity(1f).dur(.6f,.2f,2f).tags("glare","sensation","shine").build());
        reg(new TransitionPreset.B("dark_scale", "Dark Scale", DY, TransitionType.DARK_SCALE).dur(.6f,.2f,2f).trend().tags("dark","scale","zoom","moody").build());
        reg(new TransitionPreset.B("fade_wipe_d", "Fade Wipe (trend)", DY, TransitionType.FADE_WIPE).dir("left").dur(.6f,.2f,2f).tags("fade","wipe").build());
        reg(new TransitionPreset.B("vertical_blur_d", "Vertical Blur (trend)", DY, TransitionType.BLUR_DIRECTIONAL).dir("down").intensity(1f).dur(.5f,.2f,1.5f).tags("vertical","blur").build());
        reg(new TransitionPreset.B("random_gallery", "Random Gallery", DY, TransitionType.RANDOM_GALLERY).dur(.5f,.2f,1.5f).tags("gallery","random","slideshow","fun").build());

        // ============ GALLERY (v1.8) — 16 real multi-panel renderers ============
        // 16 presets map onto 15 TransitionTypes: the left/right 3D-scroll
        // pair shares GALLERY_SCROLL_3D via the preset's direction field.
        reg(new TransitionPreset.B("gal_motion", "Motion Gallery", GAL, TransitionType.GALLERY_MOTION).dur(.8f,.35f,2f).trend().neu().tags("gallery","motion","panels","drift").desc("Panels drift into place with coordinated offsets").build());
        reg(new TransitionPreset.B("gal_wall", "Wall Gallery", GAL, TransitionType.GALLERY_WALL).dur(.9f,.4f,2.5f).neu().tags("gallery","wall","tiles","wave").desc("Tiled image wall with a diagonal wave").build());
        reg(new TransitionPreset.B("gal_wall_v", "Gallery Wall", GAL, TransitionType.GALLERY_WALL_V).dir("down").dur(.9f,.4f,2.5f).neu().tags("gallery","wall","vertical","tiles").desc("Tiled image wall, vertical wave").build());
        reg(new TransitionPreset.B("gal_scroll3d", "3D Gallery Scroll", GAL, TransitionType.GALLERY_SCROLL_3D).dir("left").dur(.9f,.35f,2.5f).trend().neu().tags("3d","gallery","scroll","camera").desc("Perspective filmstrip scroll").build());
        reg(new TransitionPreset.B("gal_scroll3d_r", "3D-Gallery Scroll", GAL, TransitionType.GALLERY_SCROLL_3D).dir("right").intensity(.8f).dur(.9f,.35f,2.5f).neu().tags("3d","gallery","scroll").desc("Perspective filmstrip scroll, right to left").build());
        reg(new TransitionPreset.B("gal_align", "Gallery Alignment", GAL, TransitionType.GALLERY_ALIGN).dur(1f,.5f,3f).neu().tags("gallery","align","grid","scatter").desc("Scattered panels align into a grid").build());
        reg(new TransitionPreset.B("gal_social", "Social Gallery", GAL, TransitionType.GALLERY_SOCIAL).dur(.9f,.4f,2.5f).neu().tags("social","gallery","cards","feed").desc("Social-feed cards, staggered").build());
        reg(new TransitionPreset.B("gal_frame", "Gallery Frame", GAL, TransitionType.GALLERY_FRAME).dur(.8f,.35f,2.5f).neu().tags("gallery","frame","photo","border").desc("Framed panel presentation").build());
        reg(new TransitionPreset.B("gal_cam", "Cam Gallery", GAL, TransitionType.GALLERY_CAM).dur(1f,.5f,3f).neu().tags("camera","gallery","viewfinder","zoom").desc("Camera viewfinder with brackets and zoom settle").build());
        reg(new TransitionPreset.B("gal_space", "Space Gallery", GAL, TransitionType.GALLERY_SPACE).dur(1f,.5f,3f).neu().tags("space","gallery","3d","depth").desc("Depth planes flying through 3D space").build());
        reg(new TransitionPreset.B("gal_preview", "Gallery Preview", GAL, TransitionType.GALLERY_PREVIEW).dur(.9f,.4f,2.5f).neu().tags("gallery","preview","scrub","editor").desc("Editor preview-card with scrub line").build());
        reg(new TransitionPreset.B("gal_grid", "Gallery Grid", GAL, TransitionType.GALLERY_GRID).dur(.9f,.4f,2.5f).neu().tags("gallery","grid","tiles","cascade").desc("3×3 tile cascade").build());
        reg(new TransitionPreset.B("gal_messy", "Messy Gallery", GAL, TransitionType.GALLERY_MESSY).dur(.8f,.35f,2.5f).neu().tags("gallery","messy","scattered","fun").desc("Controlled irregular panels").build());
        reg(new TransitionPreset.B("gal_morph", "Gallery Morph", GAL, TransitionType.GALLERY_MORPH).dur(.8f,.35f,2.5f).trend().neu().tags("gallery","morph","quadrant","grid").desc("Quadrant matrix morph").build());
        reg(new TransitionPreset.B("gal_carousel", "Gallery Carousel", GAL, TransitionType.GALLERY_CAROUSEL).dur(1f,.4f,3f).neu().tags("3d","gallery","carousel","ring").desc("3D ring swing with real perspective").build());
        reg(new TransitionPreset.B("gal_columns", "Gallery Columns", GAL, TransitionType.GALLERY_COLUMNS).dur(.8f,.35f,2.5f).neu().tags("gallery","columns","vertical","reveal").desc("Vertical column reveal").build());

        // ============ SOCIAL (v1.8) — real presets on existing renderers ============
        reg(new TransitionPreset.B("stories_pop", "Stories Pop", SO, TransitionType.FAKE_ZOOM).trend().dur(.5f,.2f,1.5f).tags("social","stories","pop","reel").desc("Punchy stories-style pop").build());
        reg(new TransitionPreset.B("feed_swipe", "Feed Swipe", SO, TransitionType.DRAG_SWITCH).dir("right").dur(.6f,.3f,2f).tags("social","feed","swipe","stories").desc("Feed-card swipe").build());
        reg(new TransitionPreset.B("reel_zoom", "Reel Zoom", SO, TransitionType.ZOOM_SWITCH).trend().dur(.5f,.2f,1.5f).tags("social","reel","zoom","tiktok").desc("Reel-style zoom switch").build());

        // ============ PHOTO (v1.8) — real presets on existing renderers ============
        reg(new TransitionPreset.B("polaroid_pop", "Polaroid Pop", PH, TransitionType.SHAPE_REVEAL).dir("roundrect").dur(.7f,.3f,2f).tags("photo","polaroid","frame","memory").desc("Polaroid-style framed reveal").build());
        reg(new TransitionPreset.B("album_flip", "Album Flip", PH, TransitionType.PAGE_TURN_3D).dur(.7f,.3f,2f).tags("photo","album","flip","3d","memory").desc("Photo album page turn").build());
    }

    public static List<TransitionPreset> all() { return ALL; }

    public static TransitionPreset byId(String id) {
        if (id == null) return null;
        TransitionPreset p = BY_ID.get(id);
        if (p != null) return p;
        // legacy: a raw TransitionType name may have been stored
        try {
            TransitionType t = TransitionType.valueOf(id);
            for (TransitionPreset pr : ALL) if (pr.type == t) return pr;
        } catch (Exception ignored) {}
        return null;
    }

    /** Presets in one category (RECENT/FAVORITES are filled by the UI from stores). */
    public static List<TransitionPreset> byCategory(TransitionCategory cat) {
        List<TransitionPreset> out = new ArrayList<>();
        for (TransitionPreset p : ALL) if (p.category == cat) out.add(p);
        return out;
    }

    public static List<TransitionPreset> trending() {
        List<TransitionPreset> out = new ArrayList<>();
        for (TransitionPreset p : ALL) if (p.isTrending) out.add(p);
        return out;
    }

    /** Search by name/category/tags/id; invalid/empty query returns all. */
    public static List<TransitionPreset> search(String query) {
        List<TransitionPreset> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return ALL;
        String q = query.trim().toLowerCase();
        for (TransitionPreset p : ALL) if (p.hasTag(q)) out.add(p);
        return out;
    }

    /** Safe fallback renderer if a preset/renderer fails (Part 40: never crash). */
    public static TransitionPreset fallback() { return BY_ID.get("fade"); }

    public static int count() { return ALL.size(); }
}
