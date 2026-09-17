package com.classroomscanner.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.classroomscanner.items.ItemEmbeddingEntity
import com.classroomscanner.items.ItemEntity
import com.classroomscanner.items.ItemsDao
import com.classroomscanner.people.FaceEmbeddingEntity
import com.classroomscanner.people.PeopleDao
import com.classroomscanner.people.PersonEntity

@Database(
    entities = [
        ScanEntity::class, DetectedObjectEntity::class, PersonEntity::class, FaceEmbeddingEntity::class,
        ItemEntity::class, ItemEmbeddingEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun peopleDao(): PeopleDao
    abstract fun itemsDao(): ItemsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "classroom-scanner.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
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

        /** Version 3 adds saved cars and objects with their image embeddings. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`photoPath` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `item_embeddings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`itemId` INTEGER NOT NULL, `vector` BLOB NOT NULL, " +
                        "FOREIGN KEY(`itemId`) REFERENCES `saved_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_item_embeddings_itemId` ON `item_embeddings` (`itemId`)"
                )
            }
        }
    }
}
