package com.classroomscanner.people

import android.content.Context
import android.graphics.Bitmap
import com.classroomscanner.core.KnownFace
import com.classroomscanner.history.AppDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Saved people: names, face photos (app storage) and FaceNet embeddings (Room). */
class PeopleRepository(context: Context) {
    private val dao = AppDatabase.get(context).peopleDao()
    private val photoDir = File(context.applicationContext.filesDir, "faces")

    fun people(): Flow<List<PersonEntity>> = dao.observePeople()

    suspend fun person(id: Long): PersonEntity? = dao.person(id)

    suspend fun knownFaces(): List<KnownFace> =
        dao.allFaces().map { KnownFace(it.personId, it.name, toFloats(it.vector)) }

    /** Saves the photo and embeddings; call off the main thread. */
    suspend fun add(name: String, photo: Bitmap, vectors: List<FloatArray>): Long {
        photoDir.mkdirs()
        val file = File(photoDir, "person_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { photo.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return dao.insertPersonWithFaces(
            PersonEntity(name = name, photoPath = file.absolutePath, createdAt = System.currentTimeMillis()),
            vectors.map { toBytes(it) },
        )
    }

    suspend fun delete(person: PersonEntity) {
        dao.deletePerson(person.id)
        File(person.photoPath).delete()
    }

    private companion object {
        const val JPEG_QUALITY = 90

        fun toBytes(v: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(v)
            return buffer.array()
        }

        fun toFloats(bytes: ByteArray): FloatArray {
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(floats.remaining()).also { floats.get(it) }
        }
    }
}
