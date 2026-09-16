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

    /**
     * True when the box touches exactly one side (left or right) of the UPRIGHT frame,
     * within [margin] as a 0..1 fraction. A box spanning the whole frame (touching both
     * sides) or touching neither returns false.
     */
    fun touchesOneSideEdge(
        left: Float, top: Float, right: Float, bottom: Float,
        imageWidth: Int, imageHeight: Int, rotationDegrees: Int,
        margin: Float = 0.02f,
    ): Boolean {
        val a: Float
        val b: Float
        when (rotationDegrees) {
            90 -> {
                a = 1f - top / imageHeight
                b = 1f - bottom / imageHeight
            }
            180 -> {
                a = 1f - left / imageWidth
                b = 1f - right / imageWidth
            }
            270 -> {
                a = top / imageHeight
                b = bottom / imageHeight
            }
            else -> {
                a = left / imageWidth
                b = right / imageWidth
            }
        }
        val minFraction = minOf(a, b)
        val maxFraction = maxOf(a, b)
        val touchesLeft = minFraction <= margin
        val touchesRight = maxFraction >= 1f - margin
        return touchesLeft != touchesRight
    }
}
