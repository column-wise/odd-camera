# Odd Camera Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android camera app (photo + video, standard controls) with pluggable live GPU filters; first two filters are Light Boxes and ASCII, and captures look exactly like the preview.

**Architecture:** CameraX feeds frames into a `SurfaceTexture` owned by a single GL render thread. Each frame: camera OES texture → small analysis readback (luminance histogram + optional CPU analysis such as blob detection) → active `Filter` draws to the display surface and, while recording, to a MediaCodec input surface. Photos are full-res CameraX captures rendered offscreen through the same filter using the analysis snapshot taken at the shutter moment.

**Tech Stack:** Kotlin, CameraX (core/camera2/lifecycle), OpenGL ES 3.0, MediaCodec + MediaMuxer + AudioRecord, MediaStore, Jetpack Compose (controls overlay), JUnit4 (JVM) + AndroidX Test (instrumented, on device).

**Spec:** `docs/superpowers/specs/2026-10-17-odd-camera-design.md`

## Global Constraints

- App name **Odd Camera**; package/applicationId `com.juhyun.oddcamera`.
- minSdk **29**, targetSdk **36**; compileSdk 36 (or the installed `android-36.1`).
- Build: Gradle wrapper **9.4.1** (already cached), latest stable AGP compatible with it, Kotlin/JVM toolchain **17** (JDK at `~/.sdkman/candidates/java/17.0.14-zulu`; system default is JDK 8 — set `JAVA_HOME` for every Gradle run).
- Video: **1080p, 30 fps**, H.264 + AAC audio, MP4.
- Photo: device default still-capture size (binned ~12 MP), never 200 MP modes. Aspect: **photo 4:3, video 16:9**; preview reframes to the active mode's aspect.
- Saving: MediaStore, `DCIM/OddCamera`.
- Front camera: saved output is **mirrored exactly as the preview shows it**.
- Filter parameters are **resolution-independent** (normalized to frame width / relative thresholds).
- Brightness threshold default is **relative** ("top N % brightest").
- **Every filter's parameter values persist per filter** across launches (DataStore Preferences, key `"<filterId>.<paramKey>"`).
- ASCII glyph set is user-selectable (presets + custom string) in the same param panel as the threshold.
- Light Boxes photo shows **exactly the boxes on screen at the shutter moment** (analysis snapshot at shutter, or at timer expiry).
- Adding a filter = new `Filter` implementation + one registry line; no camera/record/save code changes.
- Must run on other Android phones, not only the S26 Ultra: performance degradation tiers + encoder fallback.
- Activity locked to portrait; output orientation follows device orientation (via `OrientationEventListener`) at shutter / recording start. (Claude's call.)
- No 4K, manual exposure, HDR, RAW, in-app gallery. (ML person segmentation allowed for ASCII v2, Task 13.)

## Review Focus

1. **Device rotated to landscape** → saved photo/video is upright landscape and still matches the preview's filter look (ASCII glyphs upright in the output). Test in Task 9.
2. **App backgrounded / lens switched / phone call mid-recording** → recording stops cleanly, MP4 is playable, camera released, no crash. Test in Task 10.
3. **Camera or microphone permission denied** → no crash; camera denied shows a rationale screen; mic denied records video without audio. Test in Task 4 (camera) and Task 10 (mic).
4. **Near-uniform frame** (lens covered, white wall) → percentile threshold must not light up the whole frame; no boxes / no ASCII cells. Test in Task 2.
5. **MediaStore insert fails** (storage full) → user sees an error toast, pending row is deleted, preview keeps running. Test in Task 9.

---

## File Structure

```
odd-camera/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew*, .gitignore
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/juhyun/oddcamera/
    MainActivity.kt                 permissions, hosts CameraScreen
    ui/CameraScreen.kt              Compose overlay: shutter, mode, flash, lens, timer, zoom, thumbnail
    ui/FilterPager.kt               swipe between filters + generated param panel
    camera/CameraController.kt      CameraX binding, zoom/flash/torch/focus/lens, ViewPort aspect
    camera/CountdownTimer.kt        pure timer logic
    filter/ParamStore.kt            per-filter persistence (DataStore)
    filter/FilterParam.kt           param declarations + ParamValues
    filter/Filter.kt                Filter / FilterRenderer / FrameAnalysis interfaces
    filter/FilterRegistry.kt        ordered list of filters
    filter/Threshold.kt             histogram + percentile threshold
    filter/none/NoneFilter.kt
    filter/boxes/BlobDetector.kt    pure: connected bright regions
    filter/boxes/BoxTracker.kt      pure: smoothing, stable ids, splits, marks
    filter/boxes/LightBoxesFilter.kt
    filter/ascii/AsciiFilter.kt     GL renderer + glyph atlas
    gl/EglCore.kt, gl/GlProgram.kt, gl/GlUtil.kt
    render/RenderThread.kt          owns EGL, SurfaceTexture, per-frame loop
    render/AnalysisReader.kt        downsample FBO + glReadPixels luminance
    render/PerfGovernor.kt          pure: tier selection from frame times
    capture/PhotoCapturer.kt        full-res capture → offscreen filter render → JPEG
    capture/MediaSaver.kt           MediaStore insert/pending/finalize
    record/VideoEncoder.kt          MediaCodec surface (or buffer fallback) + audio + muxer
    record/EncoderSelector.kt       pure-ish: pick config / fallback order
  app/src/test/java/com/juhyun/oddcamera/...       JVM tests
  app/src/androidTest/java/com/juhyun/oddcamera/... device tests
```

## Shared Interfaces (defined in Task 2 and Task 5, used everywhere)

```kotlin
// filter/FilterParam.kt
sealed interface FilterParam { val key: String; val label: String }
data class FloatParam(override val key: String, override val label: String,
                      val min: Float, val max: Float, val default: Float, val step: Float) : FilterParam
data class IntParam(override val key: String, override val label: String,
                    val min: Int, val max: Int, val default: Int) : FilterParam
data class BoolParam(override val key: String, override val label: String, val default: Boolean) : FilterParam
data class ChoiceParam(override val key: String, override val label: String,
                       val options: List<Pair<String, String>>, val default: String) : FilterParam   // (id, label)
data class TextParam(override val key: String, override val label: String,
                     val default: String, val maxCodePoints: Int) : FilterParam
class ParamValues(defs: List<FilterParam>) {          // thread-safe snapshot-able map
  fun float(key: String): Float; fun int(key: String): Int; fun bool(key: String): Boolean; fun string(key: String): String
  fun set(key: String, value: Any); fun snapshot(): ParamValues
  fun toStringMap(): Map<String, String>; fun loadFrom(map: Map<String, String>)   // persistence; unknown/invalid entries ignored
}

// filter/Filter.kt
class LumaFrame(val luma: ByteArray, val width: Int, val height: Int, val histogram: IntArray)
interface FrameAnalysis { val threshold: Int }        // absolute 0..255 luma threshold actually used
interface Filter {
  val id: String; val title: String; val params: List<FilterParam>
  fun analyze(frame: LumaFrame, params: ParamValues, previous: FrameAnalysis?): FrameAnalysis
  fun createRenderer(): FilterRenderer                 // called on GL thread
}
interface FilterRenderer {
  fun init()
  /** srcTex = GL_TEXTURE_2D RGBA of the upright, cropped, (mirrored) frame; draws into currently bound target of size w×h. */
  fun draw(srcTex: Int, w: Int, h: Int, params: ParamValues, analysis: FrameAnalysis)
  fun release()
}
```

Box geometry (Task 3): `data class Box(val x: Float, val y: Float, val w: Float, val h: Float, val id: Int, val mark: Boolean)` — normalized 0..1 frame coordinates.

---

### Task 1: Project scaffold

**Files:**
- Create: root Gradle files, `gradle/libs.versions.toml`, wrapper, `.gitignore`, `app/build.gradle.kts`, `AndroidManifest.xml`, `MainActivity.kt`, `app/src/test/.../SmokeTest.kt`

**Interfaces:** Produces: buildable `:app` module; `MainActivity` showing "Odd Camera" text.

- [ ] **Step 1:** Generate wrapper for Gradle 9.4.1; settings/root/app build files with constraints above; Compose enabled; dependencies for CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`), Compose BOM, activity-compose, JUnit4, androidx.test (runner, ext-junit). Manifest: `CAMERA`, `RECORD_AUDIO` permissions, `uses-feature android.hardware.camera.any`, activity `screenOrientation="portrait"`. `.gitignore`: `.gradle/ build/ local.properties *.iml .idea/ .superpowers/`.
- [ ] **Step 2:** `SmokeTest` asserting `1 + 1 == 2`. Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-zulu ./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 3:** `./gradlew :app:installDebug` and `adb shell am start -n com.juhyun.oddcamera/.MainActivity` → app opens on device.
- [ ] **Step 4:** Commit `chore: scaffold Android project` and push.

### Task 2: Filter params + percentile threshold

**Files:** Create `filter/FilterParam.kt`, `filter/Threshold.kt`; Test `test/.../filter/ThresholdTest.kt`, `test/.../filter/ParamValuesTest.kt`

**Interfaces:** Produces the `FilterParam`/`ParamValues` types above and:
- `fun lumaHistogram(luma: ByteArray): IntArray` (256 bins)
- `fun percentileThreshold(hist: IntArray, topPercent: Float, minContrast: Int = 24): Int` — returns smallest luma T such that pixels with luma > T are ≤ topPercent % of total; returns **256** (nothing passes) when `maxLuma - medianLuma < minContrast`.

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun top3PercentOfRampSelectsBrightestTail() {
  val luma = ByteArray(1000) { (it * 256 / 1000).toByte() }
  val t = percentileThreshold(lumaHistogram(luma), 3f)
  val passing = luma.count { (it.toInt() and 0xFF) > t }
  assertTrue(passing in 20..30)
}
@Test fun uniformFrameYieldsNothing() {               // Review Focus 4
  val luma = ByteArray(1000) { 200.toByte() }
  assertEquals(256, percentileThreshold(lumaHistogram(luma), 3f))
}
@Test fun paramValuesRoundTripAndRejectsGarbage() {
  val defs = listOf(IntParam("n", "N", 5, 150, 40), ChoiceParam("g", "G", listOf("a" to "A", "b" to "B"), "a"))
  val v = ParamValues(defs); v.set("n", 77); v.set("g", "b")
  val w = ParamValues(defs); w.loadFrom(v.toStringMap() + mapOf("n" to "oops", "zzz" to "1"))
  assertEquals(40, w.int("n")); assertEquals("b", w.string("g"))   // bad value → default, unknown key ignored
}
@Test fun paramValuesDefaultsAndSnapshotIsolation() {
  val v = ParamValues(listOf(FloatParam("p", "P", 0f, 10f, 3f, 0.5f)))
  val snap = v.snapshot(); v.set("p", 5f)
  assertEquals(3f, snap.float("p")); assertEquals(5f, v.float("p"))
}
```

- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest` → FAIL (unresolved references).
- [ ] **Step 3:** Implement. `ParamValues.set` clamps to declared range; Choice rejects unknown ids; Text truncates to `maxCodePoints`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: filter params and percentile threshold`.

### Task 3: Blob detection + box tracking (pure Kotlin)

**Files:** Create `filter/boxes/BlobDetector.kt`, `filter/boxes/BoxTracker.kt`; Test `test/.../boxes/BlobDetectorTest.kt`, `BoxTrackerTest.kt`

**Interfaces:**
- Consumes: `LumaFrame` shape (luma, width, height) — pass primitives here to stay pure.
- Produces:
  - `data class Blob(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val area: Int)` (inclusive pixel bounds)
  - `fun detectBlobs(luma: ByteArray, w: Int, h: Int, threshold: Int, minArea: Int = 2): List<Blob>` — 4-connected components of `luma > threshold`, sorted by area desc. Iterative (explicit stack), no recursion.
  - `class BoxTracker(private val smoothing: Float = 0.5f) { fun update(blobs: List<Blob>, w: Int, h: Int, maxBoxes: Int): List<Box> }`
    - Takes top `maxBoxes` blobs, normalizes, pads by 1 analysis pixel.
    - Matches each to the previous-frame box with highest IoU > 0.2 → inherits `id`, position eased: `new = prev + (target - prev) * smoothing`. Unmatched → new id (monotonic counter).
    - Blobs whose normalized area > 0.02 are emitted as 4 quadrant sub-boxes (ids `id*4+k`, k=0..3).
    - `mark = (id % 3 == 0)` (deterministic per id).

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun twoSeparateSpotsGiveTwoBlobs() {
  val w = 10; val h = 10; val l = ByteArray(100)
  l[11] = -1; l[12] = -1; l[77] = -1; l[78] = -1   // 0xFF
  val b = detectBlobs(l, w, h, threshold = 200)
  assertEquals(2, b.size); assertEquals(Blob(1, 1, 2, 1, 2), b.first { it.y0 == 1 })
}
@Test fun maxBoxesCapsOutput() { /* 50 single 2px blobs, maxBoxes = 10 → 10 boxes */ }
@Test fun stableIdAndEasingAcrossFrames() {
  val t = BoxTracker(0.5f)
  val a = t.update(listOf(Blob(10, 10, 19, 19, 100)), 100, 100, 40).single()
  val b = t.update(listOf(Blob(12, 10, 21, 19, 100)), 100, 100, 40).single()
  assertEquals(a.id, b.id); assertTrue(b.x > a.x && b.x < 0.13f)
}
@Test fun largeBlobSplitsIntoFour() { /* 30x30 blob in 100x100 → 4 boxes with ids id*4+k */ }
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: blob detection and box tracking`.

### Task 4: GL foundation + camera passthrough preview

**Files:** Create `gl/EglCore.kt`, `gl/GlProgram.kt`, `gl/GlUtil.kt`, `render/RenderThread.kt`, `camera/CameraController.kt`; Modify `MainActivity.kt`; Test `androidTest/.../PermissionDeniedTest.kt`

**Interfaces:**
- Produces:
  - `class EglCore { fun createWindowSurface(surface: Any): EGLSurface; fun makeCurrent(s: EGLSurface); fun swap(s: EGLSurface): Boolean; fun setPresentationTime(s: EGLSurface, ns: Long); fun release() }` — GLES 3 context, `EGL_RECORDABLE_ANDROID` config.
  - `class RenderThread : HandlerThread` with `fun attachDisplay(surface: Surface, w: Int, h: Int)`, `fun detachDisplay()`, `fun cameraSurfaceProvider(): Preview.SurfaceProvider`, `var mirror: Boolean`, `var outputRotationDeg: Int`.
    - On each `onFrameAvailable`: `updateTexImage`, draw OES → RGBA **frame FBO** at the camera crop size (upright via `SurfaceTexture` transform matrix + `SurfaceRequest.TransformationInfo.cropRect`, mirrored if `mirror`), then blit frame FBO to display with aspect-fit. Task 5 inserts analysis + filter between those two steps.
  - `class CameraController(context, lifecycleOwner, renderThread)` with `fun bind(lens: Int, aspect: Aspect)`; `enum class Aspect { PHOTO_4_3, VIDEO_16_9 }`. Binds `Preview` (+ `ImageCapture` in Task 9) in a `UseCaseGroup` with `ViewPort` of the aspect so preview crop == capture crop.
- `MainActivity`: `SurfaceView` (via Compose `AndroidView`) → `RenderThread.attachDisplay`. Requests CAMERA; if denied shows a rationale screen with a "Open settings" button (no crash).

- [ ] **Step 1:** Write `PermissionDeniedTest` (UiAutomator/`ActivityScenario` with permission revoked via `adb shell pm revoke` in `@Before` using `InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand`) asserting the rationale text "카메라 권한이 필요합니다" is displayed and activity is not finishing.
- [ ] **Step 2:** Run `./gradlew :app:connectedDebugAndroidTest` → FAIL.
- [ ] **Step 3:** Implement the classes above.
- [ ] **Step 4:** Run test → PASS. Manual on device: back camera preview is upright, correct aspect 4:3, no stretching.
- [ ] **Step 5:** Commit `feat: GL camera preview pipeline`.

### Task 5: Filter pipeline, registry, analysis readback, NoneFilter

**Files:** Create `filter/Filter.kt`, `filter/FilterRegistry.kt`, `filter/none/NoneFilter.kt`, `render/AnalysisReader.kt`; Modify `render/RenderThread.kt`; Test `androidTest/.../FilterPipelineTest.kt`

**Interfaces:**
- Produces the `Filter`/`FilterRenderer`/`FrameAnalysis`/`LumaFrame` types above, and:
  - `object FilterRegistry { val all: List<Filter> }` — order: None, Light Boxes, ASCII.
  - `class AnalysisReader { fun read(frameTex: Int, frameW: Int, frameH: Int, analysisWidth: Int): LumaFrame }` — draws into a small FBO (height keeps aspect), luma computed in shader into R channel, `glReadPixels` (use a PBO pair to avoid stalls), histogram via `lumaHistogram`.
  - `RenderThread`: `fun setFilter(filter: Filter, params: ParamValues)`, `val latestAnalysis: FrameAnalysis?` (volatile), `fun snapshotForCapture(): CaptureSnapshot` where `data class CaptureSnapshot(val filter: Filter, val params: ParamValues, val analysis: FrameAnalysis, val mirror: Boolean, val rotationDeg: Int)`.
  - Per frame: frame FBO → `AnalysisReader.read` → `filter.analyze(frame, params.snapshot(), previous)` → `renderer.draw(frameTex, displayW, displayH, ...)`. Renderers created lazily per filter on GL thread and cached.
  - `NoneFilter`: no params, `analyze` returns `threshold = 256`, renderer copies src.

- [ ] **Step 1:** `FilterPipelineTest`: offscreen EGL pbuffer, upload a synthetic 320×240 bitmap (black with one white 20×20 square), run `AnalysisReader.read(..., 160)` and assert histogram top bin count ≈ (10×10 analysis pixels) ±20 %; render `NoneFilter` and assert center pixel of output equals input.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; device preview unchanged visually.
- [ ] **Step 5:** Commit `feat: pluggable filter pipeline`.

### Task 6: Light Boxes filter

**Files:** Create `filter/boxes/LightBoxesFilter.kt`; Modify `FilterRegistry.kt`; Test `androidTest/.../LightBoxesRenderTest.kt`

**Interfaces:**
- Consumes: `percentileThreshold`, `detectBlobs`, `BoxTracker`, `Filter` API.
- Produces: `object LightBoxesFilter : Filter` with `id = "light_boxes"`, `title = "광원 박스"`, params:
  - `FloatParam("topPercent", "밝은 부분 상위 %", 0.2f, 20f, 3f, 0.2f)`
  - `IntParam("maxBoxes", "최대 박스 수", 5, 150, 40)`
  - `class LightBoxesAnalysis(override val threshold: Int, val boxes: List<Box>) : FrameAnalysis`
- Renderer: draws src, then each box as a white outline with line width = `max(1px, 0.0025 × w)` and a filled square of side `0.006 × w` at center when `mark`. Batch all boxes in one VBO/draw call.

- [ ] **Step 1:** `LightBoxesRenderTest`: synthetic frame with two white squares → analyze → `boxes.size == 2`; render at 360 and 1440 widths, downscale both to 90 px and assert mean absolute diff < 6/255 (resolution independence).
- [ ] **Step 2:** FAIL → **Step 3:** Implement → **Step 4:** PASS. Manual: point phone at a lamp, boxes follow without flicker.
- [ ] **Step 5:** Commit `feat: light boxes filter`.

### Task 7: ASCII filter

**Files:** Create `filter/ascii/AsciiFilter.kt`; Modify `FilterRegistry.kt`; Test `androidTest/.../AsciiRenderTest.kt`, `test/.../ascii/GlyphIndexTest.kt`

**Interfaces:**
- Produces: `object AsciiFilter : Filter`, `id = "ascii"`, `title = "아스키"`, params:
  - `FloatParam("topPercent", "밝은 부분 상위 %", 1f, 60f, 25f, 1f)`
  - `IntParam("columns", "가로 문자 수", 30, 160, 60)` (cell size = w / columns → resolution-independent)
  - `BoolParam("fullFrame", "전체 화면", false)`
  - `ChoiceParam("glyphSet", "문자 종류", options, "classic")` — presets (dark→bright ramps): `classic` " .:-=+*#%@", `dots` " ·•●", `blocks` " ░▒▓█", `binary` " 01", `custom` "직접 입력"
  - `TextParam("customGlyphs", "직접 입력 문자", "", maxCodePoints = 16)` — used when `glyphSet == custom`
  - `fun resolveRamp(glyphSet: String, custom: String, inkOf: (String) -> Float): List<String>` (pure) — preset → its ramp split by code point; custom → distinct code points sorted by `inkOf` ascending with a leading space; fewer than 1 usable glyph → classic. Works on code points (emoji / Hangul safe).
  - `fun glyphIndex(luma: Int, threshold: Int, rampSize: Int): Int` (pure; brighter → denser glyph, clamped to `0 until rampSize`)
- Glyph atlas texture built from `Canvas` text into a bitmap (monospace bold), 1 row × ramp size; **rebuilt on the GL thread whenever the resolved ramp changes**. `inkOf` = fraction of opaque pixels when the glyph is drawn into a cell bitmap. Ramp size passed as a uniform.
- Shader: per cell, sample src at cell center (mip/box average of 4 taps), compute luma. Mask mode: luma > threshold → light background `#F4F0FF` with glyph in `#555555` (every third cell `#CC3333`), else src pixel. Full-frame: black background, glyph tinted with cell color. Threshold passed as uniform from `FrameAnalysis.threshold`.

- [ ] **Step 1:** `GlyphIndexTest`: `glyphIndex(255, 100, 10) == 9`, `glyphIndex(101, 100, 10) == 0`, out-of-range clamps. `ResolveRampTest`: `resolveRamp("blocks", "", ink) == listOf(" ","░","▒","▓","█")`; custom `"@a."` with fake ink {.:0.1,a:0.4,@:0.8} → `[" ",".","a","@"]`; custom `""` → classic; custom `"😀😀가"` → 3 entries, no exception. `AsciiRenderTest`: render synthetic half-white frame at 2 widths, compare downscaled (same as Task 6); in mask mode a pixel in the dark half equals the src pixel.
- [ ] **Step 2:** FAIL → **Step 3:** Implement → **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat: ascii filter`.

### Task 8: Camera controls + UI

**Files:** Create `ui/CameraScreen.kt`, `ui/FilterPager.kt`, `camera/CountdownTimer.kt`, `filter/ParamStore.kt`; Modify `CameraController.kt`, `MainActivity.kt`, `app/build.gradle.kts` (add `androidx.datastore:datastore-preferences`); Test `test/.../camera/CountdownTimerTest.kt`, `test/.../filter/ParamStoreTest.kt`

**Interfaces:**
- Consumes: `FilterRegistry`, `RenderThread.setFilter`, `CameraController`.
- Produces:
  - `CameraController`: `fun setZoomRatio(r: Float)`, `val zoomRange: StateFlow<ClosedFloatingPointRange<Float>>`, `fun setFlash(mode: FlashMode)` (`enum FlashMode { OFF, AUTO, ON }`), `fun setTorch(on: Boolean)`, `fun focusAt(xNorm: Float, yNorm: Float)` (`FocusMeteringAction` AF+AE, auto-cancel 3 s), `fun switchLens()` (sets `renderThread.mirror = lens == FRONT`), `fun setAspect(a: Aspect)`.
  - `class CountdownTimer(seconds: Int, tick: (Int) -> Unit, done: () -> Unit, scheduler: (Long, () -> Unit) -> Unit)` with `start()`, `cancel()`; seconds ∈ {0, 3, 10}; 0 fires `done` immediately.
  - UI: top bar flash (cycles OFF/AUTO/ON; in video mode becomes torch toggle), timer (cycles 0/3/10), lens switch. Bottom: last-capture thumbnail (tap → `ACTION_VIEW` on the MediaStore URI), shutter, Photo/Video toggle. Zoom: pinch + `0.5x/1x/2x/5x` buttons filtered to `zoomRange`. Tap on preview → `focusAt` + focus ring. Horizontal swipe on preview → next/prev filter, filter title toast-chip. A slider icon opens the param panel generated from `filter.params` (Slider for Float/Int, Switch for Bool, chip row for Choice, single-line TextField for Text — shown only when a sibling Choice is `custom`), writing to that filter's `ParamValues` (one `ParamValues` per filter, kept in a ViewModel).
  - `class ParamStore(dataStore: DataStore<Preferences>) { suspend fun load(filter: Filter): ParamValues; suspend fun save(filterId: String, values: ParamValues) }` — keys `"<filterId>.<paramKey>"` as strings via `toStringMap`/`loadFrom`. ViewModel loads all filters at start and saves a filter's values debounced 300 ms after any change.

- [ ] **Step 1:** `CountdownTimerTest` with fake scheduler: 3 s → ticks 3,2,1 then done; cancel stops done; 0 → done immediately. `ParamStoreTest` (DataStore on a temp file via `PreferenceDataStoreFactory.create`): save ASCII `columns=90`, Light Boxes `maxBoxes=10`; a fresh store instance on the same file loads 90 / 10, and Light Boxes `topPercent` is still its default (per-filter isolation).
- [ ] **Step 2:** FAIL → **Step 3:** Implement → **Step 4:** PASS. Manual checklist on device: each control works, swipe cycles 3 filters, sliders change preview live.
- [ ] **Step 5:** Commit `feat: camera controls and filter UI`.

### Task 9: Filtered photo capture + saving

**Files:** Create `capture/PhotoCapturer.kt`, `capture/MediaSaver.kt`; Modify `CameraController.kt`, `RenderThread.kt`, `ui/CameraScreen.kt`; Test `androidTest/.../PhotoMatchesPreviewTest.kt`, `androidTest/.../MediaSaverFailureTest.kt`

**Interfaces:**
- Consumes: `CaptureSnapshot`, `FilterRenderer`, `ImageCapture` bound in the same `UseCaseGroup` (Task 4).
- Produces:
  - `class MediaSaver(resolver: ContentResolver) { fun savePhoto(write: (OutputStream) -> Unit): Uri; fun beginVideo(): PendingVideo; }` where `PendingVideo(val uri: Uri, val fd: ParcelFileDescriptor) { fun commit(); fun abort() }`. Uses `RELATIVE_PATH = "DCIM/OddCamera"`, `IS_PENDING` 1→0; on any exception deletes the row and rethrows.
  - `class PhotoCapturer(renderThread, imageCapture, saver) { suspend fun capture(snapshot: CaptureSnapshot): Uri }`:
    1. `imageCapture.takePicture` in-memory → `ImageProxy` (JPEG) → Bitmap, apply `imageInfo.rotationDegrees` and `cropRect` (ViewPort crop).
    2. On GL thread: upload as texture, mirror if `snapshot.mirror`, rotate to `snapshot.rotationDeg`, render via the snapshot filter's renderer into an offscreen FBO of the bitmap size using `snapshot.params` and `snapshot.analysis` (boxes are normalized → map directly; when rotated, rotate box coords the same way). Check `GL_MAX_TEXTURE_SIZE`; if exceeded, downscale to fit.
    3. `glReadPixels` → Bitmap → JPEG q=95 via `MediaSaver.savePhoto`.
  - Flash mode is applied to `ImageCapture.flashMode`. Shutter (after timer) calls `renderThread.snapshotForCapture()` **first**, then captures.
  - On failure show toast "사진 저장 실패" and keep preview running.

- [ ] **Step 1:** `PhotoMatchesPreviewTest`: feed a synthetic frame through the preview path at display size, take `snapshotForCapture()`, run the offscreen photo render on a 4× larger version of the same image; downscale both to 90 px wide, assert mean abs diff < 8/255 for Light Boxes and ASCII; repeat with `mirror = true` and `rotationDeg = 90` (Review Focus 1: compare against the preview image rotated 90°).
- [ ] **Step 2:** `MediaSaverFailureTest`: `savePhoto { throw IOException() }` → exception propagates and no row with that display name remains in MediaStore (Review Focus 5).
- [ ] **Step 3:** Run → FAIL. **Step 4:** Implement. **Step 5:** Run → PASS. Manual: take photo with each filter, front and back, portrait and landscape; open in Gallery, compare with what was on screen.
- [ ] **Step 6:** Commit `feat: filtered photo capture`.

### Task 10: Filtered video recording

**Files:** Create `record/VideoEncoder.kt`, `record/EncoderSelector.kt`; Modify `RenderThread.kt`, `CameraController.kt`, `ui/CameraScreen.kt`, `MainActivity.kt`; Test `test/.../record/EncoderSelectorTest.kt`, `androidTest/.../RecordingLifecycleTest.kt`

**Interfaces:**
- Consumes: `MediaSaver.beginVideo`, `EglCore.createWindowSurface`/`setPresentationTime`.
- Produces:
  - `data class EncoderConfig(val width: Int, val height: Int, val fps: Int, val bitrate: Int, val surfaceInput: Boolean)`
  - `fun encoderCandidates(rotationDeg: Int): List<EncoderConfig>` — ordered fallbacks: 1920×1080 surface (12 Mbps) → 1280×720 surface (6 Mbps) → 1280×720 buffer input. Width/height swapped for portrait (rotation 0/180 → 1080×1920).
  - `class VideoEncoder(config, pending: PendingVideo, withAudio: Boolean)`: `fun inputSurface(): Surface?` (null in buffer mode), `fun submitFrame(rgba: ByteBuffer, ptsNs: Long)` (buffer mode, RGBA→NV12 on CPU), `fun start()`, `suspend fun stop()` (signal EOS, drain, release, `pending.commit()`; on error `pending.abort()`). Audio: `AudioRecord` 44.1 kHz mono AAC 128 kbps on its own thread; muxer starts once both tracks' formats are known (video-only when `withAudio == false`).
  - Creation tries `encoderCandidates` in order; a candidate fails if `configure`/`createInputSurface` throws.
  - `RenderThread`: `fun startRecording(encoder: VideoEncoder)`, `fun stopRecording()`. While recording, after drawing to display, draw the same filtered frame into the encoder window surface at encoder size (letterbox not needed: aspect is 16:9 in video mode) with presentation time from `SurfaceTexture.timestamp`; buffer mode reads back via PBO and calls `submitFrame`.
  - Record start goes through the same `CountdownTimer` as photos (Task 8).
  - Recording stops (and file finalizes) on: shutter tap, `onStop` of activity, lens switch, mode switch.
  - Mic permission requested on first video record; if denied, record without audio and show toast "마이크 권한 없음: 소리 없이 녹화".

- [ ] **Step 1:** `EncoderSelectorTest`: portrait first candidate is 1080×1920 surface; third candidate has `surfaceInput == false`.
- [ ] **Step 2:** `RecordingLifecycleTest`: start recording via `ActivityScenario`, wait 2 s, move to `Lifecycle.State.CREATED`; assert a new `DCIM/OddCamera` video row exists, `IS_PENDING == 0`, and `MediaMetadataRetriever` reports duration ≥ 1500 ms and a video track (Review Focus 2). Second case with RECORD_AUDIO revoked: file has no audio track and no crash (Review Focus 3).
- [ ] **Step 3:** Run → FAIL. **Step 4:** Implement. **Step 5:** Run → PASS. Manual: 30 s recording with each filter, play in Gallery, compare to preview; torch toggle works while recording.
- [ ] **Step 6:** Commit `feat: filtered video recording`.

### Task 11: Performance governor + degradation

**Files:** Create `render/PerfGovernor.kt`; Modify `RenderThread.kt`; Test `test/.../render/PerfGovernorTest.kt`

**Interfaces:**
- Produces: `class PerfGovernor(tiers: List<Tier>, windowFrames: Int = 30)` with `fun onFrame(frameTimeMs: Float): Tier`; `data class Tier(val analysisWidth: Int, val processScale: Float)`; tiers `[(160, 1.0), (120, 0.75), (80, 0.5)]`. Steps down when the rolling average over the window > 30 ms; steps up when < 20 ms for 3 consecutive windows. Never changes tier while recording (frozen at recording start).
- `RenderThread` measures GPU+CPU frame time (from `onFrameAvailable` to `swap` return), applies `analysisWidth` to `AnalysisReader` and renders the frame FBO at `processScale` of camera crop size. Logs `OddPerf` tag every second: fps, avg ms, tier.

- [ ] **Step 1:** Tests: 30 frames at 40 ms → tier 1; then 90 frames at 15 ms → back to tier 0; frozen governor ignores input.
- [ ] **Step 2:** FAIL → **Step 3:** Implement → **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat: adaptive performance tiers`.

### Task 12: Device verification + README

**Files:** Create `README.md`

- [ ] **Step 1:** On the S26 Ultra, each filter, preview and while recording: `adb logcat -s OddPerf` shows ≥ 29 fps sustained for 60 s and tier stays 0.
- [ ] **Step 2:** On a second, lower-end device (ask the user which one): same check; fps ≥ 29 allowed at a degraded tier; recording works (any fallback config) and output plays.
- [ ] **Step 3:** Full `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest` → all PASS.
- [ ] **Step 4:** README (Korean): what the app is, build/install commands (incl. `JAVA_HOME` note), how to add a filter (implement `Filter`, add to `FilterRegistry`).
- [ ] **Step 5:** Commit `docs: readme` and push.

### Task 13: ASCII v2 — person segmentation look (added 2026-10-18, runs before Task 12)

**Files:** Modify `filter/ascii/AsciiFilter.kt`, `filter/Filter.kt`, `render/AnalysisReader.kt`, `render/RenderThread.kt`, `render/PhotoRenderer.kt` (only if the still path needs RGBA), `gradle/libs.versions.toml`, `app/build.gradle.kts`; Create `filter/ascii/PersonSegmenter.kt`; Tests `test/.../ascii/AsciiV2Test.kt`, `androidTest/.../AsciiRenderTest.kt` (update)

**Spec:** ASCII section "v2" in the design doc.

**Interfaces:**
- `LumaFrame` gains `rgba: ByteArray?` (null unless requested) — RGBA readback of the same analysis frame at `segWidth` (256 px wide, aspect kept). `Filter` gains `val needsRgba: Boolean get() = false`; RenderThread/PhotoRenderer request RGBA only when the active filter needs it. No other filter changes.
- `class PersonSegmenter` wraps ML Kit `segmentation-selfie` (stream mode for preview, single-image mode for stills): `fun submit(rgba, w, h, rotation=0)` non-blocking, drops input while busy; `val latest: Mask?` where `class Mask(val data: ByteArray /*0..255*/, val w: Int, val h: Int)`; `fun segmentBlocking(rgba, w, h): Mask?` for stills (timeout 1500 ms → null).
- `class AsciiAnalysis(override val threshold: Int, val mask: Mask?, val glyphEpoch: Int) : FrameAnalysis, RotatableAnalysis` — `rotated()` rotates the mask array.
- Params (ids stable; labels Korean): `ChoiceParam("region","영역 기준",[person 사람, bright 밝은 부분],"person")`, existing `topPercent` (bright mode only), `columns`, `fullFrame`, `glyphSet` presets now `symbols "#%@=+*$&"` (label 기호, new default), `classic`, `dots`, `blocks`, `binary`, `custom`; `BoolParam("grayBackground","배경 흑백",true)`.
- Pure: `fun glyphFor(cellX: Int, cellY: Int, epoch: Int, setSize: Int): Int` — deterministic hash; ≈10 % of cells change when epoch increments; `fun glyphEpoch(timeMs: Long): Int = (timeMs / 250).toInt()`.
- Shader (mask mode): cell in-region if mask at cell center > 0.5 → white bg `#FFFFFF` + glyph `#D0141E`; outside: if grayBackground → luma×0.8 gray else src; edge glow: outside pixels blend toward white by `smoothstep(0.15, 0.5, maskBilinear)`. Bright mode uses the same look with the region = cells whose luma > threshold. Full-frame unchanged.
- Photo: `captureAnalysis` (person mode) runs `segmentBlocking` on the still's RGBA; null → keep snapshot mask. Bright mode keeps R13 behavior. Video uses per-frame analysis (R18).

- [ ] **Step 1:** JVM tests: `glyphFor` deterministic, in range, ~5–20 % cells differ between consecutive epochs, 0 % within an epoch; mask rotation 90/180/270 maps corners correctly; region param defaults to person; resolve glyph set for `symbols`.
- [ ] **Step 2:** Device test (AsciiRenderTest): inject an `AsciiAnalysis` with a synthetic mask (left half 255) → left-half cells contain red+white only, right half gray (R≈G≈B) when grayBackground, equals src when off; orientation-sensitive; 2-width comparison holds.
- [ ] **Step 3:** FAIL → implement → PASS. Device manual: point at a person (or the user) → silhouette filled; screencaps.
- [ ] **Step 4:** Commit `feat: ascii v2 person segmentation`.
