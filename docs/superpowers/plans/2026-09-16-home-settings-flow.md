# Home, Scan Settings and Scan Text Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the bottom-tab app with a guided flow (Home → Scan settings → Scanner → View text, Home → History) and add camera, compute, model, confidence, speech and color settings.

**Architecture:** Pure-Kotlin `core/` gets the settings model, a configurable detection filter, front-camera angle math and an immutable scan log (all TDD). Thin Android pieces persist settings, mute speech, pick the camera's FOV, mirror the overlay and hold the log in an activity-scoped ViewModel. Navigation uses Safe Args with a `ScanMode` argument; View text is a full-screen `DialogFragment` over the scanner so the scan keeps running.

**Tech Stack:** Kotlin 2.1, AGP 8.11, Material Components 1.12 (Material 3), Navigation 2.8.9 + Safe Args, CameraX 1.4.2, MediaPipe Tasks Vision 1.0.0, Room 2.7.2, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-16-home-settings-flow-design.md` (the original app spec `docs/superpowers/specs/2026-09-16-classroom-scanner-design.md` still governs scanning behavior)

## Global Constraints

- Repo `D:\classroom-scanner`, branch `feat/home-settings-flow` (already checked out). Never use git worktrees.
- Commit messages: conventional commits, **no** `Co-Authored-By` or any AI trailer.
- Package `com.classroomscanner`; `minSdk 24`, `compileSdk 35`, `targetSdk 35`; portrait only.
- All UI text, speech, code and comments are English.
- `app/src/main/java/com/classroomscanner/core/` must not import `android.*` or `androidx.*`.
- Settings defaults: camera Back, compute CPU, model Accurate (EfficientDet-Lite2), confidence 0.5, speech on, colors on.
- Confidence range 0.3–0.7, step 0.1. Non-person labels need `score >= confidence`; people need `score >= max(0.3, confidence − 0.2)`. The detector runs at 0.3.
- Front camera: `objectAngle = normalize(relHeading + 180 + (centerNorm − 0.5) · hfov)`; overlay mirrored.
- GPU init failure: switch to CPU once and show "GPU unavailable, using CPU."
- Run Gradle from `D:\classroom-scanner` (`./gradlew.bat` in Git Bash). adb: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`, phone serial `13192704AA003312`. Never run `connectedDebugAndroidTest` (it uninstalls the app and wipes History).
- Never commit `.superpowers/`, `local.properties`, `build/`, `app/src/main/assets/`.

---

## File Map

```
core/ScanSettings.kt          CameraFacing, Compute, ModelChoice, ScanSettings           (new)
core/DetectionFilter.kt       object -> class with confidence                             (modify)
core/BoxGeometry.kt           objectAngle(..., facing)                                    (modify)
core/ScanLogState.kt          LogEntry, ScanLogState                                      (new)
settings/SettingsStore.kt     SharedPreferences load/save                                 (new)
scanlog/ScanLogViewModel.kt   StateFlow<ScanLogState>                                     (new)
scanlog/LogAdapter.kt         RecyclerView adapter for log rows                           (new)
speech/SpeechAnnouncer.kt     + muted                                                     (modify)
sensor/CameraFov.kt           + facing parameter                                          (modify)
OverlayView.kt                + mirrored                                                  (modify)
MainActivity.kt               toolbar <-> navigation, no bottom nav                       (modify)
fragments/HomeFragment.kt     Home cards                                                  (new)
fragments/SettingsFragment.kt Scan settings                                               (new)
fragments/ScanTextDialog.kt   View text full-screen dialog                                (new)
fragments/PermissionsFragment.kt  mode argument                                           (modify)
fragments/CameraFragment.kt   settings, mode arg, log, View text, GPU fallback            (modify)
res/layout: activity_main, fragment_home, fragment_settings, fragment_camera,
            dialog_scan_text, item_log_entry
res/navigation/nav_graph.xml, res/values/strings.xml, res/values/styles.xml,
res/drawable: ic_360_24, ic_arrow_back_24, ic_chevron_right_24, ic_notes_24
res/menu/menu_bottom_nav.xml  (delete)
app/build.gradle, app/download_models.gradle, README.md
```

---

### Task 1: Core — settings, confidence filter, front-camera angle, scan log

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/ScanSettings.kt`, `app/src/main/java/com/classroomscanner/core/ScanLogState.kt`
- Modify: `app/src/main/java/com/classroomscanner/core/DetectionFilter.kt`, `app/src/main/java/com/classroomscanner/core/BoxGeometry.kt`, `app/src/main/java/com/classroomscanner/fragments/CameraFragment.kt` (one call site)
- Test: `app/src/test/java/com/classroomscanner/core/ScanSettingsTest.kt`, `ScanLogStateTest.kt` (new), `DetectionFilterTest.kt` (replace), `BoxGeometryTest.kt` (add one test)

**Interfaces:**
- Produces:
  - `enum class CameraFacing { BACK, FRONT }`, `enum class Compute { CPU, GPU }`, `enum class ModelChoice { FAST, ACCURATE }`
  - `data class ScanSettings(camera: CameraFacing = BACK, compute: Compute = CPU, model: ModelChoice = ACCURATE, minScore: Float = 0.5f, speechOn: Boolean = true, colorsOn: Boolean = true)` with `fun normalized(): ScanSettings` and companion `MIN_SCORE_LOW = 0.3f`, `MIN_SCORE_HIGH = 0.7f`, `DEFAULT_MIN_SCORE = 0.5f`
  - `class DetectionFilter(minScore: Float = ScanSettings.DEFAULT_MIN_SCORE)` with `fun keep(label: String, score: Float): Boolean`; companion `const val DETECTOR_THRESHOLD = 0.3f`
  - `BoxGeometry.objectAngle(relHeading: Float, centerNorm: Float, hfovDeg: Float, facing: CameraFacing = CameraFacing.BACK): Float`
  - `data class LogEntry(val timeMs: Long, val text: String)`; `data class ScanLogState(val entries: List<LogEntry> = emptyList(), val summary: String? = null)` with `fun add(timeMs: Long, text: String): ScanLogState` and `fun finish(summaryText: String): ScanLogState`

- [ ] **Step 1: Write the failing tests**

`ScanSettingsTest.kt`:

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSettingsTest {

    @Test
    fun defaults() {
        val s = ScanSettings()
        assertEquals(CameraFacing.BACK, s.camera)
        assertEquals(Compute.CPU, s.compute)
        assertEquals(ModelChoice.ACCURATE, s.model)
        assertEquals(0.5f, s.minScore, 0f)
        assertTrue(s.speechOn)
        assertTrue(s.colorsOn)
    }

    @Test
    fun confidenceIsClamped() {
        assertEquals(0.3f, ScanSettings(minScore = 0.1f).normalized().minScore, 0f)
        assertEquals(0.7f, ScanSettings(minScore = 0.95f).normalized().minScore, 0f)
    }

    @Test
    fun confidenceIsRoundedToOneDecimal() {
        assertEquals(0.6f, ScanSettings(minScore = 0.62f).normalized().minScore, 0f)
        assertEquals(0.5f, ScanSettings(minScore = 0.5f).normalized().minScore, 0f)
    }

    @Test
    fun nanFallsBackToDefault() {
        assertEquals(0.5f, ScanSettings(minScore = Float.NaN).normalized().minScore, 0f)
    }
}
```

