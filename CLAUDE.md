# Classroom Scanner

This is an Android app (Kotlin) for a university AI Engineering course. It is a solo project, and the deliverable is a live demo.

The user turns 360° holding the phone. The app detects objects on-device with MediaPipe (COCO), records each object's direction and dominant color, removes duplicates, and speaks a summary with TTS. Past scans are saved to history with Room.

- **Deadline:** about 2026-09-18/19.

## Read first

- **Spec:** `docs/superpowers/specs/2026-09-16-classroom-scanner-design.md` (source of truth)
- **Plan:** `docs/superpowers/plans/2026-09-16-classroom-scanner.md` (execute task by task)
- **Research:** `docs/research.md` (repos, datasets, hardware)

## Working rules

- **Communication:** the user writes in Uzbek, so reply in Uzbek. Code, comments, UI text, speech and docs stay in English.
- **Reuse first:** the base is the MediaPipe object detection Android sample. Prefer existing libraries (CameraX, MediaPipe Tasks, Palette, TextToSpeech, Room) and write only glue code.
- **TDD:** use test-driven development for everything in the pure-Kotlin `core/` package. That package must have no Android imports.
- **Git:**
  - Do not use git worktrees; always work in this folder.
  - Never add `Co-Authored-By` or any other AI co-author line to commits.
- **Storage:** keep large files (datasets, models, builds) on drive D:, because drive C: is almost full.
- **Scope:** do not add features outside the spec. Stretch goals are only allowed if the user asks.

## Commands (PowerShell, from repo root)

The Android project root may be a subfolder after the sample is imported. Check the plan.

```powershell
.\gradlew.bat testDebugUnitTest          # JVM unit tests (core)
.\gradlew.bat assembleDebug              # build APK
.\gradlew.bat installDebug               # install on connected phone
.\gradlew.bat connectedDebugAndroidTest  # Room DAO instrumented test
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s ClassroomScanner
```

## Device

- **Phone:** Infinix X6880, Android 15 (API 35), arm64-v8a, adb serial `13192704AA003312`, USB debugging on.
- **If the phone shows as `offline` or `unauthorized`:** run `adb reconnect offline`, or ask the user to accept the prompt on the phone.
