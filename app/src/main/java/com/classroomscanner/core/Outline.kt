package com.classroomscanner.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Turns a segmentation confidence mask into outline line segments (marching squares). */
object MaskOutline {

    /**
     * Outline of the mask inside the region `[left, top, right, bottom]`, sampled on a grid of at most
     * [maxCells] cells per side. Returns `x1, y1, x2, y2, ...` in mask pixel coordinates.
     * Samples just outside the region count as background, so an object cut by the region is closed there.
     */
    fun segments(
        mask: FloatArray, maskWidth: Int, maskHeight: Int,
        left: Float, top: Float, right: Float, bottom: Float,
        maxCells: Int = 48, threshold: Float = 0.5f,
    ): FloatArray {
        val regionW = right - left
        val regionH = bottom - top
        if (regionW <= 0f || regionH <= 0f || maskWidth <= 0 || maskHeight <= 0) return FloatArray(0)
        val cell = max(max(regionW, regionH) / maxCells, 1f)
        val cols = ceil(regionW / cell).toInt() + 1
        val rows = ceil(regionH / cell).toInt() + 1

        // Grid with a one-sample ring of background around the region.
        val gw = cols + 2
        val gh = rows + 2
        val inside = BooleanArray(gw * gh)
        for (j in 0 until rows) for (i in 0 until cols) {
            val px = min((left + i * cell).toInt(), maskWidth - 1).coerceAtLeast(0)
            val py = min((top + j * cell).toInt(), maskHeight - 1).coerceAtLeast(0)
            inside[(j + 1) * gw + (i + 1)] = mask[py * maskWidth + px] >= threshold
        }

        val out = ArrayList<Float>()
        val half = cell / 2f
        for (j in 0 until gh - 1) for (i in 0 until gw - 1) {
            val tl = inside[j * gw + i]
            val tr = inside[j * gw + i + 1]
            val br = inside[(j + 1) * gw + i + 1]
            val bl = inside[(j + 1) * gw + i]
            val code = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
            if (code == 0 || code == 15) continue
            val x = left + (i - 1) * cell
            val y = top + (j - 1) * cell
            val topMid = floatArrayOf(x + half, y)
            val rightMid = floatArrayOf(x + cell, y + half)
            val bottomMid = floatArrayOf(x + half, y + cell)
            val leftMid = floatArrayOf(x, y + half)
            fun seg(a: FloatArray, b: FloatArray) {
                out += a[0]; out += a[1]; out += b[0]; out += b[1]
            }
            when (code) {
                1, 14 -> seg(leftMid, bottomMid)
                2, 13 -> seg(bottomMid, rightMid)
                3, 12 -> seg(leftMid, rightMid)
                4, 11 -> seg(topMid, rightMid)
                5 -> { seg(leftMid, topMid); seg(bottomMid, rightMid) }
                6, 9 -> seg(topMid, bottomMid)
                7, 8 -> seg(leftMid, topMid)
                10 -> { seg(topMid, rightMid); seg(leftMid, bottomMid) }
            }
        }
        return out.toFloatArray()
    }

    /**
     * Joins marching-squares segments into closed loops and returns the loop with the largest
     * bounding box as `x0, y0, x1, y1, ...` (small loops are mask noise).
     */
    fun largestLoop(segments: FloatArray): FloatArray {
        val count = segments.size / 4
        if (count == 0) return FloatArray(0)
        fun key(x: Float, y: Float) = (Math.round(x * 1000).toLong() shl 32) xor (Math.round(y * 1000).toLong() and 0xffffffffL)
        val byPoint = HashMap<Long, MutableList<Int>>()
        for (k in 0 until count) {
            byPoint.getOrPut(key(segments[4 * k], segments[4 * k + 1])) { mutableListOf() } += k
            byPoint.getOrPut(key(segments[4 * k + 2], segments[4 * k + 3])) { mutableListOf() } += k
        }
        val used = BooleanArray(count)
        var best = FloatArray(0)
        var bestArea = -1f
        for (start in 0 until count) {
            if (used[start]) continue
            val points = ArrayList<Float>()
            var seg = start
            var x = segments[4 * seg]
            var y = segments[4 * seg + 1]
            while (true) {
                used[seg] = true
                points += x
                points += y
                // Walk to the other end of this segment, then to an unused segment touching it.
                val sx = segments[4 * seg]
                val sy = segments[4 * seg + 1]
                val atStart = key(sx, sy) == key(x, y)
                x = if (atStart) segments[4 * seg + 2] else sx
                y = if (atStart) segments[4 * seg + 3] else sy
                seg = byPoint[key(x, y)]?.firstOrNull { !used[it] } ?: break
            }
            val area = bboxArea(points)
            if (area > bestArea) {
                bestArea = area
                best = points.toFloatArray()
            }
        }
        return best
    }

