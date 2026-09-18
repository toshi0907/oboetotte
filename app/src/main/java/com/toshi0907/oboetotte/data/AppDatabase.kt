package com.toshi0907.oboetotte.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Task::class,
        TaskList::class,
        SavedLocation::class,
        TaskAttachment::class,
        NotificationLog::class,
        LocationUpdateLog::class,
        GeofenceState::class
    ],
    version = 20,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskListDao(): TaskListDao
    abstract fun savedLocationDao(): SavedLocationDao
    abstract fun taskAttachmentDao(): TaskAttachmentDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun locationUpdateLogDao(): LocationUpdateLogDao
    abstract fun geofenceStateDao(): GeofenceStateDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // バージョン6より前の更新は明示的なMigrationが無いため引き続きfallbackToDestructiveMigration()でデータを破棄する。
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatDaysOfWeek TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN locationName TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE tasks ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE tasks ADD COLUMN radiusMeters INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN notifyOnArrival INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN notifyOnDeparture INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_locations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`radiusMeters` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN url TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN memo TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_attachments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`storedFileName` TEXT NOT NULL, " +
                        "`mimeType` TEXT, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notification_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`triggeredAt` INTEGER NOT NULL, " +
                        "`taskTitle` TEXT NOT NULL, " +
                        "`triggerCondition` TEXT NOT NULL)"
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_update_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`taskTitle` TEXT, " +
                        "`latitude` REAL, " +
                        "`longitude` REAL, " +
                        "`accuracy` REAL, " +
                        "`detail` TEXT)"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN seriesId INTEGER")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_update_logs ADD COLUMN distanceMeters REAL")
                db.execSQL("ALTER TABLE location_update_logs ADD COLUMN radiusMeters INTEGER")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `geofence_states` (" +
                        "`taskId` INTEGER NOT NULL, " +
                        "`isInside` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`radiusMeters` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`))"
                )
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN aiPrompt TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN aiCachedResponse TEXT")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN autoSnoozeMinutes INTEGER")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN aiUseWebSearch INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN aiUseMaps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN aiUseUrlContext INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN aiCachedSources TEXT")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN notifyOnlyMode INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "oboetotte.db"
                )
                    .addMigrations(
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20
                    )
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
