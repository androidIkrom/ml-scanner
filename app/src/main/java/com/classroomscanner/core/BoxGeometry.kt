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
