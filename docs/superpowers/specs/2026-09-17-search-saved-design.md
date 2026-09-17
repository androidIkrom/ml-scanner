# Search Scan, Saved Items and Voice Input — Design

Date: 2026-09-17. Status: approved in chat.

## Goal

The app gets a new top level with three entries: **Full scan**, **Search scan** and **Saved**. Users can also talk to it. A blind user can say "find my bag" and the app guides them to it with speech, beeps and vibration.

## Navigation

- **Home** shows three large cards: Full scan, Search scan and Saved.
  - A floating mic button (bottom-right) takes voice commands.
  - The voice-guide toggle stays top-right.
  - The People card leaves Home and lives inside Saved.
- **Full scan hub** (new screen) has three cards:
  - Full scan and Live scan, each going to Settings and then the Scanner, as today.
  - History.
- **Saved** (new screen) has a `TabLayout` with People, Cars and Objects.
  - Each tab shows a list; tapping a row opens a detail screen with the photo and Delete.
  - A large **Add** button at the bottom adds to the current tab.
  - The People tab reuses the existing list, detail, add and enroll screens.
- **Search** (new screen):
  - A big mic button; the prompt "What should I find?" is spoken on entry.
  - A text field is kept as a fallback.
  - When the query resolves, the Search camera screen opens.

## Saved cars and objects

- **Recognition:** MediaPipe `ImageEmbedder` (already in `tasks-vision`) with `mobilenet_v3_small.tflite`, a float32 model of about 4 MB.
  - Downloaded by `download_models.gradle`.
  - `l2Normalize` on, so cosine similarity is a dot product.
- **Kind rules:**
  - A car must be a COCO `car`, `truck`, `bus` or `motorcycle`.
  - An object can be any COCO class except `person`.
  - An item stores the COCO label it was enrolled with, and matching only compares detections with that label.
- **Enrollment:**
  1. The user enters a name (typed or spoken).
  2. The camera opens and the object detector runs on each frame.
  3. The candidate is the kept detection of an allowed class whose box center is closest to the frame center. On the first sample it must also be large enough (box area ≥ 5% of the frame).
  4. After the first sample, the label is locked; later samples must have the same label.
  5. Twelve samples are taken in three steps of four:
     - "Hold the camera still."
     - "Move a little to the left."
     - "Move a little to the right."
  6. The app speaks progress in percent.
  7. The crop from the first sample becomes the photo.
  8. The app saves the name, kind, label, photo and 12 embeddings.
- **Storage:** Room version 3 adds two tables:
  - `saved_items(id, name, kind TEXT, label TEXT, photoPath, createdAt)`
  - `item_embeddings(id, itemId FK cascade, vector BLOB)`
  - `MIGRATION_2_3` keeps history and people. Photos go to `filesDir/items`.
- **Match:**
  - Best cosine similarity among the item's embeddings, with the threshold `ItemMatcher.THRESHOLD = 0.75`.
  - This threshold is tuned on the device.
  - For each detection only the item with the highest score is kept.
- **In Full/Live scans:**
  - Every 3rd frame that has a kept detection of a saved item's label, crops of those boxes are embedded.
  - A match renames the detection to the item name (`isName = true`, no color), the same way people are named.
  - At most 3 crops are embedded per frame.

## Voice input (STT)

- **`speech/SpeechInput`:**
  - Wraps `android.speech.SpeechRecognizer` with language `en-US`, `EXTRA_PREFER_OFFLINE = true` and partial results off.
  - The callback gives `onResult(text)` or `onError(message)`.
  - It stops TTS before it starts listening.
  - It requires the `RECORD_AUDIO` permission, requested at runtime by the screen that owns the mic button.
- **Used in three places:**
  1. Search query.
  2. The name field on Add person and Add item, via a mic end-icon on the `TextInputLayout`.
  3. Home voice commands.
- **No recognizer:** if `SpeechRecognizer.isRecognitionAvailable` is false, the mic end-icons are hidden, the Search mic button is disabled, and tapping the Home mic says voice input is not available.

