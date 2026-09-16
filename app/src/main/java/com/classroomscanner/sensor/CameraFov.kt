package com.classroomscanner.sensor

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.classroomscanner.core.CameraFacing
import kotlin.math.atan

object CameraFov {
    const val FALLBACK_DEG = 65f

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
}
