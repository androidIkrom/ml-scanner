# People and Face Recognition — Design Spec

- **Date:** 2026-09-17
- **Status:** Approved in chat
- **Builds on:** `2026-09-16-classroom-scanner-design.md`, `2026-09-16-home-settings-flow-design.md`

## Goal

Let the user save known people and have scans speak their names.

## Libraries

| Need | Library |
|---|---|
| Face detection and head angles | Google ML Kit Face Detection 16.1.7 (bundled model) |
| Face embeddings | FaceNet-512 TFLite (`facenet_512.tflite`, 160×160 input, 512-d output) from `shubham0204/FaceRecognition_With_FaceNet_Android` (Apache-2.0), run with `org.tensorflow:tensorflow-lite` 2.17.0 |
| Storage | Room (existing database, version 1 → 2 with a migration) |

Preprocessing follows the reference app: bilinear resize to 160×160, RGB floats, per-image standardization `(x − mean) / max(std, 1/√n)`. Two faces match when cosine similarity ≥ 0.3 (reference threshold for FaceNet-512); the best match wins.

## Screens

- **Home:** fourth card "People".
- **People:** list of saved people (photo thumbnail + name), empty state, large "Add person" button at the bottom.
- **Person detail:** large photo, name, "Delete" button with a confirmation dialog.
- **Add person:** step 1 asks for the name and the camera (Back/Front) and has a Start button. Step 2 shows the camera with a large instruction and a progress bar with percent. It speaks each new instruction and the percent.
  - Exactly one face must be in view; otherwise it says "Only one person should be in view." or "No face found."
  - Five poses in order: straight, left, right, up, down; four samples each (20 total, 5% each).
  - Pose rules on ML Kit angles (degrees): straight |yaw| < 10 and |pitch| < 10; left yaw > 20, right yaw < −20 (with |pitch| < 15); up pitch > 12, down pitch < −12 (with |yaw| < 15). CameraX does not mirror the analyzed frame, so the signs are the same for both cameras (to be confirmed on the phone).
  - Spoken: the first instruction, then at each finished pose "<percent> percent." plus the next instruction.
  - The photo is the first straight sample's face crop, saved as JPEG in app storage.
  - At 100% it says "Done. <name> added." and returns to People.

## Scanning

- On frames with at least one kept "person" box (and at least one saved person), every third such frame runs face detection on the frame, embeds each face and matches it.
- A matched face whose center lies inside a person box renames that detection to the person's name.
- Names are spoken without articles, plurals or colors: live "Ali in front."; full "Around you: Ali and Vali in front; 2 people behind you."
- A plain "person" cluster within 20° of a named cluster is left out of the summary.
- The last two groups in one direction are joined with "and" ("4 chairs and a black laptop in front").

## Data

- `people(id, name, photoPath, createdAt)`, `face_embeddings(id, personId → people.id ON DELETE CASCADE, vector BLOB)`.
- Deleting a person deletes the photo file and its embeddings.

## Testing

- TDD for pure core: `FaceMatcher`, `EnrollmentGuide`, `FaceNetPreprocess.standardize`, summary name wording, named/unnamed person merge.
- Build + unit tests; the user checks on the phone (front-camera left/right, recognition quality).
