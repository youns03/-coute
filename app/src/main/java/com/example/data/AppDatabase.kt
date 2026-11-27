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
        TranslationChunkEntity::class,
        ParagraphTranslationEntity::class,
        RecentActivityEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun modelRecordDao(): ModelRecordDao
    abstract fun benchmarkReportDao(): BenchmarkReportDao
    abstract fun translationJobDao(): TranslationJobDao
    abstract fun recentActivityDao(): RecentActivityDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_jobs ADD COLUMN jobEpoch INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `paragraph_translations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` TEXT NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `paragraphIndex` INTEGER NOT NULL,
                        `sourceLanguage` TEXT NOT NULL,
                        `arabicTranslation` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_paragraph_translations_bookId_chapterIndex_paragraphIndex_sourceLanguage`
                    ON `paragraph_translations` (`bookId`, `chapterIndex`, `paragraphIndex`, `sourceLanguage`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `mediaKind` TEXT NOT NULL DEFAULT 'BOOK'")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `coverStyleId` TEXT NOT NULL DEFAULT 'auto'")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `coverImagePath` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `sourceContentHash` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `fileSizeBytes` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `audioFilePath` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `audioDurationMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `isCompleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `lastPositionMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recent_activities` (
                        `id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `subtitle` TEXT NOT NULL,
                        `targetRoute` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `progress` REAL NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
