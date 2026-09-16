# Classroom Scanner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an on-device Android app. The user turns 360° with the phone; the app detects objects, records each object's direction and color, removes duplicates, speaks a summary, and saves every scan to history.

**Architecture:**
- **Base:** fork the official MediaPipe object detection Android sample. It already provides CameraX, the detector and the overlay.
- **Pure-Kotlin logic (`core/`), fully unit tested:**
  - angle math
  - coverage tracking
  - clustering
  - color mapping
  - summary text
  - the scan session state machine
- **Thin Android wrappers:**
  - sensor, FOV and color extraction
  - text-to-speech
  - Room storage
- **Wiring:** the sample's `CameraFragment` connects the wrappers to `core/`.

**Tech Stack:** Kotlin 2.1, AGP 8.11, Gradle 8.14.3, CameraX 1.4.2, MediaPipe Tasks Vision (EfficientDet-Lite0), `SensorManager` rotation vector, `androidx.palette`, `TextToSpeech`, Room 2.7.2 (kapt), Navigation 2.8.9, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-16-classroom-scanner-design.md`

## Global Constraints

- **Repo and project location:** the repo is `D:\classroom-scanner`. The Android project root is the repo root, so `gradlew.bat` lives there.
- **Git:**
  - Never use git worktrees.
  - Never add `Co-Authored-By` or any other AI co-author line to commit messages.
- **Package, SDK and orientation:**
  - Package / namespace / applicationId: `com.classroomscanner`.
  - `minSdk 24`, `compileSdk 35`, `targetSdk 35`.
  - The app is locked to portrait.
- **Language:** everything is in English: UI text, speech, code and comments. TTS locale is `Locale.US`.
- **Architecture rule:** `app/src/main/java/com/classroomscanner/core/` must not import any `android.*` or `androidx.*` class.
- **Constants:**

  | Constant | Value |
  |---|---|
  | Detector score threshold | 0.5 |
  | Max detector results | 10 |
  | Delegate | CPU |
  | Model | EfficientDet-Lite0 |
  | Cluster merge distance | 20° |
  | Frames to confirm a cluster | 3 |
  | Coverage bins | 36 (10° each) |
  | Full-scan timeout | 60 s |
  | Slow-down threshold | 60°/s, spoken at most every 5 s |
  | Gap between TTS announcements | 1.5 s |
  | Max pending announcements | 3 |
  | FOV fallback | 65° |

- **Color names:** exactly these 11: black, white, gray, red, orange, yellow, green, blue, purple, pink, brown.
- **Phone:** Infinix X6880, Android 15, adb serial `13192704AA003312`.
  - If `adb devices` shows `offline`, run `adb reconnect offline`.
  - If it shows `unauthorized`, ask the user to accept the prompt on the phone.
  - If install fails with `INSTALL_FAILED_USER_RESTRICTED`, ask the user to enable "Install via USB" in Developer options.
- **Commands:**
  - Run Gradle from PowerShell in `D:\classroom-scanner`.
  - Use `adb` from `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`.

**Spec deviations (decided while planning, keep them):**
- Room uses **kapt**, not KSP, because the sample already applies `kotlin-kapt` for data binding.
- The color is named on **every** frame, using a Palette crop downscaled to 48×48 px area, instead of every 5th frame. Only add throttling if FPS visibly drops.
- The permission screen shows text plus a button, with no spoken hint.

---

## File Map

```
build.gradle, settings.gradle, gradle.properties, gradlew(.bat), gradle/wrapper/*   (from sample)
local.properties                                   sdk.dir (gitignored)
app/build.gradle, app/download_models.gradle, app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/java/com/classroomscanner/
  MainActivity.kt                  single activity, bottom nav, window insets
  ObjectDetectorHelper.kt          sample; + frame bitmap in ResultBundle, maxResults 10
  OverlayView.kt                   sample; + custom per-box labels
  core/AngleMath.kt                normalize, diff, sectors, weighted mean; Sector8, Sector4
  core/BoxGeometry.kt              box center → upright image fraction; object angle
  core/CoverageTracker.kt          36-bin 360° coverage
  core/ColorMapper.kt              HSV → one of 11 color names
  core/ObjectClusterer.kt          FrameDetection, Cluster, dedup/count/color vote
  core/SummaryBuilder.kt           ObjectSummary, spoken sentences
  core/ScanSession.kt              ScanMode, ScanResult, per-scan state machine
  sensor/HeadingProvider.kt        rotation vector → relative heading + speed + accuracy
  sensor/CameraFov.kt              back camera horizontal FOV in portrait
  color/ColorNamer.kt              Palette crop → ColorMapper; frame darkness
  speech/SpeechAnnouncer.kt        throttled TTS queue
  history/Entities.kt              ScanEntity, DetectedObjectEntity
  history/ScanDao.kt               Room DAO
  history/AppDatabase.kt           Room database singleton
  history/HistoryRepository.kt     ScanResult → entities
  history/HistoryAdapter.kt        RecyclerView adapter
  ui/CoverageRingView.kt           360° progress ring
  fragments/CameraFragment.kt      Scan screen (sample, rewritten)
  fragments/PermissionsFragment.kt sample + explanation UI
  fragments/HistoryFragment.kt     History screen
app/src/main/res/layout/ activity_main.xml, fragment_camera.xml, fragment_permissions.xml,
                         fragment_history.xml, item_scan.xml
app/src/main/res/menu/menu_bottom_nav.xml, navigation/nav_graph.xml, values/strings.xml
app/src/test/java/com/classroomscanner/core/*Test.kt
app/src/androidTest/java/com/classroomscanner/history/ScanDaoTest.kt
```

---

### Task 1: Import the MediaPipe sample as the app skeleton

**Files:**
- Create: everything under the sample's `examples/object_detection/android/`, except its `README.md`, `object_detection.gif` and `app/src/androidTest/`.
- Modify: `build.gradle`, `app/build.gradle`, `app/download_models.gradle`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/navigation/nav_graph.xml`, `app/src/main/res/menu/menu_bottom_nav.xml`, `app/src/main/res/layout/activity_main.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/java/com/classroomscanner/MainActivity.kt`
- Delete: `GalleryFragment.kt`, `res/layout/fragment_gallery.xml`, `res/drawable/media_pipe_banner.xml`

**Interfaces:**
- Produces: a buildable app, package `com.classroomscanner`, containing the sample classes `ObjectDetectorHelper`, `OverlayView`, `MainActivity`, `MainViewModel`, `fragments.CameraFragment` and `fragments.PermissionsFragment`. The layout has a `fragment_container` nav host and the `navigation` bottom nav.

- [ ] **Step 1: Get the sample source**

The sample is already checked out in `D:\_ref\mediapipe-samples`. If that folder is missing, run:

```powershell
git clone --depth 1 --filter=blob:none --sparse https://github.com/google-ai-edge/mediapipe-samples.git D:\_ref\mediapipe-samples
git -C D:\_ref\mediapipe-samples sparse-checkout set examples/object_detection/android
```

- [ ] **Step 2: Copy the project into the repo root**

Do not copy the sample's `README.md`: it would overwrite ours.

```powershell
$src = "D:\_ref\mediapipe-samples\examples\object_detection\android"
$dst = "D:\classroom-scanner"
Copy-Item "$src\build.gradle","$src\settings.gradle","$src\gradle.properties","$src\gradlew","$src\gradlew.bat" $dst
Copy-Item -Recurse "$src\gradle" "$dst\gradle"
Copy-Item -Recurse "$src\app" "$dst\app"
Remove-Item -Recurse -Force "$dst\app\src\androidTest"
Set-Content -Encoding ascii "$dst\local.properties" "sdk.dir=C\:\\Users\\user\\AppData\\Local\\Android\\Sdk"
```

- [ ] **Step 3: Move the package to `com.classroomscanner`**

Run in Git Bash:

```bash
cd /d/classroom-scanner/app/src/main/java
mkdir -p com/classroomscanner
mv com/google/mediapipe/examples/objectdetection/* com/classroomscanner/
rm -rf com/google
cd /d/classroom-scanner/app
grep -rl 'com\.google\.mediapipe\.examples\.objectdetection' src build.gradle | xargs sed -i 's/com\.google\.mediapipe\.examples\.objectdetection/com.classroomscanner/g'
grep -rn 'examples.objectdetection' src build.gradle || echo "clean"
```

Expected: the last command prints `clean`.

- [ ] **Step 4: Remove the gallery feature and the MediaPipe banner**

```powershell
$app = "D:\classroom-scanner\app\src\main"
Remove-Item "$app\java\com\classroomscanner\fragments\GalleryFragment.kt", "$app\res\layout\fragment_gallery.xml", "$app\res\drawable\media_pipe_banner.xml"
```

1. In `res/navigation/nav_graph.xml`, delete the whole `<fragment android:id="@+id/gallery_fragment" ... />` element.
2. Replace `res/menu/menu_bottom_nav.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@id/camera_fragment"
        android:icon="@drawable/ic_baseline_photo_camera_24"
        android:title="@string/menu_camera" />
</menu>
```

3. In `res/layout/activity_main.xml`, replace the `<ImageView ... android:src="@drawable/media_pipe_banner" />` inside the Toolbar with:

```xml
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/app_name"
                android:textColor="@android:color/black"
                android:textSize="20sp"
                android:textStyle="bold" />
```

4. In `res/values/strings.xml`:
   - change `app_name` to `Classroom Scanner`;
   - delete the `menu_gallery` and `tv_gallery_placeholder` strings (the placeholder string spans two lines; delete both).

- [ ] **Step 5: Update the Gradle files**

1. In root `build.gradle`:
   - delete the line `classpath 'com.android.tools.build:gradle:7.3.1'` (keep `8.11.0`);
   - change the safe-args line to `classpath 'androidx.navigation:navigation-safe-args-gradle-plugin:2.8.9'`.
2. In `app/download_models.gradle`:
   - delete the `downloadModelFile1` task;
   - change the last line to `preBuild.dependsOn downloadModelFile0`.
3. Replace `app/build.gradle` with:

```groovy
apply plugin: 'com.android.application'
apply plugin: 'kotlin-android'
apply plugin: 'kotlin-kapt'
apply plugin: "androidx.navigation.safeargs"
apply plugin: 'de.undercouch.download'

android {
    compileSdk 35
    namespace "com.classroomscanner"

    defaultConfig {
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
        applicationId "com.classroomscanner"
        minSdk 24
        targetSdk 35
        versionCode 1
        versionName "1.0.0"
    }

    dataBinding {
        enabled = true
    }

    compileOptions {
        sourceCompatibility rootProject.ext.java_version
        targetCompatibility rootProject.ext.java_version
    }

    kotlinOptions {
        jvmTarget = rootProject.ext.java_version
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
        }
    }

    buildFeatures {
        viewBinding true
    }
    androidResources {
        noCompress 'tflite'
    }
}

project.ext.ASSET_DIR = projectDir.toString() + '/src/main/assets'
apply from: 'download_models.gradle'

dependencies {
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version"
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'

    implementation 'androidx.appcompat:appcompat:1.3.1'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.8.7'
    implementation 'androidx.constraintlayout:constraintlayout:2.0.4'
    // Keep 1.0.0: newer Material requires a MaterialComponents theme for BottomNavigationView.
    implementation 'com.google.android.material:material:1.0.0'
    implementation 'androidx.fragment:fragment-ktx:1.8.5'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'

    def nav_version = "2.8.9"
    implementation "androidx.navigation:navigation-fragment-ktx:$nav_version"
    implementation "androidx.navigation:navigation-ui-ktx:$nav_version"

    def camerax_version = '1.4.2'
    implementation "androidx.camera:camera-core:$camerax_version"
    implementation "androidx.camera:camera-camera2:$camerax_version"
    implementation "androidx.camera:camera-lifecycle:$camerax_version"
    implementation "androidx.camera:camera-view:$camerax_version"

    implementation 'androidx.window:window:1.0.0-alpha09'

    implementation 'com.google.mediapipe:tasks-vision:1.0.0'
    implementation 'androidx.palette:palette-ktx:1.0.0'

    def room_version = '2.7.2'
    implementation "androidx.room:room-runtime:$room_version"
    implementation "androidx.room:room-ktx:$room_version"
    kapt "androidx.room:room-compiler:$room_version"

    testImplementation 'junit:junit:4.13.2'

    androidTestImplementation 'androidx.test.ext:junit:1.2.1'
    androidTestImplementation 'androidx.test:core:1.6.1'
    androidTestImplementation 'androidx.test:runner:1.6.2'
}
```

If Gradle reports that the `tasks-vision` version cannot be resolved, use the version from the sample's own `app/build.gradle` at `D:\_ref`, which is the source of truth.

- [ ] **Step 6: Manifest — portrait lock, TTS visibility, no `package` attribute**

In `app/src/main/AndroidManifest.xml`:
1. Remove `package="com.classroomscanner"` from `<manifest>`.
2. Add `android:screenOrientation="portrait"` to the `<activity>` element.
3. Add this block directly after `<uses-permission android:name="android.permission.CAMERA" />`:

```xml
    <!-- Needed to bind a TextToSpeech engine on Android 11+ -->
    <queries>
        <intent>
            <action android:name="android.intent.action.TTS_SERVICE" />
        </intent>
    </queries>
```

- [ ] **Step 7: Edge-to-edge insets in `MainActivity`**

`targetSdk 35` forces edge-to-edge on Android 15, so the root view needs padding for the system bars. In `MainActivity.onCreate`, directly after `setContentView(activityMainBinding.root)`, add:

```kotlin
        ViewCompat.setOnApplyWindowInsetsListener(activityMainBinding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
```

Also add these imports: `androidx.core.view.ViewCompat` and `androidx.core.view.WindowInsetsCompat`.

- [ ] **Step 8: Build**

```powershell
cd D:\classroom-scanner
.\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`, and `app\src\main\assets\efficientdet-lite0.tflite` exists.

If the build fails, read the first error and fix only that. Likely causes:
- a deprecated API in `CameraFragment` or `PermissionsFragment` that is now an error; fix it minimally;
- a missing import after the package move.

- [ ] **Step 9: Install and smoke-test on the phone**

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
.\gradlew.bat installDebug
& $adb shell am start -n com.classroomscanner/.MainActivity
```

Expected: the app opens and asks for camera permission. After granting, the camera preview shows boxes labeled like `chair 0.62`. Ask the user to confirm this on the phone.

- [ ] **Step 10: Commit**

Also add `app/src/main/assets/` to `.gitignore`, because the model is downloaded at build time.

```powershell
Add-Content .gitignore "app/src/main/assets/"
git add -A
git commit -m "chore: import MediaPipe object detection sample as app skeleton"
```

---

### Task 2: AngleMath and BoxGeometry

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/AngleMath.kt`, `app/src/main/java/com/classroomscanner/core/BoxGeometry.kt`
- Test: `app/src/test/java/com/classroomscanner/core/AngleMathTest.kt`, `app/src/test/java/com/classroomscanner/core/BoxGeometryTest.kt`

**Interfaces:**
- Produces:
  - `enum class Sector8 { FRONT, FRONT_RIGHT, RIGHT, BEHIND_RIGHT, BEHIND, BEHIND_LEFT, LEFT, FRONT_LEFT }`
  - `enum class Sector4(val phrase: String) { FRONT("in front"), RIGHT("on your right"), BEHIND("behind you"), LEFT("on your left") }`
  - `object AngleMath`:
    - `normalize(deg: Float): Float` returns a value in `[0,360)`
    - `diff(a: Float, b: Float): Float` returns the shortest `a − b`, in `(-180,180]`
    - `sector8(relDeg: Float): Sector8`
    - `sector4(relDeg: Float): Sector4`
    - `weightedMean(mean: Float, count: Int, sample: Float): Float`
  - `object BoxGeometry`:
    - `horizontalCenter(left: Float, top: Float, right: Float, bottom: Float, imageWidth: Int, imageHeight: Int, rotationDegrees: Int): Float` returns a value in `0..1`
    - `objectAngle(relHeading: Float, centerNorm: Float, hfovDeg: Float): Float`

- [ ] **Step 1: Write the failing tests**

`AngleMathTest.kt`:

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleMathTest {
    private val eps = 0.001f

    @Test
    fun normalizeWrapsIntoZeroTo360() {
        assertEquals(350f, AngleMath.normalize(-10f), eps)
        assertEquals(10f, AngleMath.normalize(370f), eps)
        assertEquals(0f, AngleMath.normalize(360f), eps)
        assertEquals(0f, AngleMath.normalize(0f), eps)
        assertTrue(AngleMath.normalize(-0.00001f) < 360f)
    }

    @Test
    fun diffTakesShortestWay() {
        assertEquals(2f, AngleMath.diff(1f, 359f), eps)
        assertEquals(-2f, AngleMath.diff(359f, 1f), eps)
        assertEquals(180f, AngleMath.diff(180f, 0f), eps)
        assertEquals(-90f, AngleMath.diff(0f, 90f), eps)
    }

    @Test
    fun sector8Boundaries() {
        assertEquals(Sector8.FRONT, AngleMath.sector8(0f))
        assertEquals(Sector8.FRONT, AngleMath.sector8(22.4f))
        assertEquals(Sector8.FRONT_RIGHT, AngleMath.sector8(22.5f))
        assertEquals(Sector8.RIGHT, AngleMath.sector8(90f))
        assertEquals(Sector8.BEHIND_RIGHT, AngleMath.sector8(135f))
        assertEquals(Sector8.BEHIND, AngleMath.sector8(180f))
        assertEquals(Sector8.BEHIND_LEFT, AngleMath.sector8(225f))
        assertEquals(Sector8.LEFT, AngleMath.sector8(270f))
        assertEquals(Sector8.FRONT_LEFT, AngleMath.sector8(337.4f))
        assertEquals(Sector8.FRONT, AngleMath.sector8(337.5f))
        assertEquals(Sector8.FRONT, AngleMath.sector8(-10f))
    }

    @Test
    fun sector4Boundaries() {
        assertEquals(Sector4.FRONT, AngleMath.sector4(44.9f))
        assertEquals(Sector4.RIGHT, AngleMath.sector4(45f))
        assertEquals(Sector4.RIGHT, AngleMath.sector4(134.9f))
        assertEquals(Sector4.BEHIND, AngleMath.sector4(135f))
        assertEquals(Sector4.LEFT, AngleMath.sector4(225f))
        assertEquals(Sector4.LEFT, AngleMath.sector4(314.9f))
        assertEquals(Sector4.FRONT, AngleMath.sector4(315f))
    }

    @Test
    fun weightedMeanHandlesWrapAround() {
        assertEquals(42f, AngleMath.weightedMean(123f, 0, 42f), eps)
        assertEquals(0f, AngleMath.weightedMean(350f, 1, 10f), eps)
        assertEquals(20f, AngleMath.weightedMean(10f, 1, 30f), eps)
    }
}
```

`BoxGeometryTest.kt`:

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BoxGeometryTest {
    private val eps = 0.001f

    @Test
    fun noRotationUsesX() {
        assertEquals(0.125f, BoxGeometry.horizontalCenter(0f, 0f, 100f, 50f, 400, 300, 0), eps)
    }

    @Test
    fun rotation90UsesInvertedY() {
        // Unrotated buffer 640x480; box vertical center y=60 -> upright x fraction 1 - 60/480
        assertEquals(0.875f, BoxGeometry.horizontalCenter(0f, 0f, 10f, 120f, 640, 480, 90), eps)
    }

    @Test
    fun rotation180UsesInvertedX() {
        assertEquals(0.875f, BoxGeometry.horizontalCenter(0f, 0f, 100f, 50f, 400, 300, 180), eps)
    }

    @Test
    fun rotation270UsesY() {
        assertEquals(0.125f, BoxGeometry.horizontalCenter(0f, 0f, 10f, 120f, 640, 480, 270), eps)
    }

    @Test
    fun objectAngleAddsFovOffsetAndWraps() {
        assertEquals(20f, BoxGeometry.objectAngle(350f, 1f, 60f), eps)
        assertEquals(340f, BoxGeometry.objectAngle(10f, 0f, 60f), eps)
        assertEquals(90f, BoxGeometry.objectAngle(90f, 0.5f, 65f), eps)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.*"`
Expected: compilation FAILS with `Unresolved reference 'AngleMath'` and `Unresolved reference 'BoxGeometry'`.

- [ ] **Step 3: Implement**

`AngleMath.kt`:

```kotlin
package com.classroomscanner.core

enum class Sector8 { FRONT, FRONT_RIGHT, RIGHT, BEHIND_RIGHT, BEHIND, BEHIND_LEFT, LEFT, FRONT_LEFT }

enum class Sector4(val phrase: String) {
    FRONT("in front"),
    RIGHT("on your right"),
    BEHIND("behind you"),
    LEFT("on your left"),
}

/** Angles are degrees, clockwise, relative to the direction the user faced when the scan started. */
object AngleMath {