`ScanLogStateTest.kt`:

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLogStateTest {

    @Test
    fun startsEmpty() {
        val s = ScanLogState()
        assertTrue(s.entries.isEmpty())
        assertNull(s.summary)
    }

    @Test
    fun addAppendsInOrderAndTrims() {
        val s = ScanLogState().add(1L, "Turn slowly.").add(2L, "  Chair in front. ")
        assertEquals(listOf(LogEntry(1L, "Turn slowly."), LogEntry(2L, "Chair in front.")), s.entries)
    }

    @Test
    fun blankTextIsIgnored() {
        val s = ScanLogState().add(1L, "Hi.")
        assertSame(s, s.add(2L, "   "))
    }

    @Test
    fun finishKeepsEntriesAndSetsSummary() {
        val s = ScanLogState().add(1L, "Hi.").finish("Around you: a chair in front.")
        assertEquals(1, s.entries.size)
        assertEquals("Around you: a chair in front.", s.summary)
    }
}
```

Replace `DetectionFilterTest.kt` entirely:

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFilterTest {
    private val default = DetectionFilter()

    @Test
    fun peopleAreKeptFromTheLowerThreshold() {
        assertTrue(default.keep("person", 0.30f))
        assertTrue(default.keep("person", 0.45f))
        assertFalse(default.keep("person", 0.29f))
    }

    @Test
    fun otherObjectsStillNeedTheDefaultThreshold() {
        assertFalse(default.keep("chair", 0.45f))
        assertTrue(default.keep("chair", 0.50f))
        assertTrue(default.keep("laptop", 0.9f))
    }

    @Test
    fun detectorThresholdIsTheLowestPerLabelThreshold() {
        assertTrue(DetectionFilter.DETECTOR_THRESHOLD <= 0.30f)
        assertTrue(default.keep("person", DetectionFilter.DETECTOR_THRESHOLD))
    }

    @Test
    fun strictSettingRaisesBothThresholds() {
        val f = DetectionFilter(0.7f)
        assertFalse(f.keep("chair", 0.69f))
        assertTrue(f.keep("chair", 0.7f))
        assertFalse(f.keep("person", 0.49f))
        assertTrue(f.keep("person", 0.5f))
    }

    @Test
    fun lenientSettingNeverGoesBelowDetectorThreshold() {
        val f = DetectionFilter(0.3f)
        assertTrue(f.keep("chair", 0.3f))
        assertFalse(f.keep("chair", 0.29f))
        assertTrue(f.keep("person", 0.3f))
        assertFalse(f.keep("person", 0.29f))
    }

    @Test
    fun outOfRangeSettingIsClamped() {
        val f = DetectionFilter(0.05f)
        assertFalse(f.keep("chair", 0.29f))
        assertTrue(f.keep("chair", 0.3f))
    }
}
```

Add to `BoxGeometryTest.kt` (inside the class, after `objectAngleAddsFovOffsetAndWraps`):

```kotlin
    @Test
    fun frontCameraLooksBackward() {
        assertEquals(180f, BoxGeometry.objectAngle(0f, 0.5f, 60f, CameraFacing.FRONT), eps)
        assertEquals(220f, BoxGeometry.objectAngle(10f, 1f, 60f, CameraFacing.FRONT), eps)
        assertEquals(140f, BoxGeometry.objectAngle(350f, 0f, 60f, CameraFacing.FRONT), eps)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew.bat testDebugUnitTest`
Expected: compilation FAILS with `Unresolved reference 'ScanSettings'`, `'ScanLogState'`, `'CameraFacing'`.

- [ ] **Step 3: Implement**

`ScanSettings.kt`:

```kotlin
package com.classroomscanner.core

import kotlin.math.roundToInt

enum class CameraFacing { BACK, FRONT }

enum class Compute { CPU, GPU }

enum class ModelChoice { FAST, ACCURATE }

/** Everything the user picks on the Scan settings screen. */
data class ScanSettings(
    val camera: CameraFacing = CameraFacing.BACK,
    val compute: Compute = Compute.CPU,
    val model: ModelChoice = ModelChoice.ACCURATE,
    val minScore: Float = DEFAULT_MIN_SCORE,
    val speechOn: Boolean = true,
    val colorsOn: Boolean = true,
) {
    /** Copy with the confidence clamped to [MIN_SCORE_LOW]..[MIN_SCORE_HIGH] and rounded to one decimal. */
    fun normalized(): ScanSettings {
        val score = if (minScore.isNaN()) DEFAULT_MIN_SCORE else minScore.coerceIn(MIN_SCORE_LOW, MIN_SCORE_HIGH)
        return copy(minScore = (score * 10).roundToInt() / 10f)
    }

    companion object {
        const val MIN_SCORE_LOW = 0.3f
        const val MIN_SCORE_HIGH = 0.7f
        const val DEFAULT_MIN_SCORE = 0.5f
    }
}
```

`ScanLogState.kt`:

```kotlin
package com.classroomscanner.core

/** One line of the scan log: what was said and when (epoch milliseconds). */
data class LogEntry(val timeMs: Long, val text: String)

/** Immutable log of the current scan; [summary] is set when the scan ends. */
data class ScanLogState(
    val entries: List<LogEntry> = emptyList(),
    val summary: String? = null,
) {
    fun add(timeMs: Long, text: String): ScanLogState =
        if (text.isBlank()) this else copy(entries = entries + LogEntry(timeMs, text.trim()))

    fun finish(summaryText: String): ScanLogState = copy(summary = summaryText)
}
```

Replace `DetectionFilter.kt` entirely:

```kotlin
package com.classroomscanner.core

import kotlin.math.roundToInt

/**
 * Per-label confidence thresholds. [minScore] is the user's confidence setting for ordinary objects.
 * People are often far away or half hidden behind desks, so they are accepted 0.2 earlier,
 * but never below the detector's own threshold.
 */
class DetectionFilter(minScore: Float = ScanSettings.DEFAULT_MIN_SCORE) {
    private val defaultTenths = (ScanSettings(minScore = minScore).normalized().minScore * 10).roundToInt()
    private val defaultMin = defaultTenths / 10f
    private val personMin = maxOf(DETECTOR_THRESHOLD, (defaultTenths - PERSON_BONUS_TENTHS) / 10f)

    fun keep(label: String, score: Float): Boolean =
        score >= if (label == PERSON) personMin else defaultMin

    companion object {
        /** Threshold given to the detector itself; must not be above any per-label threshold. */
        const val DETECTOR_THRESHOLD = 0.3f
        private const val PERSON_BONUS_TENTHS = 2
        private const val PERSON = "person"
    }
}
```

In `BoxGeometry.kt`, replace the `objectAngle` function with:

```kotlin
    /** The front camera looks backward when the phone is upright, so its base direction is turned by 180°. */
    fun objectAngle(
        relHeading: Float,
        centerNorm: Float,
        hfovDeg: Float,
        facing: CameraFacing = CameraFacing.BACK,
    ): Float {
        val base = if (facing == CameraFacing.FRONT) relHeading + 180f else relHeading
        return AngleMath.normalize(base + (centerNorm - 0.5f) * hfovDeg)
    }
```

In `CameraFragment.kt`, so the app still compiles:
1. Add a field next to `private var hfov = CameraFov.FALLBACK_DEG`:

```kotlin
    private val detectionFilter = DetectionFilter()
```