## Pure core logic (TDD)

- **`core/VoiceCommand`:** `VoiceCommandParser.parse(text)` returns one of:
  - `FullScan`, `LiveScan`, `History`, `Saved`, `Search(query)` or `Unknown`.
  - It understands "full scan", "scan the room", "live", "history", "saved", "search X", "find X", "where is X" and "look for X".
- **`core/SearchQuery`:** `SearchResolver.resolve(text, saved: List<SavedName>)` returns one of:
  - `Target.Person(id, name)`, `Target.Item(id, name, label)` or `Target.Label(cocoLabel)`.
  - `Target.Unknown(cleanedText)`.
  - How it resolves:
    - Normalize: lowercase, strip punctuation, drop "find/search/where is/look for/my/the/a/an/for".
    - Saved names first: exact match, then containment, then Levenshtein ≤ 2 for names of 4 or more characters.
    - Then COCO labels, with a plural "s"/"es" stripped and a synonym map, for example:
      - phone/mobile → cell phone
      - table/desk → dining table
      - sofa → couch
      - tv/monitor/screen → tv
      - bag/backpack → backpack
      - man/woman/people → person
      - bike → bicycle
      - motorbike → motorcycle
      - plant → potted plant
      - glass → wine glass
      - computer/notebook → laptop
- **`core/SearchGuide`:** `direction(centerX)` returns one of five phrases:
  - `< 0.2` "far left"
  - `< 0.4` "left"
  - `0.4–0.6` "ahead"
  - `≤ 0.8` "right"
  - `> 0.8` "far right"
  - The front camera is mirrored by the caller.
  - `beepIntervalMs(centerX)` goes linearly from 1000 ms at the edge to 150 ms in the center.
  - `SearchTracker` holds found/lost state. It announces when the target is first found, when the direction phrase changes (at most every 2 s) and "Lost it" after 3 s without a match.
- **`core/ItemCore`:**
  - `ItemKind` (CAR, OBJECT) with `allows(label)`.
  - `ItemMatcher` (cosine, best match per label).
  - `ItemEnrollmentGuide`: 3 steps × 4 samples, `percent`, `stepFinished`, `done`.
  - `CenterPick.pick(boxes)`.

## Search camera screen

- `SearchCameraFragment` takes the arguments `targetKind` (PERSON/ITEM/LABEL), `targetId`, `targetName`, `targetLabel` and the camera facing.
- It reuses `ObjectDetectorHelper` (LIVE_STREAM, CPU, Lite2, confidence 0.4) and `OverlayView`, drawing only matching boxes.
- **Per frame, by target kind:**
  - Label: any kept detection with that label.
  - Item: kept detections with the item's label are cropped and embedded, and the best match ≥ threshold is taken. This runs on every frame (at most 3 crops).
  - Person: the face recognizer runs on frames that have a kept person. Only faces whose match is the target person count, and the target is the person box holding that face.
- **Feedback:**
  - `SearchTracker` provides the announcements.
  - `ToneGenerator` beeps at the guide's interval while the target is visible.
  - A 60 ms vibration fires each time the target enters the center band.
  - The screen text shows the latest phrase.
- **Controls:**
  - Top: "Searching for my bag".
  - Bottom: "Stop" (back) and a camera switch.

## Error handling

- Missing models or a failed recognizer: the app speaks and shows "Search is not available", and the screen stays open.
- Unknown query: "I can't search for X. Try again." The user taps the mic again.
- An item enrollment frame with no candidate gives the warning "Point the camera at the object", spoken at most every 3 s.

## Testing

- **JVM unit tests** for `VoiceCommandParser`, `SearchResolver`, `SearchGuide`/`SearchTracker`, `ItemKind`, `ItemMatcher`, `ItemEnrollmentGuide` and `CenterPick`.
- **Android code** is verified by `assembleDebug` only.
- **Device checks** are done by the user from a checklist (see the plan's last task).

## Out of scope

- Objects outside the 80 COCO classes.
- Languages other than English.
- Training models.
