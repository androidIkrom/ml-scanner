package com.classroomscanner.core

import kotlin.math.max
import kotlin.math.min

/**
 * Follows detected objects from frame to frame (by box overlap) and gives each one a single,
 * lasting name: once a name has [confirmHits] recognition votes it is decided and kept while the
 * object stays in view, so labels no longer flip between "laptop" and "my new laptop".
 */
class StickyNames(
    private val confirmHits: Int = 2,
    private val forgetAfterFrames: Int = 15,
    private val minIou: Float = 0.3f,
) {
    private class Track(val id: Int, val group: String, var box: FloatArray, var lastSeen: Int) {
        val votes = HashMap<String, Int>()
        var name: String? = null
    }

    private val tracks = mutableListOf<Track>()
    private var frame = 0
    private var nextId = 0

    /**
     * Starts a new frame and returns a track id for each detection. [groups] keeps unlike things
     * apart (for example "person" and "thing"); [boxes] are `[left, top, right, bottom]`.
     */
    fun track(groups: List<String>, boxes: List<FloatArray>): List<Int> {
        frame++
        tracks.removeAll { frame - it.lastSeen > forgetAfterFrames }
        val used = HashSet<Int>()
        val pairs = buildList {
            for (i in boxes.indices) {
                for (t in tracks) {
                    if (t.group != groups[i]) continue
                    val overlap = iou(boxes[i], t.box)
                    if (overlap >= minIou) add(Triple(i, t, overlap))
                }
            }
        }.sortedByDescending { it.third }
        val result = IntArray(boxes.size) { -1 }
        for ((i, t, _) in pairs) {
            if (result[i] != -1 || t.id in used) continue
            result[i] = t.id
            used += t.id
            t.box = boxes[i]
            t.lastSeen = frame
        }
        for (i in boxes.indices) {
            if (result[i] != -1) continue
            val t = Track(nextId++, groups[i], boxes[i], frame)
            tracks += t
            result[i] = t.id
        }
        return result.toList()
    }

    /** Records one recognition result for a track; null (no match) is not a vote. */
    fun vote(trackId: Int, name: String?) {
        val t = find(trackId) ?: return
        if (name == null || t.name != null) return
        val count = (t.votes[name] ?: 0) + 1
        t.votes[name] = count
        if (count >= confirmHits) t.name = name
    }

    fun isDecided(trackId: Int): Boolean = find(trackId)?.name != null

    fun nameOf(trackId: Int): String? = find(trackId)?.name

    fun clear() {
        tracks.clear()
    }

    private fun find(id: Int): Track? = tracks.firstOrNull { it.id == id }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val w = min(a[2], b[2]) - max(a[0], b[0])
        val h = min(a[3], b[3]) - max(a[1], b[1])
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
