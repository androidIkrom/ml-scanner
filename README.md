# ML Scanner

An on-device Android app that helps blind and low-vision users understand a room. Turn around once with the phone and it tells you what is around you:

> "Around you: 3 blue chairs in front; a black laptop on your right."

## Features

- **Home:** Full scan, Search and Saved, plus a voice command button ("live scan", "history", "find my bag").
- **Full scan hub:** Full Scan, Live Scan and History.
- **Scan settings:** back or front camera, CPU or GPU, fast or accurate model, confidence, speech and colors. The last choices are remembered.
- **Full Scan:** turn 360°. The scan stops automatically and a spoken summary follows.
- **Live Scan:** objects are announced as they are found.
- **View text:** everything the app said during the scan, with times and the final summary.
- **History:** past scans are saved on the phone and can be replayed.
- **Saved:** people, cars and objects in three tabs. People are saved with a guided face scan; cars and objects with a guided item scan. Scans then say their names ("Ali in front", "my bag on your left").
- **Search:** say or type what to find, such as a saved name or an everyday object like "chair". The app then guides you to it with speech, beeps that speed up near the center, and vibration.
- **Voice guide:** tapping a control says its name; tapping empty space reads the whole screen.

## How it works

Everything runs on the phone:

- **CameraX + MediaPipe ObjectDetector** (EfficientDet-Lite0 or Lite2, COCO) detect objects in the camera feed.
- **The rotation vector sensor** gives each object its direction.
- **Pixel color voting with gray-world white balance** names each object's color.
- **TextToSpeech** reads the results aloud.
- **Room** stores past scans and saved people, cars and objects.
- **MediaPipe ImageEmbedder** (mobilenet_v3_small, Apache-2.0) recognizes saved cars and objects.
- **Android SpeechRecognizer** turns spoken commands and names into text (English; works offline when the offline speech pack is installed).
- **ML Kit Face Detection + FaceNet-512 (TensorFlow Lite)** find and recognize faces. The FaceNet model comes from [shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android) (Apache-2.0).

The app is based on the [MediaPipe object detection sample](https://github.com/google-ai-edge/mediapipe-samples) (Apache-2.0).

## Build

1. Connect the phone with USB debugging enabled.
2. Run:

```powershell
.\gradlew.bat installDebug
```

See `docs/` for the design spec and plan.