2. Replace `DetectionFilter.keep(label, category.score())` with `detectionFilter.keep(label, category.score())`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew.bat testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; 102 tests, 0 failures (sum the `tests` attributes in `app/build/test-results/testDebugUnitTest/*.xml`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/classroomscanner/core app/src/test/java/com/classroomscanner/core app/src/main/java/com/classroomscanner/fragments/CameraFragment.kt
git commit -m "feat(core): add scan settings, confidence filter, front-camera angle and scan log"
```

---

### Task 2: Android services — settings store, mute, camera FOV, mirrored overlay, both models, log ViewModel

**Files:**
- Create: `app/src/main/java/com/classroomscanner/settings/SettingsStore.kt`, `app/src/main/java/com/classroomscanner/scanlog/ScanLogViewModel.kt`
- Modify: `app/src/main/java/com/classroomscanner/speech/SpeechAnnouncer.kt`, `app/src/main/java/com/classroomscanner/sensor/CameraFov.kt`, `app/src/main/java/com/classroomscanner/OverlayView.kt`, `app/download_models.gradle`, `app/build.gradle`

**Interfaces:**
- Consumes: `ScanSettings`, `CameraFacing`, `Compute`, `ModelChoice`, `ScanLogState` (Task 1)
- Produces:
  - `class SettingsStore(context: Context)` with `fun load(): ScanSettings` and `fun save(settings: ScanSettings)`
  - `class ScanLogViewModel : ViewModel()` with `val state: StateFlow<ScanLogState>`, `fun start()`, `fun add(text: String)`, `fun finish(summaryText: String)`
  - `SpeechAnnouncer.muted: Boolean` (var, default false)
  - `CameraFov.portraitHorizontalFov(context: Context, facing: CameraFacing = CameraFacing.BACK): Float`
  - `OverlayView.mirrored: Boolean` (var, default false)
  - Assets `efficientdet-lite0.tflite` and `efficientdet-lite2.tflite` are both downloaded at build time.

These are Android wrappers without JVM tests; the build is the check, and Task 4 exercises them on the phone.

- [ ] **Step 1: Create `SettingsStore.kt`**

```kotlin
package com.classroomscanner.settings

import android.content.Context
import com.classroomscanner.core.ScanSettings

/** Keeps the last Scan settings choices on the phone. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): ScanSettings {
        val d = ScanSettings()
        return ScanSettings(
            camera = enumOrDefault(prefs.getString(KEY_CAMERA, null), d.camera),
            compute = enumOrDefault(prefs.getString(KEY_COMPUTE, null), d.compute),
            model = enumOrDefault(prefs.getString(KEY_MODEL, null), d.model),
            minScore = prefs.getFloat(KEY_MIN_SCORE, d.minScore),
            speechOn = prefs.getBoolean(KEY_SPEECH, d.speechOn),
            colorsOn = prefs.getBoolean(KEY_COLORS, d.colorsOn),
        ).normalized()
    }

    fun save(settings: ScanSettings) {
        val s = settings.normalized()
        prefs.edit()
            .putString(KEY_CAMERA, s.camera.name)
            .putString(KEY_COMPUTE, s.compute.name)
            .putString(KEY_MODEL, s.model.name)
            .putFloat(KEY_MIN_SCORE, s.minScore)
            .putBoolean(KEY_SPEECH, s.speechOn)
            .putBoolean(KEY_COLORS, s.colorsOn)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val FILE = "scan_settings"
        const val KEY_CAMERA = "camera"
        const val KEY_COMPUTE = "compute"
        const val KEY_MODEL = "model"
        const val KEY_MIN_SCORE = "min_score"
        const val KEY_SPEECH = "speech_on"
        const val KEY_COLORS = "colors_on"
    }
}
```

- [ ] **Step 2: Create `ScanLogViewModel.kt`**

```kotlin
package com.classroomscanner.scanlog

import androidx.lifecycle.ViewModel
import com.classroomscanner.core.ScanLogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Activity-scoped log of the current scan, shared by the scanner and the View text screen. */
class ScanLogViewModel : ViewModel() {
    private val _state = MutableStateFlow(ScanLogState())
    val state: StateFlow<ScanLogState> = _state.asStateFlow()

    fun start() {
        _state.value = ScanLogState()
    }

    fun add(text: String) {
        _state.update { it.add(System.currentTimeMillis(), text) }
    }

    fun finish(summaryText: String) {
        _state.update { it.finish(summaryText) }
    }
}
```

In `app/build.gradle`, directly after `implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.8.7'` add:

```groovy
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7'
```

- [ ] **Step 3: Add `muted` to `SpeechAnnouncer.kt`**

1. After the `available` property add:

```kotlin
    /** When true nothing is spoken; callers still show the text on screen. */
    var muted: Boolean = false
```

2. In both `announce` and `speakNow`, change the first line `if (!available) return` to `if (!available || muted) return`.

- [ ] **Step 4: Give `CameraFov` a facing parameter**

Replace the function in `CameraFov.kt` with (and add `import com.classroomscanner.core.CameraFacing`):

```kotlin
    /** Horizontal field of view of the chosen camera when the phone is held in portrait. */
    fun portraitHorizontalFov(context: Context, facing: CameraFacing = CameraFacing.BACK): Float = try {
        val lens = if (facing == CameraFacing.FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lens
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
```

- [ ] **Step 5: Add `mirrored` to `OverlayView.kt`**

1. After `private var labels: List<String?>? = null` add:

```kotlin
    /** True for the front camera: its preview is mirrored, so boxes are flipped horizontally too. */
    var mirrored: Boolean = false
```

2. In `draw`, directly after `matrix.mapRect(boxRect)` and before the lambda's final `boxRect` line, add:

```kotlin
            if (mirrored) {
                val rotatedWidth = if (outputRotate == 90 || outputRotate == 270) outputHeight else outputWidth
                boxRect.set(rotatedWidth - boxRect.right, boxRect.top, rotatedWidth - boxRect.left, boxRect.bottom)
            }
```

- [ ] **Step 6: Download both models**

In `app/download_models.gradle`, keep the license comment and replace everything after it with:

```groovy
task downloadModelFile0(type: Download) {
    src 'https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite'
    dest project.ext.ASSET_DIR + '/efficientdet-lite0.tflite'
    overwrite false
}

task downloadModelFile1(type: Download) {
    src 'https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float32/1/efficientdet_lite2.tflite'
    dest project.ext.ASSET_DIR + '/efficientdet-lite2.tflite'
    overwrite false
}

preBuild.dependsOn downloadModelFile0, downloadModelFile1
```

- [ ] **Step 7: Build**

Run: `./gradlew.bat testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`, 102 tests pass, and `app/src/main/assets/` contains both `efficientdet-lite0.tflite` and `efficientdet-lite2.tflite`.

- [ ] **Step 8: Commit**

```bash
git add app/build.gradle app/download_models.gradle app/src/main/java/com/classroomscanner/settings app/src/main/java/com/classroomscanner/scanlog app/src/main/java/com/classroomscanner/speech/SpeechAnnouncer.kt app/src/main/java/com/classroomscanner/sensor/CameraFov.kt app/src/main/java/com/classroomscanner/OverlayView.kt
git commit -m "feat: add settings store, scan log view model, mute, front FOV and mirrored overlay"
```

---

### Task 3: Navigation shell — Home, Scan settings, permission argument, toolbar

**Files:**
- Create: `app/src/main/java/com/classroomscanner/fragments/HomeFragment.kt`, `app/src/main/java/com/classroomscanner/fragments/SettingsFragment.kt`, `app/src/main/res/layout/fragment_home.xml`, `app/src/main/res/layout/fragment_settings.xml`, `app/src/main/res/drawable/ic_360_24.xml`, `ic_arrow_back_24.xml`, `ic_chevron_right_24.xml`, `ic_notes_24.xml`
- Modify: `app/src/main/res/navigation/nav_graph.xml`, `app/src/main/res/layout/activity_main.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/java/com/classroomscanner/MainActivity.kt`, `app/src/main/java/com/classroomscanner/fragments/PermissionsFragment.kt`, `app/src/main/java/com/classroomscanner/fragments/CameraFragment.kt` (onResume only)
- Delete: `app/src/main/res/menu/menu_bottom_nav.xml`

**Interfaces:**
- Consumes: `ScanSettings` & enums (Task 1), `SettingsStore` (Task 2), `HeadingProvider(context, listener).isAvailable`, `PermissionsFragment.hasPermissions(context)`, `ScanMode { FULL, LIVE }`
- Produces:
  - Nav destinations `home_fragment` (start), `settings_fragment(mode: ScanMode)`, `permissions_fragment(mode: ScanMode)`, `camera_fragment(mode: ScanMode)`, `history_fragment`
  - Safe Args: `HomeFragmentDirections.actionHomeToSettings(mode)`, `HomeFragmentDirections.actionHomeToHistory()`, `SettingsFragmentDirections.actionSettingsToCamera(mode)`, `SettingsFragmentDirections.actionSettingsToPermissions(mode)`, `PermissionsFragmentDirections.actionPermissionsToCamera(mode)`, `CameraFragmentArgs(mode)`
  - Strings and icons listed below (Task 4 uses `view_text`, `scan_text_title`, `scan_text_empty`, `scan_text_summary`, `gpu_fallback`, `navigate_up`, `ic_arrow_back_24`, `ic_notes_24`)

- [ ] **Step 1: Icons**

Create four vector drawables with this template, replacing `PATH`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:tint="?attr/colorControlNormal"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="PATH" />
</vector>
```

| File | PATH |
|---|---|
| `ic_360_24.xml` | `M12,7C6.48,7 2,9.24 2,12c0,2.24 2.94,4.13 7,4.77V20l4,-4 -4,-4v2.73c-3.15,-0.56 -5,-1.9 -5,-2.73 0,-1.06 3.04,-3 8,-3s8,1.94 8,3c0,0.73 -1.46,1.89 -4,2.53v2.05c3.53,-0.77 6,-2.53 6,-4.58 0,-2.76 -4.48,-5 -10,-5z` |
| `ic_arrow_back_24.xml` | `M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z` |
| `ic_chevron_right_24.xml` | `M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z` |
| `ic_notes_24.xml` | `M3,18h12v-2L3,16v2zM3,6v2h18L21,6L3,6zM3,13h18v-2L3,11v2z` |

- [ ] **Step 2: Strings**

In `strings.xml`:
1. Delete `menu_scan` and `menu_history`.
2. Change `hint_idle` to `Press Start when you are ready.`
3. Add before `</resources>`:

```xml
    <string name="label_home">Classroom Scanner</string>
    <string name="label_settings">Scan settings</string>
    <string name="label_scanner">Scanner</string>
    <string name="label_history">History</string>
    <string name="label_permission">Camera access</string>
    <string name="home_title">What would you like to do?</string>
    <string name="home_intro">Everything runs on your phone. Hold it upright and turn slowly.</string>
    <string name="home_full_desc">Turn once around the room, then hear a summary.</string>
    <string name="home_live_desc">Hear each object as soon as it is found.</string>
    <string name="home_history_desc">Replay the summaries of past scans.</string>
    <string name="home_no_sensor">This phone has no rotation sensor.</string>
    <string name="settings_camera">Camera</string>
    <string name="camera_back">Back</string>
    <string name="camera_front">Front</string>
    <string name="settings_compute">Processing</string>
    <string name="compute_cpu">CPU</string>
    <string name="compute_gpu">GPU</string>
    <string name="settings_compute_hint">GPU is faster on many phones. If it fails, the app uses CPU.</string>
    <string name="settings_model">Model</string>
    <string name="model_fast">Fast</string>
    <string name="model_accurate">Accurate</string>
    <string name="settings_model_hint">Accurate finds far and partly hidden people better but is slower.</string>
    <string name="settings_confidence">Confidence</string>
    <string name="settings_confidence_hint">Higher values report fewer, surer objects. People are accepted a little earlier.</string>
    <string name="settings_speech">Speak results</string>
    <string name="settings_colors">Say colors</string>
    <string name="start_scanning">Start scanning</string>
    <string name="view_text">View text</string>
    <string name="scan_text_title">Scan text</string>
    <string name="scan_text_empty">Nothing said yet.</string>
    <string name="scan_text_summary">Summary</string>
    <string name="gpu_fallback">GPU unavailable, using CPU.</string>
    <string name="navigate_up">Back</string>
```

- [ ] **Step 3: Navigation graph**

In `nav_graph.xml`, keep the license comment and replace everything from `<navigation` to the end with:

```xml
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_graph"
    app:startDestination="@id/home_fragment">

    <fragment
        android:id="@+id/home_fragment"
        android:name="com.classroomscanner.fragments.HomeFragment"
        android:label="@string/label_home">
        <action
            android:id="@+id/action_home_to_settings"
            app:destination="@id/settings_fragment" />
        <action
            android:id="@+id/action_home_to_history"
            app:destination="@id/history_fragment" />
    </fragment>

    <fragment
        android:id="@+id/settings_fragment"
        android:name="com.classroomscanner.fragments.SettingsFragment"
        android:label="@string/label_settings">
        <argument
            android:name="mode"
            app:argType="com.classroomscanner.core.ScanMode" />
        <action
            android:id="@+id/action_settings_to_camera"
            app:destination="@id/camera_fragment" />
        <action
            android:id="@+id/action_settings_to_permissions"
            app:destination="@id/permissions_fragment" />
    </fragment>

    <fragment
        android:id="@+id/permissions_fragment"
        android:name="com.classroomscanner.fragments.PermissionsFragment"
        android:label="@string/label_permission">
        <argument
            android:name="mode"
            app:argType="com.classroomscanner.core.ScanMode" />
        <action
            android:id="@+id/action_permissions_to_camera"
            app:destination="@id/camera_fragment"
            app:popUpTo="@id/permissions_fragment"
            app:popUpToInclusive="true" />
    </fragment>

    <fragment
        android:id="@+id/camera_fragment"
        android:name="com.classroomscanner.fragments.CameraFragment"
        android:label="@string/label_scanner">
        <argument
            android:name="mode"
            app:argType="com.classroomscanner.core.ScanMode" />
    </fragment>

    <fragment
        android:id="@+id/history_fragment"
        android:name="com.classroomscanner.fragments.HistoryFragment"
        android:label="@string/label_history" />
</navigation>
```

- [ ] **Step 4: Activity without bottom navigation**

1. Delete `app/src/main/res/menu/menu_bottom_nav.xml`.
2. In `activity_main.xml`, delete the whole `<com.google.android.material.bottomnavigation.BottomNavigationView ... />` element (keep the license comment, toolbar and fragment container).
3. In `MainActivity.kt`, keep the license header and package line, and replace everything after the package line with:

```kotlin
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.classroomscanner.databinding.ActivityMainBinding

/**
 * Main entry point into our app. This app follows the single-activity pattern, and all
 * functionality is implemented in the form of fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var activityMainBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        ViewCompat.setOnApplyWindowInsetsListener(activityMainBinding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        // Titles come from destination labels; every screen except Home gets a back arrow.
        activityMainBinding.toolbar.setupWithNavController(navHostFragment.navController)
    }
}
```

- [ ] **Step 5: Home screen**

`fragment_home.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorSurface">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/home_title"
            android:textAppearance="?attr/textAppearanceHeadlineSmall"
            android:textColor="?attr/colorOnSurface" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:layout_marginBottom="12dp"
            android:text="@string/home_intro"
            android:textAppearance="?attr/textAppearanceBodyMedium"
            android:textColor="?attr/colorOnSurfaceVariant" />

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/card_full"
            style="?attr/materialCardViewFilledStyle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:clickable="true"
            android:focusable="true"
            app:cardBackgroundColor="?attr/colorPrimaryContainer"
            app:cardCornerRadius="24dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:minHeight="112dp"
                android:orientation="horizontal"
                android:padding="20dp">

                <ImageView
                    android:layout_width="40dp"
                    android:layout_height="40dp"
                    android:importantForAccessibility="no"
                    android:src="@drawable/ic_360_24"
                    app:tint="?attr/colorOnPrimaryContainer" />

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="16dp"
                    android:layout_weight="1"
                    android:orientation="vertical">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/mode_full"
                        android:textAppearance="?attr/textAppearanceTitleLarge"
                        android:textColor="?attr/colorOnPrimaryContainer" />

                    <TextView
                        android:id="@+id/full_desc"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="4dp"
                        android:text="@string/home_full_desc"
                        android:textAppearance="?attr/textAppearanceBodyMedium"
                        android:textColor="?attr/colorOnPrimaryContainer" />
                </LinearLayout>

                <ImageView
                    android:layout_width="24dp"
                    android:layout_height="24dp"
                    android:importantForAccessibility="no"
                    android:src="@drawable/ic_chevron_right_24"
                    app:tint="?attr/colorOnPrimaryContainer" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/card_live"
            style="?attr/materialCardViewFilledStyle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:clickable="true"
            android:focusable="true"
            app:cardBackgroundColor="?attr/colorTertiaryContainer"
            app:cardCornerRadius="24dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:minHeight="112dp"
                android:orientation="horizontal"
                android:padding="20dp">

                <ImageView
                    android:layout_width="40dp"
                    android:layout_height="40dp"
                    android:importantForAccessibility="no"
                    android:src="@drawable/ic_graphic_eq_24"
                    app:tint="?attr/colorOnTertiaryContainer" />

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="16dp"
                    android:layout_weight="1"
                    android:orientation="vertical">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/mode_live"
                        android:textAppearance="?attr/textAppearanceTitleLarge"
                        android:textColor="?attr/colorOnTertiaryContainer" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="4dp"
                        android:text="@string/home_live_desc"
                        android:textAppearance="?attr/textAppearanceBodyMedium"
                        android:textColor="?attr/colorOnTertiaryContainer" />
                </LinearLayout>

                <ImageView
                    android:layout_width="24dp"
                    android:layout_height="24dp"
                    android:importantForAccessibility="no"
                    android:src="@drawable/ic_chevron_right_24"
                    app:tint="?attr/colorOnTertiaryContainer" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/card_history"
            style="?attr/materialCardViewFilledStyle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:clickable="true"
            android:focusable="true"
            app:cardBackgroundColor="?attr/colorSurfaceContainerHigh"
            app:cardCornerRadius="24dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:minHeight="96dp"
                android:orientation="horizontal"
                android:padding="20dp">

                <ImageView
                    android:layout_width="40dp"
                    android:layout_height="40dp"
                    android:importantForAccessibility="no"
                    android:src="@drawable/ic_history_24"
                    app:tint="?attr/colorOnSurface" />

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="16dp"
                    android:layout_weight="1"
                    android:orientation="vertical">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/label_history"
                        android:textAppearance="?attr/textAppearanceTitleLarge"
                        android:textColor="?attr/colorOnSurface" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="4dp"
                        android:text="@string/home_history_desc"
                        android:textAppearance="?attr/textAppearanceBodyMedium"
                        android:textColor="?attr/colorOnSurfaceVariant" />
                </LinearLayout>

                <ImageView
                    android:layout_width="24dp"
                    android:layout_height="24dp"
                    android:importantForAccessibility="no"
                    android:src="@drawable/ic_chevron_right_24"
                    app:tint="?attr/colorOnSurface" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
```

`HomeFragment.kt`:

```kotlin
package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import com.classroomscanner.R
import com.classroomscanner.core.ScanMode
import com.classroomscanner.databinding.FragmentHomeBinding
import com.classroomscanner.sensor.HeadingProvider

/** Start screen: pick Full Scan, Live Scan or History. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val hasSensor = HeadingProvider(requireContext(), NoOpListener).isAvailable
        binding.cardFull.isEnabled = hasSensor
        binding.cardFull.alpha = if (hasSensor) 1f else DISABLED_ALPHA
        if (!hasSensor) binding.fullDesc.setText(R.string.home_no_sensor)

        binding.cardFull.setOnClickListener { go(HomeFragmentDirections.actionHomeToSettings(ScanMode.FULL)) }
        binding.cardLive.setOnClickListener { go(HomeFragmentDirections.actionHomeToSettings(ScanMode.LIVE)) }
        binding.cardHistory.setOnClickListener { go(HomeFragmentDirections.actionHomeToHistory()) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Ignores double taps that would otherwise navigate twice. */
    private fun go(directions: NavDirections) {
        val nav = findNavController()
        if (nav.currentDestination?.id == R.id.home_fragment) nav.navigate(directions)
    }

    private object NoOpListener : HeadingProvider.Listener {
        override fun onHeading(relHeading: Float, speedDegPerSec: Float) = Unit
        override fun onAccuracyLow(low: Boolean) = Unit
    }

    private companion object {
        const val DISABLED_ALPHA = 0.5f
    }
}
```

- [ ] **Step 6: Scan settings screen**

`fragment_settings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorSurface"
    android:orientation="vertical">

    <androidx.core.widget.NestedScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingHorizontal="16dp"
            android:paddingTop="8dp"
            android:paddingBottom="16dp">

            <com.google.android.material.card.MaterialCardView
                style="?attr/materialCardViewFilledStyle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                app:cardBackgroundColor="?attr/colorPrimaryContainer"
                app:cardCornerRadius="24dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:gravity="center_vertical"
                    android:orientation="horizontal"
                    android:padding="20dp">

                    <ImageView
                        android:id="@+id/mode_icon"
                        android:layout_width="40dp"
                        android:layout_height="40dp"
                        android:importantForAccessibility="no"
                        android:src="@drawable/ic_360_24"
                        app:tint="?attr/colorOnPrimaryContainer" />

                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="16dp"
                        android:layout_weight="1"
                        android:orientation="vertical">

                        <TextView
                            android:id="@+id/mode_title"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:textAppearance="?attr/textAppearanceTitleLarge"
                            android:textColor="?attr/colorOnPrimaryContainer" />

                        <TextView
                            android:id="@+id/mode_desc"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="4dp"
                            android:textAppearance="?attr/textAppearanceBodyMedium"
                            android:textColor="?attr/colorOnPrimaryContainer" />
                    </LinearLayout>
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>

            <com.google.android.material.card.MaterialCardView
                style="?attr/materialCardViewOutlinedStyle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                app:cardCornerRadius="20dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/settings_camera"
                        android:textAppearance="?attr/textAppearanceTitleMedium"
                        android:textColor="?attr/colorOnSurface" />

                    <com.google.android.material.button.MaterialButtonToggleGroup
                        android:id="@+id/camera_group"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        app:selectionRequired="true"
                        app:singleSelection="true">

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/camera_back"
                            style="?attr/materialButtonOutlinedStyle"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:minHeight="48dp"
                            android:text="@string/camera_back" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/camera_front"
                            style="?attr/materialButtonOutlinedStyle"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:minHeight="48dp"
                            android:text="@string/camera_front" />
                    </com.google.android.material.button.MaterialButtonToggleGroup>

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="20dp"
                        android:text="@string/settings_compute"
                        android:textAppearance="?attr/textAppearanceTitleMedium"
                        android:textColor="?attr/colorOnSurface" />

                    <com.google.android.material.button.MaterialButtonToggleGroup
                        android:id="@+id/compute_group"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        app:selectionRequired="true"
                        app:singleSelection="true">

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/compute_cpu"
                            style="?attr/materialButtonOutlinedStyle"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:minHeight="48dp"
                            android:text="@string/compute_cpu" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/compute_gpu"
                            style="?attr/materialButtonOutlinedStyle"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:minHeight="48dp"
                            android:text="@string/compute_gpu" />
                    </com.google.android.material.button.MaterialButtonToggleGroup>

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="4dp"
                        android:text="@string/settings_compute_hint"
                        android:textAppearance="?attr/textAppearanceBodySmall"
                        android:textColor="?attr/colorOnSurfaceVariant" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="20dp"
                        android:text="@string/settings_model"
                        android:textAppearance="?attr/textAppearanceTitleMedium"
                        android:textColor="?attr/colorOnSurface" />

                    <com.google.android.material.button.MaterialButtonToggleGroup
                        android:id="@+id/model_group"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        app:selectionRequired="true"
                        app:singleSelection="true">

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/model_fast"
                            style="?attr/materialButtonOutlinedStyle"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:minHeight="48dp"
                            android:text="@string/model_fast" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/model_accurate"
                            style="?attr/materialButtonOutlinedStyle"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:minHeight="48dp"
                            android:text="@string/model_accurate" />
                    </com.google.android.material.button.MaterialButtonToggleGroup>

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="4dp"
                        android:text="@string/settings_model_hint"
                        android:textAppearance="?attr/textAppearanceBodySmall"
                        android:textColor="?attr/colorOnSurfaceVariant" />
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>

            <com.google.android.material.card.MaterialCardView
                style="?attr/materialCardViewOutlinedStyle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                app:cardCornerRadius="20dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal">

                        <TextView
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:text="@string/settings_confidence"
                            android:textAppearance="?attr/textAppearanceTitleMedium"
                            android:textColor="?attr/colorOnSurface" />

                        <TextView
                            android:id="@+id/confidence_value"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:textAppearance="?attr/textAppearanceTitleMedium"
                            android:textColor="?attr/colorPrimary" />
                    </LinearLayout>

                    <com.google.android.material.slider.Slider
                        android:id="@+id/confidence_slider"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:contentDescription="@string/settings_confidence"
                        android:stepSize="0.1"
                        android:value="0.5"
                        android:valueFrom="0.3"
                        android:valueTo="0.7" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/settings_confidence_hint"
                        android:textAppearance="?attr/textAppearanceBodySmall"
                        android:textColor="?attr/colorOnSurfaceVariant" />

                    <com.google.android.material.materialswitch.MaterialSwitch
                        android:id="@+id/speech_switch"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="16dp"
                        android:minHeight="48dp"
                        android:text="@string/settings_speech"
                        android:textAppearance="?attr/textAppearanceTitleMedium" />

                    <com.google.android.material.materialswitch.MaterialSwitch
                        android:id="@+id/colors_switch"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:minHeight="48dp"
                        android:text="@string/settings_colors"
                        android:textAppearance="?attr/textAppearanceTitleMedium" />
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>
        </LinearLayout>
    </androidx.core.widget.NestedScrollView>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/start_button"
        style="@style/Widget.App.Button.Large"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:text="@string/start_scanning"
        app:icon="@drawable/ic_play_24" />
