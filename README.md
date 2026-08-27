# Auto Edit

Auto Edit is an offline-first Android bulk image-to-video editor with a black/blue professional UI. It imports hundreds/thousands of images using Android document/photo picker flows, creates one timeline clip per image with a 3-second default duration, supports apply-to-all duration/formula/transition/effect operations, project save/load, undo/redo, text/audio overlay metadata, preview, and a memory-safe native MP4 export pipeline built around incremental frame rendering and Android MediaCodec/MediaMuxer.

## Build

```bash
./gradlew clean testDebugUnitTest assembleDebug
```

GitHub Actions workflow is included in `.github/workflows/android.yml`.

## Export architecture

`Project -> TimelineClip -> FormulaEngine/Keyframes -> Transition/Effects/Text/Audio -> FrameRenderer -> MediaCodecVideoEncoder -> MediaMuxer -> MP4`

The renderer decodes only the currently needed image with `BitmapFactory.Options.inSampleSize`, renders into a reusable frame bitmap, releases temporary images immediately, and never keeps all full-resolution images in memory.
