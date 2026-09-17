package com.classroomscanner.items

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A saved car or object. [kind] is an [com.classroomscanner.core.ItemKind] name; [label] its COCO class. */
@Entity(tableName = "saved_items")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val label: String,
    val photoPath: String,
    val createdAt: Long,
)

@Entity(
    tableName = "item_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("itemId")],
)
class ItemEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val vector: ByteArray,
)

/** A stored embedding joined with its item's name and label. */
class ItemRow(val itemId: Long, val name: String, val label: String, val vector: ByteArray)

@Dao
abstract class ItemsDao {

    @Insert
    abstract suspend fun insertItem(item: ItemEntity): Long

    @Insert
    abstract suspend fun insertEmbeddings(rows: List<ItemEmbeddingEntity>)

    @Transaction
    open suspend fun insertItemWithEmbeddings(item: ItemEntity, vectors: List<ByteArray>): Long {
        val id = insertItem(item)
        insertEmbeddings(vectors.map { ItemEmbeddingEntity(itemId = id, vector = it) })
        return id
    }

    @Query("SELECT * FROM saved_items WHERE kind = :kind ORDER BY name COLLATE NOCASE")
    abstract fun observeItems(kind: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM saved_items")
    abstract suspend fun allItems(): List<ItemEntity>

    @Query("SELECT * FROM saved_items WHERE id = :id")
    abstract suspend fun item(id: Long): ItemEntity?

    @Query(
        "SELECT i.id AS itemId, i.name AS name, i.label AS label, e.vector AS vector " +
            "FROM item_embeddings e JOIN saved_items i ON i.id = e.itemId"
    )
    abstract suspend fun allEmbeddings(): List<ItemRow>

    @Query("DELETE FROM item_embeddings WHERE itemId = :id")
    abstract suspend fun deleteEmbeddings(id: Long)

    @Query("DELETE FROM saved_items WHERE id = :id")
    abstract suspend fun deleteItemRow(id: Long)

    /** Deletes embeddings explicitly so it works even when SQLite foreign keys are off. */
    @Transaction
    open suspend fun deleteItem(id: Long) {
        deleteEmbeddings(id)
        deleteItemRow(id)
    }
}
