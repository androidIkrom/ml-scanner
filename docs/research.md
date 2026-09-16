# Research notes (2026-09-16)

GitHub star counts and push dates were checked through the GitHub API on 2026-09-16.

## Base project

- **[google-ai-edge/mediapipe-samples](https://github.com/google-ai-edge/mediapipe-samples)**, `examples/object_detection/android`
  - Stars: 2828. Last push: 2026-09-15. License: Apache-2.0.
  - Kotlin app with CameraX, the EfficientDet-Lite detector and a box overlay. **This is our base.**

## Borrow code from

- **[googlesamples/mlkit](https://github.com/googlesamples/mlkit)**, `android/vision-quickstart`
  - Stars: 4297. License: Apache-2.0.
  - Object tracking IDs, useful as a deduplication reference.
- **[google-ai-edge/litert-samples](https://github.com/google-ai-edge/litert-samples)**, `samples/litert_interpreter/object_detection/android`
  - Stars: 432. License: Apache-2.0.
  - Plain LiteRT detection, useful for loading a custom `.tflite` model.
- **[surendramaran/YOLO](https://github.com/surendramaran/YOLO)**
  - Stars: 210. License unclear.
  - Kotlin YOLOv8–v11 with TFLite pre/post-processing. Only needed if the COCO EfficientDet model is not good enough.
- **[meodai/color-names](https://github.com/meodai/color-names)**
  - License: MIT.
  - Color name data. We only need 11 basic names.
- **[SmartToolFactory/Compose-Color-Detector](https://github.com/SmartToolFactory/Compose-Color-Detector)**
  - License: Apache-2.0.
  - Color naming in Kotlin.

## Reference only

- **Official Ultralytics apps** (for example [ultralytics/yolo-flutter-app](https://github.com/ultralytics/yolo-flutter-app)): licensed under AGPL-3.0.
- **Assistive apps:** EyeVis, A-EYE, dhwani_drishti, World-Viewer. They are small or stale and have no license, so **do not copy their code**.
- **Not found:** no repository combines detection with compass heading or 360° scanning. That part is this project's original contribution.

## Not chosen: ML Kit

- **Object Detection & Tracking:** only 5 coarse categories and at most 5 objects per frame.
- **Image Labeling:** has no bounding boxes.

## Datasets (for the stretch goal: fine-tuning)

These are on Roboflow Universe:
- **Classroom Dataset (label-data):** 307 images; bag, chair, desk.
- **classroom-count-det:** 195 images; chair, desk, table.
- **Whiteboards Detection:** 270 images.
- **ClassRoom (yh):** chair, table, person.

No single dataset has whiteboard, projector, desk and chair together, so they would need to be merged. The stock COCO model already detects chair, laptop, tv, book, backpack, person, bottle, clock, keyboard, mouse and cell phone.

## Hardware

- **Phone:** Infinix X6880, Android 15 (API 35), arm64-v8a, 8 GB RAM.
  - Has accelerometer, gyroscope, compass and an autofocus camera.
  - adb serial: `13192704AA003312`.
- **Laptop:** i5-11400H, 16 GB RAM, RTX 2050 (4 GB).
  - Drive C: has only about 15 GB free, so keep projects, models and datasets on D:.
- **Installed tools:**
  - Android Studio: `C:\Program Files\Android\Android Studio`
  - Android SDK: `%LOCALAPPDATA%\Android\Sdk`
  - JDK 21 (Adoptium)
  - Flutter: `D:\flutter` (not used)
