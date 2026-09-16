# Classroom Scanner — Design Spec

- **Date:** 2026-09-16
- **Status:** Approved in brainstorming, awaiting written-spec review
- **Course:** AI Engineering (university project, solo)
- **Deadline:** ~2026-09-18/19 (2–3 days from start). Deliverable: **live demo only**.

## 1. Goal

An Android app that helps a person (pitched as an assistive tool for blind / low-vision users) understand a room. The user holds the phone upright and turns around. The app detects objects on-device, remembers each object's direction and dominant color, removes duplicates, and speaks what is around them:

> "Around you: 3 blue chairs in front, a black laptop on your left, a white TV behind you."

### Non-goals (YAGNI)

- No custom model training in the first version. Use the stock COCO model; fine-tuning is an optional stretch goal (§10).
- No cloud / network calls. Everything runs on the device.
- No Uzbek or multi-language support. UI and speech are English only.
- No image thumbnails, export, or settings screen.
- No distance estimation.

## 2. Constraints

| Item | Value |
|---|---|
| Phone | Infinix X6880, Android 15 (API 35), arm64-v8a, 8 GB RAM, gyroscope + compass + accelerometer, autofocus camera |
| Laptop | i5-11400H, 16 GB RAM, RTX 2050 4 GB; Android Studio, SDK, adb, JDK 21 installed |
| Language | Kotlin |
| Principle | Reuse existing repos and libraries wherever possible; write only glue code |

## 3. Approach

Fork the official **MediaPipe object detection Android sample** (`google-ai-edge/mediapipe-samples`, `examples/object_detection/android`, Apache-2.0) into this repo and build on top of it. The sample already provides CameraX, the MediaPipe Tasks object detector (EfficientDet-Lite0, 80 COCO classes, `LIVE_STREAM` mode) and a bounding-box overlay.

Rejected alternatives:
- A clean new Compose project using MediaPipe only as a library costs an extra 1–2 hours of camera wiring.
- ML Kit Object Detection only gives 5 coarse categories and at most 5 objects per frame, which is not enough.

## 4. Libraries

| Need | Library |
|---|---|
| Camera | CameraX (from sample) |
| Detection | `com.google.mediapipe:tasks-vision` ObjectDetector, EfficientDet-Lite0 (from sample) |
| Heading | Android `SensorManager`, `TYPE_ROTATION_VECTOR` |
| Dominant color | `androidx.palette:palette-ktx` |
| Speech | Android `TextToSpeech` |
| Storage | Room (`androidx.room`) with KSP |
| Unit tests | JUnit 4 (JVM) |

Remove or hide sample features we do not need, such as the gallery/video mode and model/delegate pickers. Keep defaults: EfficientDet-Lite0, CPU delegate, score threshold 0.5, max results 10. GPU is allowed if it works on the phone.

## 5. Architecture

```
CameraX frame ──► ObjectDetectorHelper (sample) ──► detections (label, score, box)
                                                        │
Rotation vector ─► HeadingProvider ─► heading ──────────┤
                                                        ▼
                     ColorNamer ◄──────────────── ScanSession (orchestrator)
                                                        │ uses pure-Kotlin core:
                                                        │   AngleMath, CoverageTracker,
                                                        │   ObjectClusterer, SummaryBuilder
                                                        ▼
                                SpeechAnnouncer (TTS)   HistoryRepository (Room)
                                                        │
                                              ScanFragment UI / HistoryFragment UI
```

Package root: `com.classroomscanner`. Use whatever UI toolkit the sample uses (Fragments/XML or Compose); do not migrate.

### 5.1 Pure-Kotlin core (`core/`, no Android imports, fully unit tested)

**`AngleMath`**
- `normalize(deg)` returns a value in `[0, 360)`.
- `diff(a, b)` returns the shortest signed difference in `(-180, 180]`.
- `sector8(relDeg)` returns one of: `FRONT, FRONT_RIGHT, RIGHT, BEHIND_RIGHT, BEHIND, BEHIND_LEFT, LEFT, FRONT_LEFT`. Each sector is 45° wide and centered on its direction; `FRONT` covers `[337.5, 22.5)`.
- `sector4(relDeg)` returns one of: `FRONT [315,45)`, `RIGHT [45,135)`, `BEHIND [135,225)`, `LEFT [225,315)`.

