package com.useinoski.gallerycleanup.data

import androidx.room.TypeConverter
import com.useinoski.gallerycleanup.model.MediaCategory

/**
 * Stores [MediaCategory.id] in `item_category.category_id`.
 *
 * Room's built-in enum storage writes the constant name (`Sensitive`). This
 * table stores the id (`sensitive`). [MediaCategory.fromId] is an exact match,
 * so the name does not read back. The ordinal is not stored either.
 *
 * The experimental flag is not a column. [MediaCategory.Experimental] has
 * `experimental` set. Readers use the enum after [fromId].
 */
class MediaCategoryConverter {
    @TypeConverter
    fun toId(category: MediaCategory): String = category.id

    @TypeConverter
    fun fromId(id: String): MediaCategory =
        MediaCategory.fromId(id)
            ?: throw IllegalArgumentException("Unknown media category id: $id")
}
