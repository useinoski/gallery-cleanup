package com.useinoski.gallerycleanup.data

import com.useinoski.gallerycleanup.model.MediaType

/**
 * Identity of one library file.
 *
 * MediaStore ids are only unique inside one collection. Image 5 and video 5
 * are different files, so the key carries [mediaType] as well as [mediaStoreId].
 * Two keys with the same fields compare equal. That is what makes
 * [RoomScanResultStore.keptKeys] usable as a skip set.
 *
 * `:core:model` is adding its own `MediaKey` (`type`, then `id`) on
 * `ScanStore`. This class is the key for the review row only. When
 * `ScanStore` is implemented here, the Room code will use the model key.
 */
data class MediaKey(
    val mediaStoreId: Long,
    val mediaType: MediaType,
)
