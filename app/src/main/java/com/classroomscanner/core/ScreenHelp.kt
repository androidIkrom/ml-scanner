package com.classroomscanner.core

/**
 * Short spoken explanations of every screen and feature: read on entry in learner mode, and read
 * again whenever the user asks "what is search" or "what does saved do". English, one or two
 * sentences, because it is heard, not read.
 */
object ScreenHelp {
    const val APP = "app"
    const val HOME = "home"
    const val SCAN_HUB = "scan_hub"
    const val FULL_SCAN = "full_scan"
    const val LIVE_SCAN = "live_scan"
    const val SETTINGS = "settings"
    const val SCANNER = "scanner"
    const val SCAN_TEXT = "scan_text"
    const val HISTORY = "history"
    const val SEARCH = "search"
    const val SEARCHING = "searching"
    const val SAVED = "saved"
    const val PERSON = "person"
    const val ITEM = "item"
    const val ADD_PERSON = "add_person"
    const val FACE_SCAN = "face_scan"
    const val ADD_ITEM = "add_item"
    const val ITEM_SCAN = "item_scan"
    const val PERMISSION = "permission"
    const val VOICE_GUIDE = "voice_guide"
    const val VOICE_COMMANDS = "voice_commands"
    const val LEARNER = "learner"

    private val help = mapOf(
        APP to "ML Scanner tells you what is around you. It finds objects with the camera, says their " +
            "direction and color, and knows the people and things you saved.",
        HOME to "Home. Three cards: full scan, search, and saved. The microphone button listens for " +
            "commands, and the speaker button turns the voice guide on or off.",
        SCAN_HUB to "Full scan screen. Choose full scan to turn around once and hear a summary, live scan " +
            "to hear objects as they are found, or history to replay older scans.",
        FULL_SCAN to "Full scan. You turn around slowly in a full circle, then the app says everything it " +
            "found, with directions and colors.",
        LIVE_SCAN to "Live scan. The app names each object as soon as it sees it, and never stops on its own.",
        SETTINGS to "Scan settings. Start scanning is the big button. Options holds the camera, processing, " +
            "model, confidence, speech and colors, and your choices are remembered.",
        SCANNER to "Scanner. Start and stop the scan here. View text shows everything said, and the small " +
            "button switches between the back and front camera. Ask who is this or what is this at any time.",
        SCAN_TEXT to "Scan text. Everything the app said in this scan, with the time, and the summary at the end.",
        HISTORY to "History. Every finished scan is kept on this phone, and play reads its summary again.",
        SEARCH to "Search. Say or type what to find, like my bag, Ali, or chair. The app then guides you to " +
            "it with words, beeps that get faster near the middle, and a buzz when it is straight ahead.",
        SEARCHING to "Searching. Turn slowly. Beeps get faster as the thing moves to the middle of the view, " +
            "and the phone buzzes when it is straight ahead. Say stop to go back.",
        SAVED to "Saved. Three tabs: people, cars and objects. The app says their names in scans and can " +
            "search for them. The big button adds a new one, and opening a row shows its photo and delete.",
        PERSON to "Saved person. Their photo and name, and a delete button that removes their face data.",
        ITEM to "Saved thing. Its photo and name, and a delete button that removes its image data.",
        ADD_PERSON to "Add person. Type the name or say it with the microphone button, choose the camera, " +
            "then start the face scan.",
        FACE_SCAN to "Face scan. Follow the spoken poses, look straight, left, right, up and down. The " +
            "percent tells you how much is done.",
        ADD_ITEM to "Add a car or object. Type the name or say it, then start. The thing must be one the " +
            "camera can name, like a bag, bottle, laptop or car.",
        ITEM_SCAN to "Item scan. Keep the thing in the middle of the view and follow the spoken steps: hold " +
            "still, move a little left, then a little right.",
        PERMISSION to "Camera access. The app needs the camera to find objects. Pictures never leave this phone.",
        VOICE_GUIDE to "Voice guide. Tapping a button says its name before it acts, and tapping empty space " +
            "reads every control on the screen with its place.",
        VOICE_COMMANDS to "Voice commands. The microphone button keeps listening until you switch it off. Say " +
            "open search, add object, start, stop, repeat, who is this, or stop listening.",
        LEARNER to "Learner mode. When it is on, every screen says what it is for as you open it. Ask what is " +
            "search or what does saved do at any time.",
    )

    val KEYS: Set<String> get() = help.keys

    fun describe(key: String): String? = help[key]

    /** The topic a spoken question is about, or null when it is about something else. */
    fun topicOf(text: String): String? {
        val t = text.lowercase().replace("'", "").replace(Regex("[^a-z0-9 ]+"), " ")
        return when {
            t.contains("learner") -> LEARNER
            t.contains("voice guide") -> VOICE_GUIDE
            t.contains("voice command") || t.contains("microphone") -> VOICE_COMMANDS
            t.contains("full scan") -> FULL_SCAN
            t.contains("live scan") -> LIVE_SCAN
            t.contains("search") -> SEARCH
            t.contains("saved") -> SAVED
            t.contains("history") -> HISTORY
            t.contains("settings") -> SETTINGS
            t.contains("scanner") -> SCANNER
            t.contains("this app") || t.contains("the app") -> APP
            else -> null
        }
    }
}