**`CoverageTracker`**
- Splits the circle into 36 bins of 10°.
- `mark(relHeading)` marks the current bin as covered.
- `percent()` returns the covered share; `isComplete()` returns true when all 36 bins are covered.

**`ObjectClusterer`** (deduplication)
- **Input per frame:** a list of `(label, objectRelAngle, colorName?)`.
- **Assignment:** a detection joins an existing cluster only if the label matches and `|diff(angle, cluster.meanAngle)| < 20°`. Otherwise it creates a new cluster. Update the cluster's circular mean angle after each assignment.
- **Per-cluster state:**
  - `framesSeen`
  - `maxInSingleFrame`: how many detections from **one** frame were assigned to this cluster; keep the maximum across frames.
  - color votes (a count per color name)
- **Count:** `count = maxInSingleFrame`. Never sum across frames, or 3 chairs seen for 100 frames would count as 300.
- **Confirmation:** a cluster is confirmed when `framesSeen >= 3`. Unconfirmed clusters are never announced or saved.
- **Color:** use the majority vote. Colors are voted rather than part of the cluster key, so frame-to-frame color flicker cannot split one object into two.
- **Events:** emit a `newlyConfirmed` event the first time a cluster is confirmed (used by Live mode).

**`ColorMapper`**
- `nameFromHsv(h, s, v)` returns one of 11 names (black, white, gray, red, orange, yellow, green, blue, purple, pink, brown), or `null` when unreliable.
- Rules, applied in order:
  1. `v < 0.2` → black
  2. `s < 0.15` → white if `v > 0.8`, otherwise gray
  3. Otherwise, by hue:
     - red: `<15` or `>=345`
     - orange: `15–45`, but brown if `v < 0.6`
     - yellow: `45–70`
     - green: `70–170`
     - blue: `170–260`
     - purple: `260–290`
     - pink: `290–345`
     - light red is also pink: red hue with `s < 0.5` and `v > 0.7`
- Return `null` (unreliable) when the frame's mean brightness is below a darkness threshold. The caller passes a flag.

**`SummaryBuilder`**
- **Full summary:** group confirmed clusters by `sector4`, in the order front, right, behind, left.
- **Wording:**
  - Singular vs plural ("a chair", "3 chairs").
  - Include the color when known: "3 blue chairs".
  - Leave out empty directions.
- **Example:** "Around you: 3 blue chairs in front; a black laptop on your right; a white tv behind you."
- **Empty result:** "No objects found. Try better lighting and turn slowly."
- **Partial coverage:** when coverage is below 100%, prefix "I scanned N percent of the room."
- **Live phrase:** "Blue chair on your left."

### 5.2 Android layer

**`HeadingProvider`**
- Use `TYPE_ROTATION_VECTOR`. Call `remapCoordinateSystem(AXIS_X, AXIS_Z)` so the reading stays stable while the phone is held upright, then `getOrientation` → azimuth in degrees.
- Low-pass filter the result.
- The first reading after "Start" becomes `startHeading`. Expose `relHeading = normalize(heading - startHeading)`.
- Report low accuracy (from `onAccuracyChanged`) and angular speed in °/s.

**Object angle**
- `objectRelAngle = normalize(relHeading + (boxCenterXNorm - 0.5) * hfov)`.
- `hfov` is the horizontal field of view of the image **as analyzed in portrait**. Compute it from `CameraCharacteristics`: `2·atan(sensorDim / (2·focalLength))`, using the sensor dimension that maps to the portrait width. If that fails, fall back to 65°.

**`ColorNamer`**
- Crop the central 50% of the bounding box from the frame bitmap.
- Get the dominant swatch with `Palette.from(crop).generate()`, convert it to HSV, then call `ColorMapper`.
- Throttle: name the color at most once every 5 frames per cluster, to keep frame rate up.

**`ScanSession`**
- Owns one scan and the mode (`FULL` or `LIVE`).
- On each detector result, it does the following in order:
  1. Read the heading.
  2. Mark coverage.
  3. Compute object angles and colors.
  4. Feed the clusterer.
  5. Update UI state.
  6. Live mode only: announce newly confirmed clusters.