</LinearLayout>
```

`SettingsFragment.kt`:

```kotlin
package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.R
import com.classroomscanner.core.CameraFacing
import com.classroomscanner.core.Compute
import com.classroomscanner.core.ModelChoice
import com.classroomscanner.core.ScanMode
import com.classroomscanner.core.ScanSettings
import com.classroomscanner.databinding.FragmentSettingsBinding
import com.classroomscanner.settings.SettingsStore
import java.util.Locale

/** Lets the user choose camera, processing, model, confidence, speech and colors before scanning. */
class SettingsFragment : Fragment() {

    private val args: SettingsFragmentArgs by navArgs()
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val store = SettingsStore(requireContext())
        val full = args.mode == ScanMode.FULL
        binding.modeTitle.setText(if (full) R.string.mode_full else R.string.mode_live)
        binding.modeDesc.setText(if (full) R.string.home_full_desc else R.string.home_live_desc)
        binding.modeIcon.setImageResource(if (full) R.drawable.ic_360_24 else R.drawable.ic_graphic_eq_24)

        if (savedInstanceState == null) show(store.load())
        showConfidence(binding.confidenceSlider.value)
        binding.confidenceSlider.addOnChangeListener { _, value, _ -> showConfidence(value) }

        binding.startButton.setOnClickListener {
            store.save(readSettings())
            val nav = findNavController()
            if (nav.currentDestination?.id != R.id.settings_fragment) return@setOnClickListener
            if (PermissionsFragment.hasPermissions(requireContext())) {
                nav.navigate(SettingsFragmentDirections.actionSettingsToCamera(args.mode))
            } else {
                nav.navigate(SettingsFragmentDirections.actionSettingsToPermissions(args.mode))
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun show(s: ScanSettings) {
        binding.cameraGroup.check(if (s.camera == CameraFacing.FRONT) R.id.camera_front else R.id.camera_back)
        binding.computeGroup.check(if (s.compute == Compute.GPU) R.id.compute_gpu else R.id.compute_cpu)
        binding.modelGroup.check(if (s.model == ModelChoice.FAST) R.id.model_fast else R.id.model_accurate)
        binding.confidenceSlider.value = s.minScore
        binding.speechSwitch.isChecked = s.speechOn
        binding.colorsSwitch.isChecked = s.colorsOn
    }

    private fun readSettings() = ScanSettings(
        camera = if (binding.cameraGroup.checkedButtonId == R.id.camera_front) CameraFacing.FRONT else CameraFacing.BACK,
        compute = if (binding.computeGroup.checkedButtonId == R.id.compute_gpu) Compute.GPU else Compute.CPU,
        model = if (binding.modelGroup.checkedButtonId == R.id.model_fast) ModelChoice.FAST else ModelChoice.ACCURATE,
        minScore = binding.confidenceSlider.value,
        speechOn = binding.speechSwitch.isChecked,
        colorsOn = binding.colorsSwitch.isChecked,
    )

    private fun showConfidence(value: Float) {
        binding.confidenceValue.text = String.format(Locale.US, "%.1f", value)
    }
}
```

- [ ] **Step 7: Permission screen keeps the mode**

In `PermissionsFragment.kt`:
1. Add imports `androidx.navigation.fragment.findNavController` and `androidx.navigation.fragment.navArgs`; remove `androidx.navigation.Navigation` and `com.classroomscanner.R` if they become unused.
2. Add as the first member of the class:

```kotlin
    private val args: PermissionsFragmentArgs by navArgs()
```

3. Replace the body of `navigateToCamera()` with:

```kotlin
        if (navigated) return
        navigated = true
        findNavController().navigate(PermissionsFragmentDirections.actionPermissionsToCamera(args.mode))
```

4. Change the class comment to `/** Asks for the camera permission, explains why, and continues to the scanner once granted. */`.

- [ ] **Step 8: Scanner leaves when the permission is missing**

In `CameraFragment.onResume`, replace

```kotlin
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToPermissions())
        }