    /** Chaikin corner cutting on a closed polyline; each iteration doubles the point count. */
    fun smooth(loop: FloatArray, iterations: Int = 2): FloatArray {
        var pts = loop
        repeat(iterations) {
            val n = pts.size / 2
            if (n < 3) return pts
            val out = FloatArray(n * 4)
            for (i in 0 until n) {
                val j = (i + 1) % n
                val x0 = pts[2 * i]
                val y0 = pts[2 * i + 1]
                val x1 = pts[2 * j]
                val y1 = pts[2 * j + 1]
                out[4 * i] = 0.75f * x0 + 0.25f * x1
                out[4 * i + 1] = 0.75f * y0 + 0.25f * y1
                out[4 * i + 2] = 0.25f * x0 + 0.75f * x1
                out[4 * i + 3] = 0.25f * y0 + 0.75f * y1
            }
            pts = out
        }
        return pts
    }

    private fun bboxArea(points: List<Float>): Float {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in points.indices step 2) {
            minX = min(minX, points[i])
            maxX = max(maxX, points[i])
            minY = min(minY, points[i + 1])
            maxY = max(maxY, points[i + 1])
        }
        return (maxX - minX) * (maxY - minY)
    }
}

/**
 * Remembers the latest outlines and moves them with their boxes between segmentation runs.
 * Boxes are `[left, top, right, bottom]`. Safe to read from one thread while another replaces the entries.
 */
class OutlineTracker {

    /** [points] is a closed outline `x0, y0, x1, y1, ...` in the same pixels as [box]. */
    class Entry(val label: String, val box: FloatArray, val points: FloatArray)

    @Volatile
    private var entries: List<Entry> = emptyList()

    fun replace(newEntries: List<Entry>) {
        entries = newEntries
    }

    /**
     * Outline for a box of [label], moved and scaled with the box since it was computed;
     * null when no stored box overlaps enough.
     */
    fun lookup(label: String, box: FloatArray): FloatArray? {
        val best = entries
            .filter { it.label == label }
            .map { it to iou(it.box, box) }
            .filter { it.second >= MIN_IOU }
            .maxByOrNull { it.second }
            ?.first ?: return null
        val oldCx = centerX(best.box)
        val oldCy = centerY(best.box)
        val sx = (box[2] - box[0]) / (best.box[2] - best.box[0]).coerceAtLeast(1f)
        val sy = (box[3] - box[1]) / (best.box[3] - best.box[1]).coerceAtLeast(1f)
        val newCx = centerX(box)
        val newCy = centerY(box)
        return FloatArray(best.points.size) { i ->
            val v = best.points[i]
            if (i % 2 == 0) newCx + (v - oldCx) * sx else newCy + (v - oldCy) * sy
        }
    }

    companion object {
        const val MIN_IOU = 0.5f

        fun iou(a: FloatArray, b: FloatArray): Float {
            val w = min(a[2], b[2]) - max(a[0], b[0])
            val h = min(a[3], b[3]) - max(a[1], b[1])
            if (w <= 0f || h <= 0f) return 0f
            val inter = w * h
            val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
            return if (union <= 0f) 0f else inter / union
        }

        private fun centerX(b: FloatArray) = (b[0] + b[2]) / 2f
        private fun centerY(b: FloatArray) = (b[1] + b[3]) / 2f
    }
}
