# AutoEdit — Code Audit & Change Log

Full pre-change inspection of the existing app. This file records the bugs
found and what was changed, in order. The golden rule of this work:

> **ONE CLIP = ONE PRIMARY MOTION. A Formula is a *repeating per-clip pattern*,
> not a stack of animations inside one clip.**
>
> For a formula with `S` pattern steps, clip index `i` plays step `i % S`, and
> that single motion spans the clip's **entire own duration**.

---

## CRITICAL findings (before changes)

### C1. Formula "sequence" runs MULTIPLE motions inside ONE clip (the core bug)
`FormulaEngine.addSequence(...)` built `S1..S4` as `Formula.steps`, and
`stateAt(formula, clipProgress)` split **one clip's timeline** into 2-second
steps (`stepAtTime` by normalized time). So "Cinematic Travel" made a single
image do ZoomIn→ZoomOut→PanLeft→PanRight within its 5 s. The user's spec says
these are 4 *different clips*. **This is the multi-motion bug.** The clip
index never participates in resolution.
`PreviewView` / `FrameRenderer` also crossfade between the intra-clip steps
(`stepTransitionMix`, `nextStepStateAt`), reinforcing the wrong behavior.

### C2. No dynamic safe-scale → black/empty edges on pan/slide
`FrameRenderer.computeFill` / `PreviewView.fill` scale the bitmap to *cover*
the canvas then pan it by `st.x*w / st.y*h`, but the keyframe scale (e.g. 1.05)
is smaller than what the pan offset (e.g. 0.12) requires. Derived requirement:
cover must satisfy `scale*minCrop ≥ 1 + 2·|pan|/minAspect` (plus rotation
margin). Motion "02" (pan 0.12, scale 1.05) exposes an empty edge. There is no
single shared, aspect-aware safe-scale calculator — the math is duplicated in
PreviewView and FrameRenderer.

### C3. Frame timing model differs between preview and export
- Export (`VideoExporter`) renders `frameIndex/fps` over the *whole project*
  and each clip emits `round(dur*fps)` frames **starting at progress 0**, so a
  boundary frame is rendered twice (incoming clip at p=0 overlaps the outgoing
  clip's last frame) and timestamps drift from real clip junctions.
- Preview loops with wall-clock and finds the clip by accumulated time.
Both should resolve (clipIndex, localProgress) from the same timeline model.

### C4. Exported frame-count / duration mismatch
`EditProject.totalFrames()` = `round(totalDur*fps)` but the export loop emits
`Σ round(clipDur*fps)`, which can differ by several frames → last-frame/
duration drift.

### C5. Export logo / completion problems
- `ExportRingView` draws the logo at a **square** `ls × ls` rect
  (`drawBitmap(bmp,-ls/2,-ls/2,...)` with a uniform `scale(s,s)`): a non-square
  logo is squished/stretched and can look clipped. It ignores the bitmap's
  aspect ratio.
- `playVideo()` uses a raw MediaStore `content://` URI with `video/mp4` but
  grants no persistent read and a chooser without `FLAG_ACTIVITY_NEW_TASK`;
  some players fail on it. There is no in-app fallback and no verification.
- The completion thumbnail uses `MediaStore.Video.Thumbnails.getThumbnail`
  (deprecated/unreliable on modern Android).

### C6. Transition coverage gaps
- `TransitionEngine.incoming()` only handles FADE/CROSS/ZOOM/SLIDE/PUSH;
  WIPE_*, CIRCLE_REVEAL, RADIAL_REVEAL, BLUR_TRANSITION, CINEMATIC_BLUR fall
  into the dissolve default. `rendered()` exposes only 8 transitions.
- PUSH == SLIDE (no real outgoing push). Slide/push incoming clip pan must
  also be safe-scaled or it shows an edge while entering.

### C7. EffectEngine stubs
Only ~9 effects have real color matrices; SHARPEN/MOTION_BLUR/BLUR/EXPOSURE/
HIGHLIGHTS/SHADOWS/TEMPERATURE/SOFT_GLOW/BLOOM/SOFT_FOCUS mostly collapse to a
saturation tweak or nothing. Motion panel hardcodes only 8 motions; Effects/
Filters/Adjust are text chips with no card preview.

### C8. Custom Formula editor is a single-clip multi-keyframe editor
`CustomFormulaActivity` authors N keyframes on ONE timeline (the old
intra-clip model). The new model needs a **per-clip pattern** editor:
steps = motions (2..N), each with optional easing/transition/effect,
reorder/add/delete, plus optional start/end keyframe override.

### C9. UI / panel layout
- Editor stacks a `weight(1)` monitor + timeline + tool bar + a `ScrollView`
  panel host all in one vertical LinearLayout; opening a panel squeezes the
  monitor permanently. No overlay bottom-sheet, no collapse.
- `showPanel` uses fixed-width `dp(104)` chips in a horizontal row — long
  labels clip and the row can overflow awkwardly; no cards, no per-item
  animated preview for Motion/Effect/Transition.

### C10. Minor / robustness
- `FrameRenderer.render(...)` and `renderClipWithState` duplicate draw math.
- `PreviewView` allocates a `new Paint()` per frame (background, fit bars).
- `DiskBitmapCache.scaleDownIfNeeded` fits a 9:16 source into a 16:9 target by
  min-ratio (letterboxed upscale path) — fine but re-encodes JPEG (quality 98,
  lossy) of every source once per resolution.
- Duplicate/dead code in `VideoExporter` (`drain`, `tryOutputFormat` unused).
- `applyFormulaSequenceToAll` exists but is never wired to the UI.

---

## Architecture after the fix (single source of truth)

```
EditProject.clips[i]                         (state: id + resolved motion)
        │  clipIndex i, localClipProgress p (0..1 over the clip's OWN duration)
        ▼
FormulaEngine
   ├─ isPattern(formula)            → steps are PER-CLIP
   ├─ patternStepForClip(formula,i) → steps[ i % steps.size ]   (deterministic)
   ├─ stateForClip(formula,i,p)     → SafeMotionState (start→end keyframes,
   │                                  normalized p mapped onto clip duration,
   │                                  per-step easing + timing/hold)
   └─ transitionForClip(formula,i)  → optional per-junction transition pattern
SafeTransform  (NEW, shared)
   └─ coverScale(srcW,srcH,canvasW,canvasH, state, rotation)  → NO black edges
        used by BOTH PreviewView and FrameRenderer
TransitionEngine (extended: push, wipe, circle, radial, blur family)
EffectEngine     (extended: real matrices/post for the listed effects)
FrameRenderer / PreviewView → identical (clipIndex, progress) → transform path
```

- Built-in **Motion presets**: 24 (data-driven `MotionCatalog`).
- Built-in **Formula patterns**: 20 (data-driven `FormulaCatalog`), each a
  real `steps[]` of motion ids; any step count 2..N allowed.
- Custom formulas stored as pattern steps and resolved through the same path.
- Existing saved projects still load: old single-motion ids (00..20) and old
  sequence ids (S1..S4) resolve; old Sx sequences are reinterpreted as the
  new per-clip pattern (same motion list, now one motion per clip).