```

with

```kotlin
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            findNavController().popBackStack()
            return
        }
```

Replace the import `androidx.navigation.Navigation` with `androidx.navigation.fragment.findNavController`.

- [ ] **Step 9: Build, install and look**

```bash
./gradlew.bat testDebugUnitTest assembleDebug installDebug
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" shell am force-stop com.classroomscanner
"$ADB" shell am start -n com.classroomscanner/.MainActivity
```

Expected: `BUILD SUCCESSFUL`, 102 tests pass. Take screenshots (`"$ADB" exec-out screencap -p > <scratch file>` from Git Bash) of Home, then tap Full Scan and screenshot Scan settings, then tap Start scanning and confirm the scanner opens (it still shows the old mode toggle until Task 4). Press back twice and confirm Home is shown. Check `"$ADB" logcat -d -s AndroidRuntime:E` is empty. If the phone is locked or not connected, report it; do not unlock it.

- [ ] **Step 10: Commit**

```bash
git add -A app/src/main
git commit -m "feat: add home and scan settings screens with guided navigation"
```

---

### Task 4: Scanner uses settings, logs everything, opens View text

**Files:**
- Create: `app/src/main/java/com/classroomscanner/fragments/ScanTextDialog.kt`, `app/src/main/java/com/classroomscanner/scanlog/LogAdapter.kt`, `app/src/main/res/layout/dialog_scan_text.xml`, `app/src/main/res/layout/item_log_entry.xml`
- Modify: `app/src/main/res/layout/fragment_camera.xml` (full replacement), `app/src/main/java/com/classroomscanner/fragments/CameraFragment.kt` (full replacement), `app/src/main/res/values/styles.xml`

**Interfaces:**
- Consumes: everything from Tasks 1–3; `ObjectDetectorHelper(threshold, maxResults, currentDelegate, currentModel, runningMode, context, objectDetectorListener)` with constants `DELEGATE_CPU`, `DELEGATE_GPU`, `MODEL_EFFICIENTDETV0`, `MODEL_EFFICIENTDETV2`, `GPU_ERROR`; `ColorNamer.frameStats`, `ColorNamer.name`; `HistoryRepository.saveDetached`
- Produces: the finished scanner; `ScanTextDialog` with `companion object { const val TAG = "ScanTextDialog" }`

- [ ] **Step 1: Full-screen dialog theme**

In `styles.xml`, add before `</resources>`:

```xml
    <!-- View text: a full-screen window over the scanner so the scan keeps running. -->
    <style name="Theme.App.FullScreenDialog" parent="AppTheme">
        <item name="android:windowIsFloating">false</item>
        <item name="android:windowBackground">@color/md_surface</item>
    </style>
