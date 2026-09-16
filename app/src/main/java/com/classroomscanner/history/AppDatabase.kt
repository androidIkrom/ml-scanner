package com.classroomscanner.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.classroomscanner.people.FaceEmbeddingEntity
import com.classroomscanner.people.PeopleDao
import com.classroomscanner.people.PersonEntity

@Database(
    entities = [ScanEntity::class, DetectedObjectEntity::class, PersonEntity::class, FaceEmbeddingEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun peopleDao(): PeopleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "classroom-scanner.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        /** Version 2 adds saved people and their face embeddings; scan history is kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `photoPath` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `face_embeddings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`personId` INTEGER NOT NULL, `vector` BLOB NOT NULL, " +
                        "FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_face_embeddings_personId` ON `face_embeddings` (`personId`)"
                )
            }
        }
    }
}
