package com.classroomscanner.people

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val photoPath: String,
    val createdAt: Long,
)

@Entity(
    tableName = "face_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("personId")],
)
class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val vector: ByteArray,
)

/** A stored embedding joined with its person's name. */
class FaceRow(val personId: Long, val name: String, val vector: ByteArray)

@Dao
abstract class PeopleDao {

    @Insert
    abstract suspend fun insertPerson(person: PersonEntity): Long

    @Insert
    abstract suspend fun insertFaces(faces: List<FaceEmbeddingEntity>)

    @Transaction
    open suspend fun insertPersonWithFaces(person: PersonEntity, vectors: List<ByteArray>): Long {
        val id = insertPerson(person)
        insertFaces(vectors.map { FaceEmbeddingEntity(personId = id, vector = it) })
        return id
    }

    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE")
    abstract fun observePeople(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people WHERE id = :id")
    abstract suspend fun person(id: Long): PersonEntity?

    @Query(
        "SELECT p.id AS personId, p.name AS name, e.vector AS vector " +
            "FROM face_embeddings e JOIN people p ON p.id = e.personId"
    )
    abstract suspend fun allFaces(): List<FaceRow>

    @Query("DELETE FROM face_embeddings WHERE personId = :id")
    abstract suspend fun deleteFaces(id: Long)

    @Query("DELETE FROM people WHERE id = :id")
    abstract suspend fun deletePersonRow(id: Long)

    /** Deletes embeddings explicitly so it works even when SQLite foreign keys are off. */
    @Transaction
    open suspend fun deletePerson(id: Long) {
        deleteFaces(id)
        deletePersonRow(id)
    }
}
