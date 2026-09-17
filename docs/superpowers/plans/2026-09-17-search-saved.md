# Search Scan, Saved Items and Voice Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the app so Home offers Full scan, Search scan and Saved. Users can save cars and objects, not only people. The app takes voice input, and Search guides a blind user to a saved thing or a COCO object.

**Architecture:**
- Pure logic lives in `core/` and is test-driven: command parsing, query resolution, search guidance, and item matching and enrollment.
- Android glue follows the patterns the app already uses:
  - MediaPipe `ImageEmbedder` embeds item crops; Room v3 stores them.
  - `SpeechRecognizer` wraps STT.
  - The new fragments copy the existing People/Enroll/Camera patterns.

**Tech Stack:**
- Kotlin 2.1, AGP 8.11, CameraX 1.4.2, Navigation 2.8.9 with Safe Args, Room 2.7.2.
- MediaPipe tasks-vision 1.0.0: `ObjectDetector` and `ImageEmbedder`.
- ML Kit Face, TFLite FaceNet, Android `SpeechRecognizer`, `ToneGenerator`, JUnit4.

**Spec:** `docs/superpowers/specs/2026-09-17-search-saved-design.md`

## Global Constraints

- **Language:** code, comments, UI text, speech and docs are English.
- **`core/`:** no `android.*` or `androidx.*` imports; all core code is test-driven (JUnit4, `app/src/test/java/com/classroomscanner/core/`).
- **Git:**
  - No git worktrees; work in `D:\classroom-scanner` on branch `feat/search-saved`.
  - Never add `Co-Authored-By` or any AI co-author line to commits.
- **Destructive commands:** never run `connectedDebugAndroidTest`, because it uninstalls the app and wipes data. Never clear app data.
- **Lean verification:** after Android tasks, run only `.\gradlew.bat assembleDebug`; after core tasks, run `.\gradlew.bat testDebugUnitTest`. Device checks are listed in Task 11 for the user to do.
- **Thresholds:** item match `ItemMatcher.THRESHOLD = 0.75f`; face match stays `FaceMatcher.THRESHOLD = 0.3f`.
- **Search detector:** Lite2, CPU, score threshold 0.4.
- **STT:** `SpeechRecognizer`, language `en-US`, `EXTRA_PREFER_OFFLINE = true`.
- **Commands:** run from the repo root in PowerShell. Unit tests: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.<Class>"`.

## File Map

**Create, `core/` (pure Kotlin):**
- `core/ItemCore.kt`: `ItemKind`, `KnownItem`, `ItemMatch`, `ItemMatcher`, `ItemStep`, `ItemEnrollmentGuide`, `Candidate`, `CenterPick`.
- `core/SearchQuery.kt`: `CocoLabels`, `SavedKind`, `SavedName`, `SearchTarget`, `SearchResolver`.
- `core/VoiceCommand.kt`: `VoiceCommand`, `VoiceCommandParser`.
- `core/SearchGuide.kt`: `SearchGuide`, `SearchTracker`.

**Create, Android:**
- `items/ItemData.kt`: `ItemEntity`, `ItemEmbeddingEntity`, `ItemRow`, `ItemsDao`.
- `items/ItemRepository.kt`
- `items/ItemEngine.kt`: `Bitmap.cropBox`, `ItemEmbedder`, `ItemRecognizer`.
- `speech/SpeechInput.kt`
- `fragments/VoiceInputController.kt`
- `people/SavedAdapter.kt`: replaces `people/PeopleAdapter.kt` and keeps `PhotoLoader`.
- `fragments/ScanHubFragment.kt`
- `fragments/SavedFragments.kt`: `SavedFragment`, `ItemFragment`, `AddItemFragment`.
- `fragments/ItemEnrollFragment.kt`
- `fragments/SearchFragment.kt`
- `fragments/SearchCameraFragment.kt`
- `search/Beeper.kt`
- Layouts: `fragment_scan_hub.xml`, `fragment_saved.xml`, `fragment_search.xml`, `fragment_search_camera.xml`.
- Drawables: `ic_mic_24.xml`, `ic_search_24.xml`, `ic_bookmark_24.xml`, `ic_car_24.xml`, `ic_category_24.xml`.

**Modify:**
- `app/download_models.gradle`, `AndroidManifest.xml`, `history/AppDatabase.kt`, `speech/SpeechAnnouncer.kt`, `guide/VoiceGuide.kt`.
- `fragments/HomeFragment.kt`, `fragment_home.xml`, `fragments/PeopleFragments.kt`, `fragments/EnrollFragment.kt`, `fragment_add_person.xml`.
- `nav_graph.xml`, `strings.xml`, `fragments/CameraFragment.kt`, `README.md`.

---

### Task 1: Core — item kinds, matching, enrollment guide and center pick

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/ItemCore.kt`
- Test: `app/src/test/java/com/classroomscanner/core/ItemCoreTest.kt`

**Interfaces:**
- Consumes: `FaceMatcher.cosine(a: FloatArray, b: FloatArray): Float` (existing, `core/FaceCore.kt`).
- Produces:
  - `enum class ItemKind { CAR, OBJECT }` with `fun allows(label: String): Boolean`.
  - `class KnownItem(val itemId: Long, val name: String, val label: String, val vector: FloatArray)`
  - `data class ItemMatch(val itemId: Long, val name: String, val similarity: Float)`
  - `object ItemMatcher { const val THRESHOLD = 0.75f; fun bestMatch(vector: FloatArray, label: String, known: List<KnownItem>, threshold: Float = THRESHOLD): ItemMatch? }`
  - `enum class ItemStep(val instruction: String)` with `STILL`, `LEFT`, `RIGHT`.
  - `class ItemEnrollmentGuide(samplesPerStep: Int = 4)` with `total`, `captured`, `done`, `currentStep: ItemStep?`, `stepFinished`, `percent(): Int` and `offer()`.
  - `data class Candidate(val centerX: Float, val centerY: Float, val area: Float)` (normalized 0..1).
  - `object CenterPick { fun pick(candidates: List<Candidate>, minArea: Float): Int? }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemKindTest {
    @Test
    fun carsAllowOnlyVehicles() {
        assertTrue(ItemKind.CAR.allows("car"))
        assertTrue(ItemKind.CAR.allows("truck"))
        assertTrue(ItemKind.CAR.allows("bus"))
        assertTrue(ItemKind.CAR.allows("motorcycle"))
        assertFalse(ItemKind.CAR.allows("backpack"))
        assertFalse(ItemKind.CAR.allows("person"))
    }

    @Test
    fun objectsAllowAnythingButPeople() {
        assertTrue(ItemKind.OBJECT.allows("backpack"))
        assertTrue(ItemKind.OBJECT.allows("car"))
        assertFalse(ItemKind.OBJECT.allows("person"))
    }
}

class ItemMatcherTest {
    private val bag = KnownItem(1, "my bag", "backpack", floatArrayOf(1f, 0f))
    private val cup = KnownItem(2, "my cup", "cup", floatArrayOf(1f, 0f))
    private val otherBag = KnownItem(3, "red bag", "backpack", floatArrayOf(0f, 1f))

    @Test
    fun matchesOnlyItemsWithTheSameLabel() {
        val match = ItemMatcher.bestMatch(floatArrayOf(1f, 0f), "cup", listOf(bag, cup))
        assertEquals(2L, match!!.itemId)
    }

    @Test
    fun picksHighestSimilarity() {
        val match = ItemMatcher.bestMatch(floatArrayOf(0.1f, 1f), "backpack", listOf(bag, otherBag))
        assertEquals("red bag", match!!.name)
    }

    @Test
    fun belowThresholdIsNull() {
        assertNull(ItemMatcher.bestMatch(floatArrayOf(1f, 1f), "backpack", listOf(otherBag), threshold = 0.9f))
        assertNull(ItemMatcher.bestMatch(floatArrayOf(1f, 0f), "backpack", emptyList()))
    }
}

class ItemEnrollmentGuideTest {
    @Test
    fun walksThroughThreeSteps() {
        val guide = ItemEnrollmentGuide(samplesPerStep = 2)
        assertEquals(6, guide.total)
        assertEquals(ItemStep.STILL, guide.currentStep)
        guide.offer()
        assertFalse(guide.stepFinished)
        assertEquals(16, guide.percent())
        guide.offer()
        assertTrue(guide.stepFinished)
        assertEquals(ItemStep.LEFT, guide.currentStep)
        repeat(4) { guide.offer() }
        assertTrue(guide.done)
        assertNull(guide.currentStep)
        assertEquals(100, guide.percent())
    }

    @Test
    fun offerAfterDoneIsIgnored() {
        val guide = ItemEnrollmentGuide(samplesPerStep = 1)
        repeat(3) { guide.offer() }
        assertFalse(guide.offer())
        assertEquals(3, guide.captured)
    }
}

class CenterPickTest {
    @Test
    fun picksClosestToCenterAmongLargeEnough() {
        val candidates = listOf(
            Candidate(0.5f, 0.5f, 0.01f), // centered but too small
            Candidate(0.2f, 0.5f, 0.2f),
            Candidate(0.6f, 0.45f, 0.1f),
        )
        assertEquals(2, CenterPick.pick(candidates, minArea = 0.05f))
    }

