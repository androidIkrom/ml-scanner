package com.classroomscanner.fragments

import com.classroomscanner.ObjectDetectorHelper
import com.classroomscanner.core.Compute
import com.classroomscanner.core.ModelChoice

/** The user's Scan settings, as the detector wants them. Shared by every screen that detects. */
internal fun delegateOf(compute: Compute): Int =
    if (compute == Compute.GPU) ObjectDetectorHelper.DELEGATE_GPU else ObjectDetectorHelper.DELEGATE_CPU

internal fun modelOf(model: ModelChoice): Int = when (model) {
    ModelChoice.LIGHT -> ObjectDetectorHelper.MODEL_SSD_MOBILENET_V2
    ModelChoice.FAST -> ObjectDetectorHelper.MODEL_EFFICIENTDETV0
    ModelChoice.ACCURATE -> ObjectDetectorHelper.MODEL_EFFICIENTDETV2
}
