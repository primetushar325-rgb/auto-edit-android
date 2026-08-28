# Auto-Edit Architecture Map

## Core model (com.autoedit.model)
- `EditProject`: canvas/export settings + ordered `ArrayList<TimelineClip>`.
- `TimelineClip`: one imported image — uri, own duration (ms/sec, clamped 3–8s),
  `Formula` reference, `TransitionType` (the junction AFTER this clip) +
  transition duration, `EffectType` + intensity. State only; never media bytes.
- `Formula`: a **repeating per-clip pattern**. `steps == null` = a single
  motion applied to every clip; `steps != null` = pattern whose step
  `clipIndex % steps.size()` defines that clip's ONE primary motion. Normalized
  timing fields (`motionStartProgress/motionEndProgress/holdUntilProgress`).
- `FormulaStep`: the single motion for one clip (`motion` = start→end
  keyframes), optional easing, optional effect, optional junction `transition`.
- `KeyframeState`: x, y (fractions of canvas), scale, rotation, opacity +
  `lerp`. `Easing`: 8 curves (Linear/EaseIn/EaseOut/EaseInOut/Cubic/Quint/
  Sine/Expo). `EffectType`, `TransitionType`, `FitMode`, `AspectRatio`,
  `ExportPreset`, `TextOverlay`, `PromptItem`.

## Engines (com.autoedit.engine) — single source of truth
- `MotionCatalog`: 24 data-driven single MOTION presets (Basic/Cinematic/
  Premium) — id, name, category, start/end keyframes, easing. Ids "00".."30"
  stay compatible with old project files.
- `FormulaCatalog`: 20 built-in FORMULA patterns ("F01".."F20") + a transition
  pattern demo ("F21") + legacy "S1".."S4" (reinterpreted as per-clip
  patterns). Each step references a motion id; step counts 3/4/6/8 (no cap).
- `FormulaEngine`: resolution. `stateForClip(formula, clipIndex, progress)`
  picks step `clipIndex % patternSize` and lerps that single motion over the
  clip's full normalized duration; `transitionForClip` / `effectForClip` expose
  the optional per-junction policy. Preview AND export call this.
- `SafeTransform`: the no-black-edge system. `fillRect/fitRect` compute the
  cover scale from source + canvas aspect, the max pan/travel distance and the
  rotation envelope, then add a safety margin — per image, never a blind 1.10x.
- `TransitionEngine`: 20 transitions (None, Fade, Cross Dissolve, Zoom,
  Slide/Push/Wipe × 4 dirs, Circle/Radial Reveal, Blur, Cinematic Blur) with
  `incoming()` + `outgoing()` transform + reveal masks — same math preview and
  export.
- `EffectEngine`: 23 real effects via color matrices + post overlays + a
  softening halo for blur/dream/soft-focus.

## Rendering
- `ui/PreviewView`: live preview. Resolves `(clipIndex, clipProgress)` from the
  one timeline, draws via `SafeTransform`, applies transition
  incoming/outgoing + reveal masks and effects — identical to export.
- `export/FrameRenderer`: same `(clipIndex, clipProgress)` → `SafeTransform`
  path into a reusable frame bitmap, with disk + LRU image caches.
- `export/VideoExporter`: validates the project, predecodes images
  (`DiskBitmapCache`), encodes frame `n` at time `n/fps` (no duplicated boundary
  frames) with MediaCodec → MediaMuxer MP4; strict configure→start→EOS→drain→
  stop→release lifecycle; the file is published only on success.
- Card previews (`MotionPreviewView`, `FormulaPreviewView`,
  `EffectPreviewView`, `TransitionPreviewView`) are lightweight ~20fps loops
  driven by the same engines.

## UI (com.autoedit.MainActivity, views in ui/)
- Single-activity programmatic UI. The editor keeps the monitor + timeline at
  full size; every tool (Motion/Formula/Effects/Filters/Transition/Duration/
  Text/Audio/Canvas/Adjust/Auto Edit) opens an overlay **`PanelSheet`** bottom
  sheet (dim scrim, drag-to-dismiss, ✕ Close, back-press collapses) — the
  preview never permanently shrinks and no state gets stuck.
- Preset cards are horizontally swipeable (HorizontalScrollView wrapping
  wrap-content rows) with real animated previews; long-press favorites a card
  (`project/FavoritesStore`).
- Custom formulas: `formula/CustomFormulaActivity` authors pattern STEPS
  (motion per clip, add/remove/reorder ↑↓, easing, optional effect/transition),
  persisted by `project/CustomFormulaStore` as a `steps[]` JSON; resolved
  through the same `FormulaEngine`. Unlimited saved formulas.
- Export: `export/ExportService` + `ExportDestination` (MediaStore IS_PENDING on
  Q+, public Movies/AutoEdit below). Completion screen verifies the MediaStore
  URI, shows a real thumbnail and Play/Share with granted read permission.
  `ui/ExportRingView` draws the aspect-preserved centered logo.

## Other
- `frames/`: offline video frame extractor (MediaMetadataRetriever → ZIP).
- `update/`: mandatory-update system (version.json on main).
- Persistence: `project/ProjectStore` (JSON in SharedPreferences; old ids
  00..20/S1..S4 and legacy keyframe custom docs all still load).
