package com.useinoski.gallerycleanup.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * On-device scan database.
 *
 * Version 1 is [ScanResultEntity] only. Version 2 adds the [ScanStore] tables
 * ([MediaItemEntity], [ScanCheckpointEntity], [ScanRunEntity],
 * [TypeProgressEntity]) through [MIGRATION_1_2] and does not rewrite
 * `scan_results`. Version 3 adds `scan_results.analysis_outcome` and
 * `failure_count` through [MIGRATION_2_3]. Version 4 adds [ItemCategoryEntity]
 * through [MIGRATION_3_4] and does not rewrite `scan_results`. Do not turn on
 * destructive migration: a schema change has to keep rows the user has marked
 * kept.
 *
 * [exportSchema] writes one JSON file per version under `schemas/`. Those
 * files are checked in.
 *
 * Backup of this file is disabled by the app manifest (`allowBackup="false"`
 * and the backup rules). Do not open a network connection from here.
 */
@Database(
    entities = [
        ScanResultEntity::class,
        MediaItemEntity::class,
        ScanCheckpointEntity::class,
        ScanRunEntity::class,
        TypeProgressEntity::class,
        ItemCategoryEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(MediaCategoryConverter::class)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun scanResultDao(): ScanResultDao

    abstract fun scanStoreDao(): ScanStoreDao

    abstract fun itemCategoryDao(): ItemCategoryDao

    companion object {
        const val NAME = "scan-results.db"

        @Volatile
        private var instance: GalleryDatabase? = null

        fun get(context: Context): GalleryDatabase {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val again = instance
                if (again != null) {
                    again
                } else {
                    Room.databaseBuilder(
                        context.applicationContext,
                        GalleryDatabase::class.java,
                        NAME,
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                        .build()
                        .also { created -> instance = created }
                }
            }
        }
    }
}

/**
 * The Room store for this process. One database connection.
 *
 * The returned object implements `ScanStore`. `:app` binds it from
 * `ScanRuntime.store(context)` so the worker and the home screen share it.
 */
fun roomScanResultStore(context: Context): RoomScanResultStore {
    return RoomScanResultStore(GalleryDatabase.get(context))
}