    @Test
    fun nullWhenNothingQualifies() {
        assertNull(CenterPick.pick(listOf(Candidate(0.5f, 0.5f, 0.01f)), minArea = 0.05f))
        assertNull(CenterPick.pick(emptyList(), minArea = 0f))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.Item*" --tests "com.classroomscanner.core.CenterPickTest"`
Expected: compilation FAIL, `Unresolved reference ItemKind`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.classroomscanner.core

/** What a saved item is; decides which detector labels it may be enrolled and matched as. */
enum class ItemKind {
    CAR,
    OBJECT;

    fun allows(label: String): Boolean = when (this) {
        CAR -> label in VEHICLES
        OBJECT -> label != PERSON
    }

    private companion object {
        const val PERSON = "person"
        val VEHICLES = setOf("car", "truck", "bus", "motorcycle")
    }
}

/** One stored image embedding of a saved car or object. */
class KnownItem(val itemId: Long, val name: String, val label: String, val vector: FloatArray)

data class ItemMatch(val itemId: Long, val name: String, val similarity: Float)

/** Compares MediaPipe image embeddings of detections with saved items of the same label. */
object ItemMatcher {
    /** Starting value for mobilenet_v3_small embeddings; tune on the device. */
    const val THRESHOLD = 0.75f

    fun bestMatch(
        vector: FloatArray,
        label: String,
        known: List<KnownItem>,
        threshold: Float = THRESHOLD,
    ): ItemMatch? =
        known.asSequence()
            .filter { it.label == label }
            .map { ItemMatch(it.itemId, it.name, FaceMatcher.cosine(vector, it.vector)) }
            .filter { it.similarity >= threshold }
            .maxByOrNull { it.similarity }
}

enum class ItemStep(val instruction: String) {
    STILL("Hold the camera still."),
    LEFT("Move a little to the left."),
    RIGHT("Move a little to the right."),
}

/** Counts samples while the user views an item from three slightly different spots. */
class ItemEnrollmentGuide(private val samplesPerStep: Int = 4) {
    private var stepIndex = 0
    private var samplesInStep = 0

    val total: Int get() = ItemStep.entries.size * samplesPerStep
    val captured: Int get() = stepIndex * samplesPerStep + samplesInStep
    val done: Boolean get() = stepIndex >= ItemStep.entries.size
    val currentStep: ItemStep? get() = ItemStep.entries.getOrNull(stepIndex)

    /** True right after a sample completed a step (time to speak the next instruction). */
    var stepFinished: Boolean = false
        private set

    fun percent(): Int = captured * 100 / total

    /** Counts one sample; false once all steps are done. */
    fun offer(): Boolean {
        stepFinished = false
        if (done) return false
        samplesInStep++
        if (samplesInStep == samplesPerStep) {
            stepIndex++
            samplesInStep = 0
            stepFinished = true
        }
        return true
    }
}

/** A detection box in normalized upright coordinates. */
data class Candidate(val centerX: Float, val centerY: Float, val area: Float)

/** Picks the detection the user is pointing the camera at. */
object CenterPick {
    fun pick(candidates: List<Candidate>, minArea: Float): Int? =
        candidates.indices
            .filter { candidates[it].area >= minArea }
            .minByOrNull {
                val dx = candidates[it].centerX - 0.5f
                val dy = candidates[it].centerY - 0.5f
                dx * dx + dy * dy
            }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.Item*" --tests "com.classroomscanner.core.CenterPickTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/classroomscanner/core/ItemCore.kt app/src/test/java/com/classroomscanner/core/ItemCoreTest.kt
git commit -m "feat(core): item kinds, matching and enrollment guide"
```

---

### Task 2: Core — search query resolution

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/SearchQuery.kt`
- Test: `app/src/test/java/com/classroomscanner/core/SearchQueryTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `object CocoLabels { val ALL: Set<String> }`
  - `enum class SavedKind { PERSON, CAR, OBJECT }`
  - `data class SavedName(val kind: SavedKind, val id: Long, val name: String, val label: String?)`: `label` is null for people.
  - `sealed interface SearchTarget` with four cases:
    - `data class Person(val id: Long, val name: String)`
    - `data class Item(val id: Long, val name: String, val label: String)`
    - `data class Label(val label: String)`
    - `data class Unknown(val text: String)`
  - `object SearchResolver { fun resolve(text: String, saved: List<SavedName>): SearchTarget; fun clean(text: String): String }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResolverTest {
    private val saved = listOf(
        SavedName(SavedKind.PERSON, 1, "Ali", null),
        SavedName(SavedKind.OBJECT, 2, "My Bag", "backpack"),
        SavedName(SavedKind.CAR, 3, "Dad's Car", "car"),
        SavedName(SavedKind.PERSON, 4, "Madina", null),
    )

    @Test
    fun cleanDropsCommandWordsAndPunctuation() {
        assertEquals("keys", SearchResolver.clean("Where is my keys?"))
        assertEquals("cup", SearchResolver.clean("find the cup"))
        assertEquals("cell phone", SearchResolver.clean("Look for a Cell-Phone!"))
    }

    @Test
    fun savedPersonByExactName() {
        assertEquals(SearchTarget.Person(1, "Ali"), SearchResolver.resolve("find Ali", saved))
    }

    @Test
    fun savedItemIgnoresMy() {
        assertEquals(SearchTarget.Item(2, "My Bag", "backpack"), SearchResolver.resolve("where is my bag", saved))
    }

    @Test
    fun savedNameContainedInQuery() {
        assertEquals(SearchTarget.Item(3, "Dad's Car", "car"), SearchResolver.resolve("find dads car please", saved))
    }

    @Test
    fun savedNameWithSmallTypo() {
        assertEquals(SearchTarget.Person(4, "Madina"), SearchResolver.resolve("find medina", saved))
    }

    @Test
    fun cocoLabelDirect() {
        assertEquals(SearchTarget.Label("cup"), SearchResolver.resolve("find a cup", saved))
    }

    @Test
    fun cocoLabelPluralAndSynonym() {
        assertEquals(SearchTarget.Label("chair"), SearchResolver.resolve("chairs", saved))
        assertEquals(SearchTarget.Label("cell phone"), SearchResolver.resolve("where is the phone", saved))
        assertEquals(SearchTarget.Label("dining table"), SearchResolver.resolve("find desks", saved))
        assertEquals(SearchTarget.Label("person"), SearchResolver.resolve("people", saved))
        assertEquals(SearchTarget.Label("cell phone"), SearchResolver.resolve("cell phones", emptyList()))
    }

    @Test
    fun unknownKeepsCleanedText() {
        assertEquals(SearchTarget.Unknown("keys"), SearchResolver.resolve("where are my keys", saved))
        assertEquals(SearchTarget.Unknown(""), SearchResolver.resolve("find", saved))
    }

    @Test
    fun cocoHasEightyLabels() {
        assertEquals(80, CocoLabels.ALL.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.SearchResolverTest"`
Expected: compilation FAIL, `Unresolved reference SearchResolver`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.classroomscanner.core

import kotlin.math.min

/** The 80 COCO classes the EfficientDet models report. */
object CocoLabels {
    val ALL: Set<String> = setOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog",
        "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
        "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
        "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant",
        "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors",
        "teddy bear", "hair drier", "toothbrush",
    )
}

enum class SavedKind { PERSON, CAR, OBJECT }

/** A saved person or item as the search sees it; [label] is null for people. */
data class SavedName(val kind: SavedKind, val id: Long, val name: String, val label: String?)

sealed interface SearchTarget {
    data class Person(val id: Long, val name: String) : SearchTarget
    data class Item(val id: Long, val name: String, val label: String) : SearchTarget
    data class Label(val label: String) : SearchTarget
    data class Unknown(val text: String) : SearchTarget
}

/** Turns spoken or typed search text into something the search screen can look for. */
object SearchResolver {
    private val fillers = setOf(
        "find", "search", "where", "wheres", "is", "are", "look", "for", "locate", "show", "me",
        "my", "the", "a", "an", "please", "can", "you", "i", "want", "to",
    )

    private val synonyms = mapOf(
        "phone" to "cell phone", "mobile" to "cell phone", "smartphone" to "cell phone",
        "mobile phone" to "cell phone",
        "table" to "dining table", "desk" to "dining table",
        "sofa" to "couch",
        "television" to "tv", "monitor" to "tv", "screen" to "tv",
        "bag" to "backpack", "purse" to "handbag", "luggage" to "suitcase",
        "man" to "person", "men" to "person", "woman" to "person", "women" to "person",
        "people" to "person", "human" to "person", "boy" to "person", "girl" to "person",
        "child" to "person", "children" to "person", "kid" to "person", "teacher" to "person",
        "student" to "person",
        "bike" to "bicycle", "motorbike" to "motorcycle",
        "plant" to "potted plant", "flower" to "potted plant",
        "glass" to "wine glass", "mug" to "cup",
        "computer" to "laptop", "notebook" to "laptop",
        "fridge" to "refrigerator", "plane" to "airplane", "seat" to "chair",
        "puppy" to "dog", "kitten" to "cat", "ball" to "sports ball",
        "remote control" to "remote", "hair dryer" to "hair drier",
    )

    /** Lowercase words without punctuation or command words. */
    fun clean(text: String): String =
        text.lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.isNotEmpty() && it !in fillers }
            .joinToString(" ")

    fun resolve(text: String, saved: List<SavedName>): SearchTarget {
        val query = clean(text)
        if (query.isEmpty()) return SearchTarget.Unknown("")
        findSaved(query, saved)?.let { return it.toTarget() }
        findLabel(query)?.let { return SearchTarget.Label(it) }
        return SearchTarget.Unknown(query)
    }

    private fun findSaved(query: String, saved: List<SavedName>): SavedName? {
        val named = saved.map { it to clean(it.name) }.filter { it.second.isNotEmpty() }
        named.firstOrNull { it.second == query }?.let { return it.first }
        named.filter { " $query ".contains(" ${it.second} ") }
            .maxByOrNull { it.second.length }?.let { return it.first }
        return named
            .filter { it.second.length >= MIN_FUZZY_LENGTH }
            .map { it to levenshtein(it.second, query) }
            .filter { it.second <= MAX_TYPOS }
            .minByOrNull { it.second }
            ?.first?.first
    }

    private fun findLabel(query: String): String? {
        val words = query.split(' ')
        val phrases = buildList {
            add(query)
            for (i in 0 until words.size - 1) add(words[i] + " " + words[i + 1])
            addAll(words)
        }
        for (phrase in phrases) {
            for (form in singulars(phrase)) {
                val label = synonyms[form] ?: form
                if (label in CocoLabels.ALL) return label
            }
        }
        return null
    }

    private fun singulars(word: String): List<String> = buildList {
        add(word)
        if (word.endsWith("ies")) add(word.dropLast(3) + "y")
        if (word.endsWith("es")) add(word.dropLast(2))
        if (word.endsWith("s")) add(word.dropLast(1))
    }

    private fun SavedName.toTarget(): SearchTarget = when (kind) {
        SavedKind.PERSON -> SearchTarget.Person(id, name)
        SavedKind.CAR, SavedKind.OBJECT -> SearchTarget.Item(id, name, label.orEmpty())
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private const val MIN_FUZZY_LENGTH = 4
    private const val MAX_TYPOS = 2
}
```

Worked check for `find medina`: `clean` gives "medina". No exact match and no containment. The Levenshtein distance to "madina" is 1 (≤ 2) and "madina" has at least 4 letters, so the result is Madina. The distance to "ali" is not checked, because "ali" is shorter than 4 letters.

Worked check for `where are my keys`: "keys" does not match any saved name, and the distances to "bag" and "dads car" are too large. The label forms "keys", "keye" and "key" are not COCO labels, so the result is `Unknown("keys")`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.SearchResolverTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/classroomscanner/core/SearchQuery.kt app/src/test/java/com/classroomscanner/core/SearchQueryTest.kt
git commit -m "feat(core): resolve search text to saved names or COCO labels"
```

---

### Task 3: Core — voice command parser

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/VoiceCommand.kt`
- Test: `app/src/test/java/com/classroomscanner/core/VoiceCommandTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface VoiceCommand` with the cases `FullScan`, `LiveScan`, `History`, `Saved`, `Unknown` (all `data object`) and `data class Search(val query: String)`.
  - `object VoiceCommandParser { fun parse(text: String): VoiceCommand }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun searchCommandsKeepTheQuery() {
        assertEquals(VoiceCommand.Search("my bag"), VoiceCommandParser.parse("Find my bag"))
        assertEquals(VoiceCommand.Search("a chair"), VoiceCommandParser.parse("search for a chair"))
        assertEquals(VoiceCommand.Search("ali"), VoiceCommandParser.parse("Where's Ali?"))
        assertEquals(VoiceCommand.Search("the cup"), VoiceCommandParser.parse("where is the cup"))
        assertEquals(VoiceCommand.Search("keys"), VoiceCommandParser.parse("look for keys"))
    }

    @Test
    fun screenCommands() {
        assertEquals(VoiceCommand.FullScan, VoiceCommandParser.parse("Full scan"))
        assertEquals(VoiceCommand.FullScan, VoiceCommandParser.parse("scan the room"))
        assertEquals(VoiceCommand.LiveScan, VoiceCommandParser.parse("start live scan"))
        assertEquals(VoiceCommand.History, VoiceCommandParser.parse("open history"))
        assertEquals(VoiceCommand.Saved, VoiceCommandParser.parse("show saved"))
    }

    @Test
    fun searchWithoutQueryAndNoiseAreUnknown() {
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse("find"))
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse("hello there"))
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse(""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.VoiceCommandParserTest"`
Expected: compilation FAIL.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.classroomscanner.core

sealed interface VoiceCommand {
    data object FullScan : VoiceCommand
    data object LiveScan : VoiceCommand
    data object History : VoiceCommand
    data object Saved : VoiceCommand
    data class Search(val query: String) : VoiceCommand
    data object Unknown : VoiceCommand
}

/** Maps a spoken Home command to an action. */
object VoiceCommandParser {
    private val search = Regex("^(search for|search|find|where is|where are|wheres|look for|locate)\\s+(.+)$")

    fun parse(text: String): VoiceCommand {
        val t = text.lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        search.find(t)?.let { return VoiceCommand.Search(it.groupValues[2]) }
        val words = t.split(' ').toSet()
        return when {
            "history" in words -> VoiceCommand.History
            "saved" in words -> VoiceCommand.Saved
            "live" in words -> VoiceCommand.LiveScan
            "full" in words || t.contains("scan the room") -> VoiceCommand.FullScan
            else -> VoiceCommand.Unknown
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.VoiceCommandParserTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/classroomscanner/core/VoiceCommand.kt app/src/test/java/com/classroomscanner/core/VoiceCommandTest.kt
git commit -m "feat(core): parse spoken home commands"
```

---

### Task 4: Core — search guidance

**Files:**
- Create: `app/src/main/java/com/classroomscanner/core/SearchGuide.kt`
- Test: `app/src/test/java/com/classroomscanner/core/SearchGuideTest.kt`

**Interfaces:**
- Produces:
  - `object SearchGuide { fun direction(centerX: Float): String; fun isCentered(centerX: Float): Boolean; fun beepIntervalMs(centerX: Float): Long }`
  - `class SearchTracker(name: String, lostAfterMs: Long = 3_000, repeatMs: Long = 2_000) { fun update(nowMs: Long, centerX: Float?): String? }`
  - `centerX` is the user's view, 0 = left and 1 = right; the caller mirrors it for the front camera.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchGuideTest {
    @Test
    fun directionBands() {
        assertEquals("far left", SearchGuide.direction(0.1f))
        assertEquals("left", SearchGuide.direction(0.3f))
        assertEquals("ahead", SearchGuide.direction(0.5f))
        assertEquals("right", SearchGuide.direction(0.7f))
        assertEquals("far right", SearchGuide.direction(0.9f))
    }

    @Test
    fun centered() {
        assertTrue(SearchGuide.isCentered(0.45f))
        assertFalse(SearchGuide.isCentered(0.3f))
    }

    @Test
    fun beepGetsFasterTowardCenter() {
        assertEquals(150L, SearchGuide.beepIntervalMs(0.5f))
        assertEquals(1000L, SearchGuide.beepIntervalMs(0f))
        assertEquals(1000L, SearchGuide.beepIntervalMs(1f))
        assertEquals(575L, SearchGuide.beepIntervalMs(0.25f))
    }
}

class SearchTrackerTest {
    @Test
    fun announcesFoundThenDirectionChangesThenLost() {
        val t = SearchTracker("my bag", lostAfterMs = 3_000, repeatMs = 2_000)
        assertNull(t.update(0, null))
        assertEquals("Found my bag, left.", t.update(100, 0.3f))
        assertNull(t.update(200, 0.3f))
        // Changed but too soon.
        assertNull(t.update(1_000, 0.5f))
        assertEquals("Ahead.", t.update(2_200, 0.5f))
        assertNull(t.update(3_000, null))
        assertEquals("Lost it. Turn slowly.", t.update(5_300, null))
        assertNull(t.update(6_000, null))
        assertEquals("Found my bag, far right.", t.update(6_100, 0.95f))
    }
}
```

The expected beep interval at 0.25 is 150 + 850 × 0.5 = 575.

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.Search*Test" --tests "com.classroomscanner.core.SearchTrackerTest"`
Expected: compilation FAIL.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.classroomscanner.core

import kotlin.math.abs
import kotlin.math.roundToLong

/** Turns where the target sits in the view into words and beep speed. */
object SearchGuide {
    private const val NEAREST_MS = 150L
    private const val FARTHEST_MS = 1_000L

    fun direction(centerX: Float): String = when {
        centerX < 0.2f -> "far left"
        centerX < 0.4f -> "left"
        centerX <= 0.6f -> "ahead"
        centerX <= 0.8f -> "right"
        else -> "far right"
    }

    fun isCentered(centerX: Float): Boolean = centerX in 0.4f..0.6f

    fun beepIntervalMs(centerX: Float): Long {
        val off = (abs(centerX - 0.5f) / 0.5f).coerceIn(0f, 1f)
        return (NEAREST_MS + (FARTHEST_MS - NEAREST_MS) * off).roundToLong()
    }
}

/** Decides what to say while searching: found, direction changes (throttled) and lost. */
class SearchTracker(
    private val name: String,
    private val lostAfterMs: Long = 3_000,
    private val repeatMs: Long = 2_000,
) {
    private var found = false
    private var lastSeenAt = 0L
    private var lastSpokeAt = 0L
    private var lastPhrase: String? = null

    /** [centerX] is null when the target is not in this frame. Returns text to speak, if any. */
    fun update(nowMs: Long, centerX: Float?): String? {
        if (centerX == null) {
            if (found && nowMs - lastSeenAt >= lostAfterMs) {
                found = false
                lastPhrase = null
                return "Lost it. Turn slowly."
            }
            return null
        }
        lastSeenAt = nowMs
        val phrase = SearchGuide.direction(centerX)
        if (!found) {
            found = true
            return spoke(nowMs, phrase, "Found $name, $phrase.")
        }
        if (phrase != lastPhrase && nowMs - lastSpokeAt >= repeatMs) {
            return spoke(nowMs, phrase, phrase.replaceFirstChar { it.uppercase() } + ".")
        }
        return null
    }

    private fun spoke(nowMs: Long, phrase: String, text: String): String {
        lastPhrase = phrase
        lastSpokeAt = nowMs
        return text
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.classroomscanner.core.SearchGuideTest" --tests "com.classroomscanner.core.SearchTrackerTest"`
Expected: PASS. Then run the whole suite, `.\gradlew.bat testDebugUnitTest`, and expect PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/classroomscanner/core/SearchGuide.kt app/src/test/java/com/classroomscanner/core/SearchGuideTest.kt
git commit -m "feat(core): search direction, beep speed and found/lost tracking"
```

---

### Task 5: Item storage and image embedder

**Files:**
- Modify: `app/download_models.gradle`
- Create: `app/src/main/java/com/classroomscanner/items/ItemData.kt`
- Create: `app/src/main/java/com/classroomscanner/items/ItemRepository.kt`
- Create: `app/src/main/java/com/classroomscanner/items/ItemEngine.kt`
- Modify: `app/src/main/java/com/classroomscanner/history/AppDatabase.kt`

**Interfaces:**
- Consumes: `ItemKind`, `KnownItem`, `ItemMatch`, `ItemMatcher` (Task 1); `Bitmap.upright(rotationDegrees: Int)` (existing, `face/FaceEngine.kt`).
- Produces:
  - `ItemEntity(id, name, kind: String, label, photoPath, createdAt)`
  - `ItemRepository(context)`:
    - `items(kind: ItemKind): Flow<List<ItemEntity>>`
    - `allItems(): List<ItemEntity>`
    - `item(id): ItemEntity?`
    - `knownItems(): List<KnownItem>`
    - `add(name, kind, label, photo: Bitmap, vectors: List<FloatArray>): Long`
    - `delete(item: ItemEntity)`
  - `fun Bitmap.cropBox(left: Float, top: Float, right: Float, bottom: Float): Bitmap?`
  - `class ItemEmbedder(context): Closeable { fun embed(crop: Bitmap): FloatArray }`
  - `class ItemRecognizer(context, known: List<KnownItem>): Closeable { val labels: Set<String>; fun match(crop: Bitmap, label: String): ItemMatch? }`
  - `AppDatabase.itemsDao()`

- [ ] **Step 1: Add the model download.** Append to `app/download_models.gradle`, then change the `preBuild` line to include the new task:

```groovy
// MediaPipe image embedder for saved cars and objects.
task downloadModelFile4(type: Download) {
    src 'https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite'
    dest project.ext.ASSET_DIR + '/mobilenet_v3_small.tflite'
    overwrite false
}

preBuild.dependsOn downloadModelFile0, downloadModelFile1, downloadModelFile2, downloadModelFile3, downloadModelFile4
```

Remove the old `preBuild.dependsOn ...` line so that only one remains.

- [ ] **Step 2: Create `items/ItemData.kt`**

```kotlin
package com.classroomscanner.items

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A saved car or object. [kind] is an [com.classroomscanner.core.ItemKind] name; [label] its COCO class. */
@Entity(tableName = "saved_items")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val label: String,
    val photoPath: String,
    val createdAt: Long,
)

@Entity(
    tableName = "item_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("itemId")],
)
class ItemEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val vector: ByteArray,
)

/** A stored embedding joined with its item's name and label. */
class ItemRow(val itemId: Long, val name: String, val label: String, val vector: ByteArray)

@Dao
abstract class ItemsDao {

    @Insert
    abstract suspend fun insertItem(item: ItemEntity): Long

    @Insert
    abstract suspend fun insertEmbeddings(rows: List<ItemEmbeddingEntity>)

    @Transaction
    open suspend fun insertItemWithEmbeddings(item: ItemEntity, vectors: List<ByteArray>): Long {
        val id = insertItem(item)
        insertEmbeddings(vectors.map { ItemEmbeddingEntity(itemId = id, vector = it) })
        return id
    }

    @Query("SELECT * FROM saved_items WHERE kind = :kind ORDER BY name COLLATE NOCASE")
    abstract fun observeItems(kind: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM saved_items")
    abstract suspend fun allItems(): List<ItemEntity>

    @Query("SELECT * FROM saved_items WHERE id = :id")
    abstract suspend fun item(id: Long): ItemEntity?

    @Query(
        "SELECT i.id AS itemId, i.name AS name, i.label AS label, e.vector AS vector " +
            "FROM item_embeddings e JOIN saved_items i ON i.id = e.itemId"
    )
    abstract suspend fun allEmbeddings(): List<ItemRow>

    @Query("DELETE FROM item_embeddings WHERE itemId = :id")
    abstract suspend fun deleteEmbeddings(id: Long)

    @Query("DELETE FROM saved_items WHERE id = :id")
    abstract suspend fun deleteItemRow(id: Long)

    /** Deletes embeddings explicitly so it works even when SQLite foreign keys are off. */
    @Transaction
    open suspend fun deleteItem(id: Long) {
        deleteEmbeddings(id)
        deleteItemRow(id)
    }
}
```

- [ ] **Step 3: Update `history/AppDatabase.kt`**
  - Add `ItemEntity::class, ItemEmbeddingEntity::class` to `entities`.
  - Set `version = 3`.
  - Add `abstract fun itemsDao(): ItemsDao`.
  - Change `.addMigrations(MIGRATION_1_2)` to `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`.
  - Add the imports `com.classroomscanner.items.ItemEmbeddingEntity`, `com.classroomscanner.items.ItemEntity` and `com.classroomscanner.items.ItemsDao`.
  - In the companion, after `MIGRATION_1_2`, add:

```kotlin
        /** Version 3 adds saved cars and objects with their image embeddings. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`photoPath` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `item_embeddings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`itemId` INTEGER NOT NULL, `vector` BLOB NOT NULL, " +
                        "FOREIGN KEY(`itemId`) REFERENCES `saved_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_item_embeddings_itemId` ON `item_embeddings` (`itemId`)"
                )
            }
        }
```

- [ ] **Step 4: Create `items/ItemRepository.kt`.** It uses its own byte conversion helpers, the same as `PeopleRepository`.

```kotlin
package com.classroomscanner.items

import android.content.Context
import android.graphics.Bitmap
import com.classroomscanner.core.ItemKind
import com.classroomscanner.core.KnownItem
import com.classroomscanner.history.AppDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Saved cars and objects: names, photos (app storage) and image embeddings (Room). */
class ItemRepository(context: Context) {
    private val dao = AppDatabase.get(context).itemsDao()
    private val photoDir = File(context.applicationContext.filesDir, "items")

    fun items(kind: ItemKind): Flow<List<ItemEntity>> = dao.observeItems(kind.name)

    suspend fun allItems(): List<ItemEntity> = dao.allItems()

    suspend fun item(id: Long): ItemEntity? = dao.item(id)

    suspend fun knownItems(): List<KnownItem> =
        dao.allEmbeddings().map { KnownItem(it.itemId, it.name, it.label, toFloats(it.vector)) }

    /** Saves the photo and embeddings; call off the main thread. */
    suspend fun add(name: String, kind: ItemKind, label: String, photo: Bitmap, vectors: List<FloatArray>): Long {
        photoDir.mkdirs()
        val file = File(photoDir, "item_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { photo.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return dao.insertItemWithEmbeddings(
            ItemEntity(
                name = name,
                kind = kind.name,
                label = label,
                photoPath = file.absolutePath,
                createdAt = System.currentTimeMillis(),
            ),
            vectors.map { toBytes(it) },
        )
    }

    suspend fun delete(item: ItemEntity) {
        dao.deleteItem(item.id)
        File(item.photoPath).delete()
    }

    private companion object {
        const val JPEG_QUALITY = 90

        fun toBytes(v: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(v)
            return buffer.array()
        }

        fun toFloats(bytes: ByteArray): FloatArray {
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(floats.remaining()).also { floats.get(it) }
        }
    }
}
```

- [ ] **Step 5: Create `items/ItemEngine.kt`**

```kotlin
package com.classroomscanner.items

import android.content.Context
import android.graphics.Bitmap
import com.classroomscanner.core.ItemMatch
import com.classroomscanner.core.ItemMatcher
import com.classroomscanner.core.KnownItem
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable

private const val MIN_CROP_PX = 16

/** Crop of a box (pixels of this bitmap), clamped; null when too small. */
fun Bitmap.cropBox(left: Float, top: Float, right: Float, bottom: Float): Bitmap? {
    val l = left.toInt().coerceIn(0, width - 1)
    val t = top.toInt().coerceIn(0, height - 1)
    val r = right.toInt().coerceIn(l + 1, width)
    val b = bottom.toInt().coerceIn(t + 1, height)
    if (r - l < MIN_CROP_PX || b - t < MIN_CROP_PX) return null
    return Bitmap.createBitmap(this, l, t, r - l, b - t)
}

/** MediaPipe image embedder (mobilenet_v3_small, L2-normalized). One background thread only. */
class ItemEmbedder(context: Context) : Closeable {
    private val embedder = ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setL2Normalize(true)
            .setQuantize(false)
            .build()
    )

    fun embed(crop: Bitmap): FloatArray =
        embedder.embed(BitmapImageBuilder(crop).build())
            .embeddingResult().embeddings()[0].floatEmbedding()

    override fun close() = embedder.close()

    private companion object {
        const val MODEL = "mobilenet_v3_small.tflite"
    }
}

/** Matches upright crops of detections against saved items. One background thread only. */
class ItemRecognizer(context: Context, private val known: List<KnownItem>) : Closeable {
    private val embedder = ItemEmbedder(context)

    /** Detector labels that have at least one saved item. */
    val labels: Set<String> = known.map { it.label }.toSet()

    fun match(crop: Bitmap, label: String): ItemMatch? {
        if (label !in labels) return null
        return ItemMatcher.bestMatch(embedder.embed(crop), label, known)
    }

    override fun close() = embedder.close()
}
```

- [ ] **Step 6: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL, and `app/src/main/assets/mobilenet_v3_small.tflite` exists. If `floatEmbedding()` or `embeddingResult()` does not resolve in tasks-vision 1.0.0, open the class in Android Studio or use `javap` on the AAR and use the accessor it actually has (`Embedding.floatEmbedding(): float[]` and `ImageEmbedderResult.embeddingResult()` in 0.10.x).

- [ ] **Step 7: Commit**

```bash
git add app/download_models.gradle app/src/main/java/com/classroomscanner/items app/src/main/java/com/classroomscanner/history/AppDatabase.kt
git commit -m "feat: store saved cars and objects with image embeddings"
```

---

### Task 6: Voice input (STT)

**Files:**
- Create: `app/src/main/java/com/classroomscanner/speech/SpeechInput.kt`
- Create: `app/src/main/java/com/classroomscanner/fragments/VoiceInputController.kt`
- Modify: `app/src/main/java/com/classroomscanner/speech/SpeechAnnouncer.kt`
- Modify: `app/src/main/java/com/classroomscanner/guide/VoiceGuide.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/drawable/ic_mic_24.xml`

**Interfaces:**
- Consumes: `Fragment.speak(text)` (existing, `fragments/PeopleFragments.kt`).
- Produces:
  - `SpeechInput(context)` with `listen(onListening: (Boolean) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit)`, `cancel()`, `destroy()` and `companion fun isAvailable(context): Boolean`.
  - `SpeechAnnouncer.stop()` and `VoiceGuide.stopSpeaking()`.
  - `class VoiceInputController(fragment: Fragment, onText: (String) -> Unit)` with `val available: Boolean`, `var onListening: ((Boolean) -> Unit)?`, `fun listen()` and `fun release()`. It must be created as a fragment field.

- [ ] **Step 1: Manifest.** Add the permissions after the CAMERA permission:

```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.VIBRATE" />
```

Inside `<queries>`, add:

```xml
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
```

- [ ] **Step 2: Add `stop()` to `SpeechAnnouncer`**, after `speakNow`:

```kotlin
    /** Silences speech right away (before listening to the microphone). */
    fun stop() {
        if (isShutDown) return
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        tts.stop()
    }
```

In `VoiceGuide`, after `fun say(...)`, add:

```kotlin
    fun stopSpeaking() = speech.stop()
```

- [ ] **Step 3: Create `speech/SpeechInput.kt`**

```kotlin
package com.classroomscanner.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One-shot English speech-to-text with Android's recognizer. Main thread only. */
class SpeechInput(context: Context) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    private var destroyed = false

    fun listen(onListening: (Boolean) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (destroyed) return
        recognizer.cancel()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                onListening(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) onError(NOT_CAUGHT) else onResult(text)
            }

            override fun onError(error: Int) {
                onListening(false)
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOT_CAUGHT
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is needed."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Voice input needs the offline English speech pack or internet."
                        else -> "Voice input failed. Try again."
                    }
                )
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        recognizer.startListening(intent)
    }

    fun cancel() {
        if (!destroyed) recognizer.cancel()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        recognizer.destroy()
    }

    companion object {
        private const val NOT_CAUGHT = "I didn't catch that. Try again."

        fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    }
}
```

- [ ] **Step 4: Create `fragments/VoiceInputController.kt`**

```kotlin
package com.classroomscanner.fragments

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.classroomscanner.MainActivity
import com.classroomscanner.R
import com.classroomscanner.speech.SpeechInput

/**
 * Microphone button logic for one fragment: asks for the permission, silences speech, listens once.
 * Create it as a fragment field (it registers a permission launcher) and call [release] in onDestroyView.
 */
class VoiceInputController(
    private val fragment: Fragment,
    private val onText: (String) -> Unit,
) {
    var onListening: ((Boolean) -> Unit)? = null
    private var input: SpeechInput? = null

    private val permission =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else fragment.speak(fragment.getString(R.string.mic_permission_needed))
        }

    val available: Boolean
        get() = fragment.context?.let { SpeechInput.isAvailable(it) } ?: false

    fun listen() {
        val context = fragment.context ?: return
        if (!available) {
            fragment.speak(fragment.getString(R.string.voice_input_unavailable))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            start()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun release() {
        input?.destroy()
        input = null
        onListening?.invoke(false)
    }

    private fun start() {
        val context = fragment.context ?: return
        (fragment.activity as? MainActivity)?.voiceGuide?.stopSpeaking()
        val speech = input ?: SpeechInput(context).also { input = it }
        speech.listen(
            onListening = { onListening?.invoke(it) },
            onResult = { if (fragment.view != null) onText(it) },
            onError = { if (fragment.view != null) fragment.speak(it) },
        )
    }
}
```

- [ ] **Step 5: Add strings and an icon.** Add to `strings.xml` before `</resources>`:

```xml
    <string name="voice_input">Speak</string>
    <string name="voice_command">Voice command</string>
    <string name="listening">Listening…</string>
    <string name="mic_permission_needed">Microphone access is needed for voice input.</string>
    <string name="voice_input_unavailable">Voice input is not available on this phone.</string>
```

Create `res/drawable/ic_mic_24.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:tint="?attr/colorControlNormal"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z" />
</vector>
```

- [ ] **Step 6: Wire the mic into Add person.** In `fragment_add_person.xml`, add the following to the `TextInputLayout` `name_layout`:

```xml
        app:endIconMode="custom"
        app:endIconDrawable="@drawable/ic_mic_24"
        app:endIconContentDescription="@string/voice_input"
```

Also give the hint `TextView` (text `@string/add_person_hint`) the ID `android:id="@+id/hint"`.

In `AddPersonFragment` (in `PeopleFragments.kt`):
- Add the field:

```kotlin
    private val voice = VoiceInputController(this) { text ->
        _binding?.nameInput?.setText(text.replaceFirstChar { it.uppercase() })
    }
```

- At the end of `onViewCreated`, add:

```kotlin
        binding.nameLayout.isEndIconVisible = voice.available
        binding.nameLayout.setEndIconOnClickListener { voice.listen() }
```

- In `onDestroyView`, call `voice.release()` before `_binding = null`.

- [ ] **Step 7: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/classroomscanner/speech app/src/main/java/com/classroomscanner/guide/VoiceGuide.kt app/src/main/java/com/classroomscanner/fragments/VoiceInputController.kt app/src/main/java/com/classroomscanner/fragments/PeopleFragments.kt app/src/main/res
git commit -m "feat: voice input with Android speech recognizer"
```

---

### Task 7: New navigation — Home, Full scan hub and Saved with tabs

**Files:**
- Create: `app/src/main/res/layout/fragment_scan_hub.xml` (from the current `fragment_home.xml`)
- Rewrite: `app/src/main/res/layout/fragment_home.xml`
- Create: `app/src/main/res/layout/fragment_saved.xml`
- Delete: `app/src/main/res/layout/fragment_people.xml`
- Create: `app/src/main/java/com/classroomscanner/fragments/ScanHubFragment.kt`
- Rewrite: `app/src/main/java/com/classroomscanner/fragments/HomeFragment.kt`
- Create: `app/src/main/java/com/classroomscanner/fragments/SavedFragments.kt`
- Modify: `app/src/main/java/com/classroomscanner/fragments/PeopleFragments.kt` (remove `PeopleFragment`)
- Move: `people/PeopleAdapter.kt` to `people/SavedAdapter.kt`
- Modify: `app/src/main/java/com/classroomscanner/fragments/EnrollFragment.kt`
- Modify: `app/src/main/res/navigation/nav_graph.xml`, `strings.xml`
- Create drawables: `ic_search_24.xml`, `ic_bookmark_24.xml`, `ic_car_24.xml`, `ic_category_24.xml`

This task also creates the destinations `search_fragment`, `item_fragment`, `add_item_fragment`, `item_enroll_fragment` and `search_camera_fragment` in the nav graph. The classes they name are created in Tasks 7–9. To keep the build green, this task also creates minimal `SearchFragment`, `SearchCameraFragment` and `ItemEnrollFragment` classes, each an empty `Fragment()` subclass that Tasks 8–9 replace.

**Interfaces:**
- Consumes:
  - `VoiceCommandParser` (Task 3), `ItemKind` (Task 1), `ItemRepository`/`ItemEntity` (Task 5), `VoiceInputController` (Task 6).
  - `PeopleRepository`, `goFrom`, `speak`, `ScanMode`, `HeadingProvider` (existing).
- Produces:
  - Nav destination IDs: `home_fragment`, `scan_hub_fragment`, `saved_fragment`, `item_fragment` (arg `itemId: long`), `add_item_fragment` (arg `kind: string`), `item_enroll_fragment` (args `name: string`, `kind: string`, `front: boolean`), `search_fragment` (arg `query: string?`, default `@null`), and `search_camera_fragment` (args `targetKind: string`, `targetId: long`, `targetName: string`, `targetLabel: string`, `front: boolean`).
  - `people/SavedAdapter.kt`: `data class SavedRow(val id: Long, val name: String, val photoPath: String)`, `class SavedAdapter(onOpen: (SavedRow) -> Unit)` and `object PhotoLoader` (unchanged).

- [ ] **Step 1: Add the icons.** Create four files with the same wrapper as `ic_mic_24.xml` and these `pathData` values:
  - `ic_search_24.xml`: `M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z`
  - `ic_bookmark_24.xml`: `M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z`
  - `ic_car_24.xml`: `M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11c-0.66,0 -1.21,0.42 -1.42,1.01L3,12v8c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-8l-2.08,-5.99zM6.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,13 6.5,13s1.5,0.67 1.5,1.5S7.33,16 6.5,16zM17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM5,11l1.5,-4.5h11L19,11L5,11z`
  - `ic_category_24.xml`: `M12,2l-5.5,9h11zM17.5,13c-2.49,0 -4.5,2.01 -4.5,4.5s2.01,4.5 4.5,4.5 4.5,-2.01 4.5,-4.5 -2.01,-4.5 -4.5,-4.5zM3,21.5h8v-8H3z`

- [ ] **Step 2: Add the strings** to `strings.xml`:

```xml
    <string name="label_scan_hub">Full scan</string>
    <string name="label_saved">Saved</string>
    <string name="label_search">Search</string>
    <string name="label_searching">Searching</string>
    <string name="label_item">Item</string>
    <string name="label_add_item">Add item</string>
    <string name="label_item_scan">Item scan</string>
    <string name="scan_hub_title">How do you want to scan?</string>
    <string name="home_scan_desc">Full scan, live scan and past scans.</string>
    <string name="home_search_desc">Say what to find and follow the beeps.</string>
    <string name="home_saved_desc">People, cars and objects the app should know.</string>
    <string name="tab_people">People</string>
    <string name="tab_cars">Cars</string>
    <string name="tab_objects">Objects</string>
    <string name="add_car">Add car</string>
    <string name="add_object">Add object</string>
    <string name="cars_empty">No cars yet.</string>
    <string name="cars_empty_hint">Add a car so scans and searches can find it.</string>
    <string name="objects_empty">No objects yet.</string>
    <string name="objects_empty_hint">Add an object, like your bag, so scans and searches can find it.</string>
    <string name="add_car_hint">Point the camera at the car. It should fill most of the view.</string>
    <string name="add_object_hint">Point the camera at the object. It should be in the middle of the view.</string>
    <string name="delete_item_message">Its photo and image data will be removed from this phone.</string>
    <string name="unknown_command">Say full scan, live scan, history, saved, or find followed by a name.</string>
```

- [ ] **Step 3: Create the hub layout.** Run `git mv app/src/main/res/layout/fragment_home.xml app/src/main/res/layout/fragment_scan_hub.xml`. In `fragment_scan_hub.xml`:
  - Delete the whole last `MaterialCardView` block (`android:id="@+id/card_people"`).
  - Change the first `TextView` text from `@string/home_title` to `@string/scan_hub_title`.

- [ ] **Step 4: Create the new `fragment_home.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorSurface">

    <androidx.core.widget.NestedScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingStart="16dp"
            android:paddingTop="16dp"
            android:paddingEnd="16dp"
            android:paddingBottom="96dp">

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
                android:id="@+id/card_scan"
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
                    android:minHeight="120dp"
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
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="4dp"
                            android:text="@string/home_scan_desc"
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
                android:id="@+id/card_search"
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
                    android:minHeight="120dp"
                    android:orientation="horizontal"
                    android:padding="20dp">

                    <ImageView
                        android:layout_width="40dp"
                        android:layout_height="40dp"
                        android:importantForAccessibility="no"
                        android:src="@drawable/ic_search_24"
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
                            android:text="@string/label_search"
                            android:textAppearance="?attr/textAppearanceTitleLarge"
                            android:textColor="?attr/colorOnTertiaryContainer" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="4dp"
                            android:text="@string/home_search_desc"
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
                android:id="@+id/card_saved"
                style="?attr/materialCardViewFilledStyle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:clickable="true"
                android:focusable="true"
                app:cardBackgroundColor="?attr/colorSecondaryContainer"
                app:cardCornerRadius="24dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:gravity="center_vertical"
                    android:minHeight="120dp"
                    android:orientation="horizontal"
                    android:padding="20dp">

                    <ImageView
                        android:layout_width="40dp"
                        android:layout_height="40dp"
                        android:importantForAccessibility="no"
                        android:src="@drawable/ic_bookmark_24"
                        app:tint="?attr/colorOnSecondaryContainer" />

                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="16dp"
                        android:layout_weight="1"
                        android:orientation="vertical">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/label_saved"
                            android:textAppearance="?attr/textAppearanceTitleLarge"
                            android:textColor="?attr/colorOnSecondaryContainer" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="4dp"
                            android:text="@string/home_saved_desc"
                            android:textAppearance="?attr/textAppearanceBodyMedium"
                            android:textColor="?attr/colorOnSecondaryContainer" />
                    </LinearLayout>

                    <ImageView
                        android:layout_width="24dp"
                        android:layout_height="24dp"
                        android:importantForAccessibility="no"
                        android:src="@drawable/ic_chevron_right_24"
                        app:tint="?attr/colorOnSecondaryContainer" />
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>
        </LinearLayout>
    </androidx.core.widget.NestedScrollView>

    <com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
        android:id="@+id/voice_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:minHeight="64dp"
        android:text="@string/voice_command"
        app:icon="@drawable/ic_mic_24" />
</FrameLayout>
```

- [ ] **Step 5: Create `ScanHubFragment.kt`.** This is the old Home logic.

```kotlin
package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.classroomscanner.R
import com.classroomscanner.core.ScanMode
import com.classroomscanner.databinding.FragmentScanHubBinding
import com.classroomscanner.sensor.HeadingProvider

/** Full scan hub: Full Scan, Live Scan or History. */
class ScanHubFragment : Fragment() {

    private var _binding: FragmentScanHubBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScanHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val hasSensor = HeadingProvider.isAvailable(requireContext())
        binding.cardFull.isEnabled = hasSensor
        binding.cardFull.alpha = if (hasSensor) 1f else DISABLED_ALPHA
        if (!hasSensor) binding.fullDesc.setText(R.string.home_no_sensor)

        val here = R.id.scan_hub_fragment
        binding.cardFull.setOnClickListener {
            goFrom(here, ScanHubFragmentDirections.actionScanHubToSettings(ScanMode.FULL))
        }
        binding.cardLive.setOnClickListener {
            goFrom(here, ScanHubFragmentDirections.actionScanHubToSettings(ScanMode.LIVE))
        }
        binding.cardHistory.setOnClickListener { goFrom(here, ScanHubFragmentDirections.actionScanHubToHistory()) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val DISABLED_ALPHA = 0.5f
    }
}
```

`HeadingProvider.isAvailable(context)` is used here because the no-op listener object is shared by Home and the hub. Add this to `HeadingProvider`'s companion object (create the companion if it has none):

```kotlin
        /** True when the phone has the rotation sensor Full Scan needs. */
        fun isAvailable(context: Context): Boolean =
            HeadingProvider(context, object : Listener {
                override fun onHeading(relHeading: Float, speedDegPerSec: Float) = Unit
                override fun onAccuracyLow(low: Boolean) = Unit
            }).isAvailable
```

Check that `Listener`'s methods match these two signatures, as in the current `HomeFragment.NoOpListener`.

- [ ] **Step 6: Rewrite `HomeFragment.kt`**

```kotlin
package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.classroomscanner.R
import com.classroomscanner.core.ScanMode
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.core.VoiceCommandParser
import com.classroomscanner.databinding.FragmentHomeBinding
import com.classroomscanner.sensor.HeadingProvider

/** Start screen: Full scan hub, Search and Saved, plus spoken commands. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val voice = VoiceInputController(this) { onCommand(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val here = R.id.home_fragment
        binding.cardScan.setOnClickListener { goFrom(here, HomeFragmentDirections.actionHomeToScanHub()) }
        binding.cardSearch.setOnClickListener { goFrom(here, HomeFragmentDirections.actionHomeToSearch(null)) }
        binding.cardSaved.setOnClickListener { goFrom(here, HomeFragmentDirections.actionHomeToSaved()) }

        binding.voiceButton.setOnClickListener { voice.listen() }
        voice.onListening = { listening ->
            _binding?.voiceButton?.setText(if (listening) R.string.listening else R.string.voice_command)
        }
    }

    override fun onDestroyView() {
        voice.release()
        _binding = null
        super.onDestroyView()
    }

    private fun onCommand(text: String) {
        val here = R.id.home_fragment
        when (val command = VoiceCommandParser.parse(text)) {
            VoiceCommand.FullScan ->
                if (HeadingProvider.isAvailable(requireContext())) {
                    goFrom(here, HomeFragmentDirections.actionHomeToSettings(ScanMode.FULL))
                } else {
                    speak(getString(R.string.home_no_sensor))
                }
            VoiceCommand.LiveScan -> goFrom(here, HomeFragmentDirections.actionHomeToSettings(ScanMode.LIVE))
            VoiceCommand.History -> goFrom(here, HomeFragmentDirections.actionHomeToHistory())
            VoiceCommand.Saved -> goFrom(here, HomeFragmentDirections.actionHomeToSaved())
            is VoiceCommand.Search -> goFrom(here, HomeFragmentDirections.actionHomeToSearch(command.query))
            VoiceCommand.Unknown -> speak(getString(R.string.unknown_command))
        }
    }
}
```

- [ ] **Step 7: Move the adapter.** Run `git mv app/src/main/java/com/classroomscanner/people/PeopleAdapter.kt app/src/main/java/com/classroomscanner/people/SavedAdapter.kt`. Replace the adapter class in it (keep `PhotoLoader` unchanged):

```kotlin
/** One row in a Saved tab: a person, car or object. */
data class SavedRow(val id: Long, val name: String, val photoPath: String)

class SavedAdapter(private val onOpen: (SavedRow) -> Unit) :
    ListAdapter<SavedRow, SavedAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.binding.name.text = row.name
        holder.binding.photo.setImageBitmap(PhotoLoader.load(row.photoPath, THUMB_PX))
        holder.binding.root.setOnClickListener { onOpen(row) }
    }

    private object Diff : DiffUtil.ItemCallback<SavedRow>() {
        override fun areItemsTheSame(oldItem: SavedRow, newItem: SavedRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SavedRow, newItem: SavedRow) = oldItem == newItem
    }

    private companion object {
        const val THUMB_PX = 168
    }
}
```

- [ ] **Step 8: Create `fragment_saved.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorSurface"
    android:orientation="vertical">

    <com.google.android.material.tabs.TabLayout
        android:id="@+id/tabs"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:tabGravity="fill"
        app:tabMinWidth="0dp"
        app:tabMode="fixed" />

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
            android:paddingTop="8dp" />

        <LinearLayout
            android:id="@+id/empty"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:gravity="center_horizontal"
            android:orientation="vertical"
            android:padding="24dp"
            android:visibility="gone">

            <ImageView
                android:id="@+id/empty_icon"
                android:layout_width="72dp"
                android:layout_height="72dp"
                android:importantForAccessibility="no"
                android:src="@drawable/ic_face_24"
                app:tint="?attr/colorOutline" />

            <TextView
                android:id="@+id/empty_title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:textAppearance="?attr/textAppearanceTitleMedium"
                android:textColor="?attr/colorOnSurface" />

            <TextView
                android:id="@+id/empty_hint"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:gravity="center"
                android:textAppearance="?attr/textAppearanceBodyMedium"
                android:textColor="?attr/colorOnSurfaceVariant" />
        </LinearLayout>
    </FrameLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/add_button"
        style="@style/Widget.App.Button.Large"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:minHeight="72dp"
        android:text="@string/add_person"
        app:icon="@drawable/ic_person_add_24" />
</LinearLayout>
```

Delete `fragment_people.xml`: `git rm app/src/main/res/layout/fragment_people.xml`.

- [ ] **Step 9: Create `SavedFragments.kt`**

```kotlin
package com.classroomscanner.fragments

import android.Manifest
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.classroomscanner.R
import com.classroomscanner.core.ItemKind
import com.classroomscanner.databinding.FragmentAddPersonBinding
import com.classroomscanner.databinding.FragmentPersonBinding
import com.classroomscanner.databinding.FragmentSavedBinding
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.people.PeopleRepository
import com.classroomscanner.people.PhotoLoader
import com.classroomscanner.people.SavedAdapter
import com.classroomscanner.people.SavedRow
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saved people, cars and objects in three tabs; the big button adds to the open tab. */
class SavedFragment : Fragment() {

    private var _binding: FragmentSavedBinding? = null
    private val binding get() = _binding!!

    /** Kept on the fragment so the tab survives going to a detail screen and back. */
    private var selectedTab = TAB_PEOPLE
    private var listJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSavedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.let { selectedTab = it.getInt(KEY_TAB, selectedTab) }
        val here = R.id.saved_fragment
        val adapter = SavedAdapter { row ->
            if (selectedTab == TAB_PEOPLE) {
                goFrom(here, SavedFragmentDirections.actionSavedToPerson(row.id))
            } else {
                goFrom(here, SavedFragmentDirections.actionSavedToItem(row.id))
            }
        }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        listOf(R.string.tab_people, R.string.tab_cars, R.string.tab_objects).forEach {
            binding.tabs.addTab(binding.tabs.newTab().setText(it))
        }
        binding.tabs.getTabAt(selectedTab)?.select()
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                show(adapter)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.addButton.setOnClickListener {
            when (selectedTab) {
                TAB_PEOPLE -> goFrom(here, SavedFragmentDirections.actionSavedToAddPerson())
                TAB_CARS -> goFrom(here, SavedFragmentDirections.actionSavedToAddItem(ItemKind.CAR.name))
                else -> goFrom(here, SavedFragmentDirections.actionSavedToAddItem(ItemKind.OBJECT.name))
            }
        }
        show(adapter)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, selectedTab)
    }

    override fun onDestroyView() {
        listJob = null
        _binding = null
        super.onDestroyView()
    }

    private fun show(adapter: SavedAdapter) {
        val b = _binding ?: return
        val context = requireContext()
        val (addText, addIcon) = when (selectedTab) {
            TAB_PEOPLE -> R.string.add_person to R.drawable.ic_person_add_24
            TAB_CARS -> R.string.add_car to R.drawable.ic_car_24
            else -> R.string.add_object to R.drawable.ic_category_24
        }
        b.addButton.setText(addText)
        b.addButton.setIconResource(addIcon)
        val (emptyTitle, emptyHint, emptyIcon) = when (selectedTab) {
            TAB_PEOPLE -> Triple(R.string.people_empty, R.string.people_empty_hint, R.drawable.ic_face_24)
            TAB_CARS -> Triple(R.string.cars_empty, R.string.cars_empty_hint, R.drawable.ic_car_24)
            else -> Triple(R.string.objects_empty, R.string.objects_empty_hint, R.drawable.ic_category_24)
        }
        b.emptyTitle.setText(emptyTitle)
        b.emptyHint.setText(emptyHint)
        b.emptyIcon.setImageResource(emptyIcon)

        val rows = when (selectedTab) {
            TAB_PEOPLE -> PeopleRepository(context).people().map { list ->
                list.map { SavedRow(it.id, it.name, it.photoPath) }
            }
            TAB_CARS -> ItemRepository(context).items(ItemKind.CAR).map { list ->
                list.map { SavedRow(it.id, it.name, it.photoPath) }
            }
            else -> ItemRepository(context).items(ItemKind.OBJECT).map { list ->
                list.map { SavedRow(it.id, it.name, it.photoPath) }
            }
        }
        listJob?.cancel()
        adapter.submitList(emptyList())
        listJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                rows.collect { list ->
                    adapter.submitList(list)
                    _binding?.empty?.isVisible = list.isEmpty()
                }
            }
        }
    }

    private companion object {
        const val TAB_PEOPLE = 0
        const val TAB_CARS = 1
        const val KEY_TAB = "saved_tab"
    }
}

/** One saved car or object: photo, name and Delete. */
class ItemFragment : Fragment() {

    private val args: ItemFragmentArgs by navArgs()
    private var _binding: FragmentPersonBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPersonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repository = ItemRepository(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val item = repository.item(args.itemId)
            if (item == null) {
                findNavController().popBackStack()
                return@launch
            }
            requireActivity().findViewById<Toolbar>(R.id.toolbar)?.title = item.name
            binding.name.text = item.name
            binding.photo.contentDescription = getString(R.string.photo_of, item.name)
            binding.photo.setImageBitmap(withContext(Dispatchers.IO) { PhotoLoader.load(item.photoPath, PHOTO_PX) })
            binding.deleteButton.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.delete_person_title, item.name))
                    .setMessage(R.string.delete_item_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(NonCancellable + Dispatchers.IO) { repository.delete(item) }
                            findNavController().popBackStack()
                        }
                    }
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val PHOTO_PX = 720
    }
}

/** Step 1 of adding a car or object: name and camera. */
class AddItemFragment : Fragment() {

    private val args: AddItemFragmentArgs by navArgs()
    private var _binding: FragmentAddPersonBinding? = null
    private val binding get() = _binding!!

    private val voice = VoiceInputController(this) { text ->
        _binding?.nameInput?.setText(text.replaceFirstChar { it.uppercase() })
    }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startItemScan() else speak(getString(R.string.camera_permission_needed))
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddPersonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val car = args.kind == ItemKind.CAR.name
        requireActivity().findViewById<Toolbar>(R.id.toolbar)?.title =
            getString(if (car) R.string.add_car else R.string.add_object)
        binding.nameInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        binding.hint.setText(if (car) R.string.add_car_hint else R.string.add_object_hint)
        binding.startButton.setText(if (car) R.string.add_car else R.string.add_object)
        binding.nameLayout.isEndIconVisible = voice.available
        binding.nameLayout.setEndIconOnClickListener { voice.listen() }
        binding.startButton.setOnClickListener {
            if (name().isEmpty()) {
                speak(getString(R.string.person_name_error))
                binding.nameLayout.error = getString(R.string.person_name_error)
                return@setOnClickListener
            }
            binding.nameLayout.error = null
            if (PermissionsFragment.hasPermissions(requireContext())) {
                startItemScan()
            } else {
                requestCamera.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onDestroyView() {
        voice.release()
        _binding = null
        super.onDestroyView()
    }

    private fun name() = binding.nameInput.text?.toString()?.trim().orEmpty()

    private fun startItemScan() {
        val b = _binding ?: return
        val front = b.cameraGroup.checkedButtonId == R.id.camera_front
        goFrom(R.id.add_item_fragment, AddItemFragmentDirections.actionAddItemToItemEnroll(name(), args.kind, front))
    }
}
```

`start_button` in `fragment_add_person.xml` is a `MaterialButton`, so `setText` works. If it has a `text` other than a string resource, `setText(Int)` still works.

- [ ] **Step 10: Remove `PeopleFragment`.** In `PeopleFragments.kt`:
  - Delete the `PeopleFragment` class and its KDoc.
  - Remove the now-unused imports `FragmentPeopleBinding`, `PeopleAdapter`, `LinearLayoutManager`, `Lifecycle`, `repeatOnLifecycle` and `isVisible`, but only where nothing else uses them.
  - In `AddPersonFragment.startFaceScan`, keep `goFrom(R.id.add_person_fragment, AddPersonFragmentDirections.actionAddPersonToEnroll(name(), front))`.

In `EnrollFragment.save()`, change `popBackStack(R.id.people_fragment, false)` to `popBackStack(R.id.saved_fragment, false)`.

- [ ] **Step 11: Create placeholder fragments** so the nav graph compiles. Tasks 8 and 9 replace them.

`fragments/ItemEnrollFragment.kt`:

```kotlin
package com.classroomscanner.fragments

import androidx.fragment.app.Fragment

class ItemEnrollFragment : Fragment()
```

`fragments/SearchFragment.kt`:

```kotlin
package com.classroomscanner.fragments

import androidx.fragment.app.Fragment

class SearchFragment : Fragment()
```

`fragments/SearchCameraFragment.kt`:

```kotlin
package com.classroomscanner.fragments

import androidx.fragment.app.Fragment

class SearchCameraFragment : Fragment()
```

- [ ] **Step 12: Rewrite `nav_graph.xml`.** Keep the license header and replace the `<navigation>` element with:

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
            android:id="@+id/action_home_to_scan_hub"
            app:destination="@id/scan_hub_fragment" />
        <action
            android:id="@+id/action_home_to_search"
            app:destination="@id/search_fragment" />
        <action
            android:id="@+id/action_home_to_saved"
            app:destination="@id/saved_fragment" />
        <action
            android:id="@+id/action_home_to_settings"
            app:destination="@id/settings_fragment" />
        <action
            android:id="@+id/action_home_to_history"
            app:destination="@id/history_fragment" />
    </fragment>

    <fragment
        android:id="@+id/scan_hub_fragment"
        android:name="com.classroomscanner.fragments.ScanHubFragment"
        android:label="@string/label_scan_hub">
        <action
            android:id="@+id/action_scan_hub_to_settings"
            app:destination="@id/settings_fragment" />
        <action
            android:id="@+id/action_scan_hub_to_history"
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

    <fragment
        android:id="@+id/saved_fragment"
        android:name="com.classroomscanner.fragments.SavedFragment"
        android:label="@string/label_saved">
        <action
            android:id="@+id/action_saved_to_person"
            app:destination="@id/person_fragment" />
        <action
            android:id="@+id/action_saved_to_add_person"
            app:destination="@id/add_person_fragment" />
        <action
            android:id="@+id/action_saved_to_item"
            app:destination="@id/item_fragment" />
        <action
            android:id="@+id/action_saved_to_add_item"
            app:destination="@id/add_item_fragment" />
    </fragment>

    <fragment
        android:id="@+id/person_fragment"
        android:name="com.classroomscanner.fragments.PersonFragment"
        android:label="@string/label_person">
        <argument
            android:name="personId"
            app:argType="long" />
    </fragment>

    <fragment
        android:id="@+id/add_person_fragment"
        android:name="com.classroomscanner.fragments.AddPersonFragment"
        android:label="@string/label_add_person">
        <action
            android:id="@+id/action_add_person_to_enroll"
            app:destination="@id/enroll_fragment" />
    </fragment>

    <fragment
        android:id="@+id/enroll_fragment"
        android:name="com.classroomscanner.fragments.EnrollFragment"
        android:label="@string/label_enroll">
        <argument
            android:name="name"
            app:argType="string" />
        <argument
            android:name="front"
            app:argType="boolean" />
    </fragment>

    <fragment
        android:id="@+id/item_fragment"
        android:name="com.classroomscanner.fragments.ItemFragment"
        android:label="@string/label_item">
        <argument
            android:name="itemId"
            app:argType="long" />
    </fragment>

    <fragment
        android:id="@+id/add_item_fragment"
        android:name="com.classroomscanner.fragments.AddItemFragment"
        android:label="@string/label_add_item">
        <argument
            android:name="kind"
            app:argType="string" />
        <action
            android:id="@+id/action_add_item_to_item_enroll"
            app:destination="@id/item_enroll_fragment" />
    </fragment>

    <fragment
        android:id="@+id/item_enroll_fragment"
        android:name="com.classroomscanner.fragments.ItemEnrollFragment"
        android:label="@string/label_item_scan">
        <argument
            android:name="name"
            app:argType="string" />
        <argument
            android:name="kind"
            app:argType="string" />
        <argument
            android:name="front"
            app:argType="boolean" />
    </fragment>

    <fragment
        android:id="@+id/search_fragment"
        android:name="com.classroomscanner.fragments.SearchFragment"
        android:label="@string/label_search">
        <argument
            android:name="query"
            android:defaultValue="@null"
            app:argType="string"
            app:nullable="true" />
        <action
            android:id="@+id/action_search_to_search_camera"
            app:destination="@id/search_camera_fragment" />
    </fragment>

    <fragment
        android:id="@+id/search_camera_fragment"
        android:name="com.classroomscanner.fragments.SearchCameraFragment"
        android:label="@string/label_searching">
        <argument
            android:name="targetKind"
            app:argType="string" />
        <argument
            android:name="targetId"
            app:argType="long" />
        <argument
            android:name="targetName"
            app:argType="string" />
        <argument
            android:name="targetLabel"
            app:argType="string" />
        <argument
            android:name="front"
            app:argType="boolean" />
    </fragment>
</navigation>
```

- [ ] **Step 13: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL. Fix any remaining references to `R.id.people_fragment`, `PeopleAdapter`, `HomeFragmentDirections.actionHomeToPeople` or `card_people`. Check with `git grep -n "people_fragment\|PeopleAdapter\|actionHomeToPeople\|card_people\|FragmentPeopleBinding"`; the command must print nothing.

- [ ] **Step 14: Commit**

```bash
git add -A app/src/main
git commit -m "feat: home with scan hub, search and saved tabs"
```

---

### Task 8: Item enrollment screen

**Files:**
- Rewrite: `app/src/main/java/com/classroomscanner/fragments/ItemEnrollFragment.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes:
  - `ItemKind`, `ItemEnrollmentGuide`, `ItemStep`, `Candidate`, `CenterPick` (Task 1).
  - `ItemEmbedder`, `Bitmap.cropBox`, `ItemRepository` (Task 5).
  - `ObjectDetectorHelper` (existing, IMAGE mode, `detectImage(bitmap): ResultBundle?`).
  - `Bitmap.upright` (existing).
  - The `FragmentEnrollBinding` layout (IDs `view_finder`, `instruction`, `status`, `progress`, `percent`).
  - `SpeechAnnouncer`, and the nav args `name`, `kind` and `front`.
- Produces: a saved item, then a pop back to `saved_fragment`.

- [ ] **Step 1: Add the strings**

```xml
    <string name="item_enroll_no_object">Point the camera at the object.</string>
    <string name="item_enroll_failed">Item scan could not start.</string>
```

- [ ] **Step 2: Write `ItemEnrollFragment.kt`**

```kotlin
package com.classroomscanner.fragments

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.ObjectDetectorHelper
import com.classroomscanner.R
import com.classroomscanner.core.Candidate
import com.classroomscanner.core.CenterPick
import com.classroomscanner.core.ItemEnrollmentGuide
import com.classroomscanner.core.ItemKind
import com.classroomscanner.core.ItemStep
import com.classroomscanner.databinding.FragmentEnrollBinding
import com.classroomscanner.face.upright
import com.classroomscanner.items.ItemEmbedder
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.items.cropBox
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Step 2 of adding a car or object: finds the thing in the middle of the view, collects image
 * embeddings from three spots, then saves it. Model work runs on [executor] only.
 */
class ItemEnrollFragment : Fragment() {

    private val args: ItemEnrollFragmentArgs by navArgs()
    private var _binding: FragmentEnrollBinding? = null
    private val binding get() = _binding!!

    private lateinit var executor: ExecutorService
    private lateinit var speech: SpeechAnnouncer

    // Executor thread only.
    private var detector: ObjectDetectorHelper? = null
    private var embedder: ItemEmbedder? = null
    private val guide = ItemEnrollmentGuide()
    private val vectors = mutableListOf<FloatArray>()
    private var lockedLabel: String? = null
    private var photo: Bitmap? = null
    private var lastSampleAt = 0L
    private var lastWarningAt = 0L
    private var finished = false

    private val kind get() = ItemKind.valueOf(args.kind)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEnrollBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext().applicationContext
        speech = SpeechAnnouncer(context)
        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                detector = ObjectDetectorHelper(
                    threshold = MIN_SCORE,
                    currentModel = ObjectDetectorHelper.MODEL_EFFICIENTDETV2,
                    runningMode = RunningMode.IMAGE,
                    context = context,
                )
                embedder = ItemEmbedder(context)
            } catch (e: Exception) {
                Log.e(TAG, "Item models failed to load", e)
                finished = true
                onMain { showStatus(getString(R.string.item_enroll_failed), speakIt = true) }
            }
        }
        showProgress(0)
        binding.instruction.text = ItemStep.STILL.instruction
        view.postDelayed({ if (_binding != null) speech.speakNow(ItemStep.STILL.instruction) }, FIRST_SPEECH_DELAY_MS)
        binding.viewFinder.post { setUpCamera() }
    }

    override fun onDestroyView() {
        speech.shutdownWhenIdle()
        _binding = null
        super.onDestroyView()
        executor.execute {
            detector?.clearObjectDetector()
            embedder?.close()
        }
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun setUpCamera() {
        val context = context ?: return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val b = _binding ?: return@addListener
            if (!isAdded) return@addListener
            val provider = future.get()
            val selector = CameraSelector.Builder()
                .requireLensFacing(if (args.front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                .build()
            @Suppress("DEPRECATION")
            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            provider.unbindAll()
            try {
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
                preview.setSurfaceProvider(b.viewFinder.surfaceProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                showStatus(getString(R.string.camera_unavailable), speakIt = true)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Executor thread.
    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val frame: Bitmap
        val rotation: Int
        proxy.use {
            if (finished || SystemClock.uptimeMillis() - lastSampleAt < SAMPLE_GAP_MS) return
            frame = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
            frame.copyPixelsFromBuffer(it.planes[0].buffer)
            rotation = it.imageInfo.rotationDegrees
        }
        val detector = detector ?: return
        val embedder = embedder ?: return
        try {
            val upright = frame.upright(rotation)
            val detections = detector.detectImage(upright)?.results?.firstOrNull()?.detections().orEmpty()
                .filter { d ->
                    val c = d.categories()[0]
                    c.score() >= MIN_SCORE && kind.allows(c.categoryName()) &&
                        (lockedLabel == null || c.categoryName() == lockedLabel)
                }
            val w = upright.width.toFloat()
            val h = upright.height.toFloat()
            val candidates = detections.map {
                val box = it.boundingBox()
                Candidate(box.centerX() / w, box.centerY() / h, box.width() * box.height() / (w * h))
            }
            val index = CenterPick.pick(candidates, if (lockedLabel == null) FIRST_MIN_AREA else NEXT_MIN_AREA)
                ?: return warn()
            val box = detections[index].boundingBox()
            val crop = upright.cropBox(box.left, box.top, box.right, box.bottom) ?: return warn()
            vectors += embedder.embed(crop)
            if (lockedLabel == null) lockedLabel = detections[index].categories()[0].categoryName()
            if (photo == null) photo = crop
            guide.offer()
            lastSampleAt = SystemClock.uptimeMillis()
            onSample()
        } catch (e: Exception) {
            Log.w(TAG, "Item sample failed", e)
        }
    }

    // Executor thread.
    private fun onSample() {
        val percent = guide.percent()
        val next = guide.currentStep
        val stepFinished = guide.stepFinished
        val done = guide.done
        if (done) finished = true
        onMain {
            showStatus(null)
            showProgress(percent)
            when {
                done -> save()
                stepFinished && next != null -> {
                    binding.instruction.text = next.instruction
                    speech.speakNow(getString(R.string.enroll_percent, percent) + " " + next.instruction)
                }
            }
        }
    }

    // Executor thread.
    private fun warn() {
        val now = SystemClock.uptimeMillis()
        val speakIt = now - lastWarningAt > WARNING_REPEAT_MS
        if (speakIt) lastWarningAt = now
        onMain { showStatus(getString(R.string.item_enroll_no_object), speakIt) }
    }

    private fun save() {
        val image = photo ?: return
        val label = lockedLabel ?: return
        val collected = vectors.toList()
        val name = args.name
        val itemKind = kind
        binding.instruction.text = getString(R.string.enroll_saving)
        val repository = ItemRepository(requireContext())
        lifecycleScope.launch {
            withContext(NonCancellable) { repository.add(name, itemKind, label, image, collected) }
            val done = getString(R.string.enroll_done, name)
            speech.speakNow(done)
            _binding?.instruction?.text = done
            findNavController().popBackStack(R.id.saved_fragment, false)
        }
    }

    private fun showProgress(percent: Int) {
        val b = _binding ?: return
        b.progress.setProgressCompat(percent, true)
        b.percent.text = "$percent%"
    }

    private fun showStatus(text: String?, speakIt: Boolean = false) {
        val b = _binding ?: return
        if (b.status.text?.toString() != (text ?: "")) b.status.text = text
        if (speakIt && text != null) speech.speakNow(text)
    }

    private fun onMain(block: () -> Unit) {
        activity?.runOnUiThread { if (_binding != null) block() }
    }

    private companion object {
        const val TAG = "ClassroomScanner"
        const val MIN_SCORE = 0.4f
        const val FIRST_MIN_AREA = 0.05f
        const val NEXT_MIN_AREA = 0.01f
        const val SAMPLE_GAP_MS = 400L
        const val WARNING_REPEAT_MS = 3_000L
        const val FIRST_SPEECH_DELAY_MS = 1_000L
    }
}
```

`ObjectDetectorHelper`'s `ResultBundle.results` is `List<ObjectDetectorResult>`, and `detections()` returns `List<Detection>`. The bounding box is an `android.graphics.RectF`, so `centerX()`, `width()` and `height()` exist.

- [ ] **Step 3: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/classroomscanner/fragments/ItemEnrollFragment.kt app/src/main/res/values/strings.xml
git commit -m "feat: guided item scan for saved cars and objects"
```

---

### Task 9: Search screens

**Files:**
- Rewrite: `app/src/main/java/com/classroomscanner/fragments/SearchFragment.kt`
- Rewrite: `app/src/main/java/com/classroomscanner/fragments/SearchCameraFragment.kt`
- Create: `app/src/main/java/com/classroomscanner/search/Beeper.kt`
- Create: `app/src/main/res/layout/fragment_search.xml`
- Create: `app/src/main/res/layout/fragment_search_camera.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes:
  - `SearchResolver`, `SearchTarget`, `SavedName`, `SavedKind` (Task 2).
  - `SearchGuide`, `SearchTracker` (Task 4).
  - `ItemRepository`, `ItemRecognizer`, `cropBox` (Task 5).
  - `VoiceInputController` (Task 6).
  - `PeopleRepository.people()` and `knownFaces()`, `FaceRecognizer`, `upright`, `ObjectDetectorHelper` (LIVE_STREAM), `OverlayView`, `BoxGeometry.horizontalCenter`, `BoxGeometry.toUpright`, `PermissionsFragment.hasPermissions`, `SpeechAnnouncer`.
- Produces: the search flow. The target kinds are passed as the strings `"PERSON"`, `"ITEM"` and `"LABEL"`.

- [ ] **Step 1: Add the strings**

```xml
    <string name="search_prompt">What should I find?</string>
    <string name="search_hint">Tap the microphone and say a name, like my bag, Ali, or chair.</string>
    <string name="search_speak">Tap to speak</string>
    <string name="search_text">Or type it</string>
    <string name="search_go">Search</string>
    <string name="search_unknown">I can\'t search for %s. Try again.</string>
    <string name="search_nothing">Say what to find.</string>
    <string name="searching_for">Searching for %s</string>
    <string name="search_start_hint">Turn slowly. I will beep when I see it.</string>
    <string name="search_unavailable">Search is not available.</string>
    <string name="stop">Stop</string>
```

- [ ] **Step 2: Create `fragment_search.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorSurface"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/search_prompt"
        android:textAppearance="?attr/textAppearanceHeadlineSmall"
        android:textColor="?attr/colorOnSurface" />

    <TextView
        android:id="@+id/status"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:accessibilityLiveRegion="polite"
        android:text="@string/search_hint"
        android:textAppearance="?attr/textAppearanceBodyLarge"
        android:textColor="?attr/colorOnSurfaceVariant" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/mic_button"
        style="@style/Widget.App.Button.Large"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="24dp"
        android:layout_weight="1"
        android:text="@string/search_speak"
        android:textSize="24sp"
        app:icon="@drawable/ic_mic_24"
        app:iconGravity="textTop"
        app:iconSize="64dp" />

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/query_layout"
        style="?attr/textInputOutlinedStyle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:hint="@string/search_text">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/query_input"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:imeOptions="actionSearch"
            android:inputType="text"
            android:maxLength="60"
            android:minHeight="56dp" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/search_button"
        style="?attr/materialButtonOutlinedStyle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:minHeight="56dp"
        android:text="@string/search_go"
        app:icon="@drawable/ic_search_24" />
</LinearLayout>
```

- [ ] **Step 3: Write `SearchFragment.kt`**

```kotlin
package com.classroomscanner.fragments

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.classroomscanner.R
import com.classroomscanner.core.ItemKind
import com.classroomscanner.core.SavedKind
import com.classroomscanner.core.SavedName
import com.classroomscanner.core.SearchResolver
import com.classroomscanner.core.SearchTarget
import com.classroomscanner.databinding.FragmentSearchBinding
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.people.PeopleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asks what to find (voice or text), resolves it, then opens the search camera. */
class SearchFragment : Fragment() {

    private val args: SearchFragmentArgs by navArgs()
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val voice = VoiceInputController(this) { resolve(it) }
    private var pendingTarget: SearchTarget? = null
    private var queryUsed = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val target = pendingTarget
            if (granted && target != null) open(target) else speak(getString(R.string.camera_permission_needed))
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.micButton.isEnabled = voice.available
        binding.micButton.setOnClickListener { voice.listen() }
        voice.onListening = { listening ->
            _binding?.micButton?.setText(if (listening) R.string.listening else R.string.search_speak)
        }
        binding.searchButton.setOnClickListener { resolve(binding.queryInput.text?.toString().orEmpty()) }
        binding.queryInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                resolve(v.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        val query = args.query
        if (query != null && !queryUsed && savedInstanceState == null) {
            queryUsed = true
            resolve(query)
        } else {
            speak(getString(R.string.search_prompt))
        }
    }

    override fun onDestroyView() {
        voice.release()
        _binding = null
        super.onDestroyView()
    }

    private fun resolve(text: String) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val people = PeopleRepository(context).people().first()
                    .map { SavedName(SavedKind.PERSON, it.id, it.name, null) }
                val items = ItemRepository(context).allItems().map {
                    val kind = if (it.kind == ItemKind.CAR.name) SavedKind.CAR else SavedKind.OBJECT
                    SavedName(kind, it.id, it.name, it.label)
                }
                people + items
            }
            val b = _binding ?: return@launch
            b.queryInput.setText(text)
            when (val target = SearchResolver.resolve(text, saved)) {
                is SearchTarget.Unknown -> {
                    val message = if (target.text.isEmpty()) {
                        getString(R.string.search_nothing)
                    } else {
                        getString(R.string.search_unknown, target.text)
                    }
                    b.status.text = message
                    speak(message)
                }
                else -> {
                    pendingTarget = target
                    if (PermissionsFragment.hasPermissions(requireContext())) {
                        open(target)
                    } else {
                        requestCamera.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        }
    }

    private fun open(target: SearchTarget) {
        val directions = when (target) {
            is SearchTarget.Person ->
                SearchFragmentDirections.actionSearchToSearchCamera(KIND_PERSON, target.id, target.name, "person", false)
            is SearchTarget.Item ->
                SearchFragmentDirections.actionSearchToSearchCamera(KIND_ITEM, target.id, target.name, target.label, false)
            is SearchTarget.Label ->
                SearchFragmentDirections.actionSearchToSearchCamera(KIND_LABEL, 0L, target.label, target.label, false)
            is SearchTarget.Unknown -> return
        }
        goFrom(R.id.search_fragment, directions)
    }

    companion object {
        const val KIND_PERSON = "PERSON"
        const val KIND_ITEM = "ITEM"
        const val KIND_LABEL = "LABEL"
    }
}
```

- [ ] **Step 4: Create `search/Beeper.kt`**

```kotlin
package com.classroomscanner.search

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat

/** Repeating beep whose speed follows the target, plus a short buzz. Main thread only. */
class Beeper(context: Context) {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
    private val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs: Long? = null
    private var released = false

    private val tick = object : Runnable {
        override fun run() {
            val interval = intervalMs ?: return
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            handler.postDelayed(this, interval)
        }
    }

    /** Null stops beeping; a value starts or retunes it. */
    fun setInterval(ms: Long?) {
        if (released) return
        val wasOff = intervalMs == null
        intervalMs = ms
        if (ms == null) {
            handler.removeCallbacks(tick)
        } else if (wasOff) {
            handler.post(tick)
        }
    }

    fun buzz() {
        if (released) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(BUZZ_MS)
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(tick)
        tone.release()
    }

    private companion object {
        const val VOLUME = 80
        const val BEEP_MS = 80
        const val BUZZ_MS = 60L
    }
}
```

- [ ] **Step 5: Create `fragment_search_camera.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
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

    <TextView
        android:id="@+id/target"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="top"
        android:layout_margin="12dp"
        android:background="@drawable/bg_announcement"
        android:paddingHorizontal="16dp"
        android:paddingVertical="12dp"
        android:textAppearance="?attr/textAppearanceTitleMedium"
        android:textColor="?attr/colorOnSurface" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:orientation="vertical"
        android:padding="12dp">

        <TextView
            android:id="@+id/announcement"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:accessibilityLiveRegion="polite"
            android:background="@drawable/bg_announcement"
            android:minHeight="48dp"
            android:paddingHorizontal="16dp"
            android:paddingVertical="12dp"
            android:text="@string/search_start_hint"
            android:textAppearance="?attr/textAppearanceBodyLarge"
            android:textColor="?attr/colorOnSurface" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
                android:id="@+id/switch_camera"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/switch_camera"
                app:icon="@drawable/ic_cameraswitch_24" />

            <Space
                android:layout_width="0dp"
                android:layout_height="0dp"
                android:layout_weight="1" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/stop_button"
                style="@style/Widget.App.Button.Large"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/stop"
                app:icon="@drawable/ic_stop_24" />
        </LinearLayout>
    </LinearLayout>
</FrameLayout>
```

Before writing, check that `fragment_camera.xml` uses the same `PreviewView` attributes (`app:scaleType`) and that `OverlayView` is declared as `com.classroomscanner.OverlayView`. Copy whatever attributes it uses so the boxes line up with the preview.

- [ ] **Step 6: Write `SearchCameraFragment.kt`**

```kotlin
package com.classroomscanner.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.ObjectDetectorHelper
import com.classroomscanner.R
import com.classroomscanner.core.BoxGeometry
import com.classroomscanner.core.SearchGuide
import com.classroomscanner.core.SearchTracker
import com.classroomscanner.databinding.FragmentSearchCameraBinding
import com.classroomscanner.face.FaceRecognizer
import com.classroomscanner.face.upright
import com.classroomscanner.items.ItemRecognizer
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.items.cropBox
import com.classroomscanner.people.PeopleRepository
import com.classroomscanner.search.Beeper
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Looks for one target (saved person, saved item or COCO label) and guides the user to it with
 * speech, beeps and vibration. Detection results arrive on the MediaPipe result thread.
 */
class SearchCameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val args: SearchCameraFragmentArgs by navArgs()
    private var _binding: FragmentSearchCameraBinding? = null
    private val binding get() = _binding!!

    private lateinit var executor: ExecutorService
    private lateinit var detector: ObjectDetectorHelper
    private lateinit var speech: SpeechAnnouncer
    private lateinit var beeper: Beeper
    private lateinit var tracker: SearchTracker

    @Volatile private var matcher: Closeable? = null
    @Volatile private var failed = false
    private var front = false
    private var wasCentered = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext().applicationContext
        front = savedInstanceState?.getBoolean(KEY_FRONT) ?: args.front
        speech = SpeechAnnouncer(context)
        beeper = Beeper(context)
        tracker = SearchTracker(spokenName())
        binding.target.text = getString(R.string.searching_for, spokenName())
        binding.stopButton.setOnClickListener { findNavController().popBackStack() }
        binding.switchCamera.setOnClickListener {
            front = !front
            wasCentered = false
            bindCamera()
        }
        view.postDelayed({
            if (_binding != null) {
                speech.speakNow(getString(R.string.searching_for, spokenName()) + ". " + getString(R.string.search_start_hint))
            }
        }, FIRST_SPEECH_DELAY_MS)

        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            detector = ObjectDetectorHelper(
                threshold = MIN_SCORE,
                currentModel = ObjectDetectorHelper.MODEL_EFFICIENTDETV2,
                runningMode = RunningMode.LIVE_STREAM,
                context = context,
                objectDetectorListener = this,
            )
            matcher = loadMatcher(context)
            if (failed) {
                activity?.runOnUiThread { showAndSay(getString(R.string.search_unavailable)) }
            }
        }
        binding.viewFinder.post { bindCamera() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FRONT, front)
    }

    override fun onDestroyView() {
        beeper.release()
        speech.shutdownWhenIdle()
        _binding = null
        super.onDestroyView()
        val m = matcher
        matcher = null
        executor.execute {
            if (this::detector.isInitialized) detector.clearObjectDetector()
            m?.close()
        }
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun spokenName(): String =
        if (args.targetKind == SearchFragment.KIND_LABEL) "a ${args.targetName}" else args.targetName

    /** Face recognizer (person), item recognizer (item) or nothing (label). Executor thread. */
    private fun loadMatcher(context: Context): Closeable? = try {
        when (args.targetKind) {
            SearchFragment.KIND_PERSON -> {
                val faces = runBlocking { PeopleRepository(context).knownFaces() }
                    .filter { it.personId == args.targetId }
                if (faces.isEmpty()) {
                    failed = true
                    null
                } else {
                    FaceRecognizer(context, faces)
                }
            }
            SearchFragment.KIND_ITEM -> {
                val items = runBlocking { ItemRepository(context).knownItems() }
                    .filter { it.itemId == args.targetId }
                if (items.isEmpty()) {
                    failed = true
                    null
                } else {
                    ItemRecognizer(context, items)
                }
            }
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Search matcher failed", e)
        failed = true
        null
    }

    private fun bindCamera() {
        val context = context ?: return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val b = _binding ?: return@addListener
            if (!isAdded) return@addListener
            val provider = future.get()
            val selector = CameraSelector.Builder()
                .requireLensFacing(if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                .build()
            @Suppress("DEPRECATION")
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(b.viewFinder.display.rotation)
                .build()
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(b.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { a ->
                    a.setAnalyzer(executor) { proxy ->
                        if (this::detector.isInitialized && !detector.isClosed()) {
                            detector.detectLivestreamFrame(proxy)
                        } else {
                            proxy.close()
                        }
                    }
                }
            b.overlay.mirrored = front
            b.overlay.clear()
            provider.unbindAll()
            try {
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
                preview.setSurfaceProvider(b.viewFinder.surfaceProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                showAndSay(getString(R.string.camera_unavailable))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // MediaPipe result thread.
    @SuppressLint("UnsafeOptInUsageError")
    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        val result = resultBundle.results[0]
        val frame = resultBundle.frame
        val w = resultBundle.inputImageWidth
        val h = resultBundle.inputImageHeight
        val rotation = resultBundle.inputImageRotation
        val detections = result.detections()
        val matches = if (failed) emptyList() else findTarget(detections, frame, w, h, rotation)

        val best = matches.maxByOrNull {
            val box = detections[it].boundingBox()
            box.width() * box.height()
        }
        val centerX = best?.let {
            val box = detections[it].boundingBox()
            val c = BoxGeometry.horizontalCenter(box.left, box.top, box.right, box.bottom, w, h, rotation)
            if (front) 1f - c else c
        }
        val labels = detections.indices.map { if (it in matches) args.targetName else null }
        val now = SystemClock.uptimeMillis()

        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            b.overlay.setResults(result, h, w, rotation, labels, null)
            b.overlay.invalidate()
            tracker.update(now, centerX)?.let { showAndSay(it) }
            beeper.setInterval(centerX?.let { SearchGuide.beepIntervalMs(it) })
            val centered = centerX != null && SearchGuide.isCentered(centerX)
            if (centered && !wasCentered) beeper.buzz()
            wasCentered = centered
        }
    }

    /** Indices of detections that are the target. MediaPipe result thread. */
    private fun findTarget(
        detections: List<com.google.mediapipe.tasks.components.containers.Detection>,
        frame: Bitmap?,
        w: Int,
        h: Int,
        rotation: Int,
    ): List<Int> {
        val kept = detections.indices.filter {
            val c = detections[it].categories()[0]
            c.score() >= MIN_SCORE && c.categoryName() == args.targetLabel
        }
        if (kept.isEmpty()) return emptyList()
        return when (args.targetKind) {
            SearchFragment.KIND_LABEL -> kept
            SearchFragment.KIND_ITEM -> {
                val recognizer = matcher as? ItemRecognizer ?: return emptyList()
                if (frame == null) return emptyList()
                kept.sortedByDescending {
                    val box = detections[it].boundingBox()
                    box.width() * box.height()
                }.take(MAX_ITEM_CROPS).filter {
                    val box = detections[it].boundingBox()
                    val crop = frame.cropBox(box.left, box.top, box.right, box.bottom)?.upright(rotation)
                    crop != null && runCatching { recognizer.match(crop, args.targetLabel) }.getOrNull() != null
                }
            }
            SearchFragment.KIND_PERSON -> {
                val recognizer = matcher as? FaceRecognizer ?: return emptyList()
                if (frame == null) return emptyList()
                val faces = runCatching { recognizer.recognize(frame.upright(rotation)) }.getOrElse {
                    Log.w(TAG, "Face search failed", it)
                    emptyList()
                }
                kept.filter { i ->
                    val box = detections[i].boundingBox()
                    val (l, t, r, bottom) = BoxGeometry.toUpright(box.left, box.top, box.right, box.bottom, w, h, rotation).toList()
                    faces.any { it.centerX in l..r && it.centerY in t..bottom }
                }
            }
            else -> emptyList()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e(TAG, error)
        activity?.runOnUiThread { if (_binding != null) showAndSay(getString(R.string.search_unavailable)) }
    }

    private fun showAndSay(text: String) {
        val b = _binding ?: return
        b.announcement.text = text
        speech.speakNow(text)
    }

    private companion object {
        const val TAG = "ClassroomScanner"
        const val MIN_SCORE = 0.4f
        const val MAX_ITEM_CROPS = 3
        const val FIRST_SPEECH_DELAY_MS = 800L
        const val KEY_FRONT = "search_front"
    }
}
```

Notes for the implementer:
- `BoxGeometry.toUpright` returns `FloatArray`, as used in `CameraFragment` (`e.uprightBox.toList()`).
- Check that `OverlayView.mirrored` is a public `var` (it is, at line 57) and that `OverlayView.clear()` exists (it does, at line 63).
- For `Detection`, use the import `com.google.mediapipe.tasks.components.containers.Detection` at the top instead of the fully qualified name if you prefer.
- The `setResults` `labels` list uses null to mean "do not draw this box", so only matches are drawn.

- [ ] **Step 7: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/classroomscanner/fragments/SearchFragment.kt app/src/main/java/com/classroomscanner/fragments/SearchCameraFragment.kt app/src/main/java/com/classroomscanner/search app/src/main/res
git commit -m "feat: search scan with voice query and beep guidance"
```

---

### Task 10: Name saved items in Full/Live scans

**Files:**
- Modify: `app/src/main/java/com/classroomscanner/fragments/CameraFragment.kt`

**Interfaces:**
- Consumes: `ItemRecognizer`, `ItemRepository.knownItems()`, `cropBox` (Task 5); `upright` (existing).
- Produces: in scans, detections of saved items are renamed to the item name, with `isName = true` and `color = null`.

- [ ] **Step 1: Add the field and loading.** Next to `private var faceRecognizer: FaceRecognizer? = null` (around line 105), add:

```kotlin
    private var itemRecognizer: ItemRecognizer? = null
    private var itemFrames = 0
```

Next to `faceRecognizer = loadRecognizer(context)` (around line 199), add:

```kotlin
            itemRecognizer = loadItemRecognizer(context)
```

In `onDestroyView`, next to the face recognizer cleanup, add:

```kotlin
        val items = itemRecognizer
        itemRecognizer = null
```

Change `backgroundExecutor.execute { recognizer?.close() }` to `backgroundExecutor.execute { recognizer?.close(); items?.close() }`.

- [ ] **Step 2: Rename item detections.** In `onResults`, change `.let { nameKnownPeople(it, frame, resultBundle.inputImageRotation) }` to:

```kotlin
        }.let { nameKnownPeople(it, frame, resultBundle.inputImageRotation) }
            .let { nameSavedItems(it, frame, result.detections(), resultBundle.inputImageRotation) }
```

After `loadRecognizer`, add:

```kotlin
    /**
     * Replaces a detection's label with a saved item's name when its crop matches.
     * Runs on the MediaPipe result thread, on every [ITEM_EVERY]th frame that has a candidate.
     */
    private fun nameSavedItems(
        evaluated: List<Evaluated>,
        frame: Bitmap?,
        detections: List<com.google.mediapipe.tasks.components.containers.Detection>,
        rotation: Int,
    ): List<Evaluated> {
        val recognizer = itemRecognizer ?: return evaluated
        if (frame == null) return evaluated
        val candidates = evaluated.indices
            .filter { evaluated[it].kept && !evaluated[it].detection.isName && evaluated[it].detection.label in recognizer.labels }
        if (candidates.isEmpty() || ++itemFrames % ITEM_EVERY != 0) return evaluated
        val names = HashMap<Int, String>()
        candidates.sortedByDescending {
            val box = detections[it].boundingBox()
            box.width() * box.height()
        }.take(MAX_ITEM_CROPS).forEach { i ->
            val box = detections[i].boundingBox()
            val crop = frame.cropBox(box.left, box.top, box.right, box.bottom)?.upright(rotation) ?: return@forEach
            val match = try {
                recognizer.match(crop, evaluated[i].detection.label)
            } catch (e: Exception) {
                Log.w(TAG, "Item recognition failed", e)
                null
            }
            if (match != null) names[i] = match.name
        }
        if (names.isEmpty()) return evaluated
        return evaluated.mapIndexed { i, e ->
            val name = names[i] ?: return@mapIndexed e
            Evaluated(e.detection.copy(label = name, color = null, isName = true), e.kept, e.counted, e.uprightBox)
        }
    }

    /** Loads saved items; null when none are saved or the model cannot start. Detector thread. */
    private fun loadItemRecognizer(context: Context): ItemRecognizer? = try {
        val known = runBlocking { ItemRepository(context).knownItems() }
        if (known.isEmpty()) null else ItemRecognizer(context, known)
    } catch (e: Exception) {
        Log.w(TAG, "Item recognition unavailable", e)
        null
    }
```

Add these to the companion object:

```kotlin
        const val ITEM_EVERY = 3
        const val MAX_ITEM_CROPS = 3
```

Add the imports `com.classroomscanner.items.ItemRecognizer`, `com.classroomscanner.items.ItemRepository` and `com.classroomscanner.items.cropBox`.

Check that the item crop and the outline pipeline read the same frame: `requestOutlines` uses `classLabels` from the raw detections, so renamed labels do not break outline tracking. `overlayLabels` then shows the item name.

- [ ] **Step 3: Check the summary wording.** `SummaryBuilder` already puts `isName` entries first and leaves out their colors, as for people. `NamedPeople.dropShadowedPersons` only touches the label `person`, so items are unaffected. No core change is needed. Run `.\gradlew.bat testDebugUnitTest` and expect PASS.

- [ ] **Step 4: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/classroomscanner/fragments/CameraFragment.kt
git commit -m "feat: say saved item names during scans"
```

---

### Task 11: README, install and device checklist

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the README.**
  - In the features list, add:
    - Search scan with voice or text queries, and beep and vibration guidance.
    - Saved people, cars and objects.
    - Voice commands on Home.
  - In credits, add the MediaPipe Image Embedder (`mobilenet_v3_small`, Apache-2.0) and the Android `SpeechRecognizer`.
  - Describe the new navigation: Home, then Full scan hub, Search or Saved.

- [ ] **Step 2: Run the full unit tests and install**

Run: `.\gradlew.bat testDebugUnitTest; if ($?) { .\gradlew.bat installDebug }`
Expected: tests PASS, and the app is installed on the phone if it is connected. If the phone is offline, tell the user and skip.

- [ ] **Step 3: Commit and push**

```bash
git add README.md
git commit -m "docs: describe search scan, saved items and voice input"
git push -u origin feat/search-saved
```

Open a PR against `feat/object-outlines`, or against `master` if that branch is already merged. Its body ends with "🤖 Generated with [Claude Code](https://claude.com/claude-code)".

- [ ] **Step 4: Give the user this device checklist** (the user runs these checks):
  1. **Home:** the three cards open the Full scan hub, Search and Saved. In the hub, Full, Live and History work as before.
  2. **Voice commands:** tap the mic and say each of these: "live scan", "history", "saved", "find chair".
  3. **Saved tabs:** People still lists the old people. Add and Delete work in every tab.
  4. **Add object:** add a bag or bottle, say its name with the mic, and follow the three steps to 100%.
  5. **Add car:** add a car, or a toy car the detector sees as a car.
  6. **Search, saved item:** say "find my bag". Beeps get faster toward the center, the phone vibrates at the center, and the app says "Lost it" when you turn away.
  7. **Search, other object:** with a different bag of the same type, check whether the app wrongly says it is found. If it does, report it; the threshold 0.75 may need raising.
  8. **Search, COCO object:** say "find a cup" and "find chairs".
  9. **Search, saved person:** say "find <saved name>".
  10. **Unknown query:** say "find keys"; the app should reply "I can't search for keys".
  11. **Scans:** in a Live scan, the saved bag is spoken by its name.
  12. **Offline:** turn on airplane mode and check whether voice input works. If it does not, install the offline English speech pack in the phone settings.

---

## Self-Review Notes

- **Spec coverage:**
  - Navigation: Task 7.
  - Saved cars and objects: Tasks 1, 5, 7 and 8.
  - STT: Task 6, used by Tasks 7 and 9.
  - Resolver and command parser: Tasks 2 and 3.
  - Guide and tracker: Task 4.
  - Search camera: Task 9.
  - Items named in scans: Task 10.
  - Testing and checklist: Task 11.
- **Spec match:** item search embeds on every frame, at most 3 crops, as the spec says.
- **Type names are the same across tasks:**
  - `ItemRecognizer.labels` and `match(crop, label)`.
  - `SearchFragment.KIND_*`.
  - `SavedRow`, `SavedAdapter`, `VoiceInputController(fragment, onText)` and `HeadingProvider.isAvailable(context)`.