    fun normalize(deg: Float): Float {
        val r = deg % 360f
        val n = if (r < 0f) r + 360f else r
        return if (n >= 360f) 0f else n
    }

    /** Shortest signed difference `a - b`, in (-180, 180]. */
    fun diff(a: Float, b: Float): Float {
        val d = normalize(a - b)
        return if (d > 180f) d - 360f else d
    }

    fun sector8(relDeg: Float): Sector8 =
        Sector8.entries[((normalize(relDeg) + 22.5f) / 45f).toInt() % 8]

    fun sector4(relDeg: Float): Sector4 =
        Sector4.entries[((normalize(relDeg) + 45f) / 90f).toInt() % 4]

    /** Running circular mean: [mean] already averages [count] samples; fold in [sample]. */
    fun weightedMean(mean: Float, count: Int, sample: Float): Float =
        normalize(mean + diff(sample, mean) / (count + 1))
}
```

`BoxGeometry.kt`:

```kotlin
package com.classroomscanner.core

object BoxGeometry {

    /**
     * Horizontal center of a detection box as a 0..1 fraction of the upright image width.
     * Box coordinates are in the unrotated camera buffer ([imageWidth] x [imageHeight]),
     * which is how the MediaPipe sample's OverlayView treats them.
     */
    fun horizontalCenter(
        left: Float, top: Float, right: Float, bottom: Float,
        imageWidth: Int, imageHeight: Int, rotationDegrees: Int,
    ): Float {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val fraction = when (rotationDegrees) {
            90 -> 1f - cy / imageHeight
            180 -> 1f - cx / imageWidth
            270 -> cy / imageHeight
            else -> cx / imageWidth
        }
        return fraction.coerceIn(0f, 1f)
    }

