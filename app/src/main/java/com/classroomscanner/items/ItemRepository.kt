package com.classroomscanner.items

import android.content.Context
import android.graphics.Bitmap
import com.classroomscanner.core.ItemKind
import com.classroomscanner.core.KnownItem
import com.classroomscanner.history.AppDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Saved cars and objects: names, photos (app storage) and image embeddings (Room). */
class ItemRepository(context: Context) {
    private val dao = AppDatabase.get(context).itemsDao()
    private val photoDir = File(context.applicationContext.filesDir, "items")

    fun items(kind: ItemKind): Flow<List<ItemEntity>> = dao.observeItems(kind.name)

    suspend fun allItems(): List<ItemEntity> = dao.allItems()

    suspend fun item(id: Long): ItemEntity? = dao.item(id)

    suspend fun knownItems(): List<KnownItem> =
        dao.allEmbeddings().map { KnownItem(it.itemId, it.name, it.label, toFloats(it.vector)) }

    /** Saves the photo and embeddings; call off the main thread. */
    suspend fun add(name: String, kind: ItemKind, label: String, photo: Bitmap, vectors: List<FloatArray>): Long {
        photoDir.mkdirs()
        val file = File(photoDir, "item_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { photo.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return dao.insertItemWithEmbeddings(
            ItemEntity(
                name = name,
                kind = kind.name,
                label = label,
                photoPath = file.absolutePath,
                createdAt = System.currentTimeMillis(),
            ),
            vectors.map { toBytes(it) },
        )
    }

    suspend fun delete(item: ItemEntity) {
        dao.deleteItem(item.id)
        File(item.photoPath).delete()
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
