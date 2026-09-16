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
