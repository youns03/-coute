package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        SentenceEntity::class,
        VocabularyEntity::class,
        ModelRecordEntity::class,
        BenchmarkReportEntity::class,
        TranslationJobEntity::class,
        TranslationChunkEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun modelRecordDao(): ModelRecordDao
    abstract fun benchmarkReportDao(): BenchmarkReportDao
    abstract fun translationJobDao(): TranslationJobDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sourceLanguage TEXT NOT NULL DEFAULT 'fr'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `translation_jobs` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `originalFileName` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `sourceLanguage` TEXT NOT NULL,
                        `targetLanguage` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `totalChunks` INTEGER NOT NULL,
                        `completedChunks` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `translation_chunks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `jobId` TEXT NOT NULL,
                        `chunkIndex` INTEGER NOT NULL,
                        `chapterTitle` TEXT NOT NULL,
                        `paragraphIndex` INTEGER NOT NULL,
                        `isHeading` INTEGER NOT NULL,
                        `originalText` TEXT NOT NULL,
                        `translatedText` TEXT,
                        `isCompleted` INTEGER NOT NULL,
                        FOREIGN KEY(`jobId`) REFERENCES `translation_jobs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_jobId_chunkIndex` ON `translation_chunks` (`jobId`, `chunkIndex`)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ecoute_studio.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
