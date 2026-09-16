# ML Scanner

An on-device Android app that helps blind and low-vision users understand a room. Turn around once with the phone and it tells you what is around you:

> "Around you: 3 blue chairs in front; a black laptop on your right."

## Features

- **Home:** choose Full Scan, Live Scan or History.
- **Scan settings:** back or front camera, CPU or GPU, fast or accurate model, confidence, speech and colors. The last choices are remembered.
- **Full Scan:** turn 360°. The scan stops automatically and a spoken summary follows.
- **Live Scan:** objects are announced as they are found.
- **View text:** everything the app said during the scan, with times and the final summary.
- **History:** past scans are saved on the phone and can be replayed.
- **People:** save a person with a guided face scan; scans then say their name ("Ali in front").
- **Voice guide:** tapping a control says its name; tapping empty space reads the whole screen.

## How it works

Everything runs on the phone:

- **CameraX + MediaPipe ObjectDetector** (EfficientDet-Lite0 or Lite2, COCO) detect objects in the camera feed.
- **The rotation vector sensor** gives each object its direction.
- **Pixel color voting with gray-world white balance** names each object's color.
- **TextToSpeech** reads the results aloud.
- **Room** stores past scans and saved people.
- **ML Kit Face Detection + FaceNet-512 (TensorFlow Lite)** find and recognize faces. The FaceNet model comes from [shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android) (Apache-2.0).

The app is based on the [MediaPipe object detection sample](https://github.com/google-ai-edge/mediapipe-samples) (Apache-2.0).

## Build

1. Connect the phone with USB debugging enabled.
2. Run:

```powershell
.\gradlew.bat installDebug
```

See `docs/` for the design spec and plan.
