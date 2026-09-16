# Home, Scan Settings and Scan Text — Design Spec

- **Date:** 2026-09-16
- **Status:** Approved in brainstorming (two sections approved in chat)
- **Builds on:** `docs/superpowers/specs/2026-09-16-classroom-scanner-design.md` (still the source of truth for scanning behavior)

## 1. Goal

Restructure the app into a guided flow:

1. **Home** shows three large cards: Full Scan, Live Scan, History.
2. Choosing Full or Live opens **Scan settings**: camera (back/front), compute (CPU/GPU), model (fast/accurate), confidence threshold, speech on/off, colors on/off, and a Start button.
3. **Scanner** is the existing camera screen without the Full/Live toggle. A "View text" button sits at the bottom right.
4. **View text** is a full-screen window over the scanner that shows the current scan's log (time + spoken text) and, once the scan ends, the summary. The scan keeps running while it is open.
5. **History** opens from Home.

The bottom navigation bar is removed. Every screen except Home has a back arrow in the toolbar.

### Non-goals

- No change to detection, clustering, color naming or summary wording rules.
- No settings screen outside this flow, no dark theme, no per-scan settings in History.

## 2. Screens

| Screen | Content |
|---|---|
| Home | Title, one-line intro, three clickable cards with icon, title and one-line description. The Full Scan card is disabled with "No rotation sensor" when the device has no rotation vector sensor. |
| Scan settings | Header card with the chosen mode. Cards: Camera (Back/Front toggle), Compute (CPU/GPU toggle), Model (Fast/Accurate toggle with helper text), Confidence (slider 0.3–0.7, step 0.1, shows the value), Speech (switch), Colors (switch). Large "Start scanning" button at the bottom. Values persist on the phone. |
| Camera access | Existing permission screen; shown only when the permission is missing, then continues to the scanner. |
| Scanner | Existing screen minus the mode toggle. Small extended FAB "View text" at the bottom right, above the controls card. |
| View text | Full-screen dialog with toolbar (back arrow, "Scan text"), list of `HH:mm:ss  text` rows that auto-scrolls to the newest, an empty state "Nothing said yet.", and a "Summary" card when the scan has finished. |
| History | Unchanged, opened from Home. |

Navigation: Home → Settings(mode) → [Camera access(mode)] → Scanner(mode); Home → History. Back from the scanner stops the scan (existing onPause behavior saves it).

## 3. Settings semantics

| Setting | Default | Effect |
|---|---|---|
| Camera | Back | CameraX lens facing. |
| Compute | CPU | MediaPipe delegate. If the GPU detector fails to initialize, the app switches to CPU, re-creates the detector and shows "GPU unavailable, using CPU." |
| Model | Accurate | Fast = EfficientDet-Lite0, Accurate = EfficientDet-Lite2. Both models are downloaded at build time. |
| Confidence | 0.5 | Non-person labels need `score >= confidence`. People need `score >= max(0.3, confidence − 0.2)`. The detector itself always runs at 0.3. Values outside 0.3–0.7 are clamped. |
| Speech | On | Off = the speech announcer stays silent; the log is still written. |
| Colors | On | Off = no color is computed, so boxes and summaries have no colors. |

## 4. Front camera

Held upright, the front camera looks at the user, i.e. backward. The analyzed frame is not mirrored, so the camera-relative offset keeps its sign and only the base direction flips:

`objectAngle = normalize(relHeading + 180 + (centerNorm − 0.5) · hfov)` for the front camera.

The horizontal FOV is read from the selected camera. The preview is mirrored, so the overlay draws boxes mirrored horizontally when the front camera is used.

## 5. Architecture

- `core/ScanSettings` (pure Kotlin): `CameraFacing`, `Compute`, `ModelChoice` enums and the `ScanSettings` data class with `normalized()`.
- `core/DetectionFilter` becomes a class taking the confidence value.
- `core/BoxGeometry.objectAngle` gains a `facing` parameter (default BACK).
- `core/ScanLogState` (pure Kotlin, immutable): entries `(timeMs, text)` and an optional summary; `add` ignores blank text.
- `settings/SettingsStore` (Android): SharedPreferences load/save.
- `ScanLogViewModel` (activity-scoped): `StateFlow<ScanLogState>`, `start()`, `add(text)`, `finish(summary)`.
- The scanner writes every hint, live phrase, slow-down warning and the summary to the log through one helper that also speaks it.
- `SpeechAnnouncer.muted`, `CameraFov.portraitHorizontalFov(context, facing)`, `OverlayView.mirrored`.
- Fragments: `HomeFragment`, `SettingsFragment`, `ScanTextDialog` (new); `CameraFragment`, `PermissionsFragment` (updated); `MainActivity` wires the toolbar to navigation and no longer overrides back.

## 6. Error handling

| Situation | Behavior |
|---|---|
| GPU init failure | Fall back to CPU once, show the message in the announcement area. |
| Permission revoked while on the scanner | Scanner pops back to Settings. |
| No rotation sensor | Full Scan card disabled on Home; Live still works. |
| TTS unavailable | Unchanged: text only; the log still fills. |

## 7. Testing

- TDD for the core changes: `ScanSettings.normalized`, `DetectionFilter` with confidence values (0.3, 0.5, 0.7), front-camera `objectAngle`, `ScanLogState`.
- Build, unit tests, install, and screenshots of every screen on the Infinix X6880.
- Manual: front-camera directions, GPU behavior, scan continues while View text is open.
