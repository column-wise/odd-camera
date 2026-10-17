# Odd Camera — Design

Date: 2026-10-17 · Status: approved · App name: **Odd Camera** (package `com.juhyun.oddcamera` — Claude's call)

## Intent

An everyday Android camera app whose distinguishing feature is two live visual filters, inspired by stage visuals seen at a concert (reference clips: `workspace/KakaoTalk_20261017_181317735.mp4`, `workspace/KakaoTalk_20261017_181425320.mp4`). The concert footage supplies the *idea* of each effect, not a look to match pixel-for-pixel: the app is for general use, so the effect parameters are exposed to the user rather than tuned to concert footage.

The user sees the filter **live in the preview**, and what they capture (photo or video) **looks the same as what they saw**.

## Goals

1. Live camera preview with one of three modes: **None / Light Boxes / ASCII**.
2. Photo and video capture with the active filter baked in.
3. Standard camera controls: zoom, flash, front/back switch, timer, tap-to-focus.
4. In-app sliders for each filter's parameters, applied to the preview immediately.

## Anti-goals (the app has failed even if it "works")

- **Preview stutters** when a filter is on. Target: smooth 30 fps preview with any filter on the dev device; no visible hitching while recording.
- **Captured result differs from preview.** A photo or video must show the same effect (same boxes / same ASCII look, same parameter values) the user was looking at.
  - **User decision:** for Light Boxes, a photo shows **exactly the boxes on screen at the shutter moment** (box list from the matching preview frame, mapped into photo coordinates), not boxes re-detected in the full-res image.

## Device scope (user decision)

Must work well on **other Android phones too**, not only the dev device. So the plan needs:
- Graceful degradation on weaker devices (e.g. lower detection/processing resolution, lower preview processing cost) so the stutter anti-goal still holds.
- A fallback where encoding from a GL input surface is unreliable.
- Testing on at least one other physical device besides the S26 Ultra (lower-end preferred) before calling it done.

## Non-goals (for now)

- Concert-specific tuning or presets.
- In-app gallery/viewer, editing, sharing.
- 4K recording, manual exposure/ISO/shutter, HDR, night mode, RAW.
- Applying filters to existing photos/videos.
- Person segmentation (ML). Masks are brightness-based.

## Filters

**More filters will be added later (user).** The filter layer must make adding one cheap: a filter is a self-contained unit (shader(s) + declared parameter list + optional per-frame CPU analysis such as Light Boxes' region detection). Adding a filter must not require touching camera, recording, or saving code. The parameter sliders are generated from each filter's declared parameter list, and the swipe switcher picks up new filters automatically.

Reference behaviour was prototyped in a browser demo (`.superpowers` scratch, not part of the app); the essentials:

### Light Boxes (from clip 1)
- Find bright regions in the frame and draw thin white rectangular outlines around them; boxes follow the regions frame to frame.
- Large bright regions may be split into a few sub-boxes so it reads "busy"; some boxes carry a small mark inside (dot/glyph).
- **Threshold is relative by default**: "brightest top N % of the frame" so it adapts to scene exposure. An absolute-brightness mode may also be offered.
- User controls: brightness threshold, max number of boxes.

### ASCII (from clip 2)
- Divide the frame into a grid of cells. Cells brighter than the threshold become a light background with a dark character chosen by brightness; cells below threshold show the original image.
- Also a **full-frame** variant (whole image as characters) behind a toggle.
- User controls: brightness threshold (same relative scheme), cell size, full-frame toggle, **glyph set** (user request): choose which characters are drawn, in the same settings panel as the threshold. Presets plus a custom string the user types.

### Settings persistence (user request)
Every filter's parameter values are **saved per filter** and restored on next launch; changing ASCII settings never affects Light Boxes and vice versa.

Parameters must be **resolution-independent** (e.g. cell size and box line width as a fraction of frame width) so the same settings produce the same look in preview, 1080p video and full-res photo.

## Camera features

| Feature | Behaviour |
|---|---|
| Zoom | Pinch + quick ratio buttons |
| Flash | Off / Auto / On for photos; torch toggle while recording |
| Lens | Front / back switch |
| Timer | Off / 3 s / 10 s, for photo and video start |
| Focus | Tap to focus + meter at point |
| Mode switch | Photo / Video |
| Filter switch | Swipe left/right between None / Light Boxes / ASCII |
| Last capture | Thumbnail; tap opens it in the system gallery app |

Saving: photos and videos to the shared gallery under `DCIM/OddCamera` via MediaStore. Video records audio.

## How it gets made

Decided (by Claude, user accepted Kotlin):
- **Kotlin**, single-activity app.
- **CameraX** for camera control (preview, zoom, flash/torch, focus/metering, lens switch).
- **OpenGL ES** shaders render camera frames with the active filter to the screen and to the recorder's input surface.
- **MediaCodec / MediaMuxer** (or an equivalent that encodes from the GL output surface) for filtered video + audio.
- Light Boxes detection: downsample luminance to a small buffer, find connected bright regions on CPU, feed the box list back to the GL renderer to draw. (Builder may choose a different approach if it meets the frame-rate anti-goal.)
- minSdk **29**, targetSdk **36**.
- Video: **1080p, 30 fps**.

Dev/test device: Samsung Galaxy S26 Ultra (SM-S948N), Android 16 / API 36, Snapdragon SM8850, connected via adb. Android SDK platform-tools present on the dev PC.

## Claude's calls (user may override)

- **Photo resolution:** the device's default (binned, ~12 MP) still-capture output, not 200 MP modes.
- **Aspect ratio:** photo 4:3, video 16:9. The preview reframes to match the active mode so preview framing == captured framing (needed for the "same as preview" rule).
- **Box motion:** detection runs every frame; boxes are lightly smoothed (eased toward new position, matched to previous-frame boxes by nearest overlap) so they don't flicker. Sub-box splits and inner marks are assigned deterministically per tracked box so they don't reshuffle every frame. The reference clips are inspiration, not a motion spec.
- **Front camera:** saved photos/videos are **mirrored exactly as the preview shows them**, so ASCII and boxes match the preview.

## Left to the builder

- Module/package layout, UI styling, icons, the app's visual design.
- How photo capture reproduces the preview effect at full resolution (e.g. full-res capture run through the same shader offscreen), as long as the anti-goal holds.
- Exact slider ranges and defaults.
- Permission UX (camera, microphone).