- **Full mode** stops automatically when coverage is complete, or after a 60 s timeout.
- **Live mode** stops only on the Stop button.
- **On stop:** build the summary, speak it, and save it to history.

**`SpeechAnnouncer`**
- Wraps `TextToSpeech` with locale `Locale.US`.
- Queues announcements with at least 1.5 s between them. If more than 3 announcements are pending, drop the stale ones.
- Before speaking the final summary, flush the queue.
- If TTS init fails, set `available = false` so the UI shows text only.

**`HistoryRepository` (Room)**
- `ScanEntity(id, startedAt: Long, mode, coveragePercent, summaryText)`
- `DetectedObjectEntity(id, scanId FK, label, count, colorName?, relAngleDeg, sector8)`
- DAO operations: insert a scan together with its objects (in one transaction), list scans newest first, and get the objects for a scan.

### 5.3 Screens

**Scan screen** (the sample's camera screen, extended):
- Camera preview with box overlay; each box label shows the color, e.g. "chair · blue".
- A **Full Scan / Live Scan** toggle.
- A Start/Stop button.
- A 360° coverage ring, which fills as bins are covered.
- The current confirmed object count.
- A text area for the last announcement or summary.
- Warning banners for "Slow down" and "Calibrate compass: move phone in a figure 8".

**History screen:**
- A list of past scans showing date, mode, coverage and summary text.
- Each row has a "Play" button that speaks the saved summary.

## 6. Error handling

| Situation | Behavior |
|---|---|
| Camera permission denied | Explanation view + "Grant" button; spoken hint if TTS available |
| Rotation vector sensor missing | Disable Full mode; Live mode still works, with angles relative to the frame center only; banner explains |
| Compass accuracy low | Calibration banner (non-blocking) |
| Angular speed > 60°/s | "Slow down" banner + spoken once per 5 s |
| Full scan 60 s timeout | Stop, summary with coverage prefix |
| TTS unavailable | Text-only summary |
| Dark frame | Color omitted, label still reported |
| Nothing confirmed | Empty-result sentence |
| Detector init error | Error text on screen and in Logcat; Start disabled |

## 7. Testing

- **TDD for all of `core/`** (JVM, `testDebugUnitTest`). Required cases:
  - **Angles:** wrap-around at 359°/1°, and `diff` sign.
  - **Sectors:** boundaries of `sector8` / `sector4`.
  - **Coverage:** partial vs complete.
  - **Clusterer:**
    - same object across 100 frames → count 1
    - 3 chairs in one frame, repeated → count 3
    - same label 90° apart → 2 clusters
    - wrap-around at 355° vs 5° → 1 cluster
    - 2 frames only → not confirmed
    - color majority vote
  - **Color mapping:** each of the 11 names, plus `null` for dark frames.
  - **Summary wording:** plural, color omitted when null, empty result, coverage prefix.
- **One instrumented Room DAO test** (`connectedDebugAndroidTest`): insert and read back.
- **Manual device checklist** on the Infinix X6880: both modes, auto-stop, timeout, history replay, permission denial, slow-down banner.

## 8. Demo plan (live)

1. Before the defense, run 2–3 real scans in the classroom so History has backup results.
2. Mirror the phone to the projector with `scrcpy` over USB (install it if missing).
3. Script, about 3–4 minutes:
   1. Live Scan: objects are announced while turning.
   2. Full Scan: the ring fills, the scan auto-stops, and the summary is spoken.
   3. History: replay a saved scan.
   4. Show a one-page architecture diagram (§5).
4. Talking points:
   - on-device inference, so no network is needed;
   - deduplication using max-in-single-frame counts;
   - heading math with field-of-view correction;
   - the accessibility use case.

## 9. Done criteria

- `gradlew testDebugUnitTest` passes.
- `gradlew assembleDebug` builds and the app installs on the Infinix.
- Full scan in a real room produces a correct spoken summary: directions right, counts plausible, and no duplicate explosion.
- Live scan announces new objects without spamming.
- History persists across app restarts, and replay works.

## 10. Stretch goals (only if time remains)

1. Fine-tune YOLO11n or MediaPipe Model Maker on classroom datasets (whiteboard, projector, desk) using the laptop GPU, export to `.tflite`, and swap it in.
2. Haptic tick for each newly confirmed object.
3. Clock-direction wording ("at 3 o'clock") as an option.