```

- [ ] **Step 2: Log row and dialog layouts**

`item_log_entry.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:paddingVertical="8dp">

    <TextView
        android:id="@+id/time"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:fontFamily="monospace"
        android:textAppearance="?attr/textAppearanceLabelLarge"
        android:textColor="?attr/colorOnSurfaceVariant" />

    <TextView
        android:id="@+id/text"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="12dp"
        android:layout_weight="1"
        android:textAppearance="?attr/textAppearanceBodyLarge"
        android:textColor="?attr/colorOnSurface" />
</LinearLayout>
```

`dialog_scan_text.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorSurface"
    android:orientation="vertical">

    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        app:navigationContentDescription="@string/navigate_up"
        app:navigationIcon="@drawable/ic_arrow_back_24"
        app:title="@string/scan_text_title"
        app:titleTextAppearance="?attr/textAppearanceTitleLarge" />

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/list"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false"
            android:paddingHorizontal="16dp"
            android:paddingBottom="8dp" />

        <TextView
            android:id="@+id/empty"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:text="@string/scan_text_empty"
            android:textAppearance="?attr/textAppearanceTitleMedium"
            android:textColor="?attr/colorOnSurfaceVariant" />
    </FrameLayout>

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/summary_card"
        style="?attr/materialCardViewFilledStyle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:visibility="gone"
        app:cardBackgroundColor="?attr/colorPrimaryContainer"
        app:cardCornerRadius="20dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/scan_text_summary"
                android:textAppearance="?attr/textAppearanceTitleSmall"
                android:textColor="?attr/colorOnPrimaryContainer" />

            <TextView
                android:id="@+id/summary_text"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:textAppearance="?attr/textAppearanceBodyLarge"
                android:textColor="?attr/colorOnPrimaryContainer" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>
</LinearLayout>
```

- [ ] **Step 3: `LogAdapter.kt`**

```kotlin
package com.classroomscanner.scanlog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.classroomscanner.core.LogEntry
import com.classroomscanner.databinding.ItemLogEntryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : ListAdapter<LogEntry, LogAdapter.Holder>(Diff) {

    class Holder(val binding: ItemLogEntryBinding) : RecyclerView.ViewHolder(binding.root)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        holder.binding.time.text = timeFormat.format(Date(entry.timeMs))
        holder.binding.text.text = entry.text
    }

    private object Diff : DiffUtil.ItemCallback<LogEntry>() {
        // Entries are append-only, so identity by time and text is enough.
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry) = oldItem == newItem
        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry) = oldItem == newItem
    }
}
```

- [ ] **Step 4: `ScanTextDialog.kt`**

```kotlin
package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.classroomscanner.R
import com.classroomscanner.databinding.DialogScanTextBinding
import com.classroomscanner.scanlog.LogAdapter
import com.classroomscanner.scanlog.ScanLogViewModel
import kotlinx.coroutines.launch

/** Full-screen log of the current scan, shown over the scanner so scanning continues underneath. */
class ScanTextDialog : DialogFragment() {

    private val scanLog: ScanLogViewModel by activityViewModels()
    private var _binding: DialogScanTextBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_App_FullScreenDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogScanTextBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { dismiss() }

