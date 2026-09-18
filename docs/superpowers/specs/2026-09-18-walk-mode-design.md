# Walk Mode — Design

Date: 2026-09-18. Status: approved in chat.

## Goal

A blind person walks, indoors or outdoors, holding the phone in front of them. The app warns about
what is in the way, names what it knows, and can point to a saved destination. It never tells the
user that it is safe to move: it only says what it sees.

## Entry

- The Full scan hub gains a fourth card, **Walk**.
- Voice commands: "walk", "start walking", "stop".
- Learner mode describes the screen like every other screen.

## Camera and depth: ARCore

- `com.google.ar:core` runs the camera in walk mode. ARCore owns the camera, so walk mode does not
  use CameraX.
- The session is configured with `DepthMode.AUTOMATIC` and, when supported, Scene Semantics.
- Per frame the app takes:
  - the camera image (YUV) for the object detector and the text reader,
  - the depth image (`acquireDepthImage16Bits`, millimetres) for distances,
  - the semantic image, when available, for ground classes (`sidewalk`, `road`, `terrain`),
  - the camera pose, for the phone's pitch.
- **Fallback:** when ARCore, its depth mode or Play Services for AR are missing, walk mode says so
  once and keeps working with CameraX and geometric distance (see below). Every distance in the app
  goes through one interface, so the rest of walk mode does not care which source answered.
- Rendering: the preview is drawn from the ARCore camera texture; walk mode works with the screen
  off-glance, so the preview is only for sighted helpers and for the demo.

## Distance

1. **Depth (preferred):** the median of a small grid of depth samples inside the lower half of the
   object's box, in metres.
2. **Geometry (fallback and sanity check):** the box's bottom edge is where the object meets the
   floor, so `distance = cameraHeight * focalPx / (bottomY - horizonY)`, with the horizon from the
   phone's pitch. Camera height is a setting, 1.4 m by default.
3. Distances are spoken in steps when step length is known, otherwise in metres:
   "chair, four steps ahead".

## What is announced

- **Hazard classes** come from COCO: person, bicycle, car, motorcycle, bus, truck, train, dog,
  bench, chair, potted plant, stop sign, fire hydrant, traffic light, plus anything saved.
- **Zones:** the frame is split into left, ahead and right by the box centre (0.35 / 0.65).
- **Urgency** by distance: under 1.5 m is "close", under 3 m is "near", beyond that is ignored while
  walking.
- **Quiet by default:** the app speaks only when something new matters:
  - a hazard enters the ahead zone under 3 m,
  - a hazard moves from near to close,
  - the ground class under the user changes (for example "road" after "sidewalk"),
  - a saved person or thing is recognized,
  - a traffic light's colour changes,
  - the beacon's clock direction changes by more than one hour.
  - The same message is not repeated within 6 s, and at most one message is spoken at a time.
- **Beeps and vibration** carry the rest: a tick whose gap shrinks with the distance of the nearest
  hazard ahead (1000 ms at 4 m, 150 ms at 0.5 m), and one short buzz when something is closer than
  1 m. They stop when the way is clear.

## Extras

- **Traffic lights:** a COCO `traffic light` box is classified red, yellow or green by counting
  hue-filtered pixels. It is spoken as an observation: "red light seen". The app never says to cross.
- **Signs:** ML Kit Text Recognition v2 (bundled, offline) reads the middle of the frame about twice
  a second when nothing more urgent is pending, and speaks text of at least three characters, each
  string once.
- **Saved people and things:** the existing face and item recognizers name what walk mode sees, with
  the same sticky-name rule as scans.
- **Steps:** `TYPE_STEP_DETECTOR` counts steps; step length is estimated from height (0.415 × height,
  default 1.70 m) so distances can be spoken in steps.

## Beacon (destination)

- The user saves the spot they are standing on: "save this place as home". Name and coordinates go
  into `SharedPreferences`.
- "take me home" starts beacon guidance: from the phone's location and compass heading the app says
  the great-circle distance and a clock direction, "home, 120 metres, 2 o'clock", and repeats when
  the clock direction changes or every 20 s.
- Needs `ACCESS_FINE_LOCATION` and GPS; it does not need internet and does not follow streets.
  Outdoors only, and the app says so when the fix is poor.

## Pure core logic (TDD)

- `WalkGeometry`: `horizonY(pitchDeg, focalPx, imageHeight)`, `distanceFromBottom(...)`,
  `steps(metres, stepLength)`, `stepLength(heightM)`.
- `WalkZone`: `of(centerX)` → LEFT, AHEAD, RIGHT.
- `Hazard`, `HazardPolicy.isHazard(label)`, `urgency(metres)`.
- `WalkAlerts`: takes the hazards of a frame and the clock, returns the one thing to say, applying
  the quiet rules above; also `beepIntervalMs(nearestMetres)`.
- `TrafficLightColor.of(redPixels, yellowPixels, greenPixels)`.
- `Beacon`: `distanceMetres(lat1, lon1, lat2, lon2)`, `bearingDeg(...)`, `clockHour(bearingDeg,
  headingDeg)`, `phrase(name, metres, hour)`.
- `WalkPhrases`: "chair, four steps ahead", "person close on your left", "road under you".

## Out of scope

- Street-by-street navigation, maps and route tiles.
- Stairs, curbs and door classes: no usable pretrained model exists; geometry only.
- Telling the user when to cross or that anything is safe.
