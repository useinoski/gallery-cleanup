package com.useinoski.gallerycleanup.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * On-device scan results. Version 1 has a single table, [ScanResultEntity].
 *
 * [exportSchema] writes `schemas/com.useinoski.gallerycleanup.data.GalleryDatabase/1.json`.
 * Those files are checked in so a later migration can be tested against them.
 * Do not turn on destructive migration: a schema change has to keep rows the
 * user has marked kept.
 *
 * Backup of this file is disabled by the app manifest (`allowBackup="false"`
 * and the backup rules). Do not open a network connection from here.
 */
@Database(
    entities = [ScanResultEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun scanResultDao(): ScanResultDao

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
                    ).build().also { created -> instance = created }
                }
            }
        }
    }
}

/**
 * The Room store for this process. One database connection.
 *
 * This is not the scan worker's binding. That binding is `ScanStore` in
 * `:core:model`, which [RoomScanResultStore] will implement once the type is
 * on `main`.
 */
fun roomScanResultStore(context: Context): RoomScanResultStore {
    return RoomScanResultStore(GalleryDatabase.get(context))
}
