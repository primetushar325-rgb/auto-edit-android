# Auto Edit

Auto Edit is an offline-first Android bulk image-to-video editor with a black/blue professional UI. It imports hundreds/thousands of images using Android document/photo picker flows, creates one timeline clip per image with a 3-second default duration, supports apply-to-all duration/formula/transition/effect operations, project save/load, undo/redo, text/audio overlay metadata, preview, and a memory-safe native MP4 export pipeline built around incremental frame rendering and Android MediaCodec/MediaMuxer.

## Build

```bash
./gradlew clean testDebugUnitTest assembleDebug
```

GitHub Actions workflows are included: `.github/workflows/android.yml` (CI) and `.github/workflows/release.yml` (GitHub Release + APK + version.json).

## Export architecture

`Project -> TimelineClip -> FormulaEngine/Keyframes -> Transition/Effects/Text/Audio -> FrameRenderer -> MediaCodecVideoEncoder -> MediaMuxer -> MP4`

The renderer decodes only the currently needed image with `BitmapFactory.Options.inSampleSize`, renders into a reusable frame bitmap, releases temporary images immediately, and never keeps all full-resolution images in memory.

## Custom Formulas

Settings → Custom Formula (or the Formula panel's "Custom Formulas" row) opens the creator: name, category, one preview image (reference only — never modified), total duration and 2–4 keyframes. Each keyframe stores time, zoom, pan X/Y, rotation, opacity and easing, plus optional per-step transition and effect. Motion presets fill values from the existing built-in motions. The live preview and the actual export render through the SAME `FormulaEngine` (keyframes → steps → `KeyframeState` lerp with easing), so preview == export. Saved formulas appear in the editor Formula panel with a CUSTOM badge; applying is undo/redo-safe and never touches original media.

## Video Frame Extractor (offline)

Home → 🎬 Video Frame Extractor. Pick any locally decodable video (MP4/MOV/MKV/WEBM/3GP), choose interval (presets or custom), frame size/aspect (Original, 16:9, 9:16, 1:1, 4:5, 4:3, 3:4, 3:2, 2:3, custom — with Center/Top/Bottom/Smart crop, never stretched), format (JPG/PNG/WEBP + quality) and an optional start/end range. Frames are extracted locally with `MediaMetadataRetriever` on a background service (one frame in memory at a time, cancelable, with real progress + ETA), previewed in a grid (select/delete), and packed into a ZIP that can be saved via the Storage Access Framework, shared, or opened through a FileProvider `content://` URI. No network is used.

## Mandatory update system

On startup the app checks `version.json` (hosted on the `main` branch of this repo) with a fallback to the GitHub Release asset. If the installed `versionCode` is below `minimumSupportedVersionCode`, a blocking "New Version Available" screen opens (no skip) and downloads the APK from the URL given by `version.json`, then hands it to the Android package installer via FileProvider. Offline/failed checks fall back to the last cached config; with no cache the app opens normally. The release workflow regenerates `version.json` from `versionCode`/`versionName` in `app/build.gradle.kts` (+ optional `release-config.json` overrides) — you only bump the version and push a `v*` tag.

## 2026-08 Gallery/export hardening

Exports are now created through `MediaStore.Video.Media` on Android 10+ using `IS_PENDING`, then published into `Movies/AutoEdit/` after a successful muxer close. Android 9 and below use public Movies/AutoEdit plus `MediaScannerConnection.scanFile()`.

Export presets include 16:9 1920x1080, 9:16 1080x1920, 1:1 1080x1080, 4:5 1080x1350, 4:3 1440x1080, and custom W/H. Last-used preset, FPS, and Fit/Fill mode are persisted with the project.

For speed and memory safety, source images are pre-decoded into a disk-based scaled export cache on a background thread pool. Rendering holds only a small LRU of scaled bitmaps and the current output frame; it does not retain full-resolution originals for 500-1000 image projects.

## Release flow (for the repo owner)

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts` (optionally edit `release-config.json` for release notes / a lower minimum version).
2. Push a `v*` tag → GitHub Actions builds, runs unit tests, assembles the release APK (signed when the `ANDROID_KEYSTORE_*` secrets are set), creates the GitHub Release with `AutoEdit-latest.apk`, `AutoEdit-vX.Y.apk` and `version.json`, and commits the updated `version.json` to `main`.
3. Old app versions below `minimumSupportedVersionCode` show the mandatory update screen and download `/releases/latest/download/AutoEdit-latest.apk`.

