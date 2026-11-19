package com.useinoski.gallerycleanup.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.useinoski.gallerycleanup.model.MediaType

/**
 * Progress for one collection inside the current pass.
 *
 * [position] is the index in [com.useinoski.gallerycleanup.model.ScanRun.byType]
 * at the time of the write. The suggested table has no order column. SQLite
 * does not promise that a later `SELECT` comes back in insert order, and the
 * contract says the list order is the order the worker wrote.
 */
@Entity(tableName = "scan_type_progress")
data class TypeProgressEntity(
    @PrimaryKey
    val type: MediaType,
    val scanned: Int,
    val total: Int,
    val position: Int,
)
