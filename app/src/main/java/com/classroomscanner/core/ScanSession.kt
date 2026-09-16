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
