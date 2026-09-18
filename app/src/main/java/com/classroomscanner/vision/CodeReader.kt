package com.classroomscanner.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable

private const val TAG = "ClassroomScanner"

/**
 * Reads QR codes and product barcodes, which none of the other models can do: a door sign with a
 * QR code, a bus stop code, a tin in a shop. Bundled with the app, so it works offline.
 * One background thread only.
 */
class CodeReader : Closeable {
    private val scanner = BarcodeScanning.getClient()

    /** What the first readable code says, or null when there is none. */
    fun read(image: Bitmap): String? = try {
        Tasks.await(scanner.process(InputImage.fromBitmap(image, 0)))
            .asSequence()
            .mapNotNull { it.describe() }
            .firstOrNull { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "Code reading failed", e)
        null
    }

    /** A code is only worth saying when it holds words or a number a person can use. */
    private fun Barcode.describe(): String? = when (valueType) {
        Barcode.TYPE_URL -> url?.url
        Barcode.TYPE_WIFI -> wifi?.ssid?.let { "wifi $it" }
        Barcode.TYPE_PHONE -> phone?.number?.let { "phone $it" }
        Barcode.TYPE_CONTACT_INFO -> contactInfo?.name?.formattedName
        Barcode.TYPE_GEO -> geoPoint?.let { "place" }
        else -> displayValue ?: rawValue
    }?.trim()?.take(MAX_CHARS)

    override fun close() = scanner.close()

    private companion object {
        /** Long codes are not worth reading out; the start is enough to know what it is. */
        const val MAX_CHARS = 80
    }
}
