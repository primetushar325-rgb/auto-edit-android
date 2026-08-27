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

## 2026-08 Gallery/export hardening

Exports are now created through `MediaStore.Video.Media` on Android 10+ using `IS_PENDING`, then published into `Movies/AutoEdit/` after a successful muxer close. Android 9 and below use public Movies/AutoEdit plus `MediaScannerConnection.scanFile()`.

Export presets include 16:9 1920x1080, 9:16 1080x1920, 1:1 1080x1080, 4:5 1080x1350, 4:3 1440x1080, and custom W/H. Last-used preset, FPS, and Fit/Fill mode are persisted with the project.

For speed and memory safety, source images are pre-decoded into a disk-based scaled export cache on a background thread pool. Rendering holds only a small LRU of scaled bitmaps and the current output frame; it does not retain full-resolution originals for 500-1000 image projects.