        val adapter = LogAdapter()
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                scanLog.state.collect { state ->
                    adapter.submitList(state.entries) {
                        if (state.entries.isNotEmpty()) _binding?.list?.scrollToPosition(state.entries.size - 1)
                    }
                    binding.empty.isVisible = state.entries.isEmpty() && state.summary == null
                    binding.summaryCard.isVisible = state.summary != null
                    binding.summaryText.text = state.summary
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "ScanTextDialog"
    }
}
```

- [ ] **Step 5: Replace `fragment_camera.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/camera_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black">

    <androidx.camera.view.PreviewView
        android:id="@+id/view_finder"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:scaleType="fillStart" />

    <com.classroomscanner.OverlayView
        android:id="@+id/overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/banner_card"
        style="?attr/materialCardViewFilledStyle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="top"
        android:layout_margin="12dp"
        android:visibility="gone"
        app:cardBackgroundColor="?attr/colorErrorContainer"
        app:cardCornerRadius="16dp">

        <TextView
            android:id="@+id/banner"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:accessibilityLiveRegion="assertive"
            android:drawablePadding="12dp"
            android:gravity="center_vertical"
            android:minHeight="48dp"
            android:paddingHorizontal="16dp"
            android:paddingVertical="12dp"
            android:textAppearance="?attr/textAppearanceTitleSmall"
            android:textColor="?attr/colorOnErrorContainer"
            app:drawableStartCompat="@drawable/ic_warning_24"
            app:drawableTint="?attr/colorOnErrorContainer" />
    </com.google.android.material.card.MaterialCardView>

    <LinearLayout
        android:id="@+id/bottom_panel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:orientation="vertical">

        <com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
            android:id="@+id/view_text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="end"
            android:layout_marginEnd="12dp"
            android:text="@string/view_text"
            app:icon="@drawable/ic_notes_24" />

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/controls"
            style="?attr/materialCardViewElevatedStyle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_margin="12dp"
            app:cardBackgroundColor="?attr/colorSurfaceContainerLowest"
            app:cardCornerRadius="28dp"
            app:cardElevation="6dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="16dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:gravity="center_vertical"
                    android:orientation="horizontal">

                    <com.classroomscanner.ui.CoverageRingView
                        android:id="@+id/coverage_ring"
                        android:layout_width="80dp"
                        android:layout_height="80dp" />

                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="16dp"
                        android:layout_weight="1"
                        android:orientation="vertical">

                        <TextView
                            android:id="@+id/mode_label"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:textAppearance="?attr/textAppearanceTitleLarge"
                            android:textColor="?attr/colorOnSurface" />

                        <TextView
                            android:id="@+id/object_count"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="4dp"
                            android:textAppearance="?attr/textAppearanceTitleMedium"
                            android:textColor="?attr/colorOnSurfaceVariant" />
                    </LinearLayout>
                </LinearLayout>

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/start_stop"
                    style="@style/Widget.App.Button.Large"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="16dp"
                    android:text="@string/start"
                    app:icon="@drawable/ic_play_24" />

                <TextView
                    android:id="@+id/announcement"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:accessibilityLiveRegion="polite"
                    android:background="@drawable/bg_announcement"
                    android:drawablePadding="12dp"
                    android:ellipsize="end"
                    android:gravity="center_vertical"
                    android:maxLines="3"
                    android:minHeight="48dp"
                    android:paddingHorizontal="16dp"
                    android:paddingVertical="12dp"
                    android:text="@string/hint_idle"
                    android:textAppearance="?attr/textAppearanceBodyLarge"
                    android:textColor="?attr/colorOnSurface"
                    app:drawableStartCompat="@drawable/ic_graphic_eq_24"
                    app:drawableTint="?attr/colorPrimary" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>
    </LinearLayout>
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 6: Replace `CameraFragment.kt`**