    fun objectAngle(relHeading: Float, centerNorm: Float, hfovDeg: Float): Float =
        AngleMath.normalize(relHeading + (centerNorm - 0.5f) * hfovDeg)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.*"`
Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/classroomscanner/core app/src/test
git commit -m "feat(core): add angle math and box geometry"
```

---

### Task 3: CoverageTracker

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/CoverageTracker.kt`
- Test: `app/src/test/java/com/classroomscanner/core/CoverageTrackerTest.kt`

**Interfaces:**
- Consumes: `AngleMath.normalize`, `AngleMath.diff`
- Produces: `class CoverageTracker(binCount: Int = 36)` with:
  - `mark(relHeading: Float)`
  - `markArc(from: Float, to: Float)`
  - `coveredBins(): Int`
  - `percent(): Int`
  - `isComplete(): Boolean`
  - `snapshot(): BooleanArray`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTrackerTest {

    @Test
    fun startsEmpty() {
        val t = CoverageTracker()
        assertEquals(0, t.percent())
        assertFalse(t.isComplete())
    }

    @Test
    fun markCoversOneBin() {
        val t = CoverageTracker()
        t.mark(5f)
        assertEquals(1, t.coveredBins())
        assertEquals(2, t.percent())
        assertTrue(t.snapshot()[0])
    }

    @Test
    fun markArcFillsBinsBetween() {
        val t = CoverageTracker()
        t.markArc(0f, 40f)
        assertEquals(5, t.coveredBins())
    }

    @Test
    fun markArcCrossesZero() {
        val t = CoverageTracker()
        t.markArc(355f, 5f)
        assertEquals(2, t.coveredBins())
        assertTrue(t.snapshot()[35])
        assertTrue(t.snapshot()[0])
    }

    @Test
    fun largeJumpOnlyMarksTarget() {
        val t = CoverageTracker()
        t.markArc(0f, 100f)
        assertEquals(1, t.coveredBins())
        assertTrue(t.snapshot()[10])
    }

    @Test
    fun fullCircleIsComplete() {
        val t = CoverageTracker()
        for (a in 0 until 360 step 5) t.markArc(a.toFloat(), (a + 5).toFloat())
        assertTrue(t.isComplete())
        assertEquals(100, t.percent())
    }

    @Test
    fun snapshotIsACopy() {
        val t = CoverageTracker()
        t.snapshot()[3] = true
        assertEquals(0, t.coveredBins())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.CoverageTrackerTest"`
Expected: compilation FAILS with `Unresolved reference 'CoverageTracker'`.

- [ ] **Step 3: Implement**

```kotlin
package com.classroomscanner.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Tracks which parts of the circle the camera has pointed at during a scan. */
class CoverageTracker(private val binCount: Int = 36) {

    private val covered = BooleanArray(binCount)
    private val binSize = 360f / binCount

    fun mark(relHeading: Float) {
        val bin = (AngleMath.normalize(relHeading) / binSize).toInt().coerceIn(0, binCount - 1)
        covered[bin] = true
    }

    /** Marks every bin swept between two consecutive headings; a big jump is treated as a glitch. */
    fun markArc(from: Float, to: Float) {
        val d = AngleMath.diff(to, from)
        if (abs(d) > MAX_ARC_DEG) {
            mark(to)
            return
        }
        val steps = max(ceil(abs(d) / STEP_DEG).toInt(), 1)
        for (i in 0..steps) mark(from + d * i / steps)
    }

    fun coveredBins(): Int = covered.count { it }

    fun percent(): Int = coveredBins() * 100 / binCount

    fun isComplete(): Boolean = covered.all { it }

    fun snapshot(): BooleanArray = covered.copyOf()

    private companion object {
        const val MAX_ARC_DEG = 45f
        const val STEP_DEG = 5f
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.CoverageTrackerTest"`
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/classroomscanner/core/CoverageTracker.kt app/src/test/java/com/classroomscanner/core/CoverageTrackerTest.kt
git commit -m "feat(core): add 360-degree coverage tracker"
```

---

### Task 4: ColorMapper

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/ColorMapper.kt`
- Test: `app/src/test/java/com/classroomscanner/core/ColorMapperTest.kt`

**Interfaces:**
- Produces: `object ColorMapper { fun nameFromHsv(h: Float, s: Float, v: Float, frameIsDark: Boolean = false): String? }`
  - `h` is in `0..360`; `s` and `v` are in `0..1`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorMapperTest {
    private fun name(h: Float, s: Float, v: Float) = ColorMapper.nameFromHsv(h, s, v)

    @Test fun black() = assertEquals("black", name(0f, 0f, 0.1f))
    @Test fun blackWinsOverHue() = assertEquals("black", name(220f, 1f, 0.15f))
    @Test fun white() = assertEquals("white", name(0f, 0.05f, 0.95f))
    @Test fun gray() = assertEquals("gray", name(0f, 0.05f, 0.5f))
    @Test fun red() = assertEquals("red", name(0f, 1f, 1f))
    @Test fun redNear360() = assertEquals("red", name(350f, 0.9f, 0.8f))
    @Test fun lightRedIsPink() = assertEquals("pink", name(5f, 0.3f, 0.9f))
    @Test fun orange() = assertEquals("orange", name(30f, 1f, 1f))
    @Test fun darkOrangeIsBrown() = assertEquals("brown", name(30f, 0.8f, 0.4f))
    @Test fun yellow() = assertEquals("yellow", name(60f, 1f, 1f))
    @Test fun green() = assertEquals("green", name(120f, 1f, 1f))
    @Test fun blue() = assertEquals("blue", name(220f, 1f, 1f))
    @Test fun purple() = assertEquals("purple", name(275f, 1f, 1f))
    @Test fun pink() = assertEquals("pink", name(320f, 1f, 1f))
    @Test fun darkFrameIsUnknown() = assertNull(ColorMapper.nameFromHsv(220f, 1f, 1f, frameIsDark = true))
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.ColorMapperTest"`
Expected: compilation FAILS with `Unresolved reference 'ColorMapper'`.

- [ ] **Step 3: Implement**

```kotlin
package com.classroomscanner.core

/** Maps an HSV color to one of 11 basic English color names. */
object ColorMapper {

    fun nameFromHsv(h: Float, s: Float, v: Float, frameIsDark: Boolean = false): String? {
        if (frameIsDark) return null
        if (v < 0.2f) return "black"
        if (s < 0.15f) return if (v > 0.8f) "white" else "gray"
        return when {
            h < 15f || h >= 345f -> if (s < 0.5f && v > 0.7f) "pink" else "red"
            h < 45f -> if (v < 0.6f) "brown" else "orange"
            h < 70f -> "yellow"
            h < 170f -> "green"
            h < 260f -> "blue"
            h < 290f -> "purple"
            else -> "pink"
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.ColorMapperTest"`
Expected: 15 tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/classroomscanner/core/ColorMapper.kt app/src/test/java/com/classroomscanner/core/ColorMapperTest.kt
git commit -m "feat(core): map HSV to basic color names"
```

---

### Task 5: ObjectClusterer (deduplication)

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/ObjectClusterer.kt`
- Test: `app/src/test/java/com/classroomscanner/core/ObjectClustererTest.kt`

**Interfaces:**
- Consumes: `AngleMath.diff`, `AngleMath.weightedMean`
- Produces:
  - `data class FrameDetection(val label: String, val angle: Float, val color: String?)`
  - `class Cluster` with these read-only properties:
    - `id: Int`
    - `label: String`
    - `meanAngle: Float`
    - `framesSeen: Int`
    - `maxInSingleFrame: Int`
    - `count: Int` (equals `maxInSingleFrame`)
    - `color: String?` (majority vote)
  - `class ObjectClusterer(mergeDeg: Float = 20f, confirmFrames: Int = 3)` with:
    - `addFrame(detections: List<FrameDetection>): List<Cluster>`, which returns the clusters confirmed for the first time by this frame
    - `clusters(): List<Cluster>`
    - `confirmed(): List<Cluster>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectClustererTest {
    private fun det(label: String, angle: Float, color: String? = null) = FrameDetection(label, angle, color)

    @Test
    fun sameObjectOverManyFramesCountsOnce() {
        val c = ObjectClusterer()
        repeat(100) { c.addFrame(listOf(det("chair", 10f + (it % 5)))) }
        assertEquals(1, c.confirmed().size)
        assertEquals(1, c.confirmed()[0].count)
    }

    @Test
    fun threeInOneFrameCountsThree() {
        val c = ObjectClusterer()
        repeat(5) { c.addFrame(listOf(det("chair", 0f), det("chair", 5f), det("chair", 10f))) }
        assertEquals(1, c.confirmed().size)
        assertEquals(3, c.confirmed()[0].count)
    }

    @Test
    fun sameLabelFarApartMakesTwoClusters() {
        val c = ObjectClusterer()
        repeat(3) { c.addFrame(listOf(det("chair", 0f))) }
        repeat(3) { c.addFrame(listOf(det("chair", 90f))) }
        assertEquals(2, c.confirmed().size)
    }

    @Test
    fun differentLabelsDoNotMerge() {
        val c = ObjectClusterer()
        repeat(3) { c.addFrame(listOf(det("chair", 0f), det("laptop", 2f))) }
        assertEquals(setOf("chair", "laptop"), c.confirmed().map { it.label }.toSet())
    }

    @Test
    fun wrapAroundMergesIntoOneCluster() {
        val c = ObjectClusterer()
        repeat(3) {
            c.addFrame(listOf(det("tv", 355f)))
            c.addFrame(listOf(det("tv", 5f)))
        }
        assertEquals(1, c.clusters().size)
    }

    @Test
    fun needsThreeFramesAndReportsConfirmationOnce() {
        val c = ObjectClusterer()
        val frame = listOf(det("chair", 0f))
        assertTrue(c.addFrame(frame).isEmpty())
        assertTrue(c.addFrame(frame).isEmpty())
        assertTrue(c.confirmed().isEmpty())
        assertEquals(1, c.addFrame(frame).size)
        assertEquals(1, c.confirmed().size)
        assertTrue(c.addFrame(frame).isEmpty())
    }

    @Test
    fun colorIsMajorityVoteIgnoringNull() {
        val c = ObjectClusterer()
        c.addFrame(listOf(det("chair", 0f, "blue")))
        c.addFrame(listOf(det("chair", 0f, "gray")))
        c.addFrame(listOf(det("chair", 0f, "blue")))
        c.addFrame(listOf(det("chair", 0f, null)))
        assertEquals("blue", c.confirmed()[0].color)
    }

    @Test
    fun colorIsNullWhenNeverNamed() {
        val c = ObjectClusterer()
        repeat(3) { c.addFrame(listOf(det("chair", 0f))) }
        assertNull(c.confirmed()[0].color)
    }

    @Test
    fun meanAngleFollowsSamplesAcrossZero() {
        val c = ObjectClusterer()
        c.addFrame(listOf(det("chair", 355f)))
        c.addFrame(listOf(det("chair", 5f)))
        assertEquals(0f, c.clusters()[0].meanAngle, 0.01f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.ObjectClustererTest"`
Expected: compilation FAILS with `Unresolved reference 'FrameDetection'`.

- [ ] **Step 3: Implement**

```kotlin
package com.classroomscanner.core

import kotlin.math.abs

/** One detection in one camera frame, already converted to a scan-relative angle. */
data class FrameDetection(val label: String, val angle: Float, val color: String?)

/** A single real-world object (or a tight group of same-label objects) seen across frames. */
class Cluster internal constructor(val id: Int, val label: String, angle: Float) {
    var meanAngle: Float = angle
        internal set
    var framesSeen: Int = 0
        internal set
    var maxInSingleFrame: Int = 0
        internal set
    internal var samples: Int = 0
    private val colorVotes = LinkedHashMap<String, Int>()

    /** Counting by the most seen in one frame keeps 3 chairs at 3 no matter how many frames saw them. */
    val count: Int get() = maxInSingleFrame

    val color: String? get() = colorVotes.maxByOrNull { it.value }?.key

    internal fun vote(color: String?) {
        if (color != null) colorVotes[color] = (colorVotes[color] ?: 0) + 1
    }
}

class ObjectClusterer(
    private val mergeDeg: Float = 20f,
    private val confirmFrames: Int = 3,
) {
    private val clusters = mutableListOf<Cluster>()
    private var nextId = 1

    fun clusters(): List<Cluster> = clusters

    fun confirmed(): List<Cluster> = clusters.filter { it.framesSeen >= confirmFrames }

    /** Adds one frame's detections; returns clusters that became confirmed because of this frame. */
    fun addFrame(detections: List<FrameDetection>): List<Cluster> {
        val hitsInFrame = LinkedHashMap<Cluster, Int>()
        for (d in detections) {
            val cluster = nearest(d) ?: Cluster(nextId++, d.label, d.angle).also { clusters += it }
            cluster.meanAngle = AngleMath.weightedMean(cluster.meanAngle, cluster.samples, d.angle)
            cluster.samples++
            cluster.vote(d.color)
            hitsInFrame[cluster] = (hitsInFrame[cluster] ?: 0) + 1
        }

        val newlyConfirmed = mutableListOf<Cluster>()
        for ((cluster, hits) in hitsInFrame) {
            val wasConfirmed = cluster.framesSeen >= confirmFrames
            cluster.framesSeen++
            cluster.maxInSingleFrame = maxOf(cluster.maxInSingleFrame, hits)
            if (!wasConfirmed && cluster.framesSeen >= confirmFrames) newlyConfirmed += cluster
        }
        return newlyConfirmed
    }

    private fun nearest(d: FrameDetection): Cluster? =
        clusters
            .filter { it.label == d.label && abs(AngleMath.diff(d.angle, it.meanAngle)) < mergeDeg }
            .minByOrNull { abs(AngleMath.diff(d.angle, it.meanAngle)) }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.ObjectClustererTest"`
Expected: 9 tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/classroomscanner/core/ObjectClusterer.kt app/src/test/java/com/classroomscanner/core/ObjectClustererTest.kt
git commit -m "feat(core): deduplicate detections across frames"
```

---

### Task 6: SummaryBuilder

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/SummaryBuilder.kt`
- Test: `app/src/test/java/com/classroomscanner/core/SummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `AngleMath.sector4`, `Sector4.phrase`
- Produces:
  - `data class ObjectSummary(val label: String, val count: Int, val color: String?, val angle: Float)`
  - `object SummaryBuilder` with:
    - `describe(o: ObjectSummary): String`
    - `plural(label: String): String`
    - `fullSummary(objects: List<ObjectSummary>, coveragePercent: Int): String`
    - `livePhrase(o: ObjectSummary): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryBuilderTest {
    private fun obj(label: String, count: Int, color: String?, angle: Float) = ObjectSummary(label, count, color, angle)

    @Test fun singularWithColor() = assertEquals("a blue chair", SummaryBuilder.describe(obj("chair", 1, "blue", 0f)))
    @Test fun anBeforeVowelColor() = assertEquals("an orange cup", SummaryBuilder.describe(obj("cup", 1, "orange", 0f)))
    @Test fun anBeforeVowelLabel() = assertEquals("an umbrella", SummaryBuilder.describe(obj("umbrella", 1, null, 0f)))
    @Test fun pluralWithColor() = assertEquals("3 blue chairs", SummaryBuilder.describe(obj("chair", 3, "blue", 0f)))
    @Test fun pluralWithoutColor() = assertEquals("2 books", SummaryBuilder.describe(obj("book", 2, null, 0f)))

    @Test
    fun pluralRules() {
        assertEquals("people", SummaryBuilder.plural("person"))
        assertEquals("mice", SummaryBuilder.plural("mouse"))
        assertEquals("knives", SummaryBuilder.plural("knife"))
        assertEquals("couches", SummaryBuilder.plural("couch"))
        assertEquals("buses", SummaryBuilder.plural("bus"))
        assertEquals("wine glasses", SummaryBuilder.plural("wine glass"))
        assertEquals("cell phones", SummaryBuilder.plural("cell phone"))
        assertEquals("skis", SummaryBuilder.plural("skis"))
        assertEquals("tvs", SummaryBuilder.plural("tv"))
    }

    @Test
    fun fullSummaryGroupsBySectorInOrder() {
        val text = SummaryBuilder.fullSummary(
            listOf(
                obj("laptop", 1, "black", 90f),
                obj("chair", 3, "blue", 10f),
                obj("tv", 1, "white", 180f),
                obj("book", 2, null, 270f),
            ),
            100,
        )
        assertEquals(
            "Around you: 3 blue chairs in front; a black laptop on your right; a white tv behind you; 2 books on your left.",
            text,
        )
    }

    @Test
    fun sameLabelAndColorInOneSectorAreMerged() {
        val text = SummaryBuilder.fullSummary(
            listOf(obj("chair", 2, "blue", 0f), obj("chair", 1, "blue", 30f)),
            100,
        )
        assertEquals("Around you: 3 blue chairs in front.", text)
    }

    @Test
    fun biggerGroupsComeFirstWithinASector() {
        val text = SummaryBuilder.fullSummary(
            listOf(obj("laptop", 1, "black", 0f), obj("chair", 4, null, 10f)),
            100,
        )
        assertEquals("Around you: 4 chairs, a black laptop in front.", text)
    }

    @Test
    fun partialCoverageAddsPrefix() {
        val text = SummaryBuilder.fullSummary(listOf(obj("chair", 1, null, 0f)), 70)
        assertEquals("I scanned 70 percent of the room. Around you: a chair in front.", text)
    }

    @Test
    fun emptyResult() {
        assertEquals(
            "No objects found. Try better lighting and turn slowly.",
            SummaryBuilder.fullSummary(emptyList(), 100),
        )
    }

    @Test
    fun livePhrases() {
        assertEquals("Blue chair on your left.", SummaryBuilder.livePhrase(obj("chair", 1, "blue", 270f)))
        assertEquals("Laptop in front.", SummaryBuilder.livePhrase(obj("laptop", 1, null, 0f)))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.SummaryBuilderTest"`
Expected: compilation FAILS with `Unresolved reference 'ObjectSummary'`.

- [ ] **Step 3: Implement**

```kotlin
package com.classroomscanner.core

/** What the app says about one object group. [angle] is scan-relative degrees. */
data class ObjectSummary(val label: String, val count: Int, val color: String?, val angle: Float)

object SummaryBuilder {

    private val irregular = mapOf(
        "person" to "people",
        "mouse" to "mice",
        "knife" to "knives",
        "sheep" to "sheep",
        "skis" to "skis",
        "scissors" to "scissors",
    )

    fun plural(label: String): String {
        val lastSpace = label.lastIndexOf(' ')
        val head = label.substring(0, lastSpace + 1)
        val word = label.substring(lastSpace + 1)
        val pluralWord = irregular[word] ?: when {
            word.endsWith("s") || word.endsWith("x") || word.endsWith("ch") || word.endsWith("sh") -> word + "es"
            word.length > 1 && word.endsWith("y") && word[word.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
            else -> word + "s"
        }
        return head + pluralWord
    }

    fun describe(o: ObjectSummary): String {
        val colorPart = o.color?.let { "$it " } ?: ""
        return if (o.count == 1) {
            val phrase = colorPart + o.label
            val article = if (phrase.first().lowercaseChar() in "aeiou") "an" else "a"
            "$article $phrase"
        } else {
            "${o.count} $colorPart${plural(o.label)}"
        }
    }

    fun fullSummary(objects: List<ObjectSummary>, coveragePercent: Int): String {
        val prefix = if (coveragePercent < 100) "I scanned $coveragePercent percent of the room. " else ""
        if (objects.isEmpty()) return prefix + "No objects found. Try better lighting and turn slowly."

        val parts = Sector4.entries.mapNotNull { sector ->
            val inSector = objects.filter { AngleMath.sector4(it.angle) == sector }
            if (inSector.isEmpty()) return@mapNotNull null
            val merged = inSector
                .groupBy { it.label to it.color }
                .map { (key, group) -> ObjectSummary(key.first, group.sumOf { it.count }, key.second, group.first().angle) }
                .sortedByDescending { it.count }
            merged.joinToString(", ") { describe(it) } + " " + sector.phrase
        }
        return prefix + "Around you: " + parts.joinToString("; ") + "."
    }

    fun livePhrase(o: ObjectSummary): String {
        val phrase = (o.color?.let { "$it " } ?: "") + o.label
        return phrase.replaceFirstChar { it.uppercaseChar() } + " " + AngleMath.sector4(o.angle).phrase + "."
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.SummaryBuilderTest"`
Expected: 12 tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/classroomscanner/core/SummaryBuilder.kt app/src/test/java/com/classroomscanner/core/SummaryBuilderTest.kt
git commit -m "feat(core): build spoken scan summaries"
```

---

### Task 7: ScanSession state machine

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/ScanSession.kt`
- Test: `app/src/test/java/com/classroomscanner/core/ScanSessionTest.kt`

**Interfaces:**
- Consumes: `ObjectClusterer`, `Cluster`, `FrameDetection`, `CoverageTracker`, `SummaryBuilder`, `ObjectSummary`
- Produces:
  - `enum class ScanMode { FULL, LIVE }`
  - `data class ScanResult(val mode: ScanMode, val startedAt: Long, val coveragePercent: Int, val objects: List<ObjectSummary>, val summaryText: String)`
  - `class ScanSession(val mode: ScanMode, val startedAtMs: Long, timeoutMs: Long = 60_000L)` with:
    - `onHeading(relHeading: Float)`
    - `onFrame(detections: List<FrameDetection>): List<String>`, which returns live phrases to speak
    - `shouldAutoStop(nowMs: Long): Boolean`
    - `coveragePercent(): Int`
    - `coverageSnapshot(): BooleanArray`
    - `confirmedCount(): Int`
    - `finish(): ScanResult`
    - `val finished: Boolean`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionTest {
    private val blueChair = listOf(FrameDetection("chair", 0f, "blue"))

    private fun turnFullCircle(s: ScanSession) {
        var a = 0f
        while (a < 360f) {
            s.onHeading(a)
            a += 5f
        }
        s.onHeading(0f)
    }

    @Test
    fun liveModeSpeaksWhenObjectConfirmed() {
        val s = ScanSession(ScanMode.LIVE, 0L)
        assertTrue(s.onFrame(blueChair).isEmpty())
        assertTrue(s.onFrame(blueChair).isEmpty())
        assertEquals(listOf("Blue chair in front."), s.onFrame(blueChair))
        assertTrue(s.onFrame(blueChair).isEmpty())
    }

    @Test
    fun fullModeNeverSpeaksPerObject() {
        val s = ScanSession(ScanMode.FULL, 0L)
        repeat(5) { assertTrue(s.onFrame(blueChair).isEmpty()) }
    }

    @Test
    fun fullModeStopsWhenCircleCovered() {
        val s = ScanSession(ScanMode.FULL, 1_000L)
        assertFalse(s.shouldAutoStop(1_000L))
        turnFullCircle(s)
        assertEquals(100, s.coveragePercent())
        assertTrue(s.shouldAutoStop(2_000L))
    }

    @Test
    fun fullModeStopsAfterTimeout() {
        val s = ScanSession(ScanMode.FULL, 1_000L, timeoutMs = 60_000L)
        assertFalse(s.shouldAutoStop(60_999L))
        assertTrue(s.shouldAutoStop(61_000L))
    }

    @Test
    fun liveModeNeverStopsItself() {
        val s = ScanSession(ScanMode.LIVE, 0L)
        turnFullCircle(s)
        assertFalse(s.shouldAutoStop(10_000_000L))
    }

    @Test
    fun confirmedCountSumsClusterCounts() {
        val s = ScanSession(ScanMode.FULL, 0L)
        repeat(3) {
            s.onFrame(listOf(FrameDetection("chair", 0f, null), FrameDetection("chair", 5f, null), FrameDetection("tv", 180f, null)))
        }
        assertEquals(3, s.confirmedCount())
    }

    @Test
    fun finishBuildsResultAndIgnoresLaterFrames() {
        val s = ScanSession(ScanMode.FULL, 5L)
        repeat(3) { s.onFrame(blueChair) }
        s.onHeading(0f)
        val r = s.finish()
        assertTrue(s.finished)
        assertEquals(ScanMode.FULL, r.mode)
        assertEquals(5L, r.startedAt)
        assertEquals(2, r.coveragePercent)
        assertEquals(listOf("chair"), r.objects.map { it.label })
        assertEquals("I scanned 2 percent of the room. Around you: a blue chair in front.", r.summaryText)
        assertTrue(s.onFrame(blueChair).isEmpty())
        assertFalse(s.shouldAutoStop(Long.MAX_VALUE))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.ScanSessionTest"`
Expected: compilation FAILS with `Unresolved reference 'ScanSession'`.

- [ ] **Step 3: Implement**

```kotlin
package com.classroomscanner.core

enum class ScanMode { FULL, LIVE }

data class ScanResult(
    val mode: ScanMode,
    val startedAt: Long,
    val coveragePercent: Int,
    val objects: List<ObjectSummary>,
    val summaryText: String,
)

/** State of one scan. Not thread-safe: call every method from the same (main) thread. */
class ScanSession(
    val mode: ScanMode,
    val startedAtMs: Long,
    private val timeoutMs: Long = 60_000L,
) {
    private val clusterer = ObjectClusterer()
    private val coverage = CoverageTracker()
    private var lastHeading: Float? = null

    var finished: Boolean = false
        private set

    fun onHeading(relHeading: Float) {
        if (finished) return
        val previous = lastHeading
        if (previous == null) coverage.mark(relHeading) else coverage.markArc(previous, relHeading)
        lastHeading = relHeading
    }

    fun onFrame(detections: List<FrameDetection>): List<String> {
        if (finished) return emptyList()
        val newlyConfirmed = clusterer.addFrame(detections)
        return if (mode == ScanMode.LIVE) newlyConfirmed.map { SummaryBuilder.livePhrase(it.toSummary()) } else emptyList()
    }

    fun shouldAutoStop(nowMs: Long): Boolean =
        !finished && mode == ScanMode.FULL && (coverage.isComplete() || nowMs - startedAtMs >= timeoutMs)

    fun coveragePercent(): Int = coverage.percent()

    fun coverageSnapshot(): BooleanArray = coverage.snapshot()

    fun confirmedCount(): Int = clusterer.confirmed().sumOf { it.count }

    fun finish(): ScanResult {
        finished = true
        val objects = clusterer.confirmed().map { it.toSummary() }
        val percent = coverage.percent()
        return ScanResult(mode, startedAtMs, percent, objects, SummaryBuilder.fullSummary(objects, percent))
    }

    private fun Cluster.toSummary() = ObjectSummary(label, count, color, meanAngle)
}
```

- [ ] **Step 4: Run all core tests**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. All 60 tests pass: 7 ScanSession tests plus 53 from earlier tasks.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/classroomscanner/core/ScanSession.kt app/src/test/java/com/classroomscanner/core/ScanSessionTest.kt
git commit -m "feat(core): add scan session state machine"
```

---

### Task 8: Room history storage

**Files:**
- Create: `app/src/main/java/com/classroomscanner/history/Entities.kt`, `ScanDao.kt`, `AppDatabase.kt`, `HistoryRepository.kt` (all in `history/`)
- Test: `app/src/androidTest/java/com/classroomscanner/history/ScanDaoTest.kt`

**Interfaces:**
- Consumes: `ScanResult`, `AngleMath.sector8`
- Produces:
  - Entities:
    - `ScanEntity(id: Long = 0, startedAt: Long, mode: String, coveragePercent: Int, summaryText: String)`
    - `DetectedObjectEntity(id: Long = 0, scanId: Long, label: String, count: Int, colorName: String?, relAngleDeg: Float, sector8: String)`
  - `abstract class ScanDao` with:
    - `suspend insertScanWithObjects(scan, objects): Long`
    - `observeScans(): Flow<List<ScanEntity>>`, newest first
    - `suspend objectsFor(scanId: Long): List<DetectedObjectEntity>`
  - `AppDatabase.get(context): AppDatabase` and `AppDatabase.scanDao()`
  - `class HistoryRepository(dao: ScanDao)` with `scans(): Flow<List<ScanEntity>>` and `suspend save(result: ScanResult): Long`

- [ ] **Step 1: Write the failing instrumented test**

```kotlin
package com.classroomscanner.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertsScanWithObjectsAndReadsBackNewestFirst() = runBlocking {
        val dao = db.scanDao()
        val olderId = dao.insertScanWithObjects(
            ScanEntity(startedAt = 1L, mode = "LIVE", coveragePercent = 40, summaryText = "old"),
            emptyList(),
        )
        val newerId = dao.insertScanWithObjects(
            ScanEntity(startedAt = 2L, mode = "FULL", coveragePercent = 100, summaryText = "Around you: a blue chair in front."),
            listOf(DetectedObjectEntity(scanId = 0, label = "chair", count = 1, colorName = "blue", relAngleDeg = 0f, sector8 = "FRONT")),
        )

        val scans = dao.observeScans().first()
        assertEquals(listOf(newerId, olderId), scans.map { it.id })

        val objects = dao.objectsFor(newerId)
        assertEquals(1, objects.size)
        assertEquals("chair", objects[0].label)
        assertEquals(newerId, objects[0].scanId)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat compileDebugAndroidTestKotlin`
Expected: compilation FAILS with `Unresolved reference 'AppDatabase'`.

- [ ] **Step 3: Implement**

`Entities.kt`:

```kotlin
package com.classroomscanner.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val mode: String,
    val coveragePercent: Int,
    val summaryText: String,
)

@Entity(
    tableName = "detected_objects",
    foreignKeys = [
        ForeignKey(
            entity = ScanEntity::class,
            parentColumns = ["id"],
            childColumns = ["scanId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("scanId")],
)
data class DetectedObjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanId: Long,
    val label: String,
    val count: Int,
    val colorName: String?,
    val relAngleDeg: Float,
    val sector8: String,
)
```

`ScanDao.kt`:

```kotlin
package com.classroomscanner.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScanDao {

    @Insert
    abstract suspend fun insertScan(scan: ScanEntity): Long

    @Insert
    abstract suspend fun insertObjects(objects: List<DetectedObjectEntity>)

    @Transaction
    open suspend fun insertScanWithObjects(scan: ScanEntity, objects: List<DetectedObjectEntity>): Long {
        val id = insertScan(scan)
        insertObjects(objects.map { it.copy(scanId = id) })
        return id
    }

    @Query("SELECT * FROM scans ORDER BY startedAt DESC")
    abstract fun observeScans(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM detected_objects WHERE scanId = :scanId")
    abstract suspend fun objectsFor(scanId: Long): List<DetectedObjectEntity>
}
```

`AppDatabase.kt`:

```kotlin
package com.classroomscanner.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ScanEntity::class, DetectedObjectEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "classroom-scanner.db",
                ).build().also { instance = it }
            }
    }
}
```

`HistoryRepository.kt`:

```kotlin
package com.classroomscanner.history

import com.classroomscanner.core.AngleMath
import com.classroomscanner.core.ScanResult
import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: ScanDao) {

    fun scans(): Flow<List<ScanEntity>> = dao.observeScans()

    suspend fun save(result: ScanResult): Long = dao.insertScanWithObjects(
        ScanEntity(
            startedAt = result.startedAt,
            mode = result.mode.name,
            coveragePercent = result.coveragePercent,
            summaryText = result.summaryText,
        ),
        result.objects.map {
            DetectedObjectEntity(
                scanId = 0,
                label = it.label,
                count = it.count,
                colorName = it.color,
                relAngleDeg = it.angle,
                sector8 = AngleMath.sector8(it.angle).name,
            )
        },
    )
}
```

- [ ] **Step 4: Run the instrumented test on the phone**

Run: `.\gradlew.bat connectedDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, with 1 test passing on `Infinix X6880`.

If no device is connected, check with `adb devices` (see Global Constraints) and ask the user to reconnect the phone.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/classroomscanner/history app/src/androidTest
git commit -m "feat(history): store scans with Room"
```

---

### Task 9: Android services — heading, FOV, color, speech, detector frame

**Files:**
- Create: `app/src/main/java/com/classroomscanner/sensor/HeadingProvider.kt`, `sensor/CameraFov.kt`, `color/ColorNamer.kt`, `speech/SpeechAnnouncer.kt`
- Modify: `app/src/main/java/com/classroomscanner/ObjectDetectorHelper.kt`, `app/src/main/java/com/classroomscanner/OverlayView.kt`

**Interfaces:**
- Consumes: `AngleMath`, `ColorMapper`
- Produces:
  - `class HeadingProvider(context: Context, listener: Listener)` with:
    - `val isAvailable: Boolean`
    - `start()` and `stop()`
    - `interface Listener { fun onHeading(relHeading: Float, speedDegPerSec: Float); fun onAccuracyLow(low: Boolean) }`, called on the main thread
  - `object CameraFov` with `const val FALLBACK_DEG = 65f` and `portraitHorizontalFov(context: Context): Float`
  - `object ColorNamer` with:
    - `name(frame: Bitmap, box: RectF, frameIsDark: Boolean): String?`
    - `isDark(frame: Bitmap): Boolean`
  - `class SpeechAnnouncer(context: Context)` with:
    - `val available: Boolean`
    - `announce(text: String)`
    - `speakNow(text: String)`
    - `shutdown()`
    - all called on the main thread
  - `ObjectDetectorHelper.ResultBundle` gains a last field `val frame: Bitmap? = null`, which is the analyzed RGBA buffer, unrotated. `MAX_RESULTS_DEFAULT` becomes `10`.
  - `OverlayView.setResults(detectionResults, outputHeight, outputWidth, imageRotation, labels: List<String>? = null)`

These classes wrap Android APIs, so there are no JVM tests for them. Task 10 verifies them on the device.

- [ ] **Step 1: Create `HeadingProvider.kt`**

```kotlin
package com.classroomscanner.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.classroomscanner.core.AngleMath
import kotlin.math.abs

/** Heading relative to where the phone pointed when [start] was called. Phone must be held upright. */
class HeadingProvider(context: Context, private val listener: Listener) : SensorEventListener {

    interface Listener {
        fun onHeading(relHeading: Float, speedDegPerSec: Float)
        fun onAccuracyLow(low: Boolean)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = sensor != null

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var filtered: Float? = null
    private var startHeading: Float? = null
    private var lastRel: Float? = null
    private var lastTimestampNs = 0L
    private var speed = 0f

    fun start() {
        filtered = null
        startHeading = null
        lastRel = null
        speed = 0f
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Phone held upright (portrait, camera facing forward): remap so azimuth stays stable.
        SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val azimuth = AngleMath.normalize(Math.toDegrees(orientation[0].toDouble()).toFloat())

        val smoothed = filtered?.let { AngleMath.normalize(it + ALPHA * AngleMath.diff(azimuth, it)) } ?: azimuth
        filtered = smoothed
        val start = startHeading ?: smoothed.also { startHeading = it }
        val rel = AngleMath.normalize(smoothed - start)

        lastRel?.let { prev ->
            val dt = (event.timestamp - lastTimestampNs) / 1_000_000_000f
            if (dt > 0f) speed = SPEED_ALPHA * (abs(AngleMath.diff(rel, prev)) / dt) + (1 - SPEED_ALPHA) * speed
        }
        lastRel = rel
        lastTimestampNs = event.timestamp

        listener.onHeading(rel, speed)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        listener.onAccuracyLow(accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW)
    }

    private companion object {
        const val ALPHA = 0.3f
        const val SPEED_ALPHA = 0.2f
    }
}
```

- [ ] **Step 2: Create `CameraFov.kt`**

```kotlin
package com.classroomscanner.sensor

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlin.math.atan

object CameraFov {
    const val FALLBACK_DEG = 65f

    /** Horizontal field of view of the back camera when the phone is held in portrait. */
    fun portraitHorizontalFov(context: Context): Float = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val chars = manager.getCameraCharacteristics(id)
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        // The sensor is landscape; in portrait the image width spans the sensor's short side.
        Math.toDegrees(2 * atan((sensorSize.height / (2 * focal)).toDouble())).toFloat()
    } catch (e: Exception) {
        Log.w("ClassroomScanner", "FOV lookup failed, using fallback", e)
        FALLBACK_DEG
    }
}
```

- [ ] **Step 3: Create `ColorNamer.kt`**

```kotlin
package com.classroomscanner.color

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.palette.graphics.Palette
import com.classroomscanner.core.ColorMapper

object ColorNamer {
    private const val MIN_CROP_PX = 8
    private const val PALETTE_AREA = 48 * 48
    private const val DARK_LUMA = 0.15f
    private const val GRID = 16

    /** Dominant color name of the central half of [box]; [box] is in [frame] pixel coordinates. */
    fun name(frame: Bitmap, box: RectF, frameIsDark: Boolean): String? {
        if (frameIsDark) return null
        val w = box.width()
        val h = box.height()
        val x = (box.left + w / 4f).toInt().coerceIn(0, frame.width - 1)
        val y = (box.top + h / 4f).toInt().coerceIn(0, frame.height - 1)
        val cropW = (w / 2f).toInt().coerceAtMost(frame.width - x)
        val cropH = (h / 2f).toInt().coerceAtMost(frame.height - y)
        if (cropW < MIN_CROP_PX || cropH < MIN_CROP_PX) return null

        val crop = Bitmap.createBitmap(frame, x, y, cropW, cropH)
        val swatch = Palette.from(crop)
            .resizeBitmapArea(PALETTE_AREA)
            .maximumColorCount(8)
            .generate()
            .dominantSwatch ?: return null
        val hsv = FloatArray(3)
        Color.colorToHSV(swatch.rgb, hsv)
        return ColorMapper.nameFromHsv(hsv[0], hsv[1], hsv[2])
    }

    fun isDark(frame: Bitmap): Boolean {
        var sum = 0f
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val p = frame.getPixel(gx * (frame.width - 1) / (GRID - 1), gy * (frame.height - 1) / (GRID - 1))
                sum += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            }
        }
        return sum / (GRID * GRID) < DARK_LUMA
    }
}
```

- [ ] **Step 4: Create `SpeechAnnouncer.kt`**

```kotlin
package com.classroomscanner.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Throttled English TTS. Call from the main thread. */
class SpeechAnnouncer(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<String>()
    private var nextAllowedAt = 0L

    var available: Boolean = false
        private set

    override fun onInit(status: Int) {
        available = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
    }

    /** Queues a short announcement; stale ones are dropped when the queue is long. */
    fun announce(text: String) {
        if (!available) return
        pending.addLast(text)
        while (pending.size > MAX_PENDING) pending.removeFirst()
        pump()
    }

    /** Clears the queue and speaks [text] immediately (final summaries, history replay). */
    fun speakNow(text: String) {
        if (!available) return
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "summary")
        nextAllowedAt = SystemClock.uptimeMillis() + GAP_MS
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        tts.stop()
        tts.shutdown()
    }

    private fun pump() {
        handler.removeCallbacksAndMessages(null)
        if (pending.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (tts.isSpeaking || now < nextAllowedAt) {
            handler.postDelayed(::pump, POLL_MS)
            return
        }
        tts.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, "live")
        nextAllowedAt = now + GAP_MS
        if (pending.isNotEmpty()) handler.postDelayed(::pump, POLL_MS)
    }

    private companion object {
        const val GAP_MS = 1_500L
        const val POLL_MS = 250L
        const val MAX_PENDING = 3
    }
}
```

- [ ] **Step 5: Expose the frame bitmap from `ObjectDetectorHelper`**

In `ObjectDetectorHelper.kt`:
1. Add the import `import com.google.mediapipe.framework.image.BitmapExtractor`.
2. Change `const val MAX_RESULTS_DEFAULT = 3` to `const val MAX_RESULTS_DEFAULT = 10`.
3. Add a last field to `ResultBundle`:

```kotlin
    data class ResultBundle(
        val results: List<ObjectDetectorResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
        val inputImageRotation: Int = 0,
        val frame: Bitmap? = null
    )
```

4. In `returnLivestreamResult`, pass the frame:

```kotlin
        objectDetectorListener?.onResults(
            ResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width,
                imageRotation,
                BitmapExtractor.extract(input)
            )
        )
```

- [ ] **Step 6: Let `OverlayView` draw custom labels**

In `OverlayView.kt`:
1. Add a field `private var labels: List<String>? = null`.
2. In `clear()`, add `labels = null`.
3. Change the `setResults` signature to add the new parameter, and store it as the first line of the body:

```kotlin
    fun setResults(
        detectionResults: ObjectDetectorResult,
        outputHeight: Int,
        outputWidth: Int,
        imageRotation: Int,
        labels: List<String>? = null
    ) {
        this.labels = labels
        results = detectionResults
```

4. In `draw`, replace the `drawableText` computation with:

```kotlin
            val drawableText = labels?.getOrNull(index)
                ?: (category.categoryName() + " " + String.format("%.2f", category.score()))
```

- [ ] **Step 7: Build**

Run: `.\gradlew.bat assembleDebug testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. The sample `CameraFragment` still compiles because the new parameters have defaults.

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "feat: add heading, FOV, color and speech services"
```

---

### Task 10: Scan screen

**Files:**
- Create: `app/src/main/java/com/classroomscanner/ui/CoverageRingView.kt`
- Modify (full replacement): `app/src/main/java/com/classroomscanner/fragments/CameraFragment.kt`, `app/src/main/res/layout/fragment_camera.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/menu/menu_bottom_nav.xml`
- Delete: `app/src/main/java/com/classroomscanner/MainViewModel.kt`, `app/src/main/res/layout/info_bottom_sheet.xml`

**Interfaces:**
- Consumes: everything from Tasks 2–9.
- Produces:
  - A Scan screen with a Full/Live toggle, a Start/Stop button, the coverage ring, an object count, the announcement text, a warning banner, and a spoken summary saved to history.
  - `CoverageRingView.setState(covered: BooleanArray, heading: Float, percent: Int)` and `CoverageRingView.reset()`.

- [ ] **Step 1: Delete the unused sample files**

```powershell
Remove-Item app\src\main\java\com\classroomscanner\MainViewModel.kt, app\src\main\res\layout\info_bottom_sheet.xml
```

Also remove the `private val viewModel: MainViewModel by viewModels()` line and its `import androidx.activity.viewModels` from `MainActivity.kt`.

- [ ] **Step 2: Replace `strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Classroom Scanner</string>
    <string name="menu_scan">Scan</string>
    <string name="menu_history">History</string>
    <string name="mode_full">Full Scan</string>
    <string name="mode_live">Live Scan</string>
    <string name="start">Start</string>
    <string name="stop">Stop</string>
    <string name="objects_count">Objects: %d</string>
    <string name="hint_full">Turn slowly in a full circle.</string>
    <string name="hint_live">Turn slowly. I will tell you what I find.</string>
    <string name="banner_slow_down">Slow down.</string>
    <string name="banner_calibrate">Calibrate compass: move the phone in a figure 8.</string>
    <string name="banner_no_compass">No rotation sensor: Full Scan is disabled.</string>
    <string name="permission_explanation">Classroom Scanner needs the camera to find objects around you. Images never leave your phone.</string>
    <string name="grant">Grant camera access</string>
    <string name="history_empty">No scans yet.</string>
    <string name="play">Play</string>
    <string name="history_item_title">%1$s · %2$s · %3$d%%</string>
    <string name="tts_unavailable">Speech is not available on this phone.</string>
</resources>
```

Then change `menu_bottom_nav.xml` so its single item uses `android:title="@string/menu_scan"`.

- [ ] **Step 3: Create `CoverageRingView.kt`**

```kotlin
package com.classroomscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Ring of 36 segments that fill as the user turns; a dot marks the current heading. */
class CoverageRingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var covered = BooleanArray(36)
    private var heading = 0f
    private var percent = 0

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        color = Color.argb(90, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        color = Color.rgb(76, 175, 80)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 36f
    }
    private val oval = RectF()

    fun setState(covered: BooleanArray, heading: Float, percent: Int) {
        this.covered = covered
        this.heading = heading
        this.percent = percent
        invalidate()
    }

    fun reset() = setState(BooleanArray(36), 0f, 0)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = min(width, height) / 2f - STROKE
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(oval, trackPaint)

        val sweep = 360f / covered.size
        covered.forEachIndexed { i, isCovered ->
            if (isCovered) canvas.drawArc(oval, -90f + i * sweep, sweep, false, fillPaint)
        }

        val rad = Math.toRadians((heading - 90f).toDouble())
        canvas.drawCircle(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat(), STROKE, markerPaint)
        canvas.drawText("$percent%", cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private companion object {
        const val STROKE = 10f
    }
}
```

- [ ] **Step 4: Replace `fragment_camera.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/camera_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <androidx.camera.view.PreviewView
        android:id="@+id/view_finder"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:scaleType="fillStart" />

    <com.classroomscanner.OverlayView
        android:id="@+id/overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <TextView
        android:id="@+id/banner"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="top"
        android:accessibilityLiveRegion="assertive"
        android:background="#CCB00020"
        android:padding="12dp"
        android:textColor="@android:color/white"
        android:textSize="18sp"
        android:visibility="gone" />

    <LinearLayout
        android:id="@+id/controls"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:background="#B3000000"
        android:orientation="vertical"
        android:padding="12dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <com.classroomscanner.ui.CoverageRingView
                android:id="@+id/coverage_ring"
                android:layout_width="88dp"
                android:layout_height="88dp" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="12dp"
                android:layout_weight="1"
                android:orientation="vertical">

                <RadioGroup
                    android:id="@+id/mode_group"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal">

                    <RadioButton
                        android:id="@+id/mode_full"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:checked="true"
                        android:text="@string/mode_full"
                        android:textColor="@android:color/white" />

                    <RadioButton
                        android:id="@+id/mode_live"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/mode_live"
                        android:textColor="@android:color/white" />
                </RadioGroup>

                <TextView
                    android:id="@+id/object_count"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@android:color/white"
                    android:textSize="16sp" />
            </LinearLayout>

            <Button
                android:id="@+id/start_stop"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:minHeight="64dp"
                android:minWidth="96dp"
                android:text="@string/start" />
        </LinearLayout>

        <TextView
            android:id="@+id/announcement"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:accessibilityLiveRegion="polite"
            android:textColor="@android:color/white"
            android:textSize="16sp" />
    </LinearLayout>
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 5: Replace `CameraFragment.kt`**

```kotlin
package com.classroomscanner.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.classroomscanner.ObjectDetectorHelper
import com.classroomscanner.R
import com.classroomscanner.color.ColorNamer
import com.classroomscanner.core.BoxGeometry
import com.classroomscanner.core.FrameDetection
import com.classroomscanner.core.ScanMode
import com.classroomscanner.core.ScanSession
import com.classroomscanner.databinding.FragmentCameraBinding
import com.classroomscanner.history.AppDatabase
import com.classroomscanner.history.HistoryRepository
import com.classroomscanner.sensor.CameraFov
import com.classroomscanner.sensor.HeadingProvider
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener, HeadingProvider.Listener {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    private lateinit var headingProvider: HeadingProvider
    private lateinit var speech: SpeechAnnouncer
    private lateinit var history: HistoryRepository
    private var hfov = CameraFov.FALLBACK_DEG

    // Main thread only.
    private var session: ScanSession? = null
    private var tooFast = false
    private var compassLow = false
    private var lastSlowDownSpokenAt = 0L

    // Written on the main thread, read on the detector thread.
    @Volatile
    private var relHeading = 0f

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToPermissions())
        }
        backgroundExecutor.execute {
            if (objectDetectorHelper.isClosed()) {
                objectDetectorHelper.setupObjectDetector()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopScan()
        if (this::objectDetectorHelper.isInitialized) {
            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    override fun onDestroyView() {
        speech.shutdown()
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        headingProvider = HeadingProvider(context, this)
        speech = SpeechAnnouncer(context)
        history = HistoryRepository(AppDatabase.get(context).scanDao())
        hfov = CameraFov.portraitHorizontalFov(context)
        Log.i(TAG, "Horizontal FOV: $hfov")

        backgroundExecutor = Executors.newSingleThreadExecutor()
        backgroundExecutor.execute {
            objectDetectorHelper = ObjectDetectorHelper(
                context = context,
                objectDetectorListener = this,
                runningMode = RunningMode.LIVE_STREAM
            )
            fragmentCameraBinding.viewFinder.post { setUpCamera() }
        }

        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)
        initScanControls()
    }

    private fun initScanControls() {
        val b = fragmentCameraBinding
        if (!headingProvider.isAvailable) {
            b.modeFull.isEnabled = false
            b.modeLive.isChecked = true
        }
        b.objectCount.text = getString(R.string.objects_count, 0)
        b.startStop.setOnClickListener { if (session == null) startScan() else stopScan() }
        updateBanner()
    }

    private fun startScan() {
        val b = fragmentCameraBinding
        val mode = if (b.modeLive.isChecked) ScanMode.LIVE else ScanMode.FULL
        session = ScanSession(mode, System.currentTimeMillis())
        relHeading = 0f
        headingProvider.start()

        b.startStop.text = getString(R.string.stop)
        b.modeFull.isEnabled = false
        b.modeLive.isEnabled = false
        b.coverageRing.reset()
        b.objectCount.text = getString(R.string.objects_count, 0)
        val hint = getString(if (mode == ScanMode.FULL) R.string.hint_full else R.string.hint_live)
        b.announcement.text = hint
        speech.announce(hint)
    }

    private fun stopScan() {
        val s = session ?: return
        session = null
        headingProvider.stop()
        tooFast = false
        compassLow = false

        val result = s.finish()
        speech.speakNow(result.summaryText)
        lifecycleScope.launch { history.save(result) }
        Log.i(TAG, "Scan finished: ${result.summaryText}")

        _fragmentCameraBinding?.let { b ->
            b.announcement.text = result.summaryText
            b.startStop.text = getString(R.string.start)
            b.modeFull.isEnabled = headingProvider.isAvailable
            b.modeLive.isEnabled = true
        }
        updateBanner()
    }

    private fun updateBanner() {
        val b = _fragmentCameraBinding ?: return
        val text = when {
            !headingProvider.isAvailable -> getString(R.string.banner_no_compass)
            tooFast -> getString(R.string.banner_slow_down)
            compassLow -> getString(R.string.banner_calibrate)
            else -> null
        }
        b.banner.text = text
        b.banner.isVisible = text != null
    }

    private fun updateScanUi(s: ScanSession) {
        val b = _fragmentCameraBinding ?: return
        b.coverageRing.setState(s.coverageSnapshot(), relHeading, s.coveragePercent())
        b.objectCount.text = getString(R.string.objects_count, s.confirmedCount())
    }

    override fun onHeading(relHeading: Float, speedDegPerSec: Float) {
        this.relHeading = relHeading
        val s = session ?: return
        s.onHeading(relHeading)

        val now = System.currentTimeMillis()
        tooFast = speedDegPerSec > MAX_SPEED_DEG_PER_SEC
        if (tooFast && now - lastSlowDownSpokenAt > SLOW_DOWN_REPEAT_MS) {
            speech.announce(getString(R.string.banner_slow_down))
            lastSlowDownSpokenAt = now
        }
        updateBanner()
        updateScanUi(s)
        if (s.shouldAutoStop(now)) stopScan()
    }

    override fun onAccuracyLow(low: Boolean) {
        compassLow = low
        updateBanner()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        // Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // Using RGBA 8888 to match how our models work
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor, objectDetectorHelper::detectLivestreamFrame)
            }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    // Runs on the MediaPipe result thread.
    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        val result = resultBundle.results[0]
        val heading = relHeading
        val frame = resultBundle.frame
        val frameIsDark = frame?.let { ColorNamer.isDark(it) } ?: false

        val detections = result.detections().map { d ->
            val box = d.boundingBox()
            val center = BoxGeometry.horizontalCenter(
                box.left, box.top, box.right, box.bottom,
                resultBundle.inputImageWidth, resultBundle.inputImageHeight, resultBundle.inputImageRotation
            )
            FrameDetection(
                label = d.categories()[0].categoryName(),
                angle = BoxGeometry.objectAngle(heading, center, hfov),
                color = frame?.let { ColorNamer.name(it, box, frameIsDark) }
            )
        }

        activity?.runOnUiThread {
            val b = _fragmentCameraBinding ?: return@runOnUiThread
            if (isAdded) {
                b.overlay.setResults(
                    result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    resultBundle.inputImageRotation,
                    detections.map { it.overlayLabel() }
                )
            }
            b.overlay.invalidate()

            val s = session ?: return@runOnUiThread
            s.onFrame(detections).forEach(speech::announce)
            updateScanUi(s)
            if (s.shouldAutoStop(System.currentTimeMillis())) stopScan()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            val b = _fragmentCameraBinding ?: return@runOnUiThread
            Log.e(TAG, error)
            b.announcement.text = error
            b.startStop.isEnabled = false
        }
    }

    private fun FrameDetection.overlayLabel() = color?.let { "$label · $it" } ?: label

    private companion object {
        const val TAG = "ClassroomScanner"
        const val MAX_SPEED_DEG_PER_SEC = 60f
        const val SLOW_DOWN_REPEAT_MS = 5_000L
    }
}
```

- [ ] **Step 6: Build and install**

```powershell
.\gradlew.bat testDebugUnitTest installDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.classroomscanner/.MainActivity
```

Expected: `BUILD SUCCESSFUL`, and the app opens on the Scan screen.

- [ ] **Step 7: Check the direction math on the device**

Run `adb logcat -s ClassroomScanner` in the background.

Ask the user to do the following and report what they hear and see:

1. **Colors.** Point the phone at a chair so its box shows `chair · <color>`. The color should match reality in normal light.
2. **Full Scan.**
   - Choose Full Scan, press Start and turn slowly clockwise one full circle.
   - The ring should fill clockwise.
   - The scan should stop by itself, the summary should be spoken, and the log should show `Scan finished: ...`.
   - Objects should be in the correct directions. For example, an object that was on the user's right when they pressed Start should be reported "on your right".
3. **Live Scan.** Choose Live Scan, press Start and turn. Each new object should be announced once, not repeatedly. Press Stop and a summary should be spoken.
4. **Slow down.** Turn fast. The "Slow down." banner should appear and be spoken, at most once every 5 s.

If directions come out mirrored (a right-side object reported on the left), the box geometry sign is wrong for this phone. Fix it in `BoxGeometry.horizontalCenter` by swapping the 90 and 270 cases, then update the matching tests in `BoxGeometryTest` so they state the verified behavior. If the whole summary is offset, log `hfov` and each `FrameDetection.angle` to diagnose it.

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "feat: scan screen with full and live modes"
```

---

### Task 11: History screen and permission explanation

**Files:**
- Create: `app/src/main/java/com/classroomscanner/fragments/HistoryFragment.kt`, `app/src/main/java/com/classroomscanner/history/HistoryAdapter.kt`, `app/src/main/res/layout/fragment_history.xml`, `app/src/main/res/layout/item_scan.xml`, `app/src/main/res/layout/fragment_permissions.xml`
- Modify: `app/src/main/java/com/classroomscanner/fragments/PermissionsFragment.kt` (full replacement), `app/src/main/res/navigation/nav_graph.xml`, `app/src/main/res/menu/menu_bottom_nav.xml`

**Interfaces:**
- Consumes: `HistoryRepository.scans()`, `ScanEntity`, `AppDatabase.get`, `SpeechAnnouncer`
- Produces: a History tab listing saved scans with a Play button per row, and a permission screen with an explanation and a Grant button.

- [ ] **Step 1: Create the layouts**

`fragment_history.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/white">

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/list"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <TextView
        android:id="@+id/empty"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:text="@string/history_empty"
        android:textSize="18sp"
        android:visibility="gone" />
</FrameLayout>
```

`item_scan.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@android:color/black"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/summary"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:textColor="@android:color/black" />
    </LinearLayout>

    <Button
        android:id="@+id/play"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="12dp"
        android:text="@string/play" />
</LinearLayout>
```

`fragment_permissions.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/white"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:text="@string/permission_explanation"
        android:textColor="@android:color/black"
        android:textSize="18sp" />

    <Button
        android:id="@+id/grant_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:minHeight="64dp"
        android:text="@string/grant" />
</LinearLayout>
```

- [ ] **Step 2: Create `HistoryAdapter.kt`**

```kotlin
package com.classroomscanner.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.classroomscanner.R
import com.classroomscanner.databinding.ItemScanBinding
import java.text.DateFormat
import java.util.Date

class HistoryAdapter(private val onPlay: (ScanEntity) -> Unit) :
    ListAdapter<ScanEntity, HistoryAdapter.Holder>(Diff) {

    class Holder(val binding: ItemScanBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val scan = getItem(position)
        val context = holder.itemView.context
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(scan.startedAt))
        holder.binding.title.text = context.getString(R.string.history_item_title, date, scan.mode, scan.coveragePercent)
        holder.binding.summary.text = scan.summaryText
        holder.binding.play.setOnClickListener { onPlay(scan) }
    }

    private object Diff : DiffUtil.ItemCallback<ScanEntity>() {
        override fun areItemsTheSame(oldItem: ScanEntity, newItem: ScanEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ScanEntity, newItem: ScanEntity) = oldItem == newItem
    }
}
```

- [ ] **Step 3: Create `HistoryFragment.kt`**

```kotlin
package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.classroomscanner.R
import com.classroomscanner.databinding.FragmentHistoryBinding
import com.classroomscanner.history.AppDatabase
import com.classroomscanner.history.HistoryAdapter
import com.classroomscanner.history.HistoryRepository
import com.classroomscanner.speech.SpeechAnnouncer
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var speech: SpeechAnnouncer

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        speech = SpeechAnnouncer(requireContext())
        val adapter = HistoryAdapter { scan ->
            if (speech.available) {
                speech.speakNow(scan.summaryText)
            } else {
                Toast.makeText(requireContext(), R.string.tts_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        val repository = HistoryRepository(AppDatabase.get(requireContext()).scanDao())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.scans().collect { scans ->
                    adapter.submitList(scans)
                    binding.empty.isVisible = scans.isEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        speech.shutdown()
        _binding = null
        super.onDestroyView()
    }
}
```

- [ ] **Step 4: Replace `PermissionsFragment.kt`**

```kotlin
package com.classroomscanner.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.classroomscanner.R
import com.classroomscanner.databinding.FragmentPermissionsBinding

private val PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)

/** Asks for the camera permission, explains why, and moves on to the Scan screen once granted. */
class PermissionsFragment : Fragment() {

    private var navigated = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && isResumed) navigateToCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions(requireContext())) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        binding.grantButton.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                // Permanently denied: the system dialog will not show again, so open app settings.
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))
                )
            }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions(requireContext())) navigateToCamera()
    }

    private fun navigateToCamera() {
        if (navigated) return
        navigated = true
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
            .navigate(PermissionsFragmentDirections.actionPermissionsToCamera())
    }

    companion object {
        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
```

- [ ] **Step 5: Wire up navigation**

1. In `nav_graph.xml`, add this before `</navigation>`:

```xml
    <fragment
        android:id="@+id/history_fragment"
        android:name="com.classroomscanner.fragments.HistoryFragment"
        android:label="HistoryFragment" />
```

2. Replace `menu_bottom_nav.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@id/camera_fragment"
        android:icon="@drawable/ic_baseline_photo_camera_24"
        android:title="@string/menu_scan" />
    <item
        android:id="@id/history_fragment"
        android:icon="@drawable/ic_baseline_photo_library_24"
        android:title="@string/menu_history" />
</menu>
```

- [ ] **Step 6: Build and install**

```powershell
.\gradlew.bat testDebugUnitTest installDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.classroomscanner/.MainActivity
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Check on the device**

Ask the user to do the following and confirm:

1. **History.**
   - Do one Full Scan, then open the History tab. The new scan is at the top, showing date · FULL · percent · summary.
   - Press Play: the summary is spoken.
   - Force-stop the app (`adb shell am force-stop com.classroomscanner`), reopen it and check that History still shows the scan.
2. **Permissions.**
   - Revoke the camera permission with `adb shell pm revoke com.classroomscanner android.permission.CAMERA` and reopen the app.
   - The explanation screen and Grant button appear, and Grant leads to the camera once permission is given.

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "feat: history screen and permission explanation"
```

---

### Task 12: Final verification and demo preparation

**Files:**
- Modify: `README.md` (only if behavior differs from what it describes)

- [ ] **Step 1: Run the full automated checks**

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, all unit tests pass, and the DAO test passes on the phone.

- [ ] **Step 2: Walk through the device checklist with the user (spec §7, §9)**

Confirm each item with the user:
- [ ] Full Scan fills the ring, stops by itself and speaks a correct summary: directions right, counts plausible, no duplicates.
- [ ] A Full Scan left idle stops after 60 s with the prefix "I scanned N percent of the room."
- [ ] Live Scan announces each new object once, with at least 1.5 s between announcements.
- [ ] Turning fast shows and speaks "Slow down."
- [ ] Denying the camera permission shows the explanation screen.
- [ ] History survives an app restart, and Play works.
- [ ] Dim light: boxes still get labels, with colors omitted.

- [ ] **Step 3: Prepare the demo (spec §8)**

1. Check whether `scrcpy` is installed: `Get-Command scrcpy`.
   - If it is missing, ask the user before installing. Suggested command: `winget install --id Genymobile.scrcpy`.
2. Ask the user to run 2–3 real scans in the classroom before the defense. They serve as backup results in History.
3. Rehearse the demo script with the user:
   1. Live Scan.
   2. Full Scan with auto-stop and the spoken summary.
   3. History replay.
   4. The architecture diagram from spec §5.

- [ ] **Step 4: Commit any doc fixes**

```powershell
git add -A
git commit -m "docs: final README touch-ups"
```

Skip this step if nothing changed.