```kotlin
package com.classroomscanner.fragments

import android.annotation.SuppressLint
import android.content.res.ColorStateList
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
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.ObjectDetectorHelper
import com.classroomscanner.R
import com.classroomscanner.color.ColorNamer
import com.classroomscanner.core.BoxGeometry
import com.classroomscanner.core.CameraFacing
import com.classroomscanner.core.ColorPolicy
import com.classroomscanner.core.Compute
import com.classroomscanner.core.DetectionFilter
import com.classroomscanner.core.FrameDetection
import com.classroomscanner.core.ModelChoice
import com.classroomscanner.core.ScanMode
import com.classroomscanner.core.ScanSession
import com.classroomscanner.core.ScanSettings
import com.classroomscanner.databinding.FragmentCameraBinding
import com.classroomscanner.history.AppDatabase
import com.classroomscanner.history.HistoryRepository
import com.classroomscanner.scanlog.ScanLogViewModel
import com.classroomscanner.sensor.CameraFov
import com.classroomscanner.sensor.HeadingProvider
import com.classroomscanner.settings.SettingsStore
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.android.material.color.MaterialColors
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener, HeadingProvider.Listener {

    private val args: CameraFragmentArgs by navArgs()
    private val scanLog: ScanLogViewModel by activityViewModels()

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

    // Set once in onViewCreated before the detector starts, then only read (also on the detector thread).
    private lateinit var settings: ScanSettings
    private lateinit var detectionFilter: DetectionFilter
    private var hfov = CameraFov.FALLBACK_DEG

    // Main thread only.
    private var session: ScanSession? = null
    private var tooFast = false
    private var compassLow = false
    private var lastSlowDownSpokenAt = 0L

    // Written on the main thread, read on the detector thread.
    @Volatile
    private var relHeading = 0f

    @Volatile
    private var gpuFallbackStarted = false

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            findNavController().popBackStack()
            return
        }
        backgroundExecutor.execute {
            if (objectDetectorHelper.isClosed()) {
                objectDetectorHelper.setupObjectDetector()
            }
            if (!objectDetectorHelper.isClosed()) {
                activity?.runOnUiThread { _fragmentCameraBinding?.startStop?.isEnabled = canStart() }
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
        settings = SettingsStore(context).load()
        detectionFilter = DetectionFilter(settings.minScore)
        headingProvider = HeadingProvider(context, this)
        speech = SpeechAnnouncer(context).apply { muted = !settings.speechOn }
        history = HistoryRepository(AppDatabase.get(context).scanDao())
        hfov = CameraFov.portraitHorizontalFov(context, settings.camera)
        Log.i(TAG, "Settings: $settings, horizontal FOV: $hfov")

        backgroundExecutor = Executors.newSingleThreadExecutor()
        backgroundExecutor.execute {
            objectDetectorHelper = ObjectDetectorHelper(
                currentDelegate = if (settings.compute == Compute.GPU) {
                    ObjectDetectorHelper.DELEGATE_GPU
                } else {
                    ObjectDetectorHelper.DELEGATE_CPU
                },
                currentModel = if (settings.model == ModelChoice.FAST) {
                    ObjectDetectorHelper.MODEL_EFFICIENTDETV0
                } else {
                    ObjectDetectorHelper.MODEL_EFFICIENTDETV2
                },
                context = context,
                objectDetectorListener = this,
                runningMode = RunningMode.LIVE_STREAM
            )
            fragmentCameraBinding.viewFinder.post { setUpCamera() }
        }

        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)
        fragmentCameraBinding.overlay.mirrored = settings.camera == CameraFacing.FRONT
        initScanControls()
    }

    private fun initScanControls() {
        val b = fragmentCameraBinding
        b.modeLabel.setText(if (args.mode == ScanMode.FULL) R.string.mode_full else R.string.mode_live)
        b.objectCount.text = getString(R.string.objects_count, 0)
        b.startStop.isEnabled = canStart()
        b.startStop.setOnClickListener { if (session == null) startScan() else stopScan() }
        b.viewText.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(ScanTextDialog.TAG) == null) {
                ScanTextDialog().show(childFragmentManager, ScanTextDialog.TAG)
            }
        }
        updateBanner()
    }

    /** Full Scan needs the rotation sensor; Live Scan works without it. */
    private fun canStart() = args.mode == ScanMode.LIVE || headingProvider.isAvailable

    private fun startScan() {
        val b = fragmentCameraBinding
        session = ScanSession(args.mode, System.currentTimeMillis())
        relHeading = 0f
        headingProvider.start()
        scanLog.start()

        showRunning(true)
        b.coverageRing.reset()
        b.objectCount.text = getString(R.string.objects_count, 0)
        say(getString(if (args.mode == ScanMode.FULL) R.string.hint_full else R.string.hint_live))
    }

    private fun stopScan() {
        val s = session ?: return
        session = null
        headingProvider.stop()
        tooFast = false
        compassLow = false

        val result = s.finish()
        speech.speakNow(result.summaryText)
        scanLog.finish(result.summaryText)
        history.saveDetached(result)
        Log.i(TAG, "Scan finished: ${result.summaryText}")

        _fragmentCameraBinding?.let { b ->
            b.announcement.text = result.summaryText
            showRunning(false)
        }
        updateBanner()
    }

    /** Shows [text], writes it to the scan log and speaks it (unless speech is off). */
    private fun say(text: String) {
        _fragmentCameraBinding?.announcement?.text = text
        scanLog.add(text)
        speech.announce(text)
    }

    private fun updateBanner() {
        val b = _fragmentCameraBinding ?: return
        val text = when {
            !headingProvider.isAvailable -> getString(R.string.banner_no_compass)
            tooFast -> getString(R.string.banner_slow_down)
            compassLow -> getString(R.string.banner_calibrate)
            else -> null
        }
        if (b.banner.text?.toString() != text) b.banner.text = text
        b.bannerCard.isVisible = text != null
    }

    /** Start is a filled primary button; Stop turns it red so the running state is obvious. */
    private fun showRunning(running: Boolean) {
        val button = fragmentCameraBinding.startStop
        val container = if (running) com.google.android.material.R.attr.colorError else com.google.android.material.R.attr.colorPrimary
        val content = if (running) com.google.android.material.R.attr.colorOnError else com.google.android.material.R.attr.colorOnPrimary
        val onColor = MaterialColors.getColor(button, content)
        button.text = getString(if (running) R.string.stop else R.string.start)
        button.setIconResource(if (running) R.drawable.ic_stop_24 else R.drawable.ic_play_24)
        button.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(button, container))
        button.setTextColor(onColor)
        button.iconTint = ColorStateList.valueOf(onColor)
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
            scanLog.add(getString(R.string.banner_slow_down))
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

        val lensFacing = if (settings.camera == CameraFacing.FRONT) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

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
        val frameStats = if (settings.colorsOn) frame?.let { ColorNamer.frameStats(it) } else null

        val evaluated = result.detections().map { d ->
            val box = d.boundingBox()
            val center = BoxGeometry.horizontalCenter(
                box.left, box.top, box.right, box.bottom,
                resultBundle.inputImageWidth, resultBundle.inputImageHeight, resultBundle.inputImageRotation
            )
            val category = d.categories()[0]
            val label = category.categoryName()
            val kept = detectionFilter.keep(label, category.score())
            val detection = FrameDetection(
                label = label,
                angle = BoxGeometry.objectAngle(heading, center, hfov, settings.camera),
                color = if (kept && frame != null && frameStats != null && ColorPolicy.hasColor(label)) {
                    ColorNamer.name(frame, box, frameStats)
                } else {
                    null
                }
            )
            val touchesEdge = BoxGeometry.touchesOneSideEdge(
                box.left, box.top, box.right, box.bottom,
                resultBundle.inputImageWidth, resultBundle.inputImageHeight, resultBundle.inputImageRotation
            )
            Evaluated(detection, kept, counted = kept && !touchesEdge)
        }
        val overlayLabels = evaluated.map { if (it.kept) it.detection.overlayLabel() else null }
        val countedDetections = evaluated.filter { it.counted }.map { it.detection }
        logInferenceTime(resultBundle.inferenceTime)

        activity?.runOnUiThread {
            val b = _fragmentCameraBinding ?: return@runOnUiThread
            if (isAdded) {
                b.overlay.setResults(
                    result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    resultBundle.inputImageRotation,
                    overlayLabels
                )
            }
            b.overlay.invalidate()

            val s = session ?: return@runOnUiThread
            s.onFrame(countedDetections).forEach(::say)
            updateScanUi(s)
            if (s.shouldAutoStop(System.currentTimeMillis())) stopScan()
        }
    }

    // Detector init errors arrive on the detector thread; live-frame errors on the MediaPipe thread.
    override fun onError(error: String, errorCode: Int) {
        if (errorCode == ObjectDetectorHelper.GPU_ERROR && settings.compute == Compute.GPU && !gpuFallbackStarted) {
            gpuFallbackStarted = true
            Log.w(TAG, "GPU detector failed, falling back to CPU: $error")
            if (!backgroundExecutor.isShutdown) {
                backgroundExecutor.execute {
                    objectDetectorHelper.currentDelegate = ObjectDetectorHelper.DELEGATE_CPU
                    objectDetectorHelper.setupObjectDetector()
                    if (!objectDetectorHelper.isClosed()) {
                        activity?.runOnUiThread { _fragmentCameraBinding?.startStop?.isEnabled = canStart() }
                    }
                }
            }
            activity?.runOnUiThread {
                if (_fragmentCameraBinding == null) return@runOnUiThread
                val message = getString(R.string.gpu_fallback)
                fragmentCameraBinding.announcement.text = message
                scanLog.add(message)
            }
            return
        }
        activity?.runOnUiThread {
            val b = _fragmentCameraBinding ?: return@runOnUiThread
            Log.e(TAG, error)
            b.announcement.text = error
            scanLog.add(error)
            if (session == null) b.startStop.isEnabled = false
        }
    }

    /** One detector output: whether it passes the confidence filter and whether it is counted. */
    private class Evaluated(val detection: FrameDetection, val kept: Boolean, val counted: Boolean)

    private var framesSinceTimingLog = 0

    // Runs on the MediaPipe result thread only.
    private fun logInferenceTime(ms: Long) {
        if (++framesSinceTimingLog >= TIMING_LOG_EVERY) {
            framesSinceTimingLog = 0
            Log.i(TAG, "Inference time: $ms ms")
        }
    }

    private fun FrameDetection.overlayLabel() = color?.let { "$label · $it" } ?: label

    private companion object {
        const val TAG = "ClassroomScanner"
        const val MAX_SPEED_DEG_PER_SEC = 60f
        const val SLOW_DOWN_REPEAT_MS = 5_000L
        const val TIMING_LOG_EVERY = 30
    }
}
```

- [ ] **Step 7: Build and install**

```bash
./gradlew.bat testDebugUnitTest assembleDebug installDebug
```

Expected: `BUILD SUCCESSFUL`, 102 tests pass.

- [ ] **Step 8: Check on the phone**

With the phone unlocked (if it is locked or disconnected, report that and skip to Step 9):
1. `adb logcat -c`, launch the app, tap Full Scan, set Camera Back / CPU / Accurate, tap Start scanning.
2. Tap Start, wait 4 s, screenshot the scanner (Stop button red, "View text" at the bottom right, mode label "Full Scan").
3. Tap View text, screenshot (log rows with times, at least "Turn slowly in a full circle.").
4. Tap the back arrow in the dialog; confirm the scanner is still running (Stop still shown). Tap Stop, tap View text again and screenshot the Summary card.
5. Go back to Settings, choose Front and GPU, start again, tap Start, wait 6 s, stop. Collect `adb logcat -d -s ClassroomScanner:I AndroidRuntime:E` and report the "Settings:" line, "Inference time" lines and any "GPU detector failed" line.
6. Go back to Home, open History, confirm the new scans are listed.
Report everything only a human can judge (front-camera directions, whether speech is muted when Speech is off) as "needs human check".

- [ ] **Step 9: Commit**

```bash
git add -A app/src/main
git commit -m "feat: scanner follows scan settings and shows the scan text screen"
```

---

### Task 5: README and final verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the README**

1. Replace the `## Features` list with:

```markdown
- **Home:** choose Full Scan, Live Scan or History.
- **Scan settings:** back or front camera, CPU or GPU, fast or accurate model, confidence, speech and colors. The last choices are remembered.
- **Full Scan:** turn 360°. The scan stops automatically and a spoken summary follows.
- **Live Scan:** objects are announced as they are found.
- **View text:** everything the app said during the scan, with times and the final summary.
- **History:** past scans are saved on the phone and can be replayed.
```

2. In `## How it works`, replace the first two bullets and the Palette bullet with:

```markdown
- **CameraX + MediaPipe ObjectDetector** (EfficientDet-Lite0 or Lite2, COCO) detect objects in the camera feed.
- **The rotation vector sensor** gives each object its direction.
- **Pixel color voting with gray-world white balance** names each object's color.
```

- [ ] **Step 2: Full check**

Run: `./gradlew.bat clean testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`, 102 tests, 0 failures.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: describe the home, settings and scan text flow"
```
